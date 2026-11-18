package com.sparrowwallet.sparrow.wallet.collectibles;

import com.google.common.eventbus.Subscribe;
import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.ordinals.CarrierScan;
import com.sparrowwallet.drongo.ordinals.OrdinalsScan;
import com.sparrowwallet.drongo.ordinals.track.ChainRules;
import com.sparrowwallet.drongo.ordinals.track.TxCache;
import com.sparrowwallet.drongo.ordinals.track.Verdict;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.wallet.BlockTransaction;
import com.sparrowwallet.drongo.wallet.InscriptionCheck;
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
import com.sparrowwallet.sparrow.net.ord.InscriptionServerMode;
import com.sparrowwallet.sparrow.net.ord.OrdClient;
import com.sparrowwallet.sparrow.net.ord.OrdOutput;
import com.sparrowwallet.sparrow.net.ord.OrdServer;
import com.sparrowwallet.sparrow.net.ord.OrdStatus;
import com.sparrowwallet.drongo.wallet.Status;
import com.sparrowwallet.drongo.wallet.TxoFilter;
import com.sparrowwallet.sparrow.wallet.Entry;
import com.sparrowwallet.sparrow.wallet.HashIndexEntry;
import com.sparrowwallet.sparrow.wallet.collectibles.track.CheckPolicy;
import com.sparrowwallet.sparrow.wallet.collectibles.track.ServerChainSource;
import com.sparrowwallet.sparrow.wallet.collectibles.track.TrackerRun;
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
                           boolean enabled, boolean runesIndexed, String chainProblem, long indexedHeight, Integer tip,
                           Map<String, InscriptionCheck> checks, String indexerNotice) {
        public Snapshot(Map<String, CollectibleState> states, Map<String, OrdOutput> answers, Instant checkedAt, String serverUrl, String error,
                        boolean enabled, boolean runesIndexed) {
            this(states, answers, checkedAt, serverUrl, error, enabled, runesIndexed, null, -1, null);
        }

        public Snapshot(Map<String, CollectibleState> states, Map<String, OrdOutput> answers, Instant checkedAt, String serverUrl, String error,
                        boolean enabled, boolean runesIndexed, String chainProblem, long indexedHeight, Integer tip) {
            this(states, answers, checkedAt, serverUrl, error, enabled, runesIndexed, chainProblem, indexedHeight, tip, Map.of(), null);
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

    private record WalletData(List<CollectiblesClassifier.Txo> txos, Map<String, Transaction> txs, Map<BlockTransactionHashIndex, WalletNode> utxos,
                              Map<Sha256Hash, BlockTransaction> blockTxs, Map<String, InscriptionCheck> stored) {
    }

    static final String CHECKING = "Checking inscriptions: %d of %d coins";
    static final String WAITING = "Waiting for a connection to the %s server";
    //Transactions traced so far, per coin and network, shared by every wallet's checks
    private final Map<String, TxCache> traceCaches = new ConcurrentHashMap<>();
    //Coins the user asked to check deeper, until their next check
    private final Map<Wallet, Set<String>> deeper = Collections.synchronizedMap(new IdentityHashMap<>());

    public static synchronized CollectiblesManager get() {
        if(INSTANCE == null) {
            INSTANCE = new CollectiblesManager();
        }
        return INSTANCE;
    }

    public static boolean applies(Wallet wallet) {
        return wallet != null && wallet.getScriptType() != ScriptType.MWEB && wallet.isValid();
    }

    /**
     * True when the wallet's outputs are checked, by an inscriptions server or, on the coins it covers, by the built-in
     * tracker; otherwise HeldOutputs guards them.
     */
    public static boolean checkedByServer(Wallet wallet) {
        Wallet master = wallet != null && wallet.isNested() ? wallet.getMasterWallet() : wallet;
        return applies(master) && (ChainRules.covers(master.getCoin()) || serverUrl(master) != null);
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
            if(ElectrumServer.isConnectedTo(wallet.getCoin()) && ((snapshot != null && snapshot.chainProblem() != null) || waiting(snapshot))) {
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
                    Map<String, InscriptionCheck> stored = new HashMap<>();
                    for(InscriptionCheck check : wallet.getInscriptionChecks()) {
                        stored.put(check.outpoint(), check);
                    }
                    future.complete(new WalletData(CollectiblesClassifier.txos(wallet), CollectiblesClassifier.transactions(wallet), wallet.getWalletUtxos(),
                            new HashMap<>(wallet.getWalletTransactions()), stored));
                } catch(Exception e) {
                    future.completeExceptionally(e);
                }
            });
            WalletData data = future.get(60, TimeUnit.SECONDS);

            String url = serverUrl(wallet);
            boolean tracked = ChainRules.covers(wallet.getCoin());
            if(url == null && !tracked) {
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
                if(url == null) {
                    throw new NoIndexerException();
                }
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
            } catch(NoIndexerException e) {
                //Built-in: the tracker alone decides
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

            if(tracked) {
                track(wallet, data, url, answers, error, chainProblem, runeIndex, indexedHeight, tip);
                return;
            }
            Map<String, CollectibleState> states = CollectiblesClassifier.classify(data.txos(), data.txs(), answers);
            publish(wallet, new Snapshot(states, Map.copyOf(answers), Instant.now(), url, error, true, runeIndex, chainProblem, indexedHeight, tip), answers);
        } catch(Exception e) {
            log.error("Error checking outputs for inscriptions", e);
        }
    }

    /** Raised inside a check when the wallet's coin is set to Built-in, so the indexer step is skipped. */
    private static final class NoIndexerException extends Exception {
        NoIndexerException() {
            super(null, null, false, false);
        }
    }

    /**
     * The built-in tracker's half of a check on a coin it covers: trace the unspent coins over the coin's server (when the
     * app is connected to it), decide each with what the indexer said, store the results, and publish.
     */
    private void track(Wallet wallet, WalletData data, String url, Map<String, OrdOutput> answers, String error, String chainProblem, boolean runeIndex,
                       long indexedHeight, Integer tip) {
        Coin coin = wallet.getCoin();
        Network network = Network.get();
        InscriptionCheck.Source indexerSource = null;
        String notice = null;
        if(url != null) {
            Coin previous = Coin.beginScope(coin);
            try {
                indexerSource = Config.get().getInscriptionServerMode() == InscriptionServerMode.PUBLIC ? InscriptionCheck.Source.PUBLIC : InscriptionCheck.Source.YOUR_OWN;
            } finally {
                Coin.endScope(previous);
            }
            if(error != null || chainProblem != null) {
                notice = (indexerSource == InscriptionCheck.Source.PUBLIC ? "Public" : "Your own") + " indexer unreachable, checking on-chain";
            }
            boolean warned;
            Coin scoped = Coin.beginScope(coin);
            try {
                warned = Config.get().isInscriptionPublicWarningShown();
            } finally {
                Coin.endScope(scoped);
            }
            if(needsPublicNotice(indexerSource, warned)) {
                String host = URI.create(url).getHost();
                Platform.runLater(() -> {
                    Coin shown = Coin.beginScope(coin);
                    try {
                        if(!Config.get().isInscriptionPublicWarningShown()) {
                            Config.get().setInscriptionPublicWarningShown();
                            AppServices.showWarningDialog("Public inscriptions server", host + " will see the outputs of every " + coin.getCapitalizedName()
                                    + " wallet you open, which can link them together. Run your own indexer and choose Your own, or choose Built-in, in Settings > Server to avoid this.");
                        }
                    } finally {
                        Coin.endScope(shown);
                    }
                });
            }
        }

        Set<String> unspent = new HashSet<>();
        for(BlockTransactionHashIndex utxo : data.utxos().keySet()) {
            unspent.add(utxo.toString());
        }
        List<CollectiblesClassifier.Txo> utxos = data.txos().stream().filter(txo -> unspent.contains(txo.outpoint())).toList();
        Map<String, Sha256Hash> blocks = confirmingBlocks(data.blockTxs().values(), ElectrumServer::getRetrievedBlockHash);

        Map<String, InscriptionCheck> checks;
        if(ElectrumServer.isConnectedTo(coin)) {
            Set<String> deep = new HashSet<>(deeper.getOrDefault(wallet, Set.of()));
            Coin previous = Coin.beginScope(coin);
            try {
                ServerChainSource source = ServerChainSource.of(data.blockTxs(), new ElectrumServer());
                TxCache cache = traceCaches.computeIfAbsent(coin.name() + "|" + network.name(), key -> new TxCache());
                checks = TrackerRun.run(coin, network, cache, source, utxos, data.stored(), data.txs(), blocks, answers, indexerSource, deep,
                        (done, total) -> Platform.runLater(() -> EventManager.get().post(new StatusEvent(String.format(CHECKING, done, total)))));
            } finally {
                Coin.endScope(previous);
            }
            if(!deep.isEmpty()) {
                deeper.computeIfPresent(wallet, (key, outpoints) -> {
                    outpoints.removeAll(deep);
                    return outpoints.isEmpty() ? null : outpoints;
                });
            }
        } else {
            checks = waitingChecks(utxos, data.stored(), coin);
        }

        Map<String, CollectibleState> states = new HashMap<>();
        Map<String, OrdOutput> shown = new HashMap<>();
        for(BlockTransactionHashIndex utxo : data.utxos().keySet()) {
            String outpoint = utxo.toString();
            InscriptionCheck check = checks.get(outpoint);
            states.put(outpoint, CheckPolicy.state(check, utxo.getStatus()));
            shown.put(outpoint, check == null ? OrdOutput.unknown(outpoint) : CheckPolicy.asAnswer(check, answers.get(outpoint), utxo.getValue()));
        }
        String indexerNotice = notice;
        publish(wallet, new Snapshot(states, Map.copyOf(shown), Instant.now(), url, error, true, runeIndex, chainProblem, indexedHeight, tip, Map.copyOf(checks),
                indexerNotice), shown);
        if(indexerNotice != null) {
            Platform.runLater(() -> EventManager.get().post(new StatusEvent(indexerNotice)));
        }
        save(wallet, checks);
    }

    /** Stores the checks on the wallet and in its file when they changed. */
    private void save(Wallet wallet, Map<String, InscriptionCheck> checks) {
        Platform.runLater(() -> {
            List<InscriptionCheck> held = wallet.getInscriptionChecks();
            if(new HashSet<>(held).equals(new HashSet<>(checks.values()))) {
                return;
            }
            held.clear();
            held.addAll(checks.values());
            com.sparrowwallet.sparrow.io.Storage storage = AppServices.get().getOpenWallets().get(wallet);
            if(storage != null && storage.isPersisted(wallet)) {
                executor.submit(() -> {
                    try {
                        storage.saveInscriptionChecks(wallet);
                    } catch(Exception e) {
                        log.debug("Could not save inscription checks", e);
                    }
                });
            }
        });
    }

    /**
     * Without a connection to the coin's server nothing is traced and no other coin's server is asked: stored results are
     * kept, every other coin waits.
     */
    static Map<String, InscriptionCheck> waitingChecks(List<CollectiblesClassifier.Txo> utxos, Map<String, InscriptionCheck> stored, Coin coin) {
        Map<String, InscriptionCheck> checks = new LinkedHashMap<>();
        for(CollectiblesClassifier.Txo utxo : utxos) {
            InscriptionCheck kept = stored.get(utxo.outpoint());
            if(kept != null) {
                checks.put(utxo.outpoint(), kept);
            } else {
                int vout = Integer.parseInt(utxo.outpoint().substring(utxo.outpoint().indexOf(':') + 1));
                checks.put(utxo.outpoint(), new InscriptionCheck(Sha256Hash.wrap(utxo.txid()), vout, Verdict.UNCHECKED, InscriptionCheck.Source.BUILT_IN, false,
                        List.of(), String.format(WAITING, coin.getCapitalizedName()), null, List.of()));
            }
        }
        return checks;
    }

    /**
     * The block each confirmed wallet transaction is in: its own block hash when it has one, otherwise the header the app
     * holds for its height. One with neither is left out, so its coin's check is not stored as final.
     */
    static Map<String, Sha256Hash> confirmingBlocks(Collection<BlockTransaction> transactions, java.util.function.IntFunction<Sha256Hash> headerHash) {
        Map<String, Sha256Hash> blocks = new HashMap<>();
        for(BlockTransaction blockTx : transactions) {
            if(blockTx.getHeight() <= 0) {
                continue;
            }
            Sha256Hash hash = blockTx.getBlockHash() != null && !Sha256Hash.ZERO_HASH.equals(blockTx.getBlockHash()) ? blockTx.getBlockHash() : headerHash.apply(blockTx.getHeight());
            if(hash != null) {
                blocks.put(blockTx.getHash().toString(), hash);
            }
        }
        return blocks;
    }

    /** The public indexer sees every output it is asked about: say so once, also when Public is only the default. */
    static boolean needsPublicNotice(InscriptionCheck.Source indexerSource, boolean warned) {
        return indexerSource == InscriptionCheck.Source.PUBLIC && !warned;
    }

    private static boolean waiting(Snapshot snapshot) {
        return snapshot != null && snapshot.checks().values().stream().anyMatch(check -> check.reason() != null && check.reason().startsWith("Waiting for a connection"));
    }

    /** The UTXOs tab's Inscriptions column: Clean, Inscribed (runes too) or Unchecked; empty before the first check. */
    public static String columnText(InscriptionCheck check) {
        if(check == null) {
            return "";
        }
        if(check.verdict() == Verdict.UNCHECKED) {
            return "Unchecked";
        }
        return check.verdict() == Verdict.INSCRIBED || check.runes() ? "Inscribed" : "Clean";
    }

    /** The column's tooltip: why a coin is unchecked, otherwise who checked it (and what it carries). */
    public static String columnTooltip(InscriptionCheck check) {
        if(check == null) {
            return "Not checked yet";
        }
        if(check.verdict() == Verdict.UNCHECKED) {
            return check.reason() == null ? "Not checked yet" : check.reason();
        }
        String by = switch(check.source()) {
            case PUBLIC -> "Checked by Public indexer";
            case YOUR_OWN -> "Checked by Your own indexer";
            case BUILT_IN -> "Checked on-chain";
            case OWN -> "Checked from this wallet's own transactions";
        };
        List<String> ids = check.found().stream().map(com.sparrowwallet.drongo.ordinals.track.Found::inscriptionId).toList();
        if(!ids.isEmpty()) {
            return by + ": " + String.join(", ", ids);
        }
        return check.runes() ? by + ": carries runes or tokens" : by;
    }

    /** Traces one coin again with the larger budget and the block walk, at once. */
    public void checkDeeper(Wallet wallet, BlockTransactionHashIndex utxo) {
        Wallet master = wallet != null && wallet.isNested() ? wallet.getMasterWallet() : wallet;
        if(master == null) {
            return;
        }
        deeper.computeIfAbsent(master, key -> ConcurrentHashMap.newKeySet()).add(utxo.toString());
        refresh(master);
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
        if(!checkedByServer(master)) {
            return HeldOutputs.held(wallet, utxos);
        }
        return unknownIn(snapshots.get(master), utxos);
    }

    /** One line per output for a dialog: the outpoint and, when no server checks the wallet, why it is held. */
    public static String describe(Wallet wallet, Collection<BlockTransactionHashIndex> utxos) {
        boolean server = checkedByServer(wallet);
        Wallet master = wallet != null && wallet.isNested() ? wallet.getMasterWallet() : wallet;
        boolean tracked = master != null && ChainRules.covers(master.getCoin());
        Snapshot snapshot = tracked ? get().getSnapshot(master) : null;
        return utxos.stream().map(utxo -> {
            if(tracked) {
                InscriptionCheck check = snapshot == null ? null : snapshot.checks().get(utxo.toString());
                return utxo + " (" + (check == null || check.reason() == null ? "Not checked yet" : check.reason()) + ")";
            }
            return server ? utxo.toString() : utxo + " (" + HeldOutputs.reason(wallet, utxo) + ")";
        }).collect(java.util.stream.Collectors.joining("\n"));
    }

    /** A filter preferring checked outputs for the wallet (outputs not held, with no server), or null when none applies. */
    public TxoFilter checkedFilter(Wallet wallet) {
        Wallet master = wallet != null && wallet.isNested() ? wallet.getMasterWallet() : wallet;
        if(!applies(master)) {
            return null;
        }
        if(!checkedByServer(master)) {
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
        return nintondo || OrdClient.LEGACY_DIALECT.equals(dialect) || OrdClient.DOGECOIN_HTML_DIALECT.equals(dialect);
    }

    /**
     * Whether a clean answer also rules out tokens. Nintondo indexes inscriptions only, and an ord 0.5 server may run
     * without its token index or report balances under a field it does not share, so neither vouches for tokens.
     */
    static boolean tokensIndexed(String dialect, boolean nintondo) {
        return !(nintondo || OrdClient.LEGACY_DIALECT.equals(dialect) || OrdClient.DOGECOIN_HTML_DIALECT.equals(dialect));
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
