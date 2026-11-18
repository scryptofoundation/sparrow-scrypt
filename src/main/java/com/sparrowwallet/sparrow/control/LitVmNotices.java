package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.evm.EvmAddress;
import com.sparrowwallet.drongo.evm.LitVmNetwork;
import com.sparrowwallet.drongo.evm.LitVmRpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/**
 * The transactions of an account whose fate is not known. A notice is kept from the moment a signed transaction is
 * handed to the network (it may have been broadcast), through the wait for its receipt (sent, still pending), and is
 * removed when the wallet sees it succeed. The LitVM tab keeps the notices in a place of its own, apart from the
 * status line: sending again while one stands could pay twice, so it must not be lost under the next status update,
 * a menu click, or the app closing during the wait.
 *
 * Notices are kept in the config, as chain, account, hash, state and an optional tag, so they outlast the app. No
 * sentence is stored: every word shown is built here from the state alone, and an entry that is not exactly a chain
 * id, an address, a transaction hash, a state and a known tag is ignored. What is written is always the notices as
 * they are when the write runs, never an earlier copy. A write that fails, or is not done in time, stops nothing (a
 * wallet whose config cannot be written must still send): the notice stands for this run, and the tab says that it will
 * not survive a restart.
 *
 * A receipt is only what a server says. One that succeeded removes the notice and does the bookkeeping its tag names
 * (the same trust the wallet gives a balance). One that failed does not settle anything by itself: the notice is
 * reworded and stays, still standing in the way of a second send, until the user has looked and dismisses it.
 *
 * A tag names bookkeeping the send would have done had it been seen to succeed (a bridge row, tracking a created
 * token), as data and not as code, so nothing here holds on to a view. For a transaction that was certainly sent,
 * bookkeeping that is safe while it is pending (a bridge row that only reads the chain) is done at once; everything
 * else when the receipt is seen, in this run or a later one.
 *
 * No JavaFX here: whoever listens moves to its own thread, and what touches the config or the screen runs through the
 * executor given. All watches share one timer ({@link #tick}); each notice is watched for a bounded time, once per
 * run unless the user asks again, and only while its account's tab is open.
 */
final class LitVmNotices {
    private static final Logger log = LoggerFactory.getLogger(LitVmNotices.class);

    /** FAILED: a server reported that it failed on chain. */
    enum State { MAYBE_SENT, NOT_ACCEPTED, PENDING, FAILED }

    /** One transaction whose fate is not known. The hash is the wallet's own (computed from what it signed), lower case. */
    record Notice(LitVmNetwork network, EvmAddress account, String hash, State state, String tag) {
        Notice {
            hash = hash.toLowerCase(Locale.ROOT);
        }

        Notice as(State other) {
            return new Notice(network, account, hash, other, tag);
        }

        /** The same transaction, whatever is said of it. */
        boolean same(Notice other) {
            return network == other.network && hash.equals(other.hash);
        }
    }

    /** Where the notices are kept between runs. */
    interface Store {
        List<String> load();

        /** Keeps exactly these entries; false when they could not be kept. */
        boolean save(List<String> entries);
    }

    /** A transaction's receipt, or empty while it has none. */
    interface Receipts {
        Optional<LitVmRpcClient.Receipt> receipt(LitVmNetwork network, String hash) throws IOException;
    }

    /** The bookkeeping a tag names. */
    interface Tags {
        void record(Notice notice);
    }

    interface Listener {
        /** The notices changed (one added, reworded or removed), or whether they could be kept. Any thread. */
        void changed();

        /** This notice's transaction was seen confirmed in this block, and the notice is gone. Any thread. */
        void confirmed(Notice notice, BigInteger block);
    }

    /** A watch asks every 5 seconds for 10 minutes, and only every sixth time once the server limits requests. */
    static final int WATCH_POLLS = 120;
    static final long TICK_MILLIS = 5_000;
    static final int LIMITED_SKIP = 5;
    /**
     * How many notices one account may have on one network before the tab asks the user to look at the old ones. No
     * notice is ever dropped to make room: each one guards a send.
     */
    static final int MAX_NOTICES = 50;
    /**
     * The most entries made into notices: far beyond what a wallet makes, so only a config filled by hand meets it. The
     * entries past it are not lost: they are written back as they were.
     */
    static final int MAX_LOADED = 5_000;

