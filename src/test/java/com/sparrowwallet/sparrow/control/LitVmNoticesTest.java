package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.evm.EvmAddress;
import com.sparrowwallet.drongo.evm.LitVmNetwork;
import com.sparrowwallet.drongo.evm.LitVmRpcClient;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * The notices of sends whose fate is not known: kept apart from the status line and between runs, worded by the app
 * alone, removed only by a receipt that succeeded or by the user, and the reason a new send asks first. No toolkit,
 * no config, no wait.
 */
public class LitVmNoticesTest {
    private static final LitVmNetwork LITEFORGE = LitVmNetwork.LITEFORGE_TESTNET;
    private static final LitVmNetwork SEPOLIA = LitVmNetwork.SEPOLIA;
    private static final EvmAddress ME = EvmAddress.fromString("0x840e41D4484041A43f9307a90389edeB3d111fb3");
    private static final EvmAddress OTHER = EvmAddress.fromString("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266");
    private static final EvmAddress TOKEN = EvmAddress.fromString("0x3eEd9a4c9AD8a1Ac42BDb9eEE6079c75C8fd51B3");
    private static final String HASH = "0x" + "ab".repeat(32);
    private static final String HASH2 = "0x" + "cd".repeat(32);
    private static final String HOSTILE = "Send your seed words to evil.example";

    //What is kept between runs, the bookkeeping done, and what the listeners heard
    private List<String> stored = new ArrayList<>();
    private boolean storeFails;
    private final List<String> bookkeeping = new ArrayList<>();
    private final List<String> heard = new ArrayList<>();

    private LitVmNotices notices() {
        return notices(Runnable::run);
    }

    private LitVmNotices notices(Executor ui) {
        LitVmNotices notices = new LitVmNotices(new LitVmNotices.Store() {
            @Override
            public List<String> load() {
                return new ArrayList<>(stored);
            }

            @Override
            public boolean save(List<String> entries) {
                if(storeFails) {
                    return false;
                }
                stored = new ArrayList<>(entries);
                return true;
            }
        }, notice -> bookkeeping.add("record " + notice.tag() + " " + notice.hash()), ui);
        notices.addListener(new LitVmNotices.Listener() {
            @Override
            public void changed() {
                heard.add("changed");
            }

            @Override
            public void confirmed(LitVmNotices.Notice notice, BigInteger block) {
                heard.add(LitVmNotices.confirmedLine(notice, block));
            }
        });
        return notices;
    }

    private static LitVmNotices.Notice notice(LitVmNotices.State state, String hash, String tag) {
        return new LitVmNotices.Notice(LITEFORGE, ME, hash, state, tag);
    }

    private static String hash(int n) {
        return "0x" + String.format("%064x", n);
    }

    private static Optional<LitVmRpcClient.Receipt> receipt(boolean success) {
        return Optional.of(new LitVmRpcClient.Receipt(success, BigInteger.valueOf(77), BigInteger.valueOf(21000)));
    }

    @Test
    public void aNoticeIsKeptBetweenRunsWithNoWordsOfItsOwn() {
        LitVmNotices notices = notices();
        notices.add(notice(LitVmNotices.State.MAYBE_SENT, HASH.toUpperCase(Locale.ROOT).replace("0X", "0x"), null));
        notices.add(notice(LitVmNotices.State.PENDING, HASH2, "bridge:zk-withdraw"));
        Assertions.assertEquals(List.of(LITEFORGE.getChainId() + "|" + ME + "|" + HASH + "|MAYBE_SENT", LITEFORGE.getChainId() + "|" + ME + "|" + HASH2 + "|PENDING|bridge:zk-withdraw"), stored);

        //Another run reads the same notices back
        LitVmNotices again = notices();
        Assertions.assertEquals(List.of(notice(LitVmNotices.State.MAYBE_SENT, HASH, null), notice(LitVmNotices.State.PENDING, HASH2, "bridge:zk-withdraw")), again.of(ME));
        Assertions.assertEquals(List.of(), again.of(OTHER));
        for(LitVmNotices.Notice notice : again.of(ME)) {
            Assertions.assertEquals(Optional.of(notice), LitVmNotices.parse(LitVmNotices.format(notice)));
        }
        //The same transaction is one notice, whatever is said of it later
        again.add(notice(LitVmNotices.State.PENDING, HASH, null));
        Assertions.assertEquals(2, again.of(ME).size());
        Assertions.assertEquals(LitVmNotices.State.PENDING, again.of(ME).getFirst().state());
    }

