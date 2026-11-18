package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.evm.*;
import com.sparrowwallet.drongo.wallet.LitVmLink;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.wallet.WalletForm;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.stage.Window;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * What every view of the LitVM sub-tab shares: the linked account, one RPC client per network, the tab's status line,
 * the tracked tokens (kept per chain in the config), and a way to tell the other views that balances changed.
 */
final class LitVmContext {
    final WalletForm walletForm;
    private final Map<LitVmNetwork, LitVmRpcClient> clients = new EnumMap<>(LitVmNetwork.class);
    private final Consumer<String> status;
    private final Supplier<Window> window;
    private final List<Runnable> balanceListeners = new ArrayList<>();

    LitVmContext(WalletForm walletForm, Consumer<String> status, Supplier<Window> window) {
        this.walletForm = walletForm;
        this.status = status;
        this.window = window;
    }

    Wallet masterWallet() {
        Wallet wallet = walletForm.getWallet();
        return wallet.isMasterWallet() ? wallet : wallet.getMasterWallet();
    }

    LitVmLink link() {
        return masterWallet().getLitVmLink();
    }

    EvmAddress address() {
        return link().getAddress();
    }

    LitVmRpcClient client(LitVmNetwork network) {
        //Every client goes by the tab's transport, which follows the proxy setting at each request
        return clients.computeIfAbsent(network, chain -> new LitVmRpcClient(chain, transport()));
    }

    /**
     * The transport of every client this tab makes: the one for its wallet's coin, since the proxy setting is kept per
     * coin and is read on threads that have no coin scope. The LitVM tab belongs to a Litecoin wallet, which is also
     * what a wallet that names no coin is.
     */
    LitVmTransport transport() {
        return transportFor(walletForm == null ? null : masterWallet());
    }

    static LitVmTransport transportFor(Wallet wallet) {
        com.sparrowwallet.drongo.Coin coin = wallet == null ? null : wallet.getCoin();
        return LitVmTransport.forCoin(coin == null ? com.sparrowwallet.drongo.Coin.LITECOIN : coin);
    }

    Window window() {
        return window.get();
    }

    void status(String text) {
        if(Platform.isFxApplicationThread()) {
            status.accept(text);
        } else {
            Platform.runLater(() -> status.accept(text));
        }
    }

    Consumer<String> statusConsumer() {
        return this::status;
    }

    void onBalancesChanged(Runnable listener) {
        balanceListeners.add(listener);
    }

    /** Called after any transaction completes, so every view re-reads what it shows. */
    void balancesChanged() {
        balanceListeners.forEach(Runnable::run);
    }

    void showDocument(String url) {
        AppServices.get().getApplication().getHostServices().showDocument(url);
    }

    static String shortAddress(EvmAddress address) {
        String text = address.toString();
        return text.substring(0, 6) + "..." + text.substring(text.length() - 4);
    }

    /** The endpoint answered for another chain than the network's. */
    static final class WrongChainException extends IOException {
        WrongChainException() {
            super("The endpoint is for another chain");
        }
    }

    /**
     * The Collectibles section's chain check: the endpoint must answer this network's chain id. Another chain is a
     * {@link WrongChainException}, so the section can say so in its own words; an answer that is not a chain id is a
     * plain failure. (Only reading is done here: nothing is signed, so the client's own verifyChain is not needed.)
     */
    static void checkChain(LitVmRpcClient client) throws IOException {
        long reported;
        try {
            reported = client.chainId();
        } catch(IllegalArgumentException | ArithmeticException e) {
            throw new IOException("eth_chainId returned an invalid answer", e);
        }
        if(reported != client.getNetwork().getChainId()) {
            throw new WrongChainException();
        }
    }

    /** The add flow's read of a contract: the chain check, then the collection, within one collection's time. */
    static Optional<Erc721Collection> readToAdd(LitVmRpcClient client, EvmAddress contract) throws IOException {
        LitVmRpcClient bounded = client.withDeadline(COLLECTION_BUDGET);
        checkChain(bounded);
        return Erc721.read(bounded, contract);
    }

    /**
     * The chain check before anything is prepared or signed: another chain is a {@link WrongChainException}, so a view
     * can say so in its own words, and the client's own check then marks the client as verified, which sending needs.
     */
    static void verifyChain(LitVmRpcClient client) throws IOException {
        checkChain(client);
        client.verifyChain();
    }

    /** A form's label: it keeps its own width when the window is narrow (the field beside it gives way), never cut to "...". */
    static javafx.scene.control.Label formLabel(String text) {
        javafx.scene.control.Label label = new javafx.scene.control.Label(text);
        label.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        return label;
    }