    private static final Pattern HASH = Pattern.compile("0x[0-9a-f]{64}");
    private static final Pattern TOKEN_TAG = Pattern.compile("token:0x[0-9a-fA-F]{40}:[A-Za-z0-9_+.-]{1,64}");
    static final String BRIDGE_TAG = "bridge:";
    static final String TOKEN_TAG_PREFIX = "token:";
    /** Not bookkeeping: the transaction was a step of a flow whose later steps were not sent. */
    static final String NOT_LAST_TAG = "flow:not-last";
    /** Bridge rows that only read the chain for their hash: safe to keep while the transaction is pending. */
    private static final Set<String> AT_ONCE = Set.of("bridge:zk-deposit", "bridge:zk-withdraw", "bridge:base-deposit");
    /** Bridge rows that say the transfer was submitted: kept only once the transaction is seen confirmed. */
    private static final Set<String> DEFERRED_BRIDGE = Set.of("bridge:jkc-withdraw");

    private final Store store;
    private final Tags tags;
    private final Executor ui;
    private final List<Notice> notices = new ArrayList<>();
    //Stored entries past MAX_LOADED, kept as they were and written back after the notices
    private final List<String> beyond = new ArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    //Changes made, and the change the last write that ran held (whether or not it could be saved): a write always
    //holds the notices as they are when it runs
    private long changes;
    private long attempted;
    //Whether the notices as they are now are only in this run's memory: the last write failed, or one was not done in time
    private boolean unsaved;

    /** One notice's watch. polls is what is left of its time in this run; it is not given back when a watch is asked for again. */
    private static final class Watch {
        private final Notice notice;
        private Receipts receipts;
        private int polls = WATCH_POLLS;
        private boolean active;
        private boolean limited;
        private int skip;

        private Watch(Notice notice) {
            this.notice = notice;
        }
    }

    private final Map<String, Watch> watches = new LinkedHashMap<>();
    private java.util.concurrent.ScheduledExecutorService timer;

    private static LitVmNotices instance;

    /** The app's notices: kept in the config, with the config's bookkeeping, on the FX thread, watched by one timer. */
    static synchronized LitVmNotices get() {
        if(instance == null) {
            instance = new LitVmNotices(new Store() {
                @Override
                public List<String> load() {
                    return com.sparrowwallet.sparrow.io.Config.get().getLitVmNotices();
                }

                @Override
                public boolean save(List<String> entries) {
                    com.sparrowwallet.sparrow.io.Config.get().setLitVmNotices(entries);
                    //The config does not say when it could not be written: read it back
                    return com.sparrowwallet.sparrow.io.Config.holdsOnDisk(entries);
                }
            }, LitVmNotices::applyTag, work -> {
                //At once when already on the FX thread, so what follows there (a refresh) sees it done
                if(javafx.application.Platform.isFxApplicationThread()) {
                    work.run();
                } else {
                    javafx.application.Platform.runLater(work);
                }
            });
            instance.startTimer();
        }
        return instance;
    }

    LitVmNotices(Store store, Tags tags, Executor ui) {
        this.store = store;
        this.tags = tags;
        this.ui = ui;
        for(String entry : store.load()) {
            Optional<Notice> notice = parse(entry);
            if(notice.isEmpty() || find(notice.get()) != null) {
                continue;
            }
            if(notices.size() < MAX_LOADED) {
                notices.add(notice.get());
            } else {
                beyond.add(entry);
            }
        }
    }