    @Test
    public void anEntryThatIsNotExactlyANoticeIsIgnored() {
        String good = LITEFORGE.getChainId() + "|" + ME + "|" + HASH + "|MAYBE_SENT";
        Assertions.assertTrue(LitVmNotices.parse(good).isPresent());
        Assertions.assertTrue(LitVmNotices.parse(good + "|bridge:jkc-withdraw").isPresent());
        Assertions.assertTrue(LitVmNotices.parse(good + "|" + LitVmNotices.NOT_LAST_TAG).isPresent());
        Assertions.assertTrue(LitVmNotices.parse(good + "|" + LitVmNotices.tokenTag(TOKEN, "mintable_bp")).isPresent());
        List<String> junk = new ArrayList<>(List.of("", "|||", HOSTILE, good + "|" + HOSTILE, good + "|bridge:" + HOSTILE, good + "|token:" + TOKEN + ":" + HOSTILE,
                good + "|flow:" + HOSTILE, good + "|bridge:zk-deposit|more", "1|" + ME + "|" + HASH + "|MAYBE_SENT", "x|" + ME + "|" + HASH + "|MAYBE_SENT",
                LITEFORGE.getChainId() + "|" + HOSTILE + "|" + HASH + "|MAYBE_SENT", LITEFORGE.getChainId() + "|" + ME.toString().toLowerCase(Locale.ROOT) + "|" + HASH + "|MAYBE_SENT",
                LITEFORGE.getChainId() + "|" + ME + "|" + HOSTILE + "|MAYBE_SENT", LITEFORGE.getChainId() + "|" + ME + "|" + HASH.substring(0, 60) + "|MAYBE_SENT",
                LITEFORGE.getChainId() + "|" + ME + "|" + HASH.toUpperCase(Locale.ROOT) + "|MAYBE_SENT", LITEFORGE.getChainId() + "|" + ME + "|" + HASH + "|" + HOSTILE,
                LITEFORGE.getChainId() + "|" + ME + "|" + HASH + "|maybe_sent", LITEFORGE.getChainId() + "|" + ME + "|" + HASH));
        junk.add(null);
        for(String entry : junk) {
            Assertions.assertTrue(LitVmNotices.parse(entry).isEmpty(), String.valueOf(entry));
        }
        //A config written by hand: only the real notice is shown, and nothing of the rest
        stored = new ArrayList<>(junk);
        stored.add(good);
        Assertions.assertEquals(List.of(notice(LitVmNotices.State.MAYBE_SENT, HASH, null)), notices().of(ME));
        //Nor does this class keep what it would not read back
        Assertions.assertThrows(IllegalArgumentException.class, () -> notices().add(notice(LitVmNotices.State.PENDING, HASH, "bridge:" + HOSTILE)));
        Assertions.assertThrows(IllegalArgumentException.class, () -> notices().add(notice(LitVmNotices.State.PENDING, HOSTILE, null)));
    }

