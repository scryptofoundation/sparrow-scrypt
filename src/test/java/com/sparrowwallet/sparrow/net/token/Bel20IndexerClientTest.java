package com.sparrowwallet.sparrow.net.token;

import com.sparrowwallet.sparrow.net.ord.OrdClient;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public class Bel20IndexerClientTest {
    private static final String BASE = "https://doge-mainnet-tokens.nintondo.io";
    private static final String DOGE_ADDRESS = "DL4PaJFm5yoHmzzVydTXXRQ7Vot5CicgZM";
    private static final String BELLS_ADDRESS = "B7CJVSw1NBkVgZn4wG9a237mWPAVytvzkU";
    private static final String OUTPOINT_A = "11307c794e3f88c9ffaf85681bed76ea9c99134ac73ccb6da8209bc27f4c645d:0";
    private static final String OUTPOINT_B = "540837120af888644dea59d1d7aefb38f404c040a944714de51eeaa2e21b95fa:0";

    //The fixtures in nintondo/ are real answers captured from the public indexers on 2026-11-07 (GET only)
    private static String fixture(String name) throws IOException {
        return FakeTokenTransport.fixture(Bel20IndexerClientTest.class, "nintondo/" + name);
    }

    private static Bel20IndexerClient client(FakeTokenTransport t) {
        return new Bel20IndexerClient(t, BASE, new FakeTokenTransport.Counting().limiter);
    }

    private static String tokenRows(int from, int to) {
        StringBuilder sb = new StringBuilder("[");
        for(int i = from; i < to; i++) {
            sb.append(i > from ? "," : "").append("{\"tick\":\"t").append(String.format("%03d", i)).append("\",\"balance\":\"1\",\"transferable_balance\":\"0\",\"transfers_count\":0}");
        }
        return sb.append("]").toString();
    }

    private static String transferRows(int from, int to) {
        StringBuilder sb = new StringBuilder("[");
        for(int i = from; i < to; i++) {
            sb.append(i > from ? "," : "").append("{\"outpoint\":\"").append(String.format("%064x", i)).append(":0\",\"amount\":\"1\"}");
        }
        return sb.append("]").toString();
    }

    @Test
    public void balanceIsAvailableAndTransferableBalanceIsTransferable() throws IOException {
        FakeTokenTransport t = new FakeTokenTransport().answer(fixture("doge-address.json"));
        List<TokenBalance> balances = client(t).balances(DOGE_ADDRESS);
        Assertions.assertEquals(List.of(new TokenBalance("dogx", "0", "872361059")), balances);
        Assertions.assertEquals("872361059", balances.get(0).total());
        Assertions.assertEquals(BASE + "/address/" + DOGE_ADDRESS + "?limit=100", t.urls.get(0));

        List<TokenBalance> bells = client(new FakeTokenTransport().answer(fixture("bells-address.json"))).balances(BELLS_ADDRESS);
        Assertions.assertEquals(List.of(new TokenBalance("nook", "4232132409908607", "0")), bells);
    }

    @Test
    public void balancesPageByTheLastTickUntilAShortPage() throws IOException {
        FakeTokenTransport t = new FakeTokenTransport().answer(tokenRows(0, 100)).answer(tokenRows(100, 130));
        List<TokenBalance> balances = client(t).balances(DOGE_ADDRESS);
        Assertions.assertEquals(130, balances.size());
        Assertions.assertEquals(2, t.urls.size());
        Assertions.assertEquals(BASE + "/address/" + DOGE_ADDRESS + "?limit=100&offset=t099", t.urls.get(1));
    }

    @Test
    public void aListThatNeverEndsIsAnError() {
        FakeTokenTransport t = new FakeTokenTransport().answer(tokenRows(0, 100));
        Assertions.assertThrows(TokenServerException.class, () -> client(t).balances(DOGE_ADDRESS));
    }

    @Test
    public void malformedBalanceRowsAreDropped() throws IOException {
        String body = "[{\"tick\":\"toolong\",\"balance\":\"1\",\"transferable_balance\":\"0\"},"
                + "{\"tick\":\"ok01\",\"balance\":\"-5\",\"transferable_balance\":\"0\"},"
                + "{\"tick\":\"ok02\",\"balance\":\"1e9\",\"transferable_balance\":\"0\"},"
                + "{\"tick\":\"ok03\",\"balance\":\"2.5\",\"transferable_balance\":\"0.5\"},"
                + "{\"tick\":\"AbCd\",\"balance\":\"1\",\"transferable_balance\":\"0\"},"
                + "{\"tick\":\"ab\\ncd\",\"balance\":\"1\",\"transferable_balance\":\"0\"}]";
        List<TokenBalance> balances = client(new FakeTokenTransport().answer(body)).balances(DOGE_ADDRESS);
        Assertions.assertEquals(List.of(new TokenBalance("ok03", "2.5", "0.5"), new TokenBalance("abcd", "1", "0")), balances);
    }

    @Test
    public void notAListIsAnError() {
        Assertions.assertThrows(IOException.class, () -> client(new FakeTokenTransport().answer("{\"error\":1}")).balances(DOGE_ADDRESS));
        Assertions.assertThrows(IOException.class, () -> client(new FakeTokenTransport().answer("<html>busy</html>")).balances(DOGE_ADDRESS));
    }

    @Test
    public void transfersAreOutpointsWithNullInscriptionIds() throws IOException {
        FakeTokenTransport t = new FakeTokenTransport().answer(fixture("doge-balance-dogx.json"));
        List<TransferInscription> transfers = client(t).transfers(DOGE_ADDRESS, "DOGX");
        Assertions.assertEquals(List.of(new TransferInscription("dogx", "872361000", null, OUTPOINT_A),
                new TransferInscription("dogx", "59", null, OUTPOINT_B)), transfers);
        Assertions.assertNull(transfers.get(0).inscriptionId());
        Assertions.assertEquals(BASE + "/address/" + DOGE_ADDRESS + "/dogx/balance?limit=300", t.urls.get(0));
    }

    @Test
    public void holderWithoutTransfersGivesEmptyList() throws IOException {
        List<TransferInscription> transfers = client(new FakeTokenTransport().answer(fixture("bells-balance-nook.json"))).transfers(BELLS_ADDRESS, "nook");
        Assertions.assertTrue(transfers.isEmpty());
    }

    @Test
    public void transfersPageByTheLastOutpoint() throws IOException {
        String first = "{\"tick\":\"dogx\",\"balance\":\"0\",\"transferable_balance\":\"400\",\"transfers_count\":350,\"transfers\":" + transferRows(0, 300) + "}";
        String second = "{\"tick\":\"dogx\",\"balance\":\"0\",\"transferable_balance\":\"400\",\"transfers_count\":350,\"transfers\":" + transferRows(300, 350) + "}";
        FakeTokenTransport t = new FakeTokenTransport().answer(first).answer(second);
        Assertions.assertEquals(350, client(t).transfers(DOGE_ADDRESS, "dogx").size());
        Assertions.assertEquals(BASE + "/address/" + DOGE_ADDRESS + "/dogx/balance?limit=300&offset=" + String.format("%064x", 299) + "%3A0", t.urls.get(1));
    }

    @Test
    public void transfersShorterThanTheirCountAreAnError() {
        String body = "{\"tick\":\"dogx\",\"balance\":\"0\",\"transferable_balance\":\"60\",\"transfers_count\":3,\"transfers\":" + transferRows(0, 2) + "}";
        Assertions.assertThrows(TokenServerException.class, () -> client(new FakeTokenTransport().answer(body)).transfers(DOGE_ADDRESS, "dogx"));
    }

    @Test
    public void badTransferRowsAreDroppedAndAnAnswerForAnotherTickIsRefused() throws IOException {
        String body = "{\"tick\":\"dogx\",\"balance\":\"0\",\"transferable_balance\":\"60\",\"transfers_count\":3,\"transfers\":["
                + "{\"outpoint\":\"not-an-outpoint\",\"amount\":\"1\"},"
                + "{\"outpoint\":\"" + OUTPOINT_A + "\",\"amount\":\"abc\"},"
                + "{\"outpoint\":\"" + OUTPOINT_B + "\",\"amount\":\"59\"}]}";
        List<TransferInscription> transfers = client(new FakeTokenTransport().answer(body)).transfers(DOGE_ADDRESS, "dogx");
        Assertions.assertEquals(List.of(new TransferInscription("dogx", "59", null, OUTPOINT_B)), transfers);

        String other = "{\"tick\":\"abcd\",\"balance\":\"0\",\"transferable_balance\":\"0\",\"transfers_count\":0}";
        Assertions.assertThrows(IOException.class, () -> client(new FakeTokenTransport().answer(other)).transfers(DOGE_ADDRESS, "dogx"));
    }

    @Test
    public void tokenGivesDecimalsAndTheMintedSupply() throws IOException {
        FakeTokenTransport t = new FakeTokenTransport().answer(fixture("doge-token-dogx.json"));
        Optional<TokenInfo> info = client(t).token("DOGX");
        Assertions.assertEquals(Optional.of(new TokenInfo("dogx", "21000000000", "1000", 18, "21000000000")), info);
        Assertions.assertEquals(18, info.get().dec());
        Assertions.assertEquals(BASE + "/token?tick=dogx", t.urls.get(0));

        Optional<TokenInfo> nook = client(new FakeTokenTransport().answer(fixture("bells-token-nook.json"))).token("nook");
        Assertions.assertEquals(new TokenInfo("nook", "50000000000000000", "500000000", 18, "50000000000000000"), nook.get());
    }

    @Test
    public void unknownTokenIsEmptyAndOutOfRangeDecimalsAreNull() throws IOException {
        Assertions.assertEquals(Optional.empty(), client(new FakeTokenTransport().answer(new OrdClient.HttpStatusException(404))).token("qqq9"));
        String odd = "{\"tick\":\"dogx\",\"supply\":\"5\",\"max\":\"5\",\"lim\":\"5\",\"dec\":99}";
        Assertions.assertNull(client(new FakeTokenTransport().answer(odd)).token("dogx").get().dec());
        Assertions.assertThrows(OrdClient.HttpStatusException.class, () -> client(new FakeTokenTransport().answer(new OrdClient.HttpStatusException(500))).token("dogx"));
    }

    @Test
    public void retriesThreeTimesOn429ThenThrows() {
        FakeTokenTransport t = new FakeTokenTransport().answer(new OrdClient.HttpStatusException(429));
        FakeTokenTransport.Counting counting = new FakeTokenTransport.Counting();
        try {
            new Bel20IndexerClient(t, BASE, counting.limiter).balances(DOGE_ADDRESS);
            Assertions.fail();
        } catch(IOException e) {
            Assertions.assertTrue(e instanceof OrdClient.HttpStatusException h && h.getStatus() == 429);
        }
        Assertions.assertEquals(4, t.urls.size());
        Assertions.assertEquals(3, counting.backoffs.get());
    }

    @Test
    public void badInputIsRejectedBeforeAnyRequestAndHostAndDunesDefaults() {
        FakeTokenTransport t = new FakeTokenTransport();
        Bel20IndexerClient client = client(t);
        Assertions.assertThrows(IllegalArgumentException.class, () -> client.transfers(DOGE_ADDRESS, "dogx&x=1"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> client.balances("../etc"));
        Assertions.assertTrue(t.urls.isEmpty());
        Assertions.assertEquals("doge-mainnet-tokens.nintondo.io", client.host());
        Assertions.assertThrows(UnsupportedOperationException.class, () -> client.dunesOnOutputs(List.of()));
    }
}