    static void start(Task<?> task) {
        Thread thread = new Thread(task, "LitVM");
        thread.setDaemon(true);
        thread.start();
    }

    // ---- tracked tokens, per chain ----

    /**
     * Tracked token contracts on a network, from the config: the user's own list (contracts are public, not wallet
     * secrets). Entries are "chainId:0x..."; a bare "0x..." entry predates other networks and means LiteForge.
     */
    static List<EvmAddress> trackedTokens(LitVmNetwork network) {
        List<EvmAddress> tokens = new ArrayList<>();
        for(String entry : Config.get().getLitVmTokens()) {
            int colon = entry.indexOf(':');
            long chainId = colon < 0 ? LitVmNetwork.LITEFORGE_TESTNET.getChainId() : parseChainId(entry.substring(0, colon));
            if(chainId != network.getChainId()) {
                continue;
            }
            try {
                EvmAddress address = EvmAddress.fromString(colon < 0 ? entry : entry.substring(colon + 1));
                if(!tokens.contains(address)) {
                    tokens.add(address);
                }
            } catch(IllegalArgumentException e) {
                //skip a malformed entry rather than failing the tab
            }
        }
        return tokens;
    }

    static void track(LitVmNetwork network, EvmAddress contract) {
        List<String> tokens = Config.get().getLitVmTokens();
        if(tokens.stream().noneMatch(existing -> sameEntry(existing, network, contract))) {
            tokens.add(network.getChainId() + ":" + contract);
            Config.get().setLitVmTokens(tokens);
        }
    }

    static void untrack(LitVmNetwork network, EvmAddress contract) {
        List<String> tokens = Config.get().getLitVmTokens();
        tokens.removeIf(existing -> sameEntry(existing, network, contract));
        Config.get().setLitVmTokens(tokens);
    }

    // ---- watched NFT collections, per chain ----

    /**
     * Collections the user watches on a network, from the config. Entries are "chainId:0x..."; anything else, or an
     * entry for another chain, is ignored.
     */
    static List<EvmAddress> watchedCollections(LitVmNetwork network) {
        List<EvmAddress> watched = new ArrayList<>();
        for(String entry : Config.get().getLitVmNfts()) {
            //A hand-edited config can hold null or other junk: ignore it
            if(entry == null) {
                continue;
            }
            int colon = entry.indexOf(':');
            if(colon < 0 || parseChainId(entry.substring(0, colon)) != network.getChainId()) {
                continue;
            }
            try {
                EvmAddress address = EvmAddress.fromString(entry.substring(colon + 1).trim());
                if(!watched.contains(address)) {
                    watched.add(address);
                }
            } catch(IllegalArgumentException e) {
                //skip a malformed entry rather than failing the tab
            }
        }
        return watched;
    }

    /** The collections listed on a network: the known ones, always, then the watched ones. */
    static List<EvmAddress> collections(LitVmNetwork network) {
        List<EvmAddress> collections = new ArrayList<>(KnownCollections.on(network));
        for(EvmAddress watched : watchedCollections(network)) {
            if(!collections.contains(watched)) {
                collections.add(watched);
            }
        }
        return collections;
    }

    /** Watches a collection; false when it is known (always listed) or already watched. */
    static boolean watchCollection(LitVmNetwork network, EvmAddress contract) {
        if(KnownCollections.isKnown(network, contract) || watchedCollections(network).contains(contract)) {
            return false;
        }
        List<String> entries = Config.get().getLitVmNfts();
        entries.add(network.getChainId() + ":" + contract);
        Config.get().setLitVmNfts(entries);
        return true;
    }

    /** Stops watching a collection; false when it is known, which cannot be removed, or was not watched. */
    static boolean unwatchCollection(LitVmNetwork network, EvmAddress contract) {
        if(KnownCollections.isKnown(network, contract)) {
            return false;
        }
        List<String> entries = Config.get().getLitVmNfts();
        boolean removed = entries.removeIf(entry -> entry != null && entry.indexOf(':') > 0 && sameEntry(entry, network, contract));
        if(removed) {
            Config.get().setLitVmNfts(entries);
        }
        return removed;
    }

    /** RATE_LIMITED: not asked, because the server limited requests earlier in this refresh. */
    enum CollectionStatus { LISTED, FAILED, NEEDS_EXPLORER, NOT_A_COLLECTION, NOT_REACHED, RATE_LIMITED }