    @Test
    public void everyWordOfANoticeIsTheAppsOwn() {
        List<String> shown = new ArrayList<>();
        for(LitVmNotices.State state : LitVmNotices.State.values()) {
            shown.add(LitVmNotices.sentence(state));
        }
        LitVmNotices.Notice notice = notice(LitVmNotices.State.MAYBE_SENT, HASH, null);
        LitVmNotices.Notice step = notice(LitVmNotices.State.PENDING, HASH2, LitVmNotices.NOT_LAST_TAG);
        shown.add(LitVmNotices.where(notice));
        shown.add(LitVmNotices.confirmedLine(notice, BigInteger.TEN));
        shown.add(LitVmNotices.confirmedLine(step, BigInteger.TEN));
        shown.add(LitVmNotices.laterSteps(step));
        shown.add(LitVmNotices.UNSAVED);
        shown.add(LitVmNotices.CROWDED);
        shown.add(LitVmNotices.dismissWarning(notice(LitVmNotices.State.PENDING, HASH, "bridge:jkc-withdraw")));
        shown.add(LitVmNotices.dismissWarning(notice(LitVmNotices.State.PENDING, HASH, LitVmNotices.tokenTag(TOKEN, "mintable_bp"))));
        shown.add(LitVmNotices.againHeader(true));
        shown.add(LitVmNotices.againHeader(false));
        shown.add(LitVmNotices.againText(List.of(notice, new LitVmNotices.Notice(SEPOLIA, ME, HASH2, LitVmNotices.State.PENDING, null))));
        for(String text : shown) {
            String lower = text.toLowerCase(Locale.ROOT);
            Assertions.assertFalse(lower.contains("evil") || lower.contains("seed"), text);
        }
        Assertions.assertEquals("A transaction may have been broadcast. Check the explorer or History before sending again.", LitVmNotices.sentence(LitVmNotices.State.MAYBE_SENT));
        Assertions.assertEquals("The server did not accept a transaction, but it may already be known to the network. Check the explorer or History before sending again.",
                LitVmNotices.sentence(LitVmNotices.State.NOT_ACCEPTED));
        Assertions.assertEquals("A transaction was sent and is still pending. Check the explorer or History before sending again.", LitVmNotices.sentence(LitVmNotices.State.PENDING));
        //A failed receipt is a server's word: the notice says so, and asks the user to look and then dismiss it
        Assertions.assertEquals("The server reports that this transaction failed on chain. Check the explorer before sending again, then dismiss this notice.",
                LitVmNotices.sentence(LitVmNotices.State.FAILED));
        Assertions.assertEquals("On " + LITEFORGE.getDisplayName() + ":", LitVmNotices.where(notice));
        Assertions.assertEquals("Confirmed in block 10: " + HASH, LitVmNotices.confirmedLine(notice, BigInteger.TEN));
        //A step of a flow that confirms later: the later steps were not sent, and the line says so
        Assertions.assertNull(LitVmNotices.laterSteps(notice));
        Assertions.assertEquals("The later steps of its flow were not sent.", LitVmNotices.laterSteps(step));
        Assertions.assertEquals("Confirmed in block 10: " + HASH2 + ". The later steps of its flow were not sent.", LitVmNotices.confirmedLine(step, BigInteger.TEN));
        Assertions.assertEquals("These notices could not be saved. They will not survive a restart.", LitVmNotices.UNSAVED);
        Assertions.assertEquals("Many transactions are not yet seen confirmed. Check the old ones in the explorer and dismiss their notices.", LitVmNotices.CROWDED);
        //The question before sending again names each outstanding hash and its network
        Assertions.assertEquals("An earlier transfer may still go through.", LitVmNotices.againHeader(true));
        Assertions.assertEquals("An earlier transaction may still go through.", LitVmNotices.againHeader(false));
        Assertions.assertEquals("Sending again pays twice if it does.\n\nNot yet seen confirmed:\n" + HASH + " (" + LITEFORGE.getDisplayName() + ")\n" + HASH2 + " ("
                        + SEPOLIA.getDisplayName() + ")\n\nCheck the explorer or History first.",
                LitVmNotices.againText(List.of(notice, new LitVmNotices.Notice(SEPOLIA, ME, HASH2, LitVmNotices.State.PENDING, null))));
    }

    @Test
    public void dismissingSaysWhatWillNotBeDone() {
        //Nothing to give up: no tag, a step of a flow, or a bridge row already kept for a pending transaction
        Assertions.assertNull(LitVmNotices.dismissWarning(notice(LitVmNotices.State.MAYBE_SENT, HASH, null)));
        Assertions.assertNull(LitVmNotices.dismissWarning(notice(LitVmNotices.State.PENDING, HASH, LitVmNotices.NOT_LAST_TAG)));
        Assertions.assertNull(LitVmNotices.dismissWarning(notice(LitVmNotices.State.PENDING, HASH, "bridge:zk-withdraw")));
        //Bookkeeping that still waits for the receipt would be lost
        String bridge = "If this transaction confirms later, the wallet will not add it to Bridges by itself.";
        Assertions.assertEquals(bridge, LitVmNotices.dismissWarning(notice(LitVmNotices.State.PENDING, HASH, "bridge:jkc-withdraw")));
        Assertions.assertEquals(bridge, LitVmNotices.dismissWarning(notice(LitVmNotices.State.MAYBE_SENT, HASH, "bridge:zk-withdraw")));
        Assertions.assertEquals(bridge, LitVmNotices.dismissWarning(notice(LitVmNotices.State.FAILED, HASH, "bridge:zk-deposit")));
        Assertions.assertEquals("If this transaction confirms later, the wallet will not track the token it creates by itself. Use Add Token with the token's contract address then.",
                LitVmNotices.dismissWarning(notice(LitVmNotices.State.PENDING, HASH, LitVmNotices.tokenTag(TOKEN, "mintable_bp"))));
    }

