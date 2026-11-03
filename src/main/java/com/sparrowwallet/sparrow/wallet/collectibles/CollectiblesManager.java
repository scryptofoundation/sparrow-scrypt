package com.sparrowwallet.sparrow.wallet.collectibles;

import com.google.common.eventbus.Subscribe;
import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.ordinals.CarrierScan;
import com.sparrowwallet.drongo.ordinals.OrdinalsScan;
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
import com.sparrowwallet.sparrow.net.ElectrumServer;
import com.sparrowwallet.sparrow.net.ord.OrdClient;
import com.sparrowwallet.sparrow.net.ord.OrdOutput;
import com.sparrowwallet.sparrow.net.ord.OrdServer;
import com.sparrowwallet.sparrow.net.ord.OrdStatus;
import com.sparrowwallet.drongo.wallet.Status;
import com.sparrowwallet.drongo.wallet.TxoFilter;
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

    /**
     * One check of a wallet. chainProblem is why the server was not accepted for the wallet's chain (its answers are
     * not used). indexedHeight is the height a lagging indexer (Nintondo, ord 0.5) has reached, or -1; tip is the coin's
     * current tip when the app is connected to that coin, else null.
     */
    public record Snapshot(Map<String, CollectibleState> states, Map<String, OrdOutput> answers, Instant checkedAt, String serverUrl, String error,
                           boolean enabled, boolean runesIndexed, String chainProblem, long indexedHeight, Integer tip) {
        public Snapshot(Map<String, CollectibleState> states, Map<String, OrdOutput> answers, Instant checkedAt, String serverUrl, String error,
                        boolean enabled, boolean runesIndexed) {
            this(states, answers, checkedAt, serverUrl, error, enabled, runesIndexed, null, -1, null);
        }

        static Snapshot disabled() {
            return new Snapshot(Map.of(), Map.of(), Instant.now(), null, null, false, true);
        }

        /** How many blocks the indexer is behind the coin's current tip; 0 when it is not, or when that is not known. */
        public long blocksBehind() {
            return indexedHeight < 0 || tip == null ? 0 : Math.max(0, tip - indexedHeight);
        }

        /** Spec section 4: the tab says by how much the indexer is behind, and why newer coins are held. Null when it is not behind. */
        public String lagNotice() {
            long behind = blocksBehind();
            if(behind == 0) {
                return null;
            }
            return "The inscriptions server is " + behind + (behind == 1 ? " block" : " blocks") + " behind; newer coins stay held until it catches up.";
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
        return wallet != null && wallet.getScriptType() != ScriptType.MWEB && wallet.isValid();
    }

    /** True when an inscriptions server checks the wallet's outputs; otherwise HeldOutputs guards them. */
    public static boolean checkedByServer(Wallet wallet) {
        Wallet master = wallet != null && wallet.isNested() ? wallet.getMasterWallet() : wallet;
        return applies(master) && serverUrl(master) != null;
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

    /** Wallets whose server could not be checked without their coin's tip are checked again once the app connects to that coin. */
    @Subscribe
    public void connected(ConnectionEvent event) {
        for(Wallet wallet : AppServices.get().getOpenWallets().keySet()) {
            Snapshot snapshot = snapshots.get(wallet);
            if(snapshot != null && snapshot.chainProblem() != null && ElectrumServer.isConnectedTo(wallet.getCoin())) {
                refresh(wallet);
            }
        }
    }

    /** The coin's current tip, from the app's Electrum connection when it is to that coin's server; null otherwise. */
    public static Integer freshTip(Coin coin) {
        return ElectrumServer.isConnectedTo(coin) ? AppServices.getCurrentBlockHeight() : null;
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
            //Read the wallet on the FX thread, like the wallet views do. History sync can still replace output objects
            //meanwhile, so protection is applied to the live outputs at publish time, not to these
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
                publish(wallet, Snapshot.disabled(), Map.of());
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
            String chainProblem = null;
            long indexedHeight = -1;
            boolean runeIndex = true;
            //The coin's own tip, never the wallet's stored height, which stops advancing while another coin is connected
            Integer tip = freshTip(wallet.getCoin());
            Coin previous = Coin.beginScope(wallet.getCoin());
            try {
                OrdClient client = OrdClient.forApp(url);
                OrdStatus status = client.status();
                client.checkChain(status, network, wallet.getCoin(), tip);
                runeIndex = status.runeIndex();
                Map<String, Integer> heights = new HashMap<>();
                List<String> ask = new ArrayList<>();
                for(BlockTransactionHashIndex utxo : data.utxos().keySet()) {
                    heights.put(utxo.toString(), utxo.getHeight());
                    if(!answers.containsKey(utxo.toString())) {
                        ask.add(utxo.toString());
                    }
                }
                boolean nintondo = OrdServer.isNintondo(url);
                //Asked after status(), which is what tells the dialect
                boolean tokensIndexed = tokensIndexed(client.dialect(), nintondo);
                boolean lagged = laggingDialect(client.dialect(), nintondo);
                if(lagged) {
                    indexedHeight = status.height();
                }
                for(OrdOutput answer : client.outputs(ask)) {
                    //Without a rune index an empty answer proves nothing, so it stays unknown and is not cached
                    int outputHeight = heights.getOrDefault(answer.outpoint(), 0);
                    OrdOutput output = answered(answer, runeIndex, data.txs().get(answer.outpoint().split(":")[0]), tokensIndexed, lagged, outputHeight, status.height());
                    answers.put(output.outpoint(), output);
                    if(cacheable(output, outputHeight)) {
                        confirmed.put(url + "|" + output.outpoint(), output);
                    }
                }
            } catch(OrdClient.ChainException e) {
                //Not accepted for this chain: nothing it says is used, so every output it was asked about stays unknown (held)
                log.info("Inscriptions server " + url + " not accepted: " + e.getMessage());
                chainProblem = e.getMessage();
            } catch(Exception e) {
                log.warn("Could not check outputs with " + url, e);
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            } finally {
                Coin.endScope(previous);
            }

            Map<String, CollectibleState> states = CollectiblesClassifier.classify(data.txos(), data.txs(), answers);
            publish(wallet, new Snapshot(states, Map.copyOf(answers), Instant.now(), url, error, true, runeIndex, chainProblem, indexedHeight, tip), answers);
        } catch(Exception e) {
            log.error("Error checking outputs for inscriptions", e);
        }
    }

    private void publish(Wallet wallet, Snapshot snapshot, Map<String, OrdOutput> answers) {
        Platform.runLater(() -> {
            snapshots.put(wallet, snapshot);
            //The wallet's outputs now, not those read before the server was asked
            Map<BlockTransactionHashIndex, WalletNode> live = wallet.getWalletUtxos();
            List<BlockTransactionHashIndex> changed = protect(live.keySet(), snapshot.states(), answers);
            if(!changed.isEmpty()) {
                List<Entry> labelled = new ArrayList<>();
                for(BlockTransactionHashIndex utxo : changed) {
                    WalletNode node = live.get(utxo);
                    if(node != null && answers.get(utxo.toString()) != null) {
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
     * The outputs among these that have not been shown clean (or carrying). With no server for the wallet's coin, the
     * outputs HeldOutputs holds. A RELEASED output is not listed: the user chose to spend it.
     */
    public List<BlockTransactionHashIndex> unknownAmong(Wallet wallet, Collection<BlockTransactionHashIndex> utxos) {
        Wallet master = wallet != null && wallet.isNested() ? wallet.getMasterWallet() : wallet;
        if(!applies(master)) {
            return List.of();
        }
        if(serverUrl(master) == null) {
            return HeldOutputs.held(wallet, utxos);
        }
        return unknownIn(snapshots.get(master), utxos);
    }

    /** One line per output for a dialog: the outpoint and, when no server checks the wallet, why it is held. */
    public static String describe(Wallet wallet, Collection<BlockTransactionHashIndex> utxos) {
        boolean server = checkedByServer(wallet);
        return utxos.stream().map(utxo -> server ? utxo.toString() : utxo + " (" + HeldOutputs.reason(wallet, utxo) + ")")
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    /** A filter preferring checked outputs for the wallet (outputs not held, with no server), or null when none applies. */
    public TxoFilter checkedFilter(Wallet wallet) {
        Wallet master = wallet != null && wallet.isNested() ? wallet.getMasterWallet() : wallet;
        if(!applies(master)) {
            return null;
        }
        if(serverUrl(master) == null) {
            return new HeldOutputs.Filter(wallet);
        }
        return new CheckedTxoFilter(snapshots.get(master));
    }

    /**
     * Outputs not shown clean. One known to carry something but not yet marked PROTECTED (the check and a history
     * sync crossed) also counts, so it is never spent without a warning.
     */
    static List<BlockTransactionHashIndex> unknownIn(Snapshot snapshot, Collection<BlockTransactionHashIndex> utxos) {
        List<BlockTransactionHashIndex> unknown = new ArrayList<>();
        for(BlockTransactionHashIndex utxo : utxos) {
            CollectibleState state = snapshot == null || !snapshot.enabled() ? null : snapshot.states().get(utxo.toString());
            boolean unmarked = state == CollectibleState.CARRIES && utxo.getStatus() != Status.PROTECTED && utxo.getStatus() != Status.RELEASED;
            if(state == null || state == CollectibleState.UNKNOWN || unmarked) {
                unknown.add(utxo);
            }
        }
        return unknown;
    }

    /** Nintondo and ord-junkcoin answer for outputs they have not indexed yet (indexed true, nothing found), so the height rule applies to them. */
    static boolean laggingDialect(String dialect, boolean nintondo) {
        return nintondo || OrdClient.LEGACY_DIALECT.equals(dialect);
    }

    /**
     * Whether a clean answer also rules out tokens. Nintondo indexes inscriptions only, and an ord 0.5 server may run
     * without its token index or report balances under a field it does not share, so neither vouches for tokens.
     */
    static boolean tokensIndexed(String dialect, boolean nintondo) {
        return !(nintondo || OrdClient.LEGACY_DIALECT.equals(dialect));
    }

    /** A server answer as far as it can be trusted, before classification: rune index, then the height rule when the dialect needs it, then token coverage. */
    static OrdOutput answered(OrdOutput answer, boolean runeIndex, Transaction creatingTx, boolean tokensIndexed, boolean lagged, int outputHeight, long indexedHeight) {
        OrdOutput output = answer.forServer(runeIndex);
        if(lagged) {
            output = indexedBy(output, outputHeight, indexedHeight);
        }
        return vouchedFor(output, creatingTx, tokensIndexed);
    }

    /** Only confirmed, indexed answers never change, so only those are kept. */
    static boolean cacheable(OrdOutput output, int outputHeight) {
        return output.indexed() && outputHeight > 0;
    }

    /**
     * A clean answer from an indexer that may lag. An output that is unconfirmed, or in a block past what the server has
     * indexed, is unknown, not clean. An answer that carries something is never downgraded.
     */
    static OrdOutput indexedBy(OrdOutput answer, int outputHeight, long indexedHeight) {
        if(answer.carries() || (outputHeight > 0 && outputHeight <= indexedHeight)) {
            return answer;
        }
        return OrdOutput.unknown(answer.outpoint());
    }

    /**
     * An answer as far as the indexer can vouch for it. Nintondo indexes inscriptions only, so its "clean" for an output
     * of a transaction writing a runestone or dunestone (Dogecoin's Dunes) proves nothing: that output stays unknown.
     */
    static OrdOutput vouchedFor(OrdOutput answer, Transaction creatingTx, boolean tokensIndexed) {
        if(tokensIndexed || !answer.indexed() || answer.carries() || creatingTx == null) {
            return answer;
        }
        if(OrdinalsScan.hasRunestone(creatingTx) || CarrierScan.hasDunestone(creatingTx)) {
            return OrdOutput.unknown(answer.outpoint());
        }
        return answer;
    }

    /** Marks carrying outputs PROTECTED and labels them, returning those changed. */
    static List<BlockTransactionHashIndex> protect(Collection<BlockTransactionHashIndex> utxos, Map<String, CollectibleState> states, Map<String, OrdOutput> answers) {
        List<BlockTransactionHashIndex> changed = CollectiblesProtector.apply(utxos, states);
        for(BlockTransactionHashIndex utxo : changed) {
            OrdOutput answer = answers.get(utxo.toString());
            if(answer != null) {
                utxo.setLabel(CollectiblesProtector.newLabel(utxo.getLabel(), answer));
            }
        }
        return changed;
    }
}
