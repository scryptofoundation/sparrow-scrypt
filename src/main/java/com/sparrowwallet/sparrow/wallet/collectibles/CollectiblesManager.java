package com.sparrowwallet.sparrow.wallet.collectibles;

import com.google.common.eventbus.Subscribe;
import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletNode;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.WalletTabData;
import com.sparrowwallet.sparrow.event.*;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.net.ord.OrdClient;
import com.sparrowwallet.sparrow.net.ord.OrdOutput;
import com.sparrowwallet.sparrow.wallet.Entry;
import com.sparrowwallet.sparrow.wallet.HashIndexEntry;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Keeps each open Litecoin wallet's outputs checked against the configured ord server: asks about the unspent
 * outputs, classifies every output, marks carrying ones PROTECTED with a label, and posts CollectiblesUpdatedEvent.
 * Wallet data is read and changed on the FX thread; only the server requests run in the background.
 */
public class CollectiblesManager {
    private static final Logger log = LoggerFactory.getLogger(CollectiblesManager.class);
    private static CollectiblesManager INSTANCE;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Collectibles");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Wallet, Snapshot> snapshots = Collections.synchronizedMap(new IdentityHashMap<>());
    private final Set<Wallet> queued = Collections.newSetFromMap(Collections.synchronizedMap(new IdentityHashMap<>()));
    //Answers for confirmed, indexed outputs never change; keyed by server URL and outpoint
    private final Map<String, OrdOutput> confirmed = new ConcurrentHashMap<>();

    public record Snapshot(Map<String, CollectibleState> states, Map<String, OrdOutput> answers, Instant checkedAt, String serverUrl, String error,
                           boolean enabled) {
        static Snapshot disabled() {
            return new Snapshot(Map.of(), Map.of(), Instant.now(), null, null, false);
        }

        public String serverHost() {
            return serverUrl == null ? null : URI.create(serverUrl).getHost();
        }
    }

    private record WalletData(List<CollectiblesClassifier.Txo> txos, Map<String, Transaction> txs, Map<BlockTransactionHashIndex, WalletNode> utxos) {
    }

    public static synchronized CollectiblesManager get() {
        if(INSTANCE == null) {
            INSTANCE = new CollectiblesManager();
        }
        return INSTANCE;
    }

    public static boolean applies(Wallet wallet) {
        return wallet != null && wallet.getCoin() == Coin.LITECOIN && wallet.getScriptType() != ScriptType.MWEB && wallet.isValid();
    }

    /** The configured server for the wallet's coin, or null when checks are off. */
    public static String serverUrl(Wallet wallet) {
        Coin previous = Coin.beginScope(wallet.getCoin());
        try {
            return Config.get().getInscriptionServerUrl();
        } finally {
            Coin.endScope(previous);
        }
    }

    public Snapshot getSnapshot(Wallet wallet) {
        return snapshots.get(wallet);
    }

    @Subscribe
    public void walletOpened(WalletOpenedEvent event) {
        refresh(event.getWallet());
    }

    @Subscribe
    public void walletHistoryChanged(WalletHistoryChangedEvent event) {
        refresh(event.getWallet());
    }

    @Subscribe
    public void walletNodesChanged(WalletNodesChangedEvent event) {
        refresh(event.getWallet());
    }

    @Subscribe
    public void inscriptionSettingsChanged(InscriptionSettingsChangedEvent event) {
        confirmed.clear();
        for(Wallet wallet : AppServices.get().getOpenWallets().keySet()) {
            if(wallet.getCoin() == event.getCoin()) {
                refresh(wallet);
            }
        }
    }

    @Subscribe
    public void walletTabsClosed(WalletTabsClosedEvent event) {
        for(WalletTabData tabData : event.getClosedWalletTabData()) {
            snapshots.remove(tabData.getWallet());
        }
    }

    /** Checks the wallet again; requests made while one is already waiting are folded into it. */
    public void refresh(Wallet wallet) {
        if(wallet != null && wallet.isNested()) {
            wallet = wallet.getMasterWallet();
        }
        if(!applies(wallet) || !queued.add(wallet)) {
            return;
        }
        Wallet target = wallet;
        executor.submit(() -> check(target));
    }