    @Test
    public void onlyASuccessOrTheUserRemovesANotice() throws IOException {
        LitVmNotices notices = notices();
        LitVmNotices.Notice maybe = notice(LitVmNotices.State.MAYBE_SENT, HASH, null);
        notices.add(maybe);
        //It is why a new send on that network asks first, and not on another network or for another account
        Assertions.assertEquals(List.of(maybe), notices.outstanding(ME, List.of(LITEFORGE)));
        Assertions.assertEquals(List.of(), notices.outstanding(ME, List.of(SEPOLIA)));
        Assertions.assertEquals(List.of(), notices.outstanding(OTHER, List.of(LITEFORGE)));

        //No receipt, and a failure to ask, change nothing
        Assertions.assertFalse(notices.resolve(maybe, Optional.empty()));
        Assertions.assertThrows(IOException.class, () -> notices.check(maybe, (network, hash) -> {
            throw new IOException(HOSTILE);
        }));
        Assertions.assertEquals(List.of(maybe), notices.of(ME));

        //A receipt that failed is a server's word: the notice is reworded and stays, and still stands in the way of a send
        heard.clear();
        Assertions.assertTrue(notices.check(maybe, (network, hash) -> receipt(false)));
        LitVmNotices.Notice failed = maybe.as(LitVmNotices.State.FAILED);
        Assertions.assertEquals(List.of(failed), notices.of(ME));
        Assertions.assertEquals(List.of(failed), notices.outstanding(ME, List.of(LITEFORGE)));
        Assertions.assertEquals(List.of("changed"), heard);
        Assertions.assertTrue(stored.getFirst().endsWith("|FAILED"));
        //Said again, nothing changes
        heard.clear();
        Assertions.assertTrue(notices.resolve(maybe, receipt(false)));
        Assertions.assertEquals(List.of(), heard);
        //The guard asks while it stands, and not once the user has dismissed it
        List<List<LitVmNotices.Notice>> asked = new ArrayList<>();
        Assertions.assertFalse(LitVmFlowPane.mayProceed(notices, ME, List.of(LITEFORGE), earlier -> {
            asked.add(earlier);
            return false;
        }));
        Assertions.assertTrue(LitVmFlowPane.mayProceed(notices, ME, List.of(LITEFORGE), earlier -> true));
        Assertions.assertEquals(List.of(List.of(failed)), asked);
        Assertions.assertTrue(LitVmFlowPane.mayProceed(notices, ME, List.of(SEPOLIA), earlier -> Assertions.fail("asked for another network")));
        Assertions.assertTrue(LitVmFlowPane.mayProceed(notices, OTHER, List.of(LITEFORGE, SEPOLIA), earlier -> Assertions.fail("asked for another account")));
        notices.dismiss(maybe);
        Assertions.assertEquals(List.of(), notices.of(ME));
        Assertions.assertEquals(List.of(), stored);
        Assertions.assertTrue(LitVmFlowPane.mayProceed(notices, ME, List.of(LITEFORGE), earlier -> Assertions.fail("asked with nothing outstanding")));

        //A receipt that succeeded removes it, also after a failure was reported
        LitVmNotices.Notice pending = notice(LitVmNotices.State.PENDING, HASH2, null);
        notices.add(pending);
        notices.resolve(pending, receipt(false));
        heard.clear();
        Assertions.assertTrue(notices.resolve(pending, receipt(true)));
        Assertions.assertEquals(List.of(), notices.of(ME));
        Assertions.assertEquals(List.of(), stored);
        Assertions.assertEquals(List.of("Confirmed in block 77: " + HASH2, "changed"), heard);

        //The caller that waited the receipt out itself settles quietly: no word of a confirmation, no bookkeeping
        LitVmNotices.Notice own = notice(LitVmNotices.State.PENDING, HASH, "bridge:jkc-withdraw");
        notices.add(own);
        heard.clear();
        bookkeeping.clear();
        notices.settle(own, receipt(true).orElseThrow());
        Assertions.assertEquals(List.of(), notices.of(ME));
        Assertions.assertEquals(List.of("changed"), heard);
        Assertions.assertEquals(List.of(), bookkeeping);
        //And a failure it saw is a report like any other
        notices.add(own);
        notices.settle(own, receipt(false).orElseThrow());
        Assertions.assertEquals(List.of(own.as(LitVmNotices.State.FAILED)), notices.of(ME));
    }

