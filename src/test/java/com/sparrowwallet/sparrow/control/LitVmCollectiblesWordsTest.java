package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.evm.Abi;
import com.sparrowwallet.drongo.evm.EvmAddress;
import com.sparrowwallet.drongo.evm.LitVmNetwork;
import com.sparrowwallet.drongo.evm.LitVmRpcClient;
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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Collectibles section speaks only in the app's own words: whatever a server or a contract says in an error never
 * reaches a status line, an alert or a label the app voices. Every failure is one of four fixed sentences.
 */
public class LitVmCollectiblesWordsTest {
    private static final LitVmNetwork LITEFORGE = LitVmNetwork.LITEFORGE_TESTNET;
    private static final EvmAddress BADGE = EvmAddress.fromString("0x3eEd9a4c9AD8a1Ac42BDb9eEE6079c75C8fd51B3");
    private static final EvmAddress WATCHED = EvmAddress.fromString("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266");
    private static final EvmAddress ME = EvmAddress.fromString("0x840e41D4484041A43f9307a90389edeB3d111fb3");
    private static final String HOSTILE = "Wallet flagged. Restore your seed at evil.example";
    private static final List<String> SENTENCES = List.of(CollectiblesPresenter.WRONG_CHAIN, CollectiblesPresenter.TOO_SLOW,
            CollectiblesPresenter.RATE_LIMITED, CollectiblesPresenter.UNREACHABLE);

    private List<String> saved;
    private HttpServer server;
    //Answers a request ("method to data") with a reply, or null for the well-behaved node's answer
    private volatile Function<String, Reply> hostile = request -> null;

    private record Reply(int status, String body) {
        static Reply error(String message) {
            return new Reply(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"" + message + "\"}}");
        }

        static Reply result(String hex) {
            return new Reply(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"" + hex + "\"}");
        }
    }

    @BeforeEach
    void start() throws IOException {
        saved = Config.get().getLitVmNfts();
        Config.get().setLitVmNfts(List.of());
        LitVmContext.watchCollection(LITEFORGE, WATCHED);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        Config.get().setLitVmNfts(saved);
    }

    private LitVmRpcClient client() {
        return new LitVmRpcClient(LITEFORGE, "http://127.0.0.1:" + server.getAddress().getPort() + "/", com.sparrowwallet.drongo.evm.EvmTransport.direct());
    }

    @Test
    public void everyFailureIsOneFixedSentence() {
        Assertions.assertEquals("This server is for another chain.", CollectiblesPresenter.failureSentence(new LitVmContext.WrongChainException()));
        Assertions.assertEquals("The LitVM server took too long.", CollectiblesPresenter.failureSentence(new LitVmRpcClient.DeadlineException(HOSTILE)));
        Assertions.assertEquals("The LitVM server took too long.", CollectiblesPresenter.failureSentence(new HttpTimeoutException(HOSTILE)));
        Assertions.assertEquals("The LitVM server took too long.", CollectiblesPresenter.failureSentence(new java.net.SocketTimeoutException(HOSTILE)));
        Assertions.assertEquals("The LitVM server is limiting requests. Refresh later.",
                CollectiblesPresenter.failureSentence(new LitVmRpcClient.RateLimitedException(HOSTILE)));
        for(Throwable other : List.of(new LitVmRpcClient.LitVmRpcException(HOSTILE), new IOException(HOSTILE), new IllegalArgumentException(HOSTILE),
                new RuntimeException(HOSTILE), new IOException(HOSTILE, new IOException(HOSTILE)))) {
            Assertions.assertEquals("The LitVM server could not be reached.", CollectiblesPresenter.failureSentence(other));
        }
        Assertions.assertEquals("The LitVM server could not be reached.", CollectiblesPresenter.failureSentence(null));
        //A cause decides as the failure itself would
        Assertions.assertEquals(CollectiblesPresenter.RATE_LIMITED, CollectiblesPresenter.failureSentence(new IOException("x", new LitVmRpcClient.RateLimitedException(HOSTILE))));
    }

