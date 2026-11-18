package com.sparrowwallet.sparrow.net.token;

import com.sparrowwallet.sparrow.net.ord.OrdClient;
import com.sparrowwallet.sparrow.net.ord.Transport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

public class LitescribeClientTest {
    private static final String BASE = "https://litescribe.io/api";
    private static final String ADDRESS = "ltc1qrlu7t8r2pptwv049kyqensq2e64hkfw4g4tkcq";

    private static String fixture(String name) throws IOException {
        try(InputStream in = LitescribeClientTest.class.getResourceAsStream("litescribe/" + name)) {
            Assertions.assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Answers from a queue of strings or IOExceptions and records every URL asked. */
    private static class FakeTransport implements Transport {
        final List<String> urls = new ArrayList<>();
        final ArrayDeque<Object> answers = new ArrayDeque<>();

        FakeTransport answer(Object answer) {
            answers.add(answer);
            return this;
        }

        @Override
        public String json(String url, String postBody) throws IOException {
            urls.add(url);
            Assertions.assertNull(postBody, "GET only");
            Object next = answers.size() > 1 ? answers.poll() : answers.peek();
            if(next instanceof IOException e) {
                throw e;
            }
            return (String)next;
        }

        @Override
        public byte[] bytes(String url, String accept, int maxBytes) {
            throw new AssertionError("not used");
        }
    }

    private static class Counting {
        final AtomicInteger backoffs = new AtomicInteger();
        final RateLimiter limiter = new RateLimiter(1000, 0, () -> 0, nanos -> backoffs.incrementAndGet());
    }

    private LitescribeClient client(FakeTransport transport, Counting counting) {
        return new LitescribeClient(transport, BASE, counting.limiter);
    }

    @Test
    public void balancesParseTheFixtureAndZeroNumberReadsAsString() throws IOException {
        FakeTransport t = new FakeTransport().answer(fixture("tokens.json"));
        List<TokenBalance> balances = client(t, new Counting()).balances(ADDRESS);

        Assertions.assertEquals(BASE + "/brc20/tokens?address=" + ADDRESS, t.urls.get(0));
        Assertions.assertEquals(3, balances.size());
        Assertions.assertEquals(new TokenBalance("3333", "800", "100"), balances.get(0));
        Assertions.assertEquals("900", balances.get(0).total());
        Assertions.assertEquals("1000000", balances.get(1).total());

        String withZero = "{\"status\":1,\"message\":\"OK\",\"result\":{\"list\":[{\"ticker\":\"abcd\",\"overallBalance\":0,\"transferableBalance\":0,\"availableBalance\":0}],\"total\":1}}";
        TokenBalance zero = client(new FakeTransport().answer(withZero), new Counting()).balances(ADDRESS).get(0);
        Assertions.assertEquals(new TokenBalance("abcd", "0", "0"), zero);
        Assertions.assertEquals("0", zero.total());
    }

    @Test
    public void balancesDropMalformedRows() throws IOException {
        String body = "{\"status\":1,\"message\":\"OK\",\"result\":{\"list\":["
                + "{\"ticker\":\"toolong\",\"overallBalance\":\"1\",\"transferableBalance\":\"0\",\"availableBalance\":\"1\"},"
                + "{\"ticker\":\"ok01\",\"overallBalance\":\"1\",\"transferableBalance\":\"0\",\"availableBalance\":\"-5\"},"
                + "{\"ticker\":\"ok02\",\"overallBalance\":\"1\",\"transferableBalance\":\"0\",\"availableBalance\":\"1e9\"},"
                + "{\"ticker\":\"ok03\",\"overallBalance\":\"2.5\",\"transferableBalance\":\"0.5\",\"availableBalance\":\"2\"}],\"total\":4}}";
        List<TokenBalance> balances = client(new FakeTransport().answer(body), new Counting()).balances(ADDRESS);
        Assertions.assertEquals(List.of(new TokenBalance("ok03", "2", "0.5")), balances);
        Assertions.assertEquals("2.5", balances.get(0).total());
    }

    @Test
    public void transfersLowerCaseTheTickerAndReturnInscriptionIds() throws IOException {
        FakeTransport t = new FakeTransport().answer(fixture("transferable-list.json"));
        List<TransferInscription> transfers = client(t, new Counting()).transfers(ADDRESS, "3333");
        Assertions.assertEquals(List.of(new TransferInscription("3333", "100",
                "87e8513e04cdfe4a0df57bac99677311cdc2f19ffa7eed301f946cc0cda67cd4i0", null)), transfers);

        FakeTransport upper = new FakeTransport().answer("{\"status\":1,\"message\":\"OK\",\"result\":{\"list\":[],\"total\":0}}");
        Assertions.assertTrue(client(upper, new Counting()).transfers(ADDRESS, "GoOg").isEmpty());
        Assertions.assertEquals(BASE + "/brc20/transferable-list?address=" + ADDRESS + "&ticker=goog", upper.urls.get(0));
    }

    @Test
    public void transfersDropBadIdsAndAmounts() throws IOException {
        String body = "{\"status\":1,\"message\":\"OK\",\"result\":{\"list\":["
                + "{\"amount\":\"100\",\"inscriptionId\":\"not-an-id\",\"ticker\":\"goog\"},"
                + "{\"amount\":\"abc\",\"inscriptionId\":\"87e8513e04cdfe4a0df57bac99677311cdc2f19ffa7eed301f946cc0cda67cd4i0\",\"ticker\":\"goog\"},"
                + "{\"amount\":\"7\",\"inscriptionId\":\"87e8513e04cdfe4a0df57bac99677311cdc2f19ffa7eed301f946cc0cda67cd4i1\",\"ticker\":\"goog\"}],\"total\":3}}";
        List<TransferInscription> transfers = client(new FakeTransport().answer(body), new Counting()).transfers(ADDRESS, "goog");
        Assertions.assertEquals(1, transfers.size());
        Assertions.assertEquals("7", transfers.get(0).amount());
    }

    @Test
    public void badTickerIsRejectedBeforeAnyRequest() {
        FakeTransport t = new FakeTransport();
        try {
            client(t, new Counting()).transfers(ADDRESS, "goog&x=1");
            Assertions.fail();
        } catch(IllegalArgumentException | IOException e) {
            Assertions.assertTrue(e instanceof IllegalArgumentException);
        }
        Assertions.assertTrue(t.urls.isEmpty());
    }

    @Test
    public void statusZeroThrowsWithServerMessage() throws IOException {
        FakeTransport t = new FakeTransport().answer(fixture("error.json"));
        try {
            client(t, new Counting()).transfers(ADDRESS, "GOOG");
            Assertions.fail("expected a server error");
        } catch(TokenServerException e) {
            Assertions.assertTrue(e.getMessage().contains("ticker invalid"), e.getMessage());
        }
    }

    @Test
    public void htmlAnswerIsAnErrorNotAnEmptyList() throws IOException {
        FakeTransport t = new FakeTransport().answer(fixture("rate-limited.html"));
        try {
            client(t, new Counting()).balances(ADDRESS);
            Assertions.fail();
        } catch(IOException e) {
            //expected
        }
    }

    @Test
    public void retriesThreeTimesOn429ThenThrows() {
        FakeTransport t = new FakeTransport().answer(new OrdClient.HttpStatusException(429));
        Counting counting = new Counting();
        try {
            client(t, counting).balances(ADDRESS);
            Assertions.fail("expected 429");
        } catch(IOException e) {
            Assertions.assertTrue(e instanceof OrdClient.HttpStatusException h && h.getStatus() == 429, e.toString());
        }
        Assertions.assertEquals(4, t.urls.size(), "first try plus three retries");
        Assertions.assertEquals(3, counting.backoffs.get());
    }

    @Test
    public void succeedsAfterOne429() throws IOException {
        FakeTransport t = new FakeTransport().answer(new OrdClient.HttpStatusException(429)).answer(fixture("tokens.json"));
        Counting counting = new Counting();
        Assertions.assertEquals(3, client(t, counting).balances(ADDRESS).size());
        Assertions.assertEquals(2, t.urls.size());
        Assertions.assertEquals(1, counting.backoffs.get());
    }

    @Test
    public void otherHttpErrorsAreNotRetried() {
        FakeTransport t = new FakeTransport().answer(new OrdClient.HttpStatusException(500));
        try {
            client(t, new Counting()).balances(ADDRESS);
            Assertions.fail();
        } catch(IOException e) {
            //expected
        }
        Assertions.assertEquals(1, t.urls.size());
    }

    @Test
    public void tokenInfoHasNullDecimals() throws IOException {
        FakeTransport t = new FakeTransport().answer(fixture("token-summary.json"));
        Optional<TokenInfo> info = client(t, new Counting()).token("GOOG");
        Assertions.assertEquals(Optional.of(new TokenInfo("goog", "84000000", null, null, "84000000")), info);
        Assertions.assertNull(info.get().dec());
        Assertions.assertTrue(t.urls.get(0).startsWith(BASE + "/brc20/token-summary?address="));
        Assertions.assertTrue(t.urls.get(0).endsWith("&ticker=goog"));
    }

    @Test
    public void unknownTokenIsEmpty() throws IOException {
        FakeTransport t = new FakeTransport().answer(fixture("token-summary-unknown.json"));
        Assertions.assertEquals(Optional.empty(), client(t, new Counting()).token("zzzz"));
    }

    @Test
    public void hostAndDunesDefaults() {
        LitescribeClient client = client(new FakeTransport(), new Counting());
        Assertions.assertEquals("litescribe.io", client.host());
        try {
            client.dunesOnOutputs(List.of());
            Assertions.fail();
        } catch(UnsupportedOperationException | IOException e) {
            Assertions.assertTrue(e instanceof UnsupportedOperationException);
        }
    }

    @Test
    public void listShorterThanTotalThrows() {
        String body = "{\"status\":1,\"message\":\"OK\",\"result\":{\"list\":[{\"ticker\":\"abcd\",\"overallBalance\":\"1\",\"transferableBalance\":\"0\",\"availableBalance\":\"1\"}],\"total\":2}}";
        Assertions.assertThrows(TokenServerException.class, () -> client(new FakeTransport().answer(body), new Counting()).balances(ADDRESS));
        String transfers = "{\"status\":1,\"message\":\"OK\",\"result\":{\"list\":[],\"total\":1}}";
        Assertions.assertThrows(TokenServerException.class, () -> client(new FakeTransport().answer(transfers), new Counting()).transfers(ADDRESS, "goog"));
    }

    @Test
    public void serverTicksAreLowerCasedAndControlCharactersDropped() throws IOException {
        String body = "{\"status\":1,\"message\":\"OK\",\"result\":{\"list\":["
                + "{\"ticker\":\"AbCd\",\"overallBalance\":\"1\",\"transferableBalance\":\"0\",\"availableBalance\":\"1\"},"
                + "{\"ticker\":\"ab\\ncd\",\"overallBalance\":\"1\",\"transferableBalance\":\"0\",\"availableBalance\":\"1\"},"
                + "{\"ticker\":\"a\\u0007bc\",\"overallBalance\":\"1\",\"transferableBalance\":\"0\",\"availableBalance\":\"1\"}],\"total\":3}}";
        Assertions.assertEquals(List.of(new TokenBalance("abcd", "1", "0")), client(new FakeTransport().answer(body), new Counting()).balances(ADDRESS));
    }

    @Test
    public void unknownTickerFixtureAndMissingTokenInfoGiveEmpty() throws IOException {
        Assertions.assertEquals(Optional.empty(), client(new FakeTransport().answer(fixture("token-summary-unknown.json")), new Counting()).token("xq9z"));
        String noInfo = "{\"status\":1,\"message\":\"OK\",\"result\":{\"tokenBalance\":{}}}";
        Assertions.assertEquals(Optional.empty(), client(new FakeTransport().answer(noInfo), new Counting()).token("goog"));
    }
}