    @Test
    public void oneSharedWatchPerNoticeForABoundedTimeEachRun() throws IOException {
        LitVmNotices notices = notices();
        LitVmNotices.Notice first = notice(LitVmNotices.State.PENDING, HASH, null);
        LitVmNotices.Notice second = notice(LitVmNotices.State.MAYBE_SENT, HASH2, null);
        notices.add(first);
        notices.add(second);
        int[] asked = {0, 0};
        LitVmNotices.Receipts none = (network, hash) -> {
            asked[hash.equals(HASH) ? 0 : 1]++;
            return Optional.empty();
        };
        //Nothing is watched until asked, and asking twice is one watch
        notices.tick();
        Assertions.assertEquals(0, asked[0] + asked[1]);
        notices.watch(first, none);
        notices.watch(first, none);
        notices.watch(first, none);
        Assertions.assertEquals(1, notices.watching());
        notices.tick();
        Assertions.assertEquals(1, asked[0]);
        //A notice that is not kept is not watched
        notices.watch(notice(LitVmNotices.State.PENDING, hash(9), null), none);
        Assertions.assertEquals(1, notices.watching());

        //The tab closes: nothing is asked; it opens again: the watch goes on with the time it has left, not a new one
        notices.stopWatching(ME);
        Assertions.assertEquals(0, notices.watching());
        notices.tick();
        Assertions.assertEquals(1, asked[0]);
        notices.watch(first, none);
        notices.watch(second, none);
        for(int i = 0; i < 2 * LitVmNotices.WATCH_POLLS; i++) {
            notices.tick();
            //Asking for the watch on every change, as a view might, does not make it longer
            notices.watch(first, none);
        }
        Assertions.assertEquals(LitVmNotices.WATCH_POLLS, asked[0]);
        Assertions.assertEquals(LitVmNotices.WATCH_POLLS, asked[1]);
        Assertions.assertEquals(0, notices.watching());
        //The notices are still there after it
        Assertions.assertEquals(List.of(first, second), notices.of(ME));
        notices.watch(first, none);
        notices.tick();
        Assertions.assertEquals(LitVmNotices.WATCH_POLLS, asked[0]);

        //Only the user gives it more time: one look now, then a new watch
        Assertions.assertFalse(notices.checkAgain(first, none));
        Assertions.assertEquals(LitVmNotices.WATCH_POLLS + 1, asked[0]);
        Assertions.assertEquals(1, notices.watching());
        notices.tick();
        Assertions.assertEquals(LitVmNotices.WATCH_POLLS + 2, asked[0]);

        //A server that limits requests is asked only every sixth round from then on
        int before = asked[0];
        int[] limited = {0};
        notices.watch(first, (network, hash) -> {
            limited[0]++;
            throw new LitVmRpcClient.RateLimitedException(HOSTILE);
        });
        for(int i = 0; i < 13; i++) {
            notices.tick();
        }
        Assertions.assertEquals(3, limited[0]);
        Assertions.assertEquals(before, asked[0]);

        //A receipt ends the watch: a success removes the notice, a reported failure leaves it reworded
        notices.checkAgain(second, none);
        notices.watch(second, (network, hash) -> receipt(false));
        notices.tick();
        Assertions.assertEquals(List.of(first, second.as(LitVmNotices.State.FAILED)), notices.of(ME));
        notices.watch(first, (network, hash) -> receipt(true));
        for(int i = 0; i <= LitVmNotices.LIMITED_SKIP; i++) {
            notices.tick();
        }
        Assertions.assertEquals(List.of(second.as(LitVmNotices.State.FAILED)), notices.of(ME));
        Assertions.assertEquals(0, notices.watching());
    }

    @Test
    public void whatIsWrittenIsAlwaysTheNoticesAsTheyAreThen() {
        //Writes that run later, as on another thread's queue, and even out of order
        List<Runnable> queued = new ArrayList<>();
        LitVmNotices notices = notices(queued::add);
        LitVmNotices.Notice first = notice(LitVmNotices.State.MAYBE_SENT, HASH, null);
        LitVmNotices.Notice second = notice(LitVmNotices.State.PENDING, HASH2, null);
        notices.add(first);
        notices.add(second);
        Assertions.assertEquals(List.of(), stored);
        Assertions.assertEquals(2, queued.size());
        //The older write, run last, does not put back the older state
        queued.get(1).run();
        Assertions.assertEquals(2, stored.size());
        queued.get(0).run();
        Assertions.assertEquals(List.of(LitVmNotices.format(first), LitVmNotices.format(second)), stored);
        queued.clear();
        notices.dismiss(first);
        notices.add(first.as(LitVmNotices.State.NOT_ACCEPTED));
        new ArrayList<>(queued).reversed().forEach(Runnable::run);
        Assertions.assertEquals(List.of(LitVmNotices.format(second), LitVmNotices.format(first.as(LitVmNotices.State.NOT_ACCEPTED))), stored);
    }

    @Test
    public void aNoticeIsWrittenBeforeItsCallerGoesOn() throws Exception {
        //The writes run on another thread: addWritten returns only once its notice is in the store
        java.util.concurrent.ExecutorService other = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            LitVmNotices notices = notices(work -> other.execute(() -> {
                try {
                    Thread.sleep(50);
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                work.run();
            }));
            LitVmNotices.Notice notice = notice(LitVmNotices.State.MAYBE_SENT, HASH, null);
            Assertions.assertTrue(notices.addWritten(notice));
            Assertions.assertEquals(List.of(LitVmNotices.format(notice)), stored);
            Assertions.assertFalse(notices.unsaved());
        } finally {
            other.shutdownNow();
        }
    }