    /** One daemon thread for every watch of the app. */
    private synchronized void startTimer() {
        if(timer == null) {
            timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(work -> {
                Thread thread = new Thread(work, "LitVM notices");
                thread.setDaemon(true);
                return thread;
            });
            timer.scheduleWithFixedDelay(() -> {
                try {
                    tick();
                } catch(RuntimeException e) {
                    log.warn("LitVM: a look for receipts failed", e);
                }
            }, TICK_MILLIS, TICK_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    // ---- words: built from the state only ----

    /** What a notice says. Nothing in it comes from a server, a contract or the stored entry's text. */
    static String sentence(State state) {
        return switch(state) {
            case MAYBE_SENT -> "A transaction may have been broadcast. Check the explorer or History before sending again.";
            case NOT_ACCEPTED -> "The server did not accept a transaction, but it may already be known to the network. Check the explorer or History before sending again.";
            case PENDING -> "A transaction was sent and is still pending. Check the explorer or History before sending again.";
            case FAILED -> "The server reports that this transaction failed on chain. Check the explorer before sending again, then dismiss this notice.";
        };
    }

    /** What a notice adds when it was a step of a flow whose later steps were not sent, or null. */
    static String laterSteps(Notice notice) {
        return NOT_LAST_TAG.equals(notice.tag()) ? "The later steps of its flow were not sent." : null;
    }

    static String where(Notice notice) {
        return "On " + notice.network().getDisplayName() + ":";
    }

    /** The status line when a notice's transaction is seen confirmed. */
    static String confirmedLine(Notice notice, BigInteger block) {
        String later = laterSteps(notice);
        return "Confirmed in block " + block + ": " + notice.hash() + (later == null ? "" : ". " + later);
    }

    static final String UNSAVED = "These notices could not be saved. They will not survive a restart.";

    /** The line when an account has very many notices on one network: none is dropped, the user is asked to look. */
    static final String CROWDED = "Many transactions are not yet seen confirmed. Check the old ones in the explorer and dismiss their notices.";

    /** What dismissing a notice gives up, to be confirmed first, or null when it gives up nothing. */
    static String dismissWarning(Notice notice) {
        String tag = notice.tag();
        if(tag == null || NOT_LAST_TAG.equals(tag) || (AT_ONCE.contains(tag) && notice.state() == State.PENDING)) {
            return null;
        }
        return tag.startsWith(TOKEN_TAG_PREFIX)
                ? "If this transaction confirms later, the wallet will not track the token it creates by itself. Use Add Token with the token's contract address then."
                : "If this transaction confirms later, the wallet will not add it to Bridges by itself.";
    }

    static final String AGAIN_TITLE = "Send Again?";
    static final String AGAIN_BUTTON = "Send Anyway";

    /** The question asked before a new send while earlier ones are outstanding. transfer: a plain transfer, else any transaction. */
    static String againHeader(boolean transfer) {
        return "An earlier " + (transfer ? "transfer" : "transaction") + " may still go through.";
    }

    static String againText(List<Notice> outstanding) {
        StringBuilder text = new StringBuilder("Sending again pays twice if it does.\n\nNot yet seen confirmed:");
        for(Notice notice : outstanding) {
            text.append("\n").append(notice.hash()).append(" (").append(notice.network().getDisplayName()).append(")");
        }
        return text.append("\n\nCheck the explorer or History first.").toString();
    }

    // ---- the stored form ----

    static String format(Notice notice) {
        return notice.network().getChainId() + "|" + notice.account() + "|" + notice.hash() + "|" + notice.state().name() + (notice.tag() == null ? "" : "|" + notice.tag());
    }

    /** A stored entry as a notice, or empty when any part of it is not exactly what this class writes. */
    static Optional<Notice> parse(String entry) {
        if(entry == null) {
            return Optional.empty();
        }
        String[] parts = entry.split("\\|", -1);
        if(parts.length != 4 && parts.length != 5) {
            return Optional.empty();
        }
        try {
            long chainId = Long.parseLong(parts[0]);
            LitVmNetwork network = null;
            for(LitVmNetwork candidate : LitVmNetwork.values()) {
                if(candidate.getChainId() == chainId) {
                    network = candidate;
                }
            }
            EvmAddress account = EvmAddress.fromString(parts[1]);
            if(network == null || !account.toString().equals(parts[1]) || !HASH.matcher(parts[2]).matches()) {
                return Optional.empty();
            }
            State state = State.valueOf(parts[3]);
            String tag = parts.length == 5 ? parts[4] : null;
            if(tag != null && !validTag(tag)) {
                return Optional.empty();
            }
            return Optional.of(new Notice(network, account, parts[2], state, tag));
        } catch(IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    static boolean validTag(String tag) {
        return NOT_LAST_TAG.equals(tag) || AT_ONCE.contains(tag) || DEFERRED_BRIDGE.contains(tag) || TOKEN_TAG.matcher(tag).matches();
    }

    static boolean atOnce(String tag) {
        return tag != null && AT_ONCE.contains(tag);
    }

    private static boolean bookkeeping(String tag) {
        return tag != null && !NOT_LAST_TAG.equals(tag);
    }

    /** The tag for a token created by a transaction: tracked, and marked as created here, once the creation is seen confirmed. */
    static String tokenTag(EvmAddress contract, String templateKey) {
        return TOKEN_TAG_PREFIX + contract + ":" + templateKey;
    }

    // ---- the notices ----

    void addListener(Listener listener) {
        listeners.add(listener);
    }

    void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    synchronized List<Notice> of(EvmAddress account) {
        return notices.stream().filter(notice -> notice.account().equals(account)).toList();
    }

    /**
     * The account's notices on these networks: every one stands in the way of a new send until it is seen confirmed
     * or dismissed, a reported failure too.
     */
    synchronized List<Notice> outstanding(EvmAddress account, java.util.Collection<LitVmNetwork> networks) {
        return notices.stream().filter(notice -> notice.account().equals(account) && networks.contains(notice.network())).toList();
    }

    /** Whether the last write of the notices failed: they are then only in this run's memory. */
    synchronized boolean unsaved() {
        return unsaved;
    }

    /** Whether the account has {@link #MAX_NOTICES} notices or more on one network. */
    synchronized boolean crowded(EvmAddress account) {
        Map<LitVmNetwork, Integer> counts = new java.util.EnumMap<>(LitVmNetwork.class);
        for(Notice notice : notices) {
            if(notice.account().equals(account) && counts.merge(notice.network(), 1, Integer::sum) >= MAX_NOTICES) {
                return true;
            }
        }
        return false;
    }

    /** The notice of this transaction that stands now, whatever is said of it, or null. */
    synchronized Notice standing(Notice notice) {
        return find(notice);
    }

    private Notice find(Notice notice) {
        for(Notice kept : notices) {
            if(kept.same(notice)) {
                return kept;
            }
        }
        return null;
    }

    /** Keeps a notice, or what is now said of one already kept. Bookkeeping that is safe at once is done at once. */
    void add(Notice notice) {
        if(!HASH.matcher(notice.hash()).matches() || (notice.tag() != null && !validTag(notice.tag()))) {
            throw new IllegalArgumentException("Not a notice this class keeps");
        }
        synchronized(this) {
            Notice existing = find(notice);
            if(existing != null) {
                notices.set(notices.indexOf(existing), notice);
            } else {
                //Never dropped to make room: every notice here guards a send until it is confirmed or dismissed
                notices.add(notice);
            }
            changed();
        }
        //Only for a transaction that certainly went out: one that may not have gets no receipt
        if(atOnce(notice.tag()) && notice.state() == State.PENDING) {
            ui.execute(() -> tags.record(notice));
        }
        listeners.forEach(Listener::changed);
    }

    /**
     * As {@link #add}, returning once the write of the notice has run, or after {@link #WRITE_WAIT_MILLIS}: for the
     * moment before a signed transaction is handed to the network, so that the notice outlasts the app even if it
     * closes during the request. True when the notice is saved. False when it is not (the write failed, or was not done
     * in time): the caller still sends, the notice stands in this run's memory, and {@link #unsaved} says so until a
     * later write succeeds. Not to be called on the executor's own thread unless the executor runs at once.
     */
    boolean addWritten(Notice notice) {
        add(notice);
        synchronized(this) {
            long mine = changes;
            long end = System.nanoTime() + writeWaitMillis * 1_000_000L;
            while(attempted < mine) {
                long left = (end - System.nanoTime()) / 1_000_000;
                if(left <= 0) {
                    break;
                }
                try {
                    wait(left);
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if(attempted < mine && !unsaved) {
                //Not written in time: until a write succeeds, the notice is not known to be kept
                unsaved = true;
                log.warn("LitVM: the notice of " + notice.hash() + " was not written before its transaction was sent");
                ui.execute(() -> listeners.forEach(Listener::changed));
            }
            return !unsaved;
        }
    }

    /** How long {@link #addWritten} waits for the write. */
    static final long WRITE_WAIT_MILLIS = 5_000;
    //A test waits less
    long writeWaitMillis = WRITE_WAIT_MILLIS;

    /** The user has seen it: the notice goes, and nothing else changes. */
    void dismiss(Notice notice) {
        synchronized(this) {
            Notice existing = find(notice);
            if(existing == null) {
                return;
            }
            notices.remove(existing);
            watches.remove(key(existing));
            changed();
        }
        listeners.forEach(Listener::changed);
    }

    /**
     * The end of a send the caller waited out itself and will finish itself: a receipt that succeeded removes the
     * notice, with no bookkeeping and no word to the listeners beyond the change (the caller does both); one that
     * failed is only a report, as in {@link #resolve}.
     */
    void settle(Notice notice, LitVmRpcClient.Receipt receipt) {
        if(!receipt.success()) {
            resolve(notice, Optional.of(receipt));
            return;
        }
        synchronized(this) {
            Notice existing = find(notice);
            if(existing == null) {
                return;
            }
            notices.remove(existing);
            watches.remove(key(existing));
            changed();
        }
        listeners.forEach(Listener::changed);
    }

    /**
     * What a look at the chain says about a notice. A receipt that succeeded removes it and does its tag's bookkeeping.
     * One that failed is a server's word: the notice is reworded and stays, standing in the way of a new send as before,
     * until the user dismisses it (a later receipt that succeeds still removes it). No receipt changes nothing. True
     * when there was a receipt.
     */
    boolean resolve(Notice notice, Optional<LitVmRpcClient.Receipt> receipt) {
        if(receipt.isEmpty()) {
            return false;
        }
        Notice current;
        synchronized(this) {
            current = find(notice);
            if(current == null) {
                return true;
            }
            if(receipt.get().success()) {
                notices.remove(current);
                watches.remove(key(current));
            } else if(current.state() == State.FAILED) {
                return true;
            } else {
                notices.set(notices.indexOf(current), current.as(State.FAILED));
            }
            changed();
        }
        if(receipt.get().success()) {
            if(bookkeeping(current.tag())) {
                //Before the listeners: what they read again (balances, rows) then holds it
                ui.execute(() -> tags.record(current));
            }
            BigInteger block = receipt.get().blockNumber();
            listeners.forEach(listener -> listener.confirmed(current, block));
        }
        listeners.forEach(Listener::changed);
        return true;
    }

    /** Asks once for a notice's receipt. A failure to ask is no answer. */
    boolean check(Notice notice, Receipts receipts) throws IOException {
        return resolve(notice, receipts.receipt(notice.network(), notice.hash()));
    }

    // ---- watching ----

    /**
     * Watches a notice until its receipt is seen, for what is left of its time in this run ({@link #WATCH_POLLS} looks
     * in all). Asking again for a notice already watched, or whose time is spent, starts nothing: only
     * {@link #checkAgain} gives a notice more time.
     */
    synchronized void watch(Notice notice, Receipts receipts) {
        if(find(notice) == null) {
            return;
        }
        Watch watch = watches.computeIfAbsent(key(notice), key -> new Watch(notice));
        watch.receipts = receipts;
        watch.active = watch.polls > 0;
    }

    /** Stops watching an account's notices (its tab closed). Their time in this run is kept as it is. */
    synchronized void stopWatching(EvmAddress account) {
        for(Watch watch : watches.values()) {
            if(watch.notice.account().equals(account)) {
                watch.active = false;
            }
        }
    }

    /** The user asks: one look now, and when there is no receipt a new watch of the full time. True when there was a receipt. */
    boolean checkAgain(Notice notice, Receipts receipts) throws IOException {
        if(check(notice, receipts)) {
            return true;
        }
        synchronized(this) {
            if(find(notice) != null) {
                Watch watch = watches.computeIfAbsent(key(notice), key -> new Watch(notice));
                watch.receipts = receipts;
                watch.polls = WATCH_POLLS;
                watch.active = true;
            }
        }
        return false;
    }

    /** How many notices are being watched now. */
    synchronized int watching() {
        return (int)watches.values().stream().filter(watch -> watch.active).count();
    }

    /** One round of the shared timer: one look for every notice being watched whose turn it is. */
    void tick() {
        List<Watch> due = new ArrayList<>();
        synchronized(this) {
            for(Watch watch : watches.values()) {
                if(!watch.active) {
                    continue;
                }
                if(watch.skip > 0) {
                    watch.skip--;
                    continue;
                }
                watch.polls--;
                if(watch.polls <= 0) {
                    watch.active = false;
                }
                due.add(watch);
            }
        }
        for(Watch watch : due) {
            try {
                if(check(watch.notice, watch.receipts)) {
                    synchronized(this) {
                        //Settled, or reported failed: either way this watch is over
                        watch.active = false;
                    }
                }
            } catch(LitVmRpcClient.RateLimitedException e) {
                //The server asked for fewer requests: this watch asks more slowly from here on
                synchronized(this) {
                    watch.limited = true;
                }
            } catch(IOException | RuntimeException e) {
                //No answer this time
            }
            synchronized(this) {
                if(watch.limited) {
                    watch.skip = LIMITED_SKIP;
                }
            }
        }
    }

    private static String key(Notice notice) {
        return notice.network().getChainId() + ":" + notice.hash();
    }

    /** Notes a change and asks for a write. Called with the lock held. */
    private void changed() {
        changes++;
        ui.execute(this::write);
    }

    /** Writes the notices as they are now, whatever they were when the write was asked for. */
    private void write() {
        List<String> entries;
        long holds;
        synchronized(this) {
            entries = new ArrayList<>(notices.stream().map(LitVmNotices::format).toList());
            entries.addAll(beyond);
            holds = changes;
        }
        boolean saved;
        try {
            saved = store.save(entries);
        } catch(RuntimeException e) {
            log.warn("LitVM: could not keep the notices", e);
            saved = false;
        }
        boolean tell;
        synchronized(this) {
            //The write ran, whether or not it saved: a waiting sender goes on, and unsaved tells which it was
            if(holds > attempted) {
                attempted = holds;
            }
            tell = unsaved == saved;
            unsaved = !saved;
            notifyAll();
        }
        if(!saved) {
            log.warn("LitVM: the notices could not be saved; they will not survive a restart");
        }
        if(tell) {
            listeners.forEach(Listener::changed);
        }
    }

    // ---- the app's bookkeeping for a tag ----

    private static void applyTag(Notice notice) {
        String tag = notice.tag();
        if(!bookkeeping(tag)) {
            return;
        }
        try {
            if(tag.startsWith(BRIDGE_TAG)) {
                LitVmBridgePane.record(notice.account(), tag.substring(BRIDGE_TAG.length()), notice.hash());
            } else if(tag.startsWith(TOKEN_TAG_PREFIX)) {
                String[] parts = tag.split(":");
                EvmAddress contract = EvmAddress.fromString(parts[1]);
                //An unknown template key is refused here, as it is when the created tokens are read
                com.sparrowwallet.drongo.evm.Erc20Templates.Options.fromKey(parts[2]);
                LitVmContext.track(notice.network(), contract);
                LitVmContext.recordCreated(notice.network(), contract, parts[2]);
            }
        } catch(RuntimeException e) {
            log.warn("LitVM: could not do the bookkeeping for " + notice.hash(), e);
        }
    }

    /** The app's bookkeeping for a tag: the bridge history and the tracked and created tokens, in the config. */
    static Tags appTags() {
        return LitVmNotices::applyTag;
    }
}