    /**
     * One collection's answer for the account: its tokens, or why there are none to show. collection is null when the
     * contract could not be read as a collection. rateLimited is set on the collection whose read or listing the
     * server limited (HTTP 429), and on those not asked after it.
     */
    record CollectionResult(EvmAddress contract, boolean known, Erc721Collection collection, CollectionStatus status, List<Nft> tokens, int more,
                            int failed, Optional<String> lookalike, boolean rateLimited) {
        CollectionResult {
            tokens = List.copyOf(tokens);
        }

        CollectionResult(EvmAddress contract, boolean known, Erc721Collection collection, CollectionStatus status, List<Nft> tokens, int more,
                         int failed, Optional<String> lookalike) {
            this(contract, known, collection, status, tokens, more, failed, lookalike, false);
        }

        CollectionResult(EvmAddress contract, boolean known, Erc721Collection collection, CollectionStatus status, List<Nft> tokens, int more) {
            this(contract, known, collection, status, tokens, more, 0, Optional.empty());
        }

        /** A collection the refresh ran out of time to read. */
        static CollectionResult notReached(EvmAddress contract, boolean known) {
            return new CollectionResult(contract, known, null, CollectionStatus.NOT_REACHED, List.of(), 0);
        }

        /** A collection not asked because the server limited requests earlier in the refresh. */
        static CollectionResult notAsked(EvmAddress contract, boolean known) {
            return new CollectionResult(contract, known, null, CollectionStatus.RATE_LIMITED, List.of(), 0, 0, Optional.empty(), true);
        }
    }

    /** One refresh's time for every collection: those not reached by then are counted, not read. */
    static final java.time.Duration REFRESH_BUDGET = java.time.Duration.ofMinutes(3);

    /** A collection's time, started at now in a refresh started at start: its minute, cut to the refresh's time left; 0 when none is left. */
    static long collectionBudgetNanos(long start, long now) {
        long left = REFRESH_BUDGET.toNanos() - (now - start);
        return left <= 0 ? 0 : Math.min(COLLECTION_BUDGET.toNanos(), left);
    }

    /** One collection's time to answer: reading it and listing the account's tokens in it. */
    static final java.time.Duration COLLECTION_BUDGET = java.time.Duration.ofSeconds(60);

    /**
     * Every listed collection on a network with the account's tokens in it, read off the FX thread. Only reaching the
     * network fails the whole read; one collection failing is that collection's FAILED result. Each collection has
     * {@link #COLLECTION_BUDGET} for reading it and listing its tokens (no call starts after it), so the whole read
     * takes at most a minute per listed collection. An added collection that takes a built-in one's name on this read
     * carries the built-in one's name in lookalike. Once the server limits requests (HTTP 429), whether reading a collection
     * or inside its listing, no further collection is asked: those left are RATE_LIMITED, and the results say so.
     */
    List<CollectionResult> readCollectibles(LitVmNetwork network) throws IOException {
        return readCollectibles(client(network), address());
    }

    /** As {@link #readCollectibles(LitVmNetwork)} for any owner, through this client (the account's view passes its own). */
    static List<CollectionResult> readCollectibles(LitVmRpcClient client, EvmAddress me) throws IOException {
        return readCollectibles(client, me, System::nanoTime);
    }

