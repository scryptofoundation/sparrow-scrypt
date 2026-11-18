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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A refresh of the Collectibles section stops asking once the LitVM server limits requests (HTTP 429). */
public class LitVmRateLimitTest {
    private static final LitVmNetwork LITEFORGE = LitVmNetwork.LITEFORGE_TESTNET;
    private static final EvmAddress BADGE = EvmAddress.fromString("0x3eEd9a4c9AD8a1Ac42BDb9eEE6079c75C8fd51B3");
    private static final EvmAddress WATCHED = EvmAddress.fromString("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266");
    private static final EvmAddress ME = EvmAddress.fromString("0x840e41D4484041A43f9307a90389edeB3d111fb3");
    private static final String LIMITING = "The LitVM server is limiting requests. Refresh later.";

    private List<String> saved;
    private HttpServer server;
    //Requests as "method to", in order
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    //The request ("method to data") answered with HTTP 429, or null
    private volatile String limited;

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
    public void aRateLimitInsideAListingStopsTheRefresh() throws IOException {
        limited = "eth_call " + BADGE + " 6352211e" + word(world(1));
        List<LitVmContext.CollectionResult> results = LitVmContext.readCollectibles(client(), ME);

        LitVmContext.CollectionResult badge = results.get(0);
        Assertions.assertEquals(LitVmContext.CollectionStatus.LISTED, badge.status());
        Assertions.assertTrue(badge.rateLimited());
        Assertions.assertEquals(2, badge.failed());
        Assertions.assertEquals(List.of("2 could not be read; Refresh to try again"), CollectiblesPresenter.statusLines(badge));
        //The watched collection is not asked, and says so with the usual line
        LitVmContext.CollectionResult watched = results.get(1);
        Assertions.assertEquals(LitVmContext.CollectionStatus.RATE_LIMITED, watched.status());
        Assertions.assertEquals(List.of("Could not be read"), CollectiblesPresenter.statusLines(watched));
        Assertions.assertEquals(0, requestsTo(WATCHED));
        Assertions.assertEquals(LIMITING, CollectiblesPresenter.stoppedLine(results));
        Assertions.assertFalse(CollectiblesPresenter.isEmpty(results));
    }

    @Test
    public void aRateLimitOnACollectionsOwnReadStopsTheRefresh() throws IOException {
        limited = "eth_getCode " + BADGE;
        List<LitVmContext.CollectionResult> results = LitVmContext.readCollectibles(client(), ME);
        Assertions.assertEquals(LitVmContext.CollectionStatus.FAILED, results.get(0).status());
        Assertions.assertTrue(results.get(0).rateLimited());
        Assertions.assertEquals(List.of("Could not be read"), CollectiblesPresenter.statusLines(results.get(0)));
        Assertions.assertEquals(LitVmContext.CollectionStatus.RATE_LIMITED, results.get(1).status());
        Assertions.assertEquals(0, requestsTo(WATCHED));
        Assertions.assertEquals(LIMITING, CollectiblesPresenter.stoppedLine(results));

        //worlds(), the badge's own rule, is a collection-level read too
        requests.clear();
        limited = "eth_call " + BADGE + " 23c4f6e3";
        results = LitVmContext.readCollectibles(client(), ME);
        Assertions.assertEquals(LitVmContext.CollectionStatus.FAILED, results.get(0).status());
        Assertions.assertEquals(LitVmContext.CollectionStatus.RATE_LIMITED, results.get(1).status());
        Assertions.assertEquals(0, requestsTo(WATCHED));
        Assertions.assertEquals(LIMITING, CollectiblesPresenter.stoppedLine(results));
    }

    @Test
    public void withoutALimitEveryCollectionIsRead() throws IOException {
        List<LitVmContext.CollectionResult> results = LitVmContext.readCollectibles(client(), ME);
        Assertions.assertEquals(LitVmContext.CollectionStatus.LISTED, results.get(0).status());
        Assertions.assertFalse(results.get(0).rateLimited());
        Assertions.assertEquals(2, results.get(0).tokens().size());
        //The watched address has no code: asked, and not a collection
        Assertions.assertEquals(LitVmContext.CollectionStatus.NOT_A_COLLECTION, results.get(1).status());
        Assertions.assertEquals(1, requestsTo(WATCHED));
        Assertions.assertNull(CollectiblesPresenter.stoppedLine(results));
        //Any other failure is not a rate limit
        Assertions.assertNull(CollectiblesPresenter.stoppedLine(List.of(new LitVmContext.CollectionResult(BADGE, true, null,
                LitVmContext.CollectionStatus.FAILED, List.of(), 0))));
        Assertions.assertEquals(LIMITING, CollectiblesPresenter.RATE_LIMITED);
    }

    private long requestsTo(EvmAddress contract) {
        synchronized(requests) {
            return requests.stream().filter(r -> r.endsWith(" " + contract)).count();
        }
    }

    private static BigInteger world(int world) {
        return new BigInteger(1, ME.getBytes()).shiftLeft(8).or(BigInteger.valueOf(world));
    }

    private static String word(BigInteger value) {
        return String.format(Locale.ROOT, "%064x", value);
    }

    private static String string(String text) {
        return Utils.bytesToHex(Abi.encodeArguments(text));
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
        requests.add(name + " " + contract);

        String key = name + " " + contract + (data.isEmpty() ? "" : " " + data);
        int status = 200;
        String result;
        if(limited != null && key.startsWith(limited)) {
            status = 429;
            result = null;
        } else {
            result = switch(name) {
                case "eth_chainId" -> "0x" + Long.toHexString(LITEFORGE.getChainId());
                case "eth_getCode" -> BADGE.equals(contract) ? "0x6080604052" : "0x";
                case "eth_call" -> BADGE.equals(contract) ? "0x" + badge(data) : "0x";
                default -> null;
            };
        }
        String reply = status == 429 ? "busy" : "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + (result == null ? "null" : "\"" + result + "\"") + "}";
        byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try(OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
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