    private void check(Wallet wallet) {
        try {
            //Read the wallet on the FX thread, where its history is updated
            CompletableFuture<WalletData> future = new CompletableFuture<>();
            Platform.runLater(() -> {
                queued.remove(wallet);
                try {
                    future.complete(new WalletData(CollectiblesClassifier.txos(wallet), CollectiblesClassifier.transactions(wallet), wallet.getWalletUtxos()));
                } catch(Exception e) {
                    future.completeExceptionally(e);
                }
            });
            WalletData data = future.get(60, TimeUnit.SECONDS);

            String url = serverUrl(wallet);
            if(url == null) {
                publish(wallet, Snapshot.disabled(), data, Map.of());
                return;
            }

            Network network = Network.get();
            Map<String, OrdOutput> answers = new HashMap<>();
            for(CollectiblesClassifier.Txo txo : data.txos()) {
                OrdOutput cached = confirmed.get(url + "|" + txo.outpoint());
                if(cached != null) {
                    answers.put(txo.outpoint(), cached);
                }
            }

            String error = null;
            Coin previous = Coin.beginScope(wallet.getCoin());
            try {
                OrdClient client = OrdClient.forApp(url);
                client.requireChain(network);
                Map<String, Integer> heights = new HashMap<>();
                List<String> ask = new ArrayList<>();
                for(BlockTransactionHashIndex utxo : data.utxos().keySet()) {
                    heights.put(utxo.toString(), utxo.getHeight());
                    if(!answers.containsKey(utxo.toString())) {
                        ask.add(utxo.toString());
                    }
                }
                for(OrdOutput output : client.outputs(ask)) {
                    answers.put(output.outpoint(), output);
                    if(output.indexed() && heights.getOrDefault(output.outpoint(), 0) > 0) {
                        confirmed.put(url + "|" + output.outpoint(), output);
                    }
                }
            } catch(Exception e) {
                log.warn("Could not check outputs with " + url, e);
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            } finally {
                Coin.endScope(previous);
            }

            Map<String, CollectibleState> states = CollectiblesClassifier.classify(data.txos(), data.txs(), answers);
            publish(wallet, new Snapshot(states, Map.copyOf(answers), Instant.now(), url, error, true), data, answers);
        } catch(Exception e) {
            log.error("Error checking outputs for inscriptions", e);
        }
    }

    private void publish(Wallet wallet, Snapshot snapshot, WalletData data, Map<String, OrdOutput> answers) {
        Platform.runLater(() -> {
            snapshots.put(wallet, snapshot);
            List<BlockTransactionHashIndex> changed = CollectiblesProtector.apply(data.utxos().keySet(), snapshot.states());
            if(!changed.isEmpty()) {
                List<Entry> labelled = new ArrayList<>();
                for(BlockTransactionHashIndex utxo : changed) {
                    OrdOutput answer = answers.get(utxo.toString());
                    if(answer != null) {
                        utxo.setLabel(CollectiblesProtector.newLabel(utxo.getLabel(), answer));
                        WalletNode node = data.utxos().get(utxo);
                        labelled.add(new HashIndexEntry(node.getWallet(), utxo, HashIndexEntry.Type.OUTPUT, node.getKeyPurpose()));
                    }
                }
                EventManager.get().post(new WalletUtxoStatusChangedEvent(wallet, changed));
                if(!labelled.isEmpty()) {
                    EventManager.get().post(new WalletEntryLabelsChangedEvent(wallet, labelled, false));
                }
            }
            EventManager.get().post(new CollectiblesUpdatedEvent(wallet));
        });
    }

    /**
     * The outputs among these that have not been shown clean (or carrying). Empty when checks are off for the
     * wallet's coin, because then no protection is claimed. A RELEASED output is not listed: the user chose to spend it.
     */
    public List<BlockTransactionHashIndex> unknownAmong(Wallet wallet, Collection<BlockTransactionHashIndex> utxos) {
        Wallet master = wallet != null && wallet.isNested() ? wallet.getMasterWallet() : wallet;
        if(!applies(master) || serverUrl(master) == null) {
            return List.of();
        }
        Snapshot snapshot = snapshots.get(master);
        List<BlockTransactionHashIndex> unknown = new ArrayList<>();
        for(BlockTransactionHashIndex utxo : utxos) {
            CollectibleState state = snapshot == null || !snapshot.enabled() ? null : snapshot.states().get(utxo.toString());
            if(state == null || state == CollectibleState.UNKNOWN) {
                unknown.add(utxo);
            }
        }
        return unknown;
    }
}