    @Test
    public void aNoticeThatCannotBeSavedStillStandsAndSaysSo() {
        //The config cannot be written. The sender is not held up (a wallet with a read-only config must still send), is
        //told at once that the notice was not saved, and the tab is told that it will not survive a restart
        LitVmNotices notices = notices();
        Assertions.assertFalse(notices.unsaved());
        storeFails = true;
        heard.clear();
        LitVmNotices.Notice notice = notice(LitVmNotices.State.MAYBE_SENT, HASH, null);
        long start = System.nanoTime();
        Assertions.assertFalse(notices.addWritten(notice));
        Assertions.assertTrue(System.nanoTime() - start < 2_000_000_000L, "a failed write held the sender up");
        Assertions.assertEquals(List.of(notice), notices.of(ME));
        Assertions.assertEquals(List.of(notice), notices.outstanding(ME, List.of(LITEFORGE)));
        Assertions.assertTrue(notices.unsaved());
        Assertions.assertEquals(List.of(), stored);
        Assertions.assertTrue(heard.contains("changed"));
        //Every later change that cannot be saved says the same
        Assertions.assertFalse(notices.addWritten(notice.as(LitVmNotices.State.PENDING)));
        Assertions.assertTrue(notices.unsaved());
        //Once a write succeeds the notices are kept, and it is no longer said
        storeFails = false;
        heard.clear();
        Assertions.assertTrue(notices.addWritten(notice.as(LitVmNotices.State.NOT_ACCEPTED)));
        Assertions.assertFalse(notices.unsaved());
        Assertions.assertEquals(List.of(LitVmNotices.format(notice.as(LitVmNotices.State.NOT_ACCEPTED))), stored);
        Assertions.assertTrue(heard.contains("changed"));
    }

    @Test
    public void aWriteThatIsNotDoneInTimeIsNotClaimedAsSaved() {
        //The write never runs (the thread it waits for is busy): the sender goes on after the wait, told that the notice
        //is not saved, and the tab says so until a write does succeed
        List<Runnable> queued = new ArrayList<>();
        LitVmNotices notices = notices(queued::add);
        notices.writeWaitMillis = 100;
        LitVmNotices.Notice notice = notice(LitVmNotices.State.MAYBE_SENT, HASH, null);
        long start = System.nanoTime();
        Assertions.assertFalse(notices.addWritten(notice));
        Assertions.assertTrue(System.nanoTime() - start >= 90_000_000L, "the sender did not wait for the write");
        Assertions.assertTrue(notices.unsaved());
        Assertions.assertEquals(List.of(), stored);
        Assertions.assertEquals(List.of(notice), notices.of(ME));
        //The write runs at last: saved, and no longer said
        new ArrayList<>(queued).forEach(Runnable::run);
        Assertions.assertFalse(notices.unsaved());
        Assertions.assertEquals(List.of(LitVmNotices.format(notice)), stored);
    }