    @Test
    public void aServersErrorTextNeverReachesTheSection() {
        //The chain check: an error, a 429, another chain, nonsense
        hostile = request -> request.startsWith("eth_chainId") ? Reply.error(HOSTILE) : null;
        Assertions.assertEquals(CollectiblesPresenter.UNREACHABLE, refreshFailure());
        hostile = request -> request.startsWith("eth_chainId") ? new Reply(429, HOSTILE) : null;
        Assertions.assertEquals(CollectiblesPresenter.RATE_LIMITED, refreshFailure());
        hostile = request -> request.startsWith("eth_chainId") ? Reply.result("0x1") : null;
        Assertions.assertEquals(CollectiblesPresenter.WRONG_CHAIN, refreshFailure());
        hostile = request -> request.startsWith("eth_chainId") ? Reply.result("0x" + HOSTILE) : null;
        Assertions.assertEquals(CollectiblesPresenter.UNREACHABLE, refreshFailure());
        hostile = request -> request.startsWith("eth_chainId") ? new Reply(502, HOSTILE) : null;
        Assertions.assertEquals(CollectiblesPresenter.UNREACHABLE, refreshFailure());

        //A collection's own read, and a token's owner: per collection, the fixed line only
        for(String failing : List.of("eth_getCode " + BADGE, "eth_call " + BADGE + " 01ffc9a7", "eth_call " + BADGE + " 23c4f6e3", "eth_call " + BADGE + " 6352211e",
                "eth_call " + BADGE + " c87b56dd")) {
            hostile = request -> request.startsWith(failing) ? Reply.error(HOSTILE) : null;
            List<String> shown = shownAfterRefresh();
            Assertions.assertTrue(shown.stream().noneMatch(text -> text.contains("evil") || text.contains("flagged")), failing + ": " + shown);
        }
        hostile = request -> request.startsWith("eth_getCode " + BADGE) ? Reply.error(HOSTILE) : null;
        Assertions.assertTrue(shownAfterRefresh().contains(CollectiblesPresenter.COULD_NOT_BE_READ));

        //Adding a collection: the read's failure is a fixed sentence (the fake node gives the badge's code, so read that)
        for(String failing : List.of("eth_chainId", "eth_getCode " + BADGE, "eth_call " + BADGE + " 01ffc9a7")) {
            hostile = request -> request.startsWith(failing) ? Reply.error(HOSTILE) : null;
            IOException failure = Assertions.assertThrows(IOException.class, () -> LitVmContext.readToAdd(client(), BADGE));
            String shown = CollectiblesPresenter.failureSentence(failure);
            Assertions.assertTrue(SENTENCES.contains(shown), shown);
        }
        hostile = request -> request.startsWith("eth_chainId") ? Reply.result("0x1") : null;
        Assertions.assertEquals(CollectiblesPresenter.WRONG_CHAIN,
                CollectiblesPresenter.failureSentence(Assertions.assertThrows(IOException.class, () -> LitVmContext.readToAdd(client(), WATCHED))));
        hostile = request -> request.startsWith("eth_chainId") ? new Reply(429, HOSTILE) : null;
        Assertions.assertEquals(CollectiblesPresenter.RATE_LIMITED,
                CollectiblesPresenter.failureSentence(Assertions.assertThrows(IOException.class, () -> LitVmContext.readToAdd(client(), WATCHED))));
    }

    //What the section shows when the refresh itself fails
    private String refreshFailure() {
        Throwable failure = Assertions.assertThrows(Exception.class, () -> LitVmContext.readCollectibles(client(), ME));
        String shown = CollectiblesPresenter.failureSentence(failure);
        Assertions.assertTrue(SENTENCES.contains(shown), shown);
        return shown;
    }

    //Every line the presenter derives from a refresh that completed
    private List<String> shownAfterRefresh() {
        List<LitVmContext.CollectionResult> results;
        try {
            results = LitVmContext.readCollectibles(client(), ME);
        } catch(IOException e) {
            throw new AssertionError(e);
        }
        List<String> shown = new ArrayList<>();
        for(LitVmContext.CollectionResult result : results) {
            shown.addAll(CollectiblesPresenter.statusLines(result));
            result.tokens().forEach(nft -> shown.add(CollectiblesPresenter.tileLabel(nft)));
        }
        String stopped = CollectiblesPresenter.stoppedLine(results);
        if(stopped != null) {
            shown.add(stopped);
        }
        return shown;
    }

    private static final Pattern METHOD = Pattern.compile("\"method\":\"([a-zA-Z_]+)\"");
    //The first quoted 20-byte address: eth_getCode's first parameter, eth_call's "to" (its data is longer)
    private static final Pattern TO = Pattern.compile("\"(0x[0-9a-fA-F]{40})\"");
    private static final Pattern DATA = Pattern.compile("\"data\":\"0x([0-9a-fA-F]*)\"");

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
                case "eth_getCode" -> BADGE.equals(contract) ? "0x6080604052" : "0x";
                case "eth_call" -> BADGE.equals(contract) ? "0x" + badge(data) : "0x";
                default -> "0x";
            });
        }
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(reply.status(), bytes.length);
        try(OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String word(BigInteger value) {
        return String.format(Locale.ROOT, "%064x", value);
    }

    private static String string(String text) {
        return Utils.bytesToHex(Abi.encodeArguments(text));
    }

    //The Vault Badge with two worlds, both held by ME
    private static String badge(String data) {
        return switch(data.substring(0, 8)) {
            case "01ffc9a7" -> word(BigInteger.valueOf(switch(data.substring(8, 16)) {
                case "01ffc9a7", "80ac58cd", "b45a3c0e" -> 1;
                default -> 0;
            }));
            case "06fdde03" -> string("Super Litecoin Vault Badge");
            case "95d89b41" -> string("SLVB");
            case "23c4f6e3" -> word(BigInteger.TWO);
            case "6352211e" -> "000000000000000000000000" + Utils.bytesToHex(ME.getBytes()).toLowerCase(Locale.ROOT);
            case "c87b56dd" -> string("{\"name\":\"T\"}");
            default -> "";
        };
    }
}