    static List<CollectionResult> readCollectibles(LitVmRpcClient client, EvmAddress me, java.util.function.LongSupplier nanoTime) throws IOException {
        long start = nanoTime.getAsLong();
        LitVmNetwork network = client.getNetwork();
        checkChain(client);
        List<CollectionResult> results = new ArrayList<>();
        //Once the server limits requests (HTTP 429), no further collection is asked in this refresh
        boolean limited = false;
        for(EvmAddress contract : collections(network)) {
            if(Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Stopped");
            }
            boolean known = KnownCollections.isKnown(network, contract);
            if(limited) {
                results.add(CollectionResult.notAsked(contract, known));
                continue;
            }
            long budget = collectionBudgetNanos(start, nanoTime.getAsLong());
            if(budget <= 0) {
                results.add(CollectionResult.notReached(contract, known));
                continue;
            }
            LitVmRpcClient bounded = client.withDeadline(java.time.Duration.ofNanos(budget));
            Erc721Collection collection = null;
            Optional<String> lookalike = Optional.empty();
            try {
                collection = Erc721.read(bounded, contract).orElse(null);
                if(collection == null) {
                    results.add(new CollectionResult(contract, known, null, CollectionStatus.NOT_A_COLLECTION, List.of(), 0));
                    continue;
                }
                //A contract can rename itself after it was added: checked on every read
                lookalike = known ? Optional.empty() : KnownCollections.lookalikeOf(network, collection);
                //The collection was just read: the lister does not read it again
                NftLister.Result listed = NftLister.list(bounded, collection, me);
                Erc721Collection read = collection;
                Optional<String> imitates = lookalike;
                results.add(switch(listed) {
                    case NftLister.Listed l -> new CollectionResult(contract, known, read, CollectionStatus.LISTED, l.tokens(), l.more(), l.failed(), imitates,
                            l.rateLimited());
                    case NftLister.NeedsExplorer n -> new CollectionResult(contract, known, read, CollectionStatus.NEEDS_EXPLORER, List.of(), 0, 0, imitates);
                    case NftLister.NotACollection n -> new CollectionResult(contract, known, read, CollectionStatus.NOT_A_COLLECTION, List.of(), 0, 0, imitates);
                });
                limited = listed instanceof NftLister.Listed l && l.rateLimited();
            } catch(LitVmRpcClient.RateLimitedException e) {
                //A collection-level read (the collection, worlds(), balanceOf) the server limited
                results.add(new CollectionResult(contract, known, collection, CollectionStatus.FAILED, List.of(), 0, 0, lookalike, true));
                limited = true;
            } catch(IOException | RuntimeException e) {
                results.add(new CollectionResult(contract, known, collection, CollectionStatus.FAILED, List.of(), 0, 0, lookalike));
            }
        }
        return results;
    }

    /** Tokens deployed from this app on a network, with the template each was made from. */
    static Map<EvmAddress, String> createdTokens(LitVmNetwork network) {
        Map<EvmAddress, String> created = new LinkedHashMap<>();
        for(String entry : Config.get().getLitVmCreatedTokens()) {
            String[] parts = entry.split(":");
            if(parts.length != 3 || parseChainId(parts[0]) != network.getChainId()) {
                continue;
            }
            try {
                Erc20Templates.Options.fromKey(parts[2]);
                created.put(EvmAddress.fromString(parts[1]), parts[2]);
            } catch(IllegalArgumentException e) {
                //skip a malformed entry
            }
        }
        return created;
    }

    static void recordCreated(LitVmNetwork network, EvmAddress contract, String key) {
        List<String> created = Config.get().getLitVmCreatedTokens();
        String entry = network.getChainId() + ":" + contract + ":" + key;
        //Once only: a creation seen pending and later confirmed is marked by both
        if(!created.contains(entry)) {
            created.add(entry);
            Config.get().setLitVmCreatedTokens(created);
        }
    }

    private static long parseChainId(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch(NumberFormatException e) {
            return -1;
        }
    }

    private static boolean sameEntry(String entry, LitVmNetwork network, EvmAddress contract) {
        int colon = entry.indexOf(':');
        long chainId = colon < 0 ? LitVmNetwork.LITEFORGE_TESTNET.getChainId() : parseChainId(entry.substring(0, colon));
        return chainId == network.getChainId() && (colon < 0 ? entry : entry.substring(colon + 1)).trim().equalsIgnoreCase(contract.toString());
    }

    /**
     * The native balance and every tracked token with its balance on a network, read off the FX thread. unread holds
     * the tracked contracts that could not be read: their addresses only, never what the server said about them.
     */
    record Balances(LitVmNetwork network, BigInteger nativeBalance, List<Erc20Token> tokens, List<BigInteger> tokenBalances, List<EvmAddress> unread) {}

    Balances readBalances(LitVmNetwork network) throws Exception {
        return readBalances(client(network), address());
    }

    /** As {@link #readBalances(LitVmNetwork)} for any account, through this client. */
    static Balances readBalances(LitVmRpcClient client, EvmAddress me) throws IOException {
        LitVmNetwork network = client.getNetwork();
        verifyChain(client);
        BigInteger nativeBalance = client.getBalance(me);
        List<Erc20Token> tokens = new ArrayList<>();
        List<BigInteger> balances = new ArrayList<>();
        List<EvmAddress> unread = new ArrayList<>();
        for(EvmAddress contract : trackedTokens(network)) {
            try {
                Erc20Token token = client.readToken(contract);
                BigInteger balance = client.getTokenBalance(token, me);
                tokens.add(token);
                balances.add(balance);
            } catch(Exception e) {
                LitVmWords.logOnly("could not read tracked token " + contract, e);
                unread.add(contract);
            }
        }
        return new Balances(network, nativeBalance, tokens, balances, unread);
    }
}