    @Test
    public void noNoticeIsEverDroppedToMakeRoom() {
        //Every notice guards a send: past the count that asks the user to look, all are still kept and still guard
        LitVmNotices notices = notices();
        for(int i = 0; i < LitVmNotices.MAX_NOTICES - 1; i++) {
            notices.add(notice(i % 4 == 0 ? LitVmNotices.State.MAYBE_SENT : i % 4 == 1 ? LitVmNotices.State.NOT_ACCEPTED : LitVmNotices.State.PENDING, hash(i + 1), null));
        }
        notices.resolve(notice(LitVmNotices.State.PENDING, hash(3), null), receipt(false));
        Assertions.assertFalse(notices.crowded(ME));
        for(int i = 0; i < 20; i++) {
            notices.add(notice(LitVmNotices.State.PENDING, hash(1000 + i), null));
        }
        Assertions.assertEquals(LitVmNotices.MAX_NOTICES + 19, notices.of(ME).size());
        Assertions.assertEquals(LitVmNotices.MAX_NOTICES + 19, notices.outstanding(ME, List.of(LITEFORGE)).size());
        Assertions.assertEquals(LitVmNotices.MAX_NOTICES + 19, stored.size());
        Assertions.assertEquals(hash(1), notices.of(ME).getFirst().hash());
        Assertions.assertEquals(LitVmNotices.State.FAILED, notices.of(ME).get(2).state());
        //The tab asks the user to look at the old ones
        Assertions.assertTrue(notices.crowded(ME));
        //Another run reads them all back
        Assertions.assertEquals(LitVmNotices.MAX_NOTICES + 19, notices().of(ME).size());

        //The count is for one account on one network
        Assertions.assertFalse(notices.crowded(OTHER));
        LitVmNotices spread = notices(Runnable::run);
        stored = new ArrayList<>();
        LitVmNotices fresh = notices();
        for(int i = 0; i < LitVmNotices.MAX_NOTICES - 1; i++) {
            fresh.add(new LitVmNotices.Notice(LITEFORGE, ME, hash(i + 1), LitVmNotices.State.PENDING, null));
            fresh.add(new LitVmNotices.Notice(SEPOLIA, ME, hash(i + 1), LitVmNotices.State.PENDING, null));
            fresh.add(new LitVmNotices.Notice(LITEFORGE, OTHER, hash(5000 + i), LitVmNotices.State.PENDING, null));
        }
        Assertions.assertEquals(3 * (LitVmNotices.MAX_NOTICES - 1), stored.size());
        Assertions.assertFalse(fresh.crowded(ME));
        Assertions.assertFalse(fresh.crowded(OTHER));
        fresh.add(new LitVmNotices.Notice(SEPOLIA, ME, hash(9000), LitVmNotices.State.PENDING, null));
        Assertions.assertTrue(fresh.crowded(ME));
        Assertions.assertFalse(fresh.crowded(OTHER));
        //Dismissing one brings the account back under it
        fresh.dismiss(new LitVmNotices.Notice(SEPOLIA, ME, hash(9000), LitVmNotices.State.PENDING, null));
        Assertions.assertFalse(fresh.crowded(ME));
        Assertions.assertNotNull(spread);
    }

    @Test
    public void entriesPastTheLoadCapAreNotOverwritten() {
        //A config holding more than is made into notices: the rest is not shown, and not lost at the next write either
        List<String> many = new ArrayList<>();
        for(int i = 0; i < LitVmNotices.MAX_LOADED + 3; i++) {
            many.add(LitVmNotices.format(notice(LitVmNotices.State.PENDING, hash(i + 1), null)));
        }
        stored = new ArrayList<>(many);
        LitVmNotices notices = notices();
        Assertions.assertEquals(LitVmNotices.MAX_LOADED, notices.of(ME).size());
        LitVmNotices.Notice added = new LitVmNotices.Notice(SEPOLIA, ME, HASH, LitVmNotices.State.MAYBE_SENT, null);
        notices.add(added);
        Assertions.assertEquals(LitVmNotices.MAX_LOADED + 4, stored.size());
        Assertions.assertTrue(stored.containsAll(many));
        Assertions.assertTrue(stored.contains(LitVmNotices.format(added)));
        notices.dismiss(notice(LitVmNotices.State.PENDING, hash(1), null));
        Assertions.assertEquals(LitVmNotices.MAX_LOADED + 3, stored.size());
        Assertions.assertTrue(stored.contains(many.getLast()));
    }

    @Test
    public void theAppsBookkeepingForATag() {
        com.sparrowwallet.sparrow.io.Config config = com.sparrowwallet.sparrow.io.Config.get();
        List<String> savedHistory = config.getLitVmBridgeHistory();
        List<String> savedTokens = config.getLitVmTokens();
        List<String> savedCreated = config.getLitVmCreatedTokens();
        try {
            config.setLitVmBridgeHistory(List.of());
            config.setLitVmTokens(List.of());
            config.setLitVmCreatedTokens(List.of());
            LitVmNotices.Tags tags = LitVmNotices.appTags();

            //A bridge row: kept once under the account
            LitVmNotices.Notice withdrawal = new LitVmNotices.Notice(LITEFORGE, ME, HASH, LitVmNotices.State.PENDING, "bridge:zk-withdraw");
            tags.record(withdrawal);
            tags.record(withdrawal);
            Assertions.assertEquals(List.of("zk-withdraw|" + HASH + "|" + ME), config.getLitVmBridgeHistory());

            //A created token: tracked and marked as created here, once
            LitVmNotices.Notice creation = new LitVmNotices.Notice(LITEFORGE, ME, HASH2, LitVmNotices.State.PENDING, LitVmNotices.tokenTag(TOKEN, "mintable_bp"));
            tags.record(creation);
            tags.record(creation);
            Assertions.assertEquals(List.of(TOKEN), LitVmContext.trackedTokens(LITEFORGE));
            Assertions.assertEquals(List.of(LITEFORGE.getChainId() + ":" + TOKEN + ":mintable_bp"), config.getLitVmCreatedTokens());
            Assertions.assertEquals(java.util.Map.of(TOKEN, "mintable_bp"), LitVmContext.createdTokens(LITEFORGE));

            //A template key that is none, a step of a flow, or no tag at all, does nothing
            config.setLitVmBridgeHistory(List.of());
            config.setLitVmTokens(List.of());
            config.setLitVmCreatedTokens(List.of());
            tags.record(new LitVmNotices.Notice(LITEFORGE, ME, HASH2, LitVmNotices.State.PENDING, LitVmNotices.tokenTag(TOKEN, "nonsense")));
            tags.record(new LitVmNotices.Notice(LITEFORGE, ME, HASH2, LitVmNotices.State.PENDING, LitVmNotices.NOT_LAST_TAG));
            tags.record(new LitVmNotices.Notice(LITEFORGE, ME, HASH2, LitVmNotices.State.PENDING, null));
            Assertions.assertEquals(List.of(), LitVmContext.trackedTokens(LITEFORGE));
            Assertions.assertEquals(List.of(), config.getLitVmCreatedTokens());
            Assertions.assertEquals(List.of(), config.getLitVmBridgeHistory());
        } finally {
            config.setLitVmBridgeHistory(savedHistory);
            config.setLitVmTokens(savedTokens);
            config.setLitVmCreatedTokens(savedCreated);
        }
    }

