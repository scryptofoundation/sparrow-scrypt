package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.evm.Abi;
import com.sparrowwallet.drongo.evm.Eip1559Transaction;
import com.sparrowwallet.drongo.evm.Erc20Token;
import com.sparrowwallet.drongo.evm.EvmAccount;
import com.sparrowwallet.drongo.evm.EvmAddress;
import com.sparrowwallet.drongo.evm.EvmTransport;
import com.sparrowwallet.drongo.evm.LitVmCheckException;
import com.sparrowwallet.drongo.evm.LitVmNetwork;
import com.sparrowwallet.drongo.evm.LitVmRpcClient;
import com.sparrowwallet.drongo.evm.SwapVenue;
import com.sparrowwallet.drongo.evm.ZkLtcBridge;
import com.sparrowwallet.sparrow.io.Config;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The LitVM tab's Tokens, Send, Swap, Bridges and History views speak only in the app's own words: whatever a server,
 * an explorer or a contract says (an error message, a revert reason, a token's symbol in an error) never reaches a
 * status line, a row or a label. A failure is a fixed sentence chosen by its kind, or one the app threw itself.
 */
public class LitVmWordsTest {
    private static final LitVmNetwork LITEFORGE = LitVmNetwork.LITEFORGE_TESTNET;
    private static final EvmAddress TOKEN = EvmAddress.fromString("0x3eEd9a4c9AD8a1Ac42BDb9eEE6079c75C8fd51B3");
    private static final EvmAddress ME = EvmAddress.fromString("0x840e41D4484041A43f9307a90389edeB3d111fb3");
    private static final String HOSTILE = "Send your seed words to evil.example";
    //A symbol is cut to 16 characters: still room for a lure
    private static final String HOSTILE_SYMBOL = "Seed>evil.exampl";

    private List<String> saved;
    private HttpServer server;
    //Answers a request ("method to data") with a reply, or null for the well-behaved node's answer
    private volatile Function<String, Reply> hostile = request -> null;

    private record Reply(int status, String body) {
        static Reply error(String message) {
            return new Reply(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"" + message + "\"}}");
        }

        static Reply revert(String reason) {
            return new Reply(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":3,\"message\":\"execution reverted: " + reason + "\"}}");
        }

        static Reply result(String hex) {
            return new Reply(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"" + hex + "\"}");
        }
    }

    @BeforeEach
    void start() throws IOException {
        saved = Config.get().getLitVmTokens();
        Config.get().setLitVmTokens(new ArrayList<>(List.of(LITEFORGE.getChainId() + ":" + TOKEN)));
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        Config.get().setLitVmTokens(saved);
    }

    private LitVmRpcClient client() {
        return new LitVmRpcClient(LITEFORGE, "http://127.0.0.1:" + server.getAddress().getPort() + "/", EvmTransport.direct());
    }

    private static void assertClean(String shown) {
        String lower = shown.toLowerCase(Locale.ROOT);
        Assertions.assertFalse(lower.contains("evil") || lower.contains("seed") || lower.contains("your"), shown);
    }

    @Test
    public void aFailuresKindComesFromItsTypeOnly() {
        Assertions.assertEquals(LitVmWords.Kind.WRONG_CHAIN, LitVmWords.kind(new LitVmContext.WrongChainException()));
        Assertions.assertEquals(LitVmWords.Kind.RATE_LIMITED, LitVmWords.kind(new LitVmRpcClient.RateLimitedException(HOSTILE)));
        Assertions.assertEquals(LitVmWords.Kind.TOO_SLOW, LitVmWords.kind(new LitVmRpcClient.DeadlineException(HOSTILE)));
        Assertions.assertEquals(LitVmWords.Kind.TOO_SLOW, LitVmWords.kind(new HttpTimeoutException(HOSTILE)));
        Assertions.assertEquals(LitVmWords.Kind.TOO_SLOW, LitVmWords.kind(new SocketTimeoutException(HOSTILE)));
        Assertions.assertEquals(LitVmWords.Kind.REFUSED, LitVmWords.kind(new LitVmRpcClient.LitVmRpcException(HOSTILE)));
        Assertions.assertEquals(LitVmWords.Kind.UNREACHABLE, LitVmWords.kind(new ConnectException(HOSTILE)));
        Assertions.assertEquals(LitVmWords.Kind.UNREACHABLE, LitVmWords.kind(new UnknownHostException(HOSTILE)));
        //Any other failure of talking to a service, and a failure with no such cause: the wallet's own
        Assertions.assertEquals(LitVmWords.Kind.REMOTE, LitVmWords.kind(new IOException(HOSTILE)));
        Assertions.assertEquals(LitVmWords.Kind.REMOTE, LitVmWords.kind(new RuntimeException(new IOException(HOSTILE))));
        Assertions.assertEquals(LitVmWords.Kind.LOCAL, LitVmWords.kind(new IllegalStateException(HOSTILE)));
        Assertions.assertEquals(LitVmWords.Kind.LOCAL, LitVmWords.kind(new NullPointerException(HOSTILE)));
        Assertions.assertEquals(LitVmWords.Kind.LOCAL, LitVmWords.kind(null));
        //A cause decides as the failure itself would; a limit or a timeout below a node error still counts
        Assertions.assertEquals(LitVmWords.Kind.RATE_LIMITED, LitVmWords.kind(new IOException("x", new LitVmRpcClient.RateLimitedException(HOSTILE))));
        Assertions.assertEquals(LitVmWords.Kind.UNREACHABLE, LitVmWords.kind(new RuntimeException(new ConnectException(HOSTILE))));
    }

    @Test
    public void everyKindHasOneFixedSentence() {
        Assertions.assertEquals("This server is for another chain.", LitVmWords.sentence(new LitVmContext.WrongChainException()));
        Assertions.assertEquals("The server is limiting requests. Try again later.", LitVmWords.sentence(new LitVmRpcClient.RateLimitedException(HOSTILE)));
        Assertions.assertEquals("The server took too long.", LitVmWords.sentence(new HttpTimeoutException(HOSTILE)));
        Assertions.assertEquals("The server refused the request.", LitVmWords.sentence(new LitVmRpcClient.LitVmRpcException(HOSTILE)));
        Assertions.assertEquals("The server could not be reached.", LitVmWords.sentence(new ConnectException(HOSTILE)));
        Assertions.assertEquals("The server could not be reached, or its answer could not be used.", LitVmWords.sentence(new IOException(HOSTILE)));
        Assertions.assertEquals("The explorer took too long.", LitVmWords.sentence(new HttpTimeoutException(HOSTILE), LitVmWords.EXPLORER));
        Assertions.assertEquals("Sourcify could not be reached, or its answer could not be used.", LitVmWords.sentence(new IOException(HOSTILE), LitVmWords.SOURCIFY));
        Assertions.assertEquals("Something went wrong in the wallet.", LitVmWords.sentence(new NullPointerException(HOSTILE)));
        Assertions.assertEquals("Something went wrong in the wallet.", LitVmWords.sentence(new IllegalStateException(HOSTILE), LitVmWords.EXPLORER));
        Assertions.assertEquals("Could not prepare the transaction. The server took too long.", LitVmSendView.prepareFailed(new SocketTimeoutException(HOSTILE)));
        Assertions.assertEquals("Could not prepare the transaction. The server took too long.", LitVmFlowPane.prepareFailed(new SocketTimeoutException(HOSTILE)));
        //A fault of the wallet's own before anything was signed: nothing was sent, and only there is that said
        Assertions.assertEquals("Could not prepare the transaction. Something went wrong in the wallet. Nothing was sent.",
                LitVmSendView.prepareFailed(new IllegalStateException(HOSTILE)));
        Assertions.assertEquals("Could not prepare the transaction. Something went wrong in the wallet. Nothing was sent.",
                LitVmFlowPane.prepareFailed(new NullPointerException(HOSTILE)));
        Assertions.assertEquals("Not completed. Something went wrong in the wallet.", LitVmFlowPane.broadcastFailed(new IllegalStateException(HOSTILE)));
        Assertions.assertEquals("Not completed. Something went wrong in the wallet.", LitVmSendView.broadcastFailed(new InterruptedException(HOSTILE)));
        Assertions.assertEquals("Could not read bridge status. Something went wrong in the wallet.", LitVmBridgePane.statusFailed(new IllegalStateException(HOSTILE)));
        Assertions.assertEquals("Could not read balances on " + LITEFORGE.getDisplayName() + ". The server refused the request.",
                LitVmWords.balancesUnread(LITEFORGE, new LitVmRpcClient.LitVmRpcException(HOSTILE)));
        Assertions.assertEquals("History unavailable. The explorer could not be reached.",
                LitVmHistoryView.historyFailed(new ConnectException(HOSTILE)));
    }

    @Test
    public void onlyTheAppsOwnFailureIsShownAsWritten() {
        LitVmWords.Said said = new LitVmWords.Said("Only 5 of this token is available.");
        Assertions.assertEquals("Only 5 of this token is available.", LitVmWords.sentence(said));
        Assertions.assertEquals("Only 5 of this token is available.", LitVmWords.sentence(new RuntimeException(HOSTILE, said)));
        Assertions.assertEquals("Not completed. Only 5 of this token is available.", LitVmFlowPane.broadcastFailed(said));
        Assertions.assertEquals("Could not prepare the transaction. Only 5 of this token is available.", LitVmSendView.prepareFailed(said));
        //Any other exception's message is never shown, whatever its type
        Assertions.assertEquals("Something went wrong in the wallet.", LitVmWords.sentence(new IllegalStateException(HOSTILE)));
        Assertions.assertEquals("Something went wrong in the wallet.", LitVmWords.sentence(new IllegalArgumentException(HOSTILE)));
    }

    @Test
    public void aCheckTheWalletMadeItselfHasItsOwnSentence() {
        Assertions.assertEquals("Could not prepare the transaction. This token needs more gas than one transaction can use on this network. "
                + "Choose fewer features, or another network.", LitVmFlowPane.prepareFailed(check(LitVmCheckException.Reason.CREATION_OVER_GAS_CAP)));
        Assertions.assertEquals("No quote. No pool can fill this trade.", LitVmSwapPane.quoteFailed(check(LitVmCheckException.Reason.NO_POOL)));
        Assertions.assertEquals("Could not prepare the transaction. No pool can fill this trade.", LitVmFlowPane.prepareFailed(check(LitVmCheckException.Reason.NO_POOL)));
        Assertions.assertEquals("Could not read a token at that address. There is no contract at that address.",
                LitVmTokensView.addTokenFailed(check(LitVmCheckException.Reason.NO_CONTRACT)));
        Assertions.assertEquals("Could not read a token at that address. The contract at that address does not answer as a token.",
                LitVmTokensView.addTokenFailed(check(LitVmCheckException.Reason.NOT_A_TOKEN)));
        Assertions.assertEquals("Could not read a token at that address. The token uses more decimal places than the wallet supports.",
                LitVmTokensView.addTokenFailed(check(LitVmCheckException.Reason.TOKEN_DECIMALS)));
        Assertions.assertEquals("Could not prepare the transaction. The Base portal is paused. Nothing was sent.",
                LitVmFlowPane.prepareFailed(check(LitVmCheckException.Reason.PORTAL_PAUSED)));
        Assertions.assertEquals("Could not prepare the transaction. The bridge contracts do not match what the wallet expects. Nothing was sent.",
                LitVmFlowPane.prepareFailed(check(LitVmCheckException.Reason.BRIDGE_MISMATCH)));
        //"Nothing was sent" is the call site's to say: only preparing a transaction, before anything is signed, says it
        Assertions.assertEquals("The Junkcoin bridge could not be verified, so no deposit was started. The bridge contracts do not match what the wallet expects.",
                LitVmBridgePane.verifyFailed(check(LitVmCheckException.Reason.BRIDGE_MISMATCH)));
        Assertions.assertEquals("No quote. The swap contracts do not match what the wallet expects.", LitVmSwapPane.quoteFailed(check(LitVmCheckException.Reason.VENUE_MISMATCH)));
        Assertions.assertEquals("Not completed. The Base portal is paused.", LitVmFlowPane.broadcastFailed(check(LitVmCheckException.Reason.PORTAL_PAUSED)));
        for(LitVmCheckException.Reason reason : LitVmCheckException.Reason.values()) {
            Assertions.assertFalse(LitVmWords.sentence(check(reason)).contains("Nothing was sent"), reason.name());
            Assertions.assertFalse(LitVmWords.checkSentence(reason, false).contains("Nothing was sent"), reason.name());
        }
        Assertions.assertEquals("Could not prepare the transaction. The swap contracts do not match what the wallet expects. Nothing was sent.",
                LitVmFlowPane.prepareFailed(new RuntimeException(HOSTILE, check(LitVmCheckException.Reason.VENUE_MISMATCH))));
        //The checks as the library makes them, against a node
        hostile = request -> request.startsWith("eth_getCode") ? Reply.result("0x") : null;
        Assertions.assertEquals("Could not read a token at that address. There is no contract at that address.", addTokenFailure());
        hostile = request -> request.startsWith("eth_call " + TOKEN + " 313ce567") ? Reply.result("0x") : null;
        Assertions.assertEquals("Could not read a token at that address. The contract at that address does not answer as a token.", addTokenFailure());
        hostile = request -> request.startsWith("eth_call " + TOKEN + " 313ce567") ? Reply.result("0x" + word(BigInteger.valueOf(99))) : null;
        Assertions.assertEquals("Could not read a token at that address. The token uses more decimal places than the wallet supports.", addTokenFailure());
        //The node's own refusal is still not shown
        hostile = request -> request.startsWith("eth_getCode") ? Reply.error(HOSTILE) : null;
        Assertions.assertEquals("Could not read a token at that address. The server refused the request.", addTokenFailure());
        //A bridge whose inbox names another bridge than the pinned one
        LitVmRpcClient sepolia = new LitVmRpcClient(ZkLtcBridge.L1, "http://127.0.0.1:" + server.getAddress().getPort() + "/", EvmTransport.direct());
        hostile = request -> request.startsWith("eth_call " + ZkLtcBridge.INBOX) ? Reply.result("0x" + word(BigInteger.ONE)) : null;
        Assertions.assertEquals("Could not prepare the transaction. The bridge contracts do not match what the wallet expects. Nothing was sent.",
                LitVmFlowPane.prepareFailed(Assertions.assertThrows(IOException.class, () -> ZkLtcBridge.verifyBridge(sepolia))));
    }

    @Test
    public void aServerThatDoesNotAnswerIsNotAMissingPool() {
        Erc20Token token = new Erc20Token(TOKEN, "T", "T", 18);
        Assertions.assertFalse(SwapVenue.forNetwork(LITEFORGE).isEmpty());
        for(SwapVenue venue : SwapVenue.forNetwork(LITEFORGE)) {
            //Every pool call reverts: the chain answered, and no pool can fill the trade
            hostile = request -> request.startsWith("eth_call") ? Reply.revert(HOSTILE) : null;
            Assertions.assertEquals("No quote. No pool can fill this trade.", quoteFailure(venue, token), venue.name());
            //The server is down, limits requests or answers nonsense for every pool: that is not the chain saying no pool
            hostile = request -> request.startsWith("eth_call") ? new Reply(502, HOSTILE) : null;
            Assertions.assertEquals("No quote. The server could not be reached, or its answer could not be used.", quoteFailure(venue, token), venue.name());
            hostile = request -> request.startsWith("eth_call") ? new Reply(429, HOSTILE) : null;
            Assertions.assertEquals("No quote. The server is limiting requests. Try again later.", quoteFailure(venue, token), venue.name());
            hostile = request -> request.startsWith("eth_call") ? new Reply(200, HOSTILE) : null;
            Assertions.assertEquals("No quote. The server could not be reached, or its answer could not be used.", quoteFailure(venue, token), venue.name());
            hostile = request -> request.startsWith("eth_call") ? Reply.error(HOSTILE) : null;
            Assertions.assertEquals("No quote. The server refused the request.", quoteFailure(venue, token), venue.name());
        }
    }

    @Test
    public void aQuoterThatDoesNotAnswerIsNotAMissingPool() {
        Erc20Token token = new Erc20Token(TOKEN, "T", "T", 18);
        String getPool = Utils.bytesToHex(Abi.selector("getPool(address,address,uint24)")).toLowerCase(Locale.ROOT);
        int found = 0;
        for(LitVmNetwork network : LitVmNetwork.values()) {
            for(SwapVenue venue : SwapVenue.forNetwork(network)) {
                if(!(venue instanceof com.sparrowwallet.drongo.evm.UniswapV3Venue)) {
                    continue;
                }
                found++;
                LitVmRpcClient client = new LitVmRpcClient(network, "http://127.0.0.1:" + server.getAddress().getPort() + "/", EvmTransport.direct());
                //The factory names a pool at every fee, and the quoter times out, is limited, or errs: not known, so not "no pool"
                for(Reply quoter : List.of(new Reply(502, HOSTILE), new Reply(429, HOSTILE), Reply.error(HOSTILE), new Reply(200, HOSTILE))) {
                    hostile = request -> !request.startsWith("eth_call") ? null : request.contains(" " + getPool) ? Reply.result("0x" + word(BigInteger.TEN)) : quoter;
                    IOException failure = Assertions.assertThrows(IOException.class, () -> venue.quote(client, null, token, BigInteger.TEN.pow(18)));
                    Assertions.assertFalse(failure instanceof LitVmCheckException, "a quoter that did not answer read as no pool");
                    String shown = LitVmSwapPane.quoteFailed(failure);
                    assertClean(shown);
                    Assertions.assertFalse(shown.contains("No pool"), shown);
                }
                //One fee tier has no pool and the others' quoter does not answer: still not known
                hostile = request -> !request.startsWith("eth_call") ? null : request.contains(" " + getPool)
                        ? Reply.result("0x" + word(request.endsWith(word(BigInteger.valueOf(500))) ? BigInteger.ZERO : BigInteger.TEN)) : new Reply(502, HOSTILE);
                Assertions.assertFalse(Assertions.assertThrows(IOException.class, () -> venue.quote(client, null, token, BigInteger.TEN.pow(18))) instanceof LitVmCheckException);
                //The factory names no pool at any fee, or the quoter reverts: the chain answered, and that is no pool
                hostile = request -> !request.startsWith("eth_call") ? null : request.contains(" " + getPool) ? Reply.result("0x" + word(BigInteger.ZERO)) : new Reply(502, HOSTILE);
                Assertions.assertEquals("No quote. No pool can fill this trade.",
                        LitVmSwapPane.quoteFailed(Assertions.assertThrows(IOException.class, () -> venue.quote(client, null, token, BigInteger.TEN.pow(18)))));
                hostile = request -> !request.startsWith("eth_call") ? null : request.contains(" " + getPool) ? Reply.result("0x" + word(BigInteger.TEN)) : Reply.revert(HOSTILE);
                Assertions.assertEquals("No quote. No pool can fill this trade.",
                        LitVmSwapPane.quoteFailed(Assertions.assertThrows(IOException.class, () -> venue.quote(client, null, token, BigInteger.TEN.pow(18)))));
            }
        }
        Assertions.assertTrue(found > 0, "no Uniswap v3 venue to test");
    }

    private String quoteFailure(SwapVenue venue, Erc20Token token) {
        String shown = LitVmSwapPane.quoteFailed(Assertions.assertThrows(IOException.class, () -> venue.quote(client(), null, token, BigInteger.TEN.pow(18))));
        assertClean(shown);
        return shown;
    }

    @Test
    public void aTransactionStillPendingIsToldAsSent() {
        String hash = "0x" + "ab".repeat(32);
        String flow = LitVmWords.stillPending("Step 1 of 2", hash, true);
        Assertions.assertEquals("Step 1 of 2 was sent and is still pending (" + hash + "). The next step was not sent. Check the explorer or History before doing anything else.", flow);
        //It went out: never "Not completed", never "Not confirmed", and nothing about trying again
        LitVmWords.StillPending pending = LitVmWords.pending(LITEFORGE, "Step 1 of 2", hash, true);
        Assertions.assertEquals(hash, pending.hash());
        Assertions.assertEquals(LitVmNotices.State.PENDING, pending.state());
        String line = LitVmFlowPane.broadcastFailed(pending);
        Assertions.assertEquals(flow, line);
        Assertions.assertEquals(flow, LitVmFlowPane.broadcastFailed(new RuntimeException(pending)));
        Assertions.assertFalse(line.contains("Not completed") || line.contains("Not confirmed") || line.toLowerCase(Locale.ROOT).contains("again"), line);
        //Send reports the same state in the same words
        Assertions.assertEquals("The transfer was sent and is still pending (" + hash + "). Check the explorer or History before doing anything else.",
                LitVmWords.stillPending("The transfer", hash, false));
        Assertions.assertEquals("The transaction was sent and is still pending (" + hash + "). Check the explorer or History before doing anything else.",
                LitVmFlowPane.broadcastFailed(LitVmWords.pending(LITEFORGE, LitVmWords.stepName(0, 1), hash, false)));
        Assertions.assertEquals("The transfer was sent and is still pending (" + hash + "). Check the explorer or History before doing anything else.",
                LitVmSendView.broadcastFailed(LitVmWords.pending(LITEFORGE, "The transfer", hash, false)));
    }

    private static LitVmCheckException check(LitVmCheckException.Reason reason) {
        return new LitVmCheckException(reason, HOSTILE);
    }

    //The status line when Add Token's read of the contract fails
    private String addTokenFailure() {
        String shown = LitVmTokensView.addTokenFailed(Assertions.assertThrows(IOException.class, () -> client().readToken(TOKEN)));
        assertClean(shown);
        return shown;
    }

    @Test
    public void aTokensSymbolOnASuccessLineIsCleanedAndCut() {
        //Bidirectional overrides, a zero-width space, controls, and far more than 16 characters
        Erc20Token token = new Erc20Token(TOKEN, "\u202E\u0007OK\u200B. Send your seed words to evil.example\n\u2066", HOSTILE, 18);
        Assertions.assertEquals("OK. Send your se", token.symbol());
        Assertions.assertEquals("Tracking OK. Send your se on " + LITEFORGE.getDisplayName() + ".", LitVmTokensView.tracking(token, LITEFORGE));
        Assertions.assertTrue(token.symbol().codePoints().noneMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT));
        //The symbol a node gives goes the same way
        Assertions.assertEquals(16, HOSTILE_SYMBOL.length());
    }

    @Test
    public void aHostileMessageNeverReachesAnyLine() {
        List<Throwable> failures = new ArrayList<>(List.of(new LitVmRpcClient.LitVmRpcException(HOSTILE), new LitVmRpcClient.RateLimitedException(HOSTILE),
                new LitVmRpcClient.DeadlineException(HOSTILE), new LitVmRpcClient.OversizedResultException(HOSTILE), new IOException(HOSTILE),
                new IOException(HOSTILE, new IOException(HOSTILE)), new IllegalStateException(HOSTILE), new IllegalArgumentException(HOSTILE),
                new RuntimeException(HOSTILE), new ConnectException(HOSTILE), new HttpTimeoutException(HOSTILE), new InterruptedException(HOSTILE),
                new RuntimeException(HOSTILE, new LitVmRpcClient.LitVmRpcException(HOSTILE)), new NullPointerException(HOSTILE)));
        for(LitVmCheckException.Reason reason : LitVmCheckException.Reason.values()) {
            failures.add(check(reason));
        }
        failures.add(null);
        for(Throwable failure : failures) {
            List<String> shown = new ArrayList<>();
            for(String who : List.of(LitVmWords.SERVER, LitVmWords.EXPLORER, LitVmWords.SOURCIFY)) {
                shown.add(LitVmWords.sentence(failure, who));
                shown.add(LitVmWords.failure("Could not publish the token's source.", failure, who));
            }
            //Tokens, Send, Swap and History: balances, a token read, preparing and sending
            shown.add(LitVmWords.balancesUnread(LITEFORGE, failure));
            //Each line is the one its view builds, so a view that went back to an exception's own text would fail here
            shown.add(LitVmTokensView.addTokenFailed(failure));
            shown.add(LitVmTokensView.manageFailed(failure));
            shown.add(LitVmTokensView.publishFailed(failure));
            shown.add(LitVmSendView.prepareFailed(failure));
            shown.add(LitVmSendView.broadcastFailed(failure));
            shown.add(LitVmFlowPane.prepareFailed(failure));
            shown.add(LitVmFlowPane.broadcastFailed(failure));
            shown.add(LitVmSwapPane.quoteFailed(failure));
            shown.add(LitVmHistoryView.historyFailed(failure));
            shown.add(LitVmWords.refused(LITEFORGE, "The transfer", "0xabc", failure).getMessage());
            shown.add(LitVmSendView.broadcastFailed(LitVmWords.refused(LITEFORGE, "The transfer", "0xabc", failure)));
            String maybeSent = LitVmWords.maybeSent(LITEFORGE, "Step 1 of 2 (later steps were not sent)", "0xabc", failure).getMessage();
            shown.add(maybeSent);
            //Whatever went wrong, a transaction that may have gone out is never to be tried again
            Assertions.assertFalse(maybeSent.toLowerCase(Locale.ROOT).contains("again"), maybeSent);
            Assertions.assertTrue(maybeSent.endsWith("Check the explorer or History before doing anything else."), maybeSent);
            shown.add(LitVmFlowPane.broadcastFailed(LitVmWords.maybeSent(LITEFORGE, "The transaction", "0xabc", failure)));
            shown.add(LitVmWords.wouldFail("Step 2 of 2", failure).getMessage());
            shown.add(LitVmFlowPane.broadcastFailed(LitVmWords.wouldFail("Step 2 of 2", failure)));
            //Tokens: the source's publication
            shown.add(LitVmTokensView.notVerified(LitVmWords.sentence(failure, LitVmWords.EXPLORER), LitVmWords.sentence(failure, LitVmWords.SOURCIFY)));
            shown.add(LitVmTokensView.notOnExplorer(LitVmWords.sentence(failure, LitVmWords.EXPLORER)));
            //Bridges: a row, the Sepolia balance, the checks
            shown.add(LitVmBridgePane.unread("Deposit 12345678...9abc", failure));
            shown.add(LitVmBridgePane.sepoliaUnavailable(failure));
            shown.add(LitVmBridgePane.statusFailed(failure));
            shown.add(LitVmBridgePane.verifyFailed(failure));
            shown.add(LitVmBridgePane.withdrawalFailed(failure));
            //Collectibles keeps its own four sentences
            shown.add(CollectiblesPresenter.failureSentence(failure));
            shown.forEach(LitVmWordsTest::assertClean);
        }
    }

    @Test
    public void aFailedStepIsNamedByItsPlace() {
        Assertions.assertEquals("The transaction", LitVmWords.stepName(0, 1));
        Assertions.assertEquals("Step 1 of 2", LitVmWords.stepName(0, 2));
        Assertions.assertEquals("Step 2 of 2", LitVmWords.stepName(1, 2));
        Assertions.assertEquals("Step 2 of 2 would fail, so it was not sent. The contract refused the call.",
                LitVmWords.wouldFail("Step 2 of 2", revert()).getMessage());
        Assertions.assertEquals("The token's source is not verified yet. The explorer took too long. Sourcify reported failed. Manage > Publish Source tries again.",
                LitVmTokensView.notVerified("The explorer took too long.", "Sourcify reported failed."));
        Assertions.assertEquals("The token's source is not verified yet. Manage > Publish Source tries again.", LitVmTokensView.notVerified(null, null));
        Assertions.assertEquals("The explorer has not verified it. Manage > Publish Source tries again.", LitVmTokensView.notOnExplorer(null));
        Assertions.assertEquals("Deposit 1234: could not be read. The server took too long.", LitVmBridgePane.unread("Deposit 1234", new HttpTimeoutException(HOSTILE)));
    }

    //A revert as the client reports one: its constructor is not public, so a node gives it
    private LitVmRpcClient.LitVmRpcException revert() {
        hostile = request -> request.startsWith("eth_call") ? Reply.revert(HOSTILE) : null;
        try {
            LitVmRpcClient.LitVmRpcException e = Assertions.assertThrows(LitVmRpcClient.LitVmRpcException.class, () -> client().ethCall(TOKEN, new byte[4]));
            Assertions.assertTrue(e.isRevert());
            Assertions.assertEquals(LitVmWords.Kind.CONTRACT_REFUSED, LitVmWords.kind(e));
            Assertions.assertEquals("The contract refused the call.", LitVmWords.sentence(e));
            return e;
        } finally {
            hostile = request -> null;
        }
    }

    @Test
    public void aRefusedTransactionIsToldInTheAppsWords() throws IOException {
        Eip1559Transaction tx = new Eip1559Transaction(LITEFORGE.getChainId(), BigInteger.ZERO, BigInteger.ONE, BigInteger.TEN, BigInteger.valueOf(21000), ME,
                BigInteger.ONE, null);
        Eip1559Transaction.Signed signed = EvmAccount.fromPrivateKey(new ECKey()).sign(tx);
        LitVmRpcClient client = client();
        LitVmContext.verifyChain(client);

        //The node answers with an error: refused, and only that is said
        hostile = request -> request.startsWith("eth_sendRawTransaction") ? Reply.error(HOSTILE) : null;
        LitVmWords.Said refused = Assertions.assertThrows(LitVmWords.Said.class, () -> LitVmFlowPane.send(client, signed, "The transfer"));
        //An error answer is not proof that it is not out (already known, a nonce used, a relay that lies): no such claim
        String notAccepted = "The transfer was not accepted by the server (" + signed.hashHex()
                + "). It may already be known to the network. Check History or the explorer before sending it again.";
        Assertions.assertEquals(notAccepted, refused.getMessage());
        //It is uncertain like a send that may have gone out: it carries the hash and the network for the notice
        Assertions.assertTrue(refused instanceof LitVmWords.NotAccepted);
        LitVmWords.Uncertain uncertain = LitVmWords.uncertain(new RuntimeException(refused));
        Assertions.assertEquals(signed.hashHex(), uncertain.hash());
        Assertions.assertEquals(LITEFORGE, uncertain.network());
        Assertions.assertEquals(LitVmNotices.State.NOT_ACCEPTED, uncertain.state());
        //And it is never "Not completed": the line is its own words, with the hash
        Assertions.assertEquals(notAccepted, LitVmSendView.broadcastFailed(refused));
        Assertions.assertEquals(notAccepted, LitVmFlowPane.broadcastFailed(new RuntimeException(refused)));
        hostile = request -> request.startsWith("eth_sendRawTransaction") ? Reply.revert(HOSTILE) : null;
        Assertions.assertEquals(notAccepted,
                Assertions.assertThrows(LitVmWords.NotAccepted.class, () -> LitVmFlowPane.send(client, signed, "The transfer")).getMessage());

        //HTTP 200 with a body that is not JSON, after the bytes were handed over: it may be out, and the hash is given
        hostile = request -> request.startsWith("eth_sendRawTransaction") ? new Reply(200, HOSTILE) : null;
        LitVmWords.MaybeSent notJson = Assertions.assertThrows(LitVmWords.MaybeSent.class, () -> LitVmFlowPane.send(client, signed, "The transfer"));
        Assertions.assertEquals("The transfer may have been broadcast as " + signed.hashHex()
                + ". The server's answer was lost or could not be used. Check the explorer or History before doing anything else.", notJson.getMessage());
        Assertions.assertEquals("Not confirmed. " + notJson.getMessage(), LitVmSendView.broadcastFailed(notJson));
        Assertions.assertEquals("Not confirmed. " + notJson.getMessage(), LitVmFlowPane.broadcastFailed(notJson));
        Assertions.assertEquals(signed.hashHex(), notJson.hash());
        Assertions.assertEquals(LitVmNotices.State.MAYBE_SENT, notJson.state());
        //So is a result that is no string at all, and a body cut short
        for(String body : List.of("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"x\":[1,2]}}", "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x", "", "[]", "null")) {
            hostile = request -> request.startsWith("eth_sendRawTransaction") ? new Reply(200, body) : null;
            Assertions.assertThrows(LitVmWords.MaybeSent.class, () -> LitVmFlowPane.send(client, signed, "The transfer"), body);
        }

        //Any other failure may come after the node took it: the transaction's own hash, and no word of the node's
        hostile = request -> request.startsWith("eth_sendRawTransaction") ? new Reply(502, HOSTILE) : null;
        Assertions.assertEquals("Step 1 of 2 (later steps were not sent) may have been broadcast as " + signed.hashHex()
                        + ". The server's answer was lost or could not be used. Check the explorer or History before doing anything else.",
                Assertions.assertThrows(LitVmWords.MaybeSent.class, () -> LitVmFlowPane.send(client, signed, "Step 1 of 2 (later steps were not sent)")).getMessage());
        //A limit on requests is no reason to send again: the line never says to
        hostile = request -> request.startsWith("eth_sendRawTransaction") ? new Reply(429, HOSTILE) : null;
        LitVmWords.MaybeSent limited = Assertions.assertThrows(LitVmWords.MaybeSent.class, () -> LitVmFlowPane.send(client, signed, "The transfer"));
        Assertions.assertEquals("The transfer may have been broadcast as " + signed.hashHex()
                + ". The server was limiting requests, so its answer is not known. Check the explorer or History before doing anything else.", limited.getMessage());
        //And the lead is not "Not completed": it may have gone out
        Assertions.assertEquals("Not confirmed. " + limited.getMessage(), LitVmSendView.broadcastFailed(limited));
        Assertions.assertEquals("Not confirmed. " + limited.getMessage(), LitVmFlowPane.broadcastFailed(new RuntimeException(limited)));
        Assertions.assertEquals("The transfer may have been broadcast as 0xabc. The server took too long to answer. Check the explorer or History before doing anything else.",
                LitVmWords.maybeSent(LITEFORGE, "The transfer", "0xabc", new HttpTimeoutException(HOSTILE)).getMessage());
        //A node that names another hash than the transaction's own
        hostile = request -> request.startsWith("eth_sendRawTransaction") ? Reply.result("0x" + HOSTILE) : null;
        assertClean(Assertions.assertThrows(LitVmWords.Said.class, () -> LitVmFlowPane.send(client, signed, "The transfer")).getMessage()
                .replace(signed.hashHex(), ""));

        hostile = request -> null;
        Assertions.assertEquals(signed.hashHex(), LitVmFlowPane.send(client, signed, "The transfer"));
    }

    @Test
    public void aServersErrorTextNeverReachesTheBalances() throws IOException {
        //A well-behaved node: the token is read, with whatever symbol its contract gives
        LitVmContext.Balances read = LitVmContext.readBalances(client(), ME);
        Assertions.assertEquals(List.of(), read.unread());
        Erc20Token token = read.tokens().getFirst();
        Assertions.assertEquals(HOSTILE_SYMBOL, token.symbol());
        //Its symbol is not in the words for a refused amount
        Assertions.assertEquals("Enter an amount with digits only and at most 18 decimal places.", LitVmWords.tokenAmount(token));

        //The chain check and the native balance: the whole read fails, in a fixed sentence
        String lead = "Could not read balances on " + LITEFORGE.getDisplayName() + ". ";
        hostile = request -> request.startsWith("eth_chainId") ? Reply.error(HOSTILE) : null;
        Assertions.assertEquals(lead + "The server refused the request.", balancesFailure());
        hostile = request -> request.startsWith("eth_chainId") ? Reply.result("0x1") : null;
        Assertions.assertEquals(lead + "This server is for another chain.", balancesFailure());
        hostile = request -> request.startsWith("eth_chainId") ? new Reply(429, HOSTILE) : null;
        Assertions.assertEquals(lead + "The server is limiting requests. Try again later.", balancesFailure());
        hostile = request -> request.startsWith("eth_chainId") ? new Reply(502, HOSTILE) : null;
        Assertions.assertEquals(lead + "The server could not be reached, or its answer could not be used.", balancesFailure());
        hostile = request -> request.startsWith("eth_getBalance") ? Reply.error(HOSTILE) : null;
        Assertions.assertEquals(lead + "The server refused the request.", balancesFailure());
        //An answer that is not a number is quoted in the client's own exception: not on the screen
        hostile = request -> request.startsWith("eth_getBalance") ? Reply.result(HOSTILE) : null;
        Assertions.assertEquals(lead + "The server could not be reached, or its answer could not be used.", balancesFailure());
        //Nor is an answer that is not JSON, or a chain id that is no number, the wallet's fault
        hostile = request -> request.startsWith("eth_getBalance") ? new Reply(200, HOSTILE) : null;
        Assertions.assertEquals(lead + "The server could not be reached, or its answer could not be used.", balancesFailure());
        hostile = request -> request.startsWith("eth_chainId") ? Reply.result(HOSTILE) : null;
        Assertions.assertEquals(lead + "The server could not be reached, or its answer could not be used.", balancesFailure());
        hostile = request -> request.startsWith("eth_chainId") ? Reply.result("0x" + "f".repeat(40)) : null;
        Assertions.assertEquals(lead + "The server could not be reached, or its answer could not be used.", balancesFailure());

        //A tracked token's read: the token is named by its address, and nothing the server said is kept
        for(String failing : List.of("eth_getCode " + TOKEN, "eth_call " + TOKEN + " 313ce567", "eth_call " + TOKEN + " 70a08231")) {
            for(Reply reply : List.of(Reply.error(HOSTILE), Reply.revert(HOSTILE), Reply.result(HOSTILE), new Reply(502, HOSTILE))) {
                hostile = request -> request.startsWith(failing) ? reply : null;
                LitVmContext.Balances balances = LitVmContext.readBalances(client(), ME);
                Assertions.assertEquals(List.of(TOKEN), balances.unread(), failing);
                Assertions.assertEquals(List.of(), balances.tokens(), failing);
                Assertions.assertEquals("Could not read a tracked token: 0x3eEd...51B3.", LitVmWords.tokensUnread(balances.unread()));
            }
        }
        Assertions.assertEquals("Could not read 2 tracked tokens: 0x3eEd...51B3, 0x840e...1fb3.", LitVmWords.tokensUnread(List.of(TOKEN, ME)));
    }

    //The status line when the whole read of the balances fails
    private String balancesFailure() {
        Throwable failure = Assertions.assertThrows(Exception.class, () -> LitVmContext.readBalances(client(), ME));
        String shown = LitVmWords.balancesUnread(LITEFORGE, failure);
        assertClean(shown);
        return shown;
    }

    // ---------------- The hand-off of a signed transaction ----------------

    //The notices as a store would hold them, and the bookkeeping done
    private List<String> storedNotices = new ArrayList<>();
    private final List<String> bookkeeping = new ArrayList<>();

    private LitVmNotices notices() {
        return new LitVmNotices(new LitVmNotices.Store() {
            @Override
            public List<String> load() {
                return new ArrayList<>(storedNotices);
            }

            @Override
            public boolean save(List<String> entries) {
                storedNotices = new ArrayList<>(entries);
                return true;
            }
        }, notice -> bookkeeping.add(notice.tag() + " " + notice.hash()), Runnable::run);
    }

    private static Reply receiptReply(String hash, String status, String block) {
        return new Reply(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{" + (hash == null ? "" : "\"transactionHash\":\"" + hash + "\",")
                + (status == null ? "" : "\"status\":\"" + status + "\",") + (block == null ? "" : "\"blockNumber\":\"" + block + "\",") + "\"gasUsed\":\"0x5208\"}}");
    }

    private static final Reply NO_RECEIPT = new Reply(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":null}");

    private Eip1559Transaction.Signed signedTransfer() {
        Eip1559Transaction tx = new Eip1559Transaction(LITEFORGE.getChainId(), BigInteger.ZERO, BigInteger.ONE, BigInteger.TEN, BigInteger.valueOf(21000), ME, BigInteger.ONE, null);
        return EvmAccount.fromPrivateKey(new ECKey()).sign(tx);
    }

    @Test
    public void theNoticeIsWrittenBeforeTheTransactionIsHandedOver() throws Exception {
        Eip1559Transaction.Signed signed = signedTransfer();
        LitVmRpcClient client = client();
        LitVmContext.verifyChain(client);
        LitVmNotices notices = notices();
        String maybe = LITEFORGE.getChainId() + "|" + signed.from() + "|" + signed.hashHex() + "|MAYBE_SENT";
        String pending = LITEFORGE.getChainId() + "|" + signed.from() + "|" + signed.hashHex() + "|PENDING";

        //When the server is handed the bytes the notice is already kept (it may be out, whatever happens from here on);
        //while the receipt is waited for it says sent and pending; a receipt that succeeded removes it
        List<List<String>> seenByServer = new ArrayList<>();
        hostile = request -> {
            if(request.startsWith("eth_sendRawTransaction") || request.startsWith("eth_getTransactionReceipt")) {
                seenByServer.add(new ArrayList<>(storedNotices));
            }
            return request.startsWith("eth_getTransactionReceipt") ? (seenByServer.size() < 4 ? NO_RECEIPT : receiptReply(signed.hashHex(), "0x1", "0x4d")) : null;
        };
        List<String> handedOver = new ArrayList<>();
        LitVmRpcClient.Receipt receipt = LitVmFlowPane.transact(notices, client, signed, "The transfer", false, null, 30, millis -> {}, handedOver::add);
        Assertions.assertTrue(receipt.success());
        Assertions.assertEquals(BigInteger.valueOf(77), receipt.blockNumber());
        Assertions.assertEquals(List.of(signed.hashHex()), handedOver);
        Assertions.assertEquals(List.of(List.of(maybe), List.of(pending), List.of(pending), List.of(pending)), seenByServer);
        Assertions.assertEquals(List.of(), notices.of(signed.from()));
        Assertions.assertEquals(List.of(), storedNotices);
        //The account is the one that signed, not one looked up afterwards
        Assertions.assertNotEquals(ME, signed.from());
    }

    @Test
    public void everyOtherEndLeavesTheNoticeStanding() throws Exception {
        Eip1559Transaction.Signed signed = signedTransfer();
        LitVmRpcClient client = client();
        LitVmContext.verifyChain(client);
        EvmAddress signer = signed.from();
        String hash = signed.hashHex();

        //The request fails, or its answer is no hash: it may be out
        for(Reply reply : List.of(new Reply(502, HOSTILE), new Reply(200, HOSTILE), new Reply(429, HOSTILE), Reply.result("0x" + HOSTILE))) {
            LitVmNotices notices = notices();
            hostile = request -> request.startsWith("eth_sendRawTransaction") ? reply : null;
            LitVmWords.MaybeSent failure = Assertions.assertThrows(LitVmWords.MaybeSent.class, () -> LitVmFlowPane.transact(notices, client, signed, "The transfer", false, null, 3, millis -> {}, null));
            Assertions.assertEquals(List.of(new LitVmNotices.Notice(LITEFORGE, signer, hash, LitVmNotices.State.MAYBE_SENT, null)), notices.of(signer));
            Assertions.assertEquals(1, storedNotices.size());
            //It is watched from there on, and a new send is asked about first
            LitVmFlowPane.watchAfter(notices, failure, signer, client);
            Assertions.assertEquals(1, notices.watching());
            Assertions.assertFalse(LitVmFlowPane.mayProceed(notices, signer, List.of(LITEFORGE), earlier -> false));
        }

        //The server answers with an error: not accepted here, which is not proof that it is not out
        LitVmNotices refusedNotices = notices();
        hostile = request -> request.startsWith("eth_sendRawTransaction") ? Reply.error(HOSTILE) : null;
        Assertions.assertThrows(LitVmWords.NotAccepted.class, () -> LitVmFlowPane.transact(refusedNotices, client, signed, "The transfer", false, null, 3, millis -> {}, null));
        Assertions.assertEquals(List.of(new LitVmNotices.Notice(LITEFORGE, signer, hash, LitVmNotices.State.NOT_ACCEPTED, null)), refusedNotices.of(signer));

        //Sent, and no receipt in time; or only answers that are no receipt for it: pending, with the bookkeeping that is
        //safe while pending done at once, and nothing else
        for(Reply reply : List.of(NO_RECEIPT, receiptReply("0x" + "11".repeat(32), "0x1", "0x4d"), receiptReply(null, "0x1", "0x4d"), receiptReply(hash, null, "0x4d"),
                receiptReply(hash, "0x2", "0x4d"), receiptReply(hash, "0x01", "0x4d"), receiptReply(hash, "0x1", null), receiptReply(hash, "0x1", HOSTILE),
                Reply.result("0x1"), Reply.error(HOSTILE), new Reply(200, HOSTILE), new Reply(502, HOSTILE))) {
            LitVmNotices notices = notices();
            bookkeeping.clear();
            hostile = request -> request.startsWith("eth_getTransactionReceipt") ? reply : null;
            LitVmWords.StillPending failure = Assertions.assertThrows(LitVmWords.StillPending.class,
                    () -> LitVmFlowPane.transact(notices, client, signed, "Step 2 of 2", false, "bridge:zk-withdraw", 3, millis -> {}, null), reply.body());
            Assertions.assertEquals(List.of(new LitVmNotices.Notice(LITEFORGE, signer, hash, LitVmNotices.State.PENDING, "bridge:zk-withdraw")), notices.of(signer), reply.body());
            Assertions.assertEquals(List.of("bridge:zk-withdraw " + hash), bookkeeping, reply.body());
            Assertions.assertEquals("Step 2 of 2 was sent and is still pending (" + hash + "). Check the explorer or History before doing anything else.", LitVmFlowPane.broadcastFailed(failure));
        }

        //A step that is not the last: its notice says so when it confirms later
        LitVmNotices stepNotices = notices();
        hostile = request -> request.startsWith("eth_getTransactionReceipt") ? NO_RECEIPT : null;
        Assertions.assertThrows(LitVmWords.StillPending.class, () -> LitVmFlowPane.transact(stepNotices, client, signed, "Step 1 of 2", true, LitVmNotices.NOT_LAST_TAG, 2, millis -> {}, null));
        Assertions.assertEquals("Confirmed in block 77: " + hash + ". The later steps of its flow were not sent.", LitVmNotices.confirmedLine(stepNotices.of(signer).getFirst(), BigInteger.valueOf(77)));

        //A receipt that says exactly 0x0: the server reports a failure. The flow stops, and the notice stays, reworded,
        //still in the way of a second send, with the bridge row it kept while pending
        LitVmNotices failedNotices = notices();
        bookkeeping.clear();
        hostile = request -> request.startsWith("eth_getTransactionReceipt") ? receiptReply(hash, "0x0", "0x4d") : null;
        LitVmWords.Said failed = Assertions.assertThrows(LitVmWords.Said.class, () -> LitVmFlowPane.transact(failedNotices, client, signed, "The transfer", false, "bridge:zk-withdraw", 3, millis -> {}, null));
        Assertions.assertNull(LitVmWords.uncertain(failed));
        Assertions.assertEquals("Not completed. The transfer failed on chain (" + hash + ").", LitVmSendView.broadcastFailed(failed));
        LitVmNotices.Notice reported = new LitVmNotices.Notice(LITEFORGE, signer, hash, LitVmNotices.State.FAILED, "bridge:zk-withdraw");
        Assertions.assertEquals(List.of(reported), failedNotices.of(signer));
        Assertions.assertEquals(List.of("bridge:zk-withdraw " + hash), bookkeeping);
        List<List<LitVmNotices.Notice>> asked = new ArrayList<>();
        Assertions.assertFalse(LitVmFlowPane.mayProceed(failedNotices, signer, List.of(LITEFORGE), earlier -> {
            asked.add(earlier);
            return false;
        }));
        Assertions.assertEquals(List.of(List.of(reported)), asked);
        //A failure that is not an uncertain send starts no watch, and the hand-off of a watch never throws
        LitVmFlowPane.watchAfter(failedNotices, failed, signer, client);
        Assertions.assertEquals(0, failedNotices.watching());
        LitVmFlowPane.watchAfter(failedNotices, null, null, null);
        LitVmFlowPane.watchAfter(null, LitVmWords.pending(LITEFORGE, "The transfer", hash, false), signer, client);

        //The client's own reading of a receipt: for this hash, exactly 0x1 or 0x0, with its block
        hostile = request -> request.startsWith("eth_getTransactionReceipt") ? receiptReply(hash.toUpperCase(Locale.ROOT).replace("0X", "0x"), "0x1", "0x4d") : null;
        Assertions.assertTrue(client.getReceipt(hash).orElseThrow().success());
        hostile = request -> request.startsWith("eth_getTransactionReceipt") ? receiptReply(hash, "0x0", "0x4d") : null;
        Assertions.assertFalse(client.getReceipt(hash).orElseThrow().success());
        hostile = request -> request.startsWith("eth_getTransactionReceipt") ? NO_RECEIPT : null;
        Assertions.assertTrue(client.getReceipt(hash).isEmpty());
        for(Reply reply : List.of(receiptReply("0x" + "11".repeat(32), "0x1", "0x4d"), receiptReply(null, "0x1", "0x4d"), receiptReply(hash, null, "0x4d"), receiptReply(hash, "0x2", "0x4d"),
                receiptReply(hash, "0x1", null), Reply.result("0x1"))) {
            hostile = request -> request.startsWith("eth_getTransactionReceipt") ? reply : null;
            Assertions.assertThrows(LitVmRpcClient.MalformedAnswerException.class, () -> client.getReceipt(hash), reply.body());
        }
    }

    @Test
    public void aSendThatCertainlyDidNotGoOutLeavesNoNotice() throws Exception {
        Eip1559Transaction.Signed signed = signedTransfer();
        String hash = signed.hashHex();
        //What the fake node was sent
        List<String> seen = new ArrayList<>();
        hostile = request -> {
            seen.add(request);
            return null;
        };
        //The route to the server: as it is, refused before anything is tried, to a port nothing listens on, or throwing
        //once the request has been handed over
        java.util.concurrent.atomic.AtomicReference<String> route = new java.util.concurrent.atomic.AtomicReference<>("open");
        int closedPort;
        try(java.net.ServerSocket closed = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = closed.getLocalPort();
        }
        EvmTransport transport = (uri, headers, body, timeout, maxBytes) -> switch(route.get()) {
            case "refused" -> throw new EvmTransport.NotSentException("refused: " + HOSTILE, new java.net.ConnectException(HOSTILE));
            case "closed" -> EvmTransport.direct().send(java.net.URI.create("http://127.0.0.1:" + closedPort + "/"), headers, body, timeout, maxBytes);
            case "lost" -> {
                EvmTransport.direct().send(uri, headers, body, timeout, maxBytes);
                throw new java.net.ConnectException(HOSTILE);
            }
            default -> EvmTransport.direct().send(uri, headers, body, timeout, maxBytes);
        };
        LitVmRpcClient client = new LitVmRpcClient(LITEFORGE, "http://127.0.0.1:" + server.getAddress().getPort() + "/", transport);
        LitVmContext.verifyChain(client);

        //The route refused the request before anything was tried (a proxy wanted and not there, plain http through a
        //proxy, a setting that could not be read): not sent, said plainly, and no notice is left
        for(String refusal : List.of("refused", "closed")) {
            route.set(refusal);
            seen.clear();
            LitVmNotices notices = notices();
            LitVmWords.NeverSent failure = Assertions.assertThrows(LitVmWords.NeverSent.class, () -> LitVmFlowPane.transact(notices, client, signed, "The transfer", false, "bridge:zk-withdraw", 3, millis -> {}, null), refusal);
            Assertions.assertEquals("The transfer was not sent. The server could not be reached.", failure.getMessage(), refusal);
            Assertions.assertEquals("The transfer was not sent. The server could not be reached.", LitVmSendView.broadcastFailed(failure), refusal);
            Assertions.assertEquals("The transfer was not sent. The server could not be reached.", LitVmFlowPane.broadcastFailed(new RuntimeException(failure)), refusal);
            Assertions.assertNull(LitVmWords.uncertain(failure));
            Assertions.assertEquals(List.of(), notices.of(signed.from()), refusal);
            Assertions.assertEquals(List.of(), storedNotices, refusal);
            Assertions.assertEquals(List.of(), bookkeeping, refusal);
            Assertions.assertTrue(LitVmFlowPane.mayProceed(notices, signed.from(), List.of(LITEFORGE), earlier -> Assertions.fail("asked after a send that did not go out")));
            Assertions.assertEquals(List.of(), seen, "the node was sent something");
            //Nothing is watched for it either
            LitVmFlowPane.watchAfter(notices, failure, signed.from(), client);
            Assertions.assertEquals(0, notices.watching());
        }
        //A step of a flow with steps after it
        route.set("refused");
        LitVmNotices stepNotices = notices();
        Assertions.assertEquals("Step 1 of 2 was not sent. Nor were the later steps. The server could not be reached.",
                Assertions.assertThrows(LitVmWords.NeverSent.class, () -> LitVmFlowPane.transact(stepNotices, client, signed, "Step 1 of 2", true, LitVmNotices.NOT_LAST_TAG, 3, millis -> {}, null)).getMessage());
        Assertions.assertEquals(List.of(), stepNotices.of(signed.from()));

        //A second try that signs the same bytes (the same nonce gives the same hash) while the first one's fate is not
        //known: when this try sends nothing, the first one's notice stays exactly as it was, and only this try is said
        //not to have been sent
        for(LitVmNotices.State state : List.of(LitVmNotices.State.MAYBE_SENT, LitVmNotices.State.NOT_ACCEPTED, LitVmNotices.State.PENDING, LitVmNotices.State.FAILED)) {
            route.set("refused");
            LitVmNotices again = notices();
            LitVmNotices.Notice first = new LitVmNotices.Notice(LITEFORGE, signed.from(), hash, state, "bridge:jkc-withdraw");
            again.add(first);
            List<String> keptBefore = new ArrayList<>(storedNotices);
            LitVmWords.NeverSent failure = Assertions.assertThrows(LitVmWords.NeverSent.class, () -> LitVmFlowPane.transact(again, client, signed, "The transfer", false, null, 3, millis -> {}, null), state.name());
            Assertions.assertEquals("The transfer was not sent this time. The server could not be reached. An earlier try with the same hash (" + hash
                    + ") may still go through. Check the explorer or History before doing anything else.", failure.getMessage());
            Assertions.assertEquals(failure.getMessage(), LitVmSendView.broadcastFailed(failure));
            assertClean(failure.getMessage());
            Assertions.assertEquals(List.of(first), again.of(signed.from()), state.name());
            Assertions.assertEquals(keptBefore, storedNotices, state.name());
            Assertions.assertFalse(LitVmFlowPane.mayProceed(again, signed.from(), List.of(LITEFORGE), earlier -> false));
            again.dismiss(first);
        }
        Assertions.assertEquals("Step 1 of 2 was not sent this time. Nor were the later steps. The server took too long. An earlier try with the same hash (0xabc) may still go through. "
                + "Check the explorer or History before doing anything else.", LitVmWords.notSentThisTime("Step 1 of 2", true, "0xabc", new HttpTimeoutException(HOSTILE)).getMessage());
        //A second try that does go out moves the same notice on, as a first one would
        route.set("open");
        hostile = request -> request.startsWith("eth_getTransactionReceipt") ? NO_RECEIPT : null;
        LitVmNotices second = notices();
        second.add(new LitVmNotices.Notice(LITEFORGE, signed.from(), hash, LitVmNotices.State.MAYBE_SENT, null));
        Assertions.assertThrows(LitVmWords.StillPending.class, () -> LitVmFlowPane.transact(second, client, signed, "The transfer", false, null, 2, millis -> {}, null));
        Assertions.assertEquals(List.of(new LitVmNotices.Notice(LITEFORGE, signed.from(), hash, LitVmNotices.State.PENDING, null)), second.of(signed.from()));
        second.dismiss(second.of(signed.from()).getFirst());
        hostile = request -> {
            seen.add(request);
            return null;
        };
        //One that is handed over and then lost says so in the same notice, keeping the bookkeeping the first one named
        route.set("lost");
        LitVmNotices third = notices();
        third.add(new LitVmNotices.Notice(LITEFORGE, signed.from(), hash, LitVmNotices.State.NOT_ACCEPTED, "bridge:jkc-withdraw"));
        Assertions.assertThrows(LitVmWords.MaybeSent.class, () -> LitVmFlowPane.transact(third, client, signed, "The transfer", false, null, 2, millis -> {}, null));
        Assertions.assertEquals(List.of(new LitVmNotices.Notice(LITEFORGE, signed.from(), hash, LitVmNotices.State.MAYBE_SENT, "bridge:jkc-withdraw")), third.of(signed.from()));
        third.dismiss(third.of(signed.from()).getFirst());

        //The time was spent before the request: nothing was sent
        route.set("open");
        seen.clear();
        LitVmNotices lateNotices = notices();
        Assertions.assertEquals("The transfer was not sent. The server took too long.",
                Assertions.assertThrows(LitVmWords.NeverSent.class, () -> LitVmFlowPane.transact(lateNotices, client.withDeadline(Duration.ZERO), signed, "The transfer", false, null, 3, millis -> {}, null)).getMessage());
        Assertions.assertEquals(List.of(), lateNotices.of(signed.from()));
        Assertions.assertEquals(List.of(), seen);

        //The chain check that comes before a broadcast fails (a client that never verified its chain): nothing was sent
        for(Reply reply : List.of(Reply.result("0x1"), Reply.error(HOSTILE), new Reply(502, HOSTILE), new Reply(429, HOSTILE))) {
            seen.clear();
            hostile = request -> {
                seen.add(request);
                return request.startsWith("eth_chainId") ? reply : null;
            };
            LitVmNotices notices = notices();
            LitVmRpcClient unverified = new LitVmRpcClient(LITEFORGE, "http://127.0.0.1:" + server.getAddress().getPort() + "/", EvmTransport.direct());
            LitVmWords.NeverSent failure = Assertions.assertThrows(LitVmWords.NeverSent.class, () -> LitVmFlowPane.transact(notices, unverified, signed, "The transfer", false, null, 3, millis -> {}, null), reply.body());
            Assertions.assertTrue(failure.getMessage().startsWith("The transfer was not sent. "), failure.getMessage());
            assertClean(failure.getMessage());
            Assertions.assertEquals(List.of(), notices.of(signed.from()), reply.body());
            Assertions.assertTrue(seen.stream().noneMatch(request -> request.startsWith("eth_sendRawTransaction")), seen.toString());
        }
        Assertions.assertEquals("The transfer was not sent. The server is limiting requests. Try again later.", LitVmWords.notSent("The transfer", false,
                new EvmTransport.NotSentException("x", new LitVmRpcClient.RateLimitedException(HOSTILE))).getMessage());

        //But a failure once the request was handed over is still "may have been broadcast", whatever its type, and its
        //notice stands
        hostile = request -> {
            seen.add(request);
            return null;
        };
        route.set("lost");
        seen.clear();
        LitVmNotices lostNotices = notices();
        LitVmWords.MaybeSent lost = Assertions.assertThrows(LitVmWords.MaybeSent.class, () -> LitVmFlowPane.transact(lostNotices, client, signed, "The transfer", false, null, 3, millis -> {}, null));
        Assertions.assertEquals(hash, lost.hash());
        Assertions.assertEquals(List.of(new LitVmNotices.Notice(LITEFORGE, signed.from(), hash, LitVmNotices.State.MAYBE_SENT, null)), lostNotices.of(signed.from()));
        Assertions.assertEquals(1, seen.stream().filter(request -> request.startsWith("eth_sendRawTransaction")).count());
    }

    private static final Pattern METHOD = Pattern.compile("\"method\":\"([a-zA-Z_]+)\"");
    //The first quoted 20-byte address: eth_getCode's first parameter, eth_call's "to" (its data is longer)
    private static final Pattern TO = Pattern.compile("\"(0x[0-9a-fA-F]{40})\"");
    private static final Pattern DATA = Pattern.compile("\"data\":\"0x([0-9a-fA-F]*)\"");
    private static final Pattern RAW = Pattern.compile("\"params\":\\[\"(0x[0-9a-fA-F]+)\"\\]");

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Matcher method = METHOD.matcher(body);
        String name = method.find() ? method.group(1) : "";
        Matcher to = TO.matcher(body);
        EvmAddress contract = to.find() ? EvmAddress.fromString(to.group(1)) : null;
        Matcher dataMatch = DATA.matcher(body);
        String data = dataMatch.find() ? dataMatch.group(1).toLowerCase(Locale.ROOT) : "";
        String request = name + (contract == null ? "" : " " + contract) + (data.isEmpty() ? "" : " " + data);

        Reply reply = hostile.apply(request);
        if(reply == null) {
            reply = Reply.result(switch(name) {
                case "eth_chainId" -> "0x" + Long.toHexString(LITEFORGE.getChainId());
                case "eth_getBalance" -> "0x10";
                case "eth_getCode" -> TOKEN.equals(contract) ? "0x6080604052" : "0x";
                case "eth_call" -> TOKEN.equals(contract) && data.length() >= 8 ? "0x" + token(data) : "0x";
                case "eth_sendRawTransaction" -> sentHash(body);
                default -> "0x";
            });
        }
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(reply.status(), bytes.length);
        try(OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    //The hash an honest node answers: the hash of the raw transaction it was given
    private static String sentHash(String body) {
        Matcher raw = RAW.matcher(body);
        return raw.find() ? "0x" + Utils.bytesToHex(com.sparrowwallet.drongo.evm.Keccak.keccak256(Utils.hexToBytes(raw.group(1).substring(2)))) : "0x";
    }

    private static String word(BigInteger value) {
        return String.format(Locale.ROOT, "%064x", value);
    }

    private static String string(String text) {
        return Utils.bytesToHex(Abi.encodeArguments(text));
    }

    //A token whose contract names itself as it likes
    private static String token(String data) {
        return switch(data.substring(0, 8)) {
            case "313ce567" -> word(BigInteger.valueOf(18));
            case "95d89b41" -> string(HOSTILE_SYMBOL);
            case "06fdde03" -> string(HOSTILE);
            case "70a08231" -> word(BigInteger.valueOf(5));
            default -> "";
        };
    }
}