    @Test
    public void theBookkeepingIsDoneOnceAndAtTheRightTime() {
        //For a transaction that was sent, a bridge row that only reads the chain is kept at once
        LitVmNotices notices = notices();
        LitVmNotices.Notice deposit = notice(LitVmNotices.State.PENDING, HASH, "bridge:zk-deposit");
        notices.add(deposit);
        Assertions.assertEquals(List.of("record bridge:zk-deposit " + HASH), bookkeeping);
        //A server's report of a failure undoes nothing by itself: the row stays with the notice
        notices.resolve(deposit, receipt(false));
        Assertions.assertEquals(List.of("record bridge:zk-deposit " + HASH), bookkeeping);
        notices.dismiss(deposit);

        //For one that may never have gone out nothing is kept until its receipt is seen
        for(LitVmNotices.State state : List.of(LitVmNotices.State.MAYBE_SENT, LitVmNotices.State.NOT_ACCEPTED)) {
            bookkeeping.clear();
            heard.clear();
            LitVmNotices.Notice unsure = notice(state, HASH, "bridge:zk-withdraw");
            notices.add(unsure);
            notices.resolve(unsure, Optional.empty());
            Assertions.assertEquals(List.of(), bookkeeping, state.name());
            notices.resolve(unsure, receipt(true));
            Assertions.assertEquals(List.of("record bridge:zk-withdraw " + HASH), bookkeeping, state.name());
            //Done before the listeners hear of it, so what they read again holds the row
            Assertions.assertEquals(List.of("changed", "Confirmed in block 77: " + HASH, "changed"), heard, state.name());
        }

        //One that says "submitted", and a created token, wait for the receipt
        bookkeeping.clear();
        LitVmNotices.Notice withdrawal = notice(LitVmNotices.State.PENDING, HASH2, "bridge:jkc-withdraw");
        notices.add(withdrawal);
        Assertions.assertEquals(List.of(), bookkeeping);
        notices.resolve(withdrawal, receipt(true));
        Assertions.assertEquals(List.of("record bridge:jkc-withdraw " + HASH2), bookkeeping);
        //Seen again: nothing more
        notices.resolve(withdrawal, receipt(true));
        Assertions.assertEquals(List.of("record bridge:jkc-withdraw " + HASH2), bookkeeping);

        //The bookkeeping is data: a later run does it from the stored tag alone
        bookkeeping.clear();
        String tokenTag = LitVmNotices.tokenTag(TOKEN, "mintable_bp");
        LitVmNotices.Notice creation = notice(LitVmNotices.State.PENDING, HASH, tokenTag);
        notices.add(creation);
        LitVmNotices later = notices();
        Assertions.assertEquals(List.of(creation), later.of(ME));
        later.resolve(creation, receipt(true));
        Assertions.assertEquals(List.of("record " + tokenTag + " " + HASH), bookkeeping);

        //A step of a flow has none; a dismissed notice does none
        bookkeeping.clear();
        LitVmNotices.Notice step = notice(LitVmNotices.State.PENDING, hash(5), LitVmNotices.NOT_LAST_TAG);
        notices.add(step);
        notices.resolve(step, receipt(true));
        notices.add(withdrawal);
        notices.dismiss(withdrawal);
        notices.resolve(withdrawal, receipt(true));
        Assertions.assertEquals(List.of(), bookkeeping);
    }
}
