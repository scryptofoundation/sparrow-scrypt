package com.sparrowwallet.sparrow.net.token;

import com.sparrowwallet.sparrow.net.ord.OrdClient;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public class OrdJunkcoinTokenClientTest {
    private static final String BASE = "http://127.0.0.1:8093";
    private static final String ADDRESS = "n1ExampleJunkcoinAddress11111111";
    private static final String T1 = "87e8513e04cdfe4a0df57bac99677311cdc2f19ffa7eed301f946cc0cda67cd4";
    private static final String T2 = "11307c794e3f88c9ffaf85681bed76ea9c99134ac73ccb6da8209bc27f4c645d";
    private static final String OUT = T1 + ":0";

    //HAND-WRITTEN fixtures, from ord-junkcoin's JSON structs (src/templates/junk20.rs, june.rs, junk20/token_info.rs and
    //subcommand/server.rs): junk20-balance.json, junk20-balance-no-utxos.json, junk20-tick.json, junes-on-outputs.json,
    //june.json, june-wxyz.json. The Junkcoin bed's ord runs without --index-junk20 and --index-junes and no ord wallet
    //reaches its node from outside the sandbox, so nothing could be deployed, minted or etched there (Ruling R2).
    //junes-on-outputs-empty.json is a real answer from the bed (2026-11-07). Task 13 re-captures all of them live.
    private static String fixture(String name) throws IOException {
        return FakeTokenTransport.fixture(OrdJunkcoinTokenClientTest.class, "ord-junkcoin/" + name);
    }

    private static OrdJunkcoinTokenClient client(FakeTokenTransport t) {
        return new OrdJunkcoinTokenClient(t, BASE, new FakeTokenTransport.Counting().limiter);
    }

    @Test
    public void balancesReadAvailableAndTransferable() throws IOException {
        FakeTokenTransport t = new FakeTokenTransport().answer(fixture("junk20-balance-no-utxos.json"));
        List<TokenBalance> balances = client(t).balances(ADDRESS);
        Assertions.assertEquals(List.of(new TokenBalance("junk", "900.5", "100"), new TokenBalance("abcd", "5", "0")), balances);
        Assertions.assertEquals("1000.5", balances.get(0).total());
        Assertions.assertEquals(BASE + "/junk20/balance/" + ADDRESS + "?show_utxos=false", t.urls.get(0));
    }

    @Test
    public void junk20TransfersCarryInscriptionIdsAndOnlyValidTransfersCount() throws IOException {
        FakeTokenTransport t = new FakeTokenTransport().answer(fixture("junk20-balance.json"));
        List<TransferInscription> transfers = client(t).transfers(ADDRESS, "JUNK");
        Assertions.assertEquals(List.of(new TransferInscription("junk", "100", T1 + "i0", null)), transfers);
        Assertions.assertEquals(BASE + "/junk20/balance/" + ADDRESS + "?show_utxos=true", t.urls.get(0));
        Assertions.assertTrue(client(new FakeTokenTransport().answer(fixture("junk20-balance.json"))).transfers(ADDRESS, "abcd").isEmpty());
    }

    @Test
    public void malformedBalanceAndTransferRowsAreDropped() throws IOException {
        String body = "{\"junk20\":[{\"tick\":\"toolong\",\"transferable\":\"1\",\"available\":\"1\",\"utxos\":null},"
                + "{\"tick\":\"ok01\",\"transferable\":\"-1\",\"available\":\"1\",\"utxos\":null},"
                + "{\"tick\":\"ok02\",\"transferable\":\"0\",\"available\":\"3\",\"utxos\":["
                + "{\"junk20\":{\"balance\":\"1\",\"operation\":\"transfer\",\"valid\":true},\"inscription_id\":\"nope\"},"
                + "{\"junk20\":{\"balance\":\"x\",\"operation\":\"transfer\",\"valid\":true},\"inscription_id\":\"" + T1 + "i0\"},"
                + "{\"junk20\":{\"balance\":\"2\",\"operation\":\"transfer\",\"valid\":\"yes\"},\"inscription_id\":\"" + T1 + "i1\"},"
                + "{\"junk20\":{\"balance\":\"2\",\"operation\":\"transfer\",\"valid\":true},\"inscription_id\":\"" + T1 + "i2\"}]}]}";
        Assertions.assertEquals(List.of(new TokenBalance("ok02", "3", "0")), client(new FakeTokenTransport().answer(body)).balances(ADDRESS));
        Assertions.assertEquals(List.of(new TransferInscription("ok02", "2", T1 + "i2", null)),
                client(new FakeTokenTransport().answer(body)).transfers(ADDRESS, "ok02"));
    }

    @Test
    public void notAnObjectIsAnError() {
        Assertions.assertThrows(IOException.class, () -> client(new FakeTokenTransport().answer("[]")).balances(ADDRESS));
        Assertions.assertThrows(IOException.class, () -> client(new FakeTokenTransport().answer("{\"junk20\":5}")).balances(ADDRESS));
        Assertions.assertThrows(IOException.class, () -> client(new FakeTokenTransport().answer("<html>")).balances(ADDRESS));
    }

    @Test
    public void tokenScalesTheIndexersIntegersByDecimals() throws IOException {
        FakeTokenTransport t = new FakeTokenTransport().answer(fixture("junk20-tick.json"));
        Optional<TokenInfo> info = client(t).token("JUNK");
        Assertions.assertEquals(Optional.of(new TokenInfo("junk", "21000000", "1000", 18, "1500.5")), info);
        Assertions.assertEquals(BASE + "/junk20/tick/junk", t.urls.get(0));
    }

    @Test
    public void unknownTokenIsEmpty() throws IOException {
        Assertions.assertEquals(Optional.empty(), client(new FakeTokenTransport().answer(new OrdClient.HttpStatusException(400))).token("abcd"));
        Assertions.assertThrows(OrdClient.HttpStatusException.class, () -> client(new FakeTokenTransport().answer(new OrdClient.HttpStatusException(500))).token("abcd"));
    }

    @Test
    public void junesOnOutputsGiveSpacedNameAmountDivisibilityAndIdFromTheEtchingLookup() throws IOException {
        FakeTokenTransport t = new FakeTokenTransport().answer(fixture("junes-on-outputs.json"))
                .answer(fixture("june.json")).answer(fixture("june-wxyz.json"));
        List<DuneBalance> balances = client(t).dunesOnOutputs(List.of(OUT));
        Assertions.assertEquals(List.of(
                new DuneBalance("AB•CD", "120:1", "1500", 2, "J", OUT),
                new DuneBalance("WXYZ", "121:3", "340282366920938463463374607431768211455", 38, "¤", OUT)), balances);
        Assertions.assertEquals(BASE + "/junes_on_outputs?outputs=" + T1 + "%3A0", t.urls.get(0));
        Assertions.assertEquals(BASE + "/june/AB%E2%80%A2CD?json=true", t.urls.get(1));
        Assertions.assertEquals(BASE + "/june/WXYZ?json=true", t.urls.get(2));
    }

    @Test
    public void eachOutputIsAskedOnItsOwnSoRowsKeepTheirOutpointAndAJuneIsLookedUpOnce() throws IOException {
        String one = "[{\"june\":\"WXYZ\",\"balances\":{\"amount\":5,\"divisibility\":0,\"symbol\":\"W\"}}]";
        FakeTokenTransport t = new FakeTokenTransport().answer(one).answer(fixture("june-wxyz.json")).answer(one);
        String second = T2 + ":1";
        List<DuneBalance> balances = client(t).dunesOnOutputs(List.of(OUT, second));
        Assertions.assertEquals(List.of(new DuneBalance("WXYZ", "121:3", "5", 0, "W", OUT), new DuneBalance("WXYZ", "121:3", "5", 0, "W", second)), balances);
        Assertions.assertEquals(3, t.urls.size());
        Assertions.assertTrue(t.urls.get(2).startsWith(BASE + "/junes_on_outputs?outputs=" + T2));
    }

    @Test
    public void noJunesAnswerIsEmptyAndNoOutputsAsksNothing() throws IOException {
        Assertions.assertTrue(client(new FakeTokenTransport().answer(fixture("junes-on-outputs-empty.json"))).dunesOnOutputs(List.of(OUT)).isEmpty());
        FakeTokenTransport t = new FakeTokenTransport();
        Assertions.assertTrue(client(t).dunesOnOutputs(List.of()).isEmpty());
        Assertions.assertTrue(t.urls.isEmpty());
    }

    @Test
    public void badJuneRowsAreDroppedAndAnUnknownJuneKeepsANullId() throws IOException {
        String body = "[{\"june\":\"lower\",\"balances\":{\"amount\":1,\"divisibility\":0,\"symbol\":null}},"
                + "{\"june\":\"ABCD\",\"balances\":{\"amount\":-1,\"divisibility\":0,\"symbol\":null}},"
                + "{\"june\":\"ABCD\",\"balances\":{\"amount\":1,\"divisibility\":39,\"symbol\":null}},"
                + "{\"june\":\"ABCD\",\"balances\":{\"amount\":\"1\",\"divisibility\":0,\"symbol\":null}},"
                + "{\"june\":\"ABCD\",\"balances\":{\"amount\":7,\"divisibility\":1,\"symbol\":\"toolong\"}}]";
        FakeTokenTransport t = new FakeTokenTransport().answer(body).answer(new OrdClient.HttpStatusException(404));
        Assertions.assertEquals(List.of(new DuneBalance("ABCD", null, "7", 1, "¤", OUT)), client(t).dunesOnOutputs(List.of(OUT)));
    }

    @Test
    public void aJuneLookupForAnotherNameGivesNoId() throws IOException {
        String row = "[{\"june\":\"ABCE\",\"balances\":{\"amount\":7,\"divisibility\":1,\"symbol\":null}}]";
        FakeTokenTransport t = new FakeTokenTransport().answer(row).answer(fixture("june.json"));
        Assertions.assertNull(client(t).dunesOnOutputs(List.of(OUT)).get(0).id());
    }

    @Test
    public void badOutpointIsRejectedBeforeAnyRequest() {
        FakeTokenTransport t = new FakeTokenTransport();
        Assertions.assertThrows(IllegalArgumentException.class, () -> client(t).dunesOnOutputs(List.of(OUT, "xyz:0")));
        Assertions.assertTrue(t.urls.isEmpty());
    }

    @Test
    public void etchingTxidByNameOrId() throws IOException {
        FakeTokenTransport t = new FakeTokenTransport().answer(fixture("june.json"));
        Assertions.assertEquals(Optional.of(T1), client(t).duneEtchingTxid("AB•CD"));
        Assertions.assertEquals(BASE + "/june/AB%E2%80%A2CD?json=true", t.urls.get(0));

        FakeTokenTransport byId = new FakeTokenTransport().answer(fixture("june.json"));
        Assertions.assertEquals(Optional.of(T1), client(byId).duneEtchingTxid("120:1"));
        Assertions.assertEquals(BASE + "/june/120%3A1?json=true", byId.urls.get(0));
    }

    @Test
    public void etchingTxidRefusesAnAnswerForAnotherJuneAndUnknownIsEmpty() throws IOException {
        Assertions.assertThrows(TokenServerException.class, () -> client(new FakeTokenTransport().answer(fixture("june-wxyz.json"))).duneEtchingTxid("AB•CD"));
        Assertions.assertThrows(TokenServerException.class, () -> client(new FakeTokenTransport().answer(fixture("june.json"))).duneEtchingTxid("121:3"));
        Assertions.assertEquals(Optional.empty(), client(new FakeTokenTransport().answer(new OrdClient.HttpStatusException(404))).duneEtchingTxid("ABCD"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> client(new FakeTokenTransport()).duneEtchingTxid("../x"));
        String noTxid = "{\"entry\":{\"etching\":\"zz\",\"june\":\"ABCD\"},\"id\":\"1:0\"}";
        Assertions.assertThrows(IOException.class, () -> client(new FakeTokenTransport().answer(noTxid)).duneEtchingTxid("ABCD"));
    }

    @Test
    public void moreThanTwoHundredOutpointsAreRefusedBeforeAnyRequest() {
        FakeTokenTransport t = new FakeTokenTransport();
        List<String> outpoints = new java.util.ArrayList<>();
        for(int i = 0; i < OrdJunkcoinTokenClient.MAX_OUTPOINTS_PER_CALL + 1; i++) {
            outpoints.add(String.format("%064x", i) + ":0");
        }
        IllegalArgumentException e = Assertions.assertThrows(IllegalArgumentException.class, () -> client(t).dunesOnOutputs(outpoints));
        Assertions.assertEquals("At most 200 outputs per call", e.getMessage());
        Assertions.assertTrue(t.urls.isEmpty());
    }

    @Test
    public void juneLookupsAreCappedAndTheRestHaveNullIds() throws IOException {
        int distinct = OrdJunkcoinTokenClient.MAX_JUNE_LOOKUPS_PER_CALL + 2;
        StringBuilder rows = new StringBuilder("[");
        for(int i = 0; i < distinct; i++) {
            String name = "" + (char)('A' + i / 26) + (char)('A' + i % 26);
            rows.append(i > 0 ? "," : "").append("{\"june\":\"").append(name).append("\",\"balances\":{\"amount\":1,\"divisibility\":0,\"symbol\":null}}");
        }
        rows.append("]");
        FakeTokenTransport t = new FakeTokenTransport().answer(rows.toString()).answer(new OrdClient.HttpStatusException(404));
        List<DuneBalance> balances = client(t).dunesOnOutputs(List.of(OUT));
        Assertions.assertEquals(distinct, balances.size());
        Assertions.assertEquals(1 + OrdJunkcoinTokenClient.MAX_JUNE_LOOKUPS_PER_CALL, t.urls.size());
        Assertions.assertTrue(balances.stream().allMatch(b -> b.id() == null));
    }

    @Test
    public void aFailedJuneLookupOfAnyKindLeavesANullIdAndTheReadGoesOn() throws IOException {
        String row = "[{\"june\":\"WXYZ\",\"balances\":{\"amount\":7,\"divisibility\":1,\"symbol\":null}}]";
        FakeTokenTransport t = new FakeTokenTransport().answer(row).answer(new IOException("timeout"));
        Assertions.assertNull(client(t).dunesOnOutputs(List.of(OUT)).get(0).id());
        FakeTokenTransport garbage = new FakeTokenTransport().answer(row).answer("<html>");
        Assertions.assertNull(client(garbage).dunesOnOutputs(List.of(OUT)).get(0).id());
    }

    @Test
    public void integersLongerThanU128AreRejected() throws IOException {
        String max = "340282366920938463463374607431768211455";
        String tooLong = "3402823669209384634633746074317682114550";
        String body = "[{\"june\":\"WXYZ\",\"balances\":{\"amount\":" + max + ",\"divisibility\":0,\"symbol\":null}},"
                + "{\"june\":\"WXYZ\",\"balances\":{\"amount\":" + tooLong + ",\"divisibility\":0,\"symbol\":null}}]";
        FakeTokenTransport t = new FakeTokenTransport().answer(body).answer(new OrdClient.HttpStatusException(404));
        Assertions.assertEquals(1, client(t).dunesOnOutputs(List.of(OUT)).size());

        String tick = "{\"tick\":\"junk\",\"supply\":" + tooLong + ",\"minted\":5,\"limit_per_mint\":7,\"decimal\":0}";
        TokenInfo info = client(new FakeTokenTransport().answer(tick)).token("junk").get();
        Assertions.assertNull(info.max());
        Assertions.assertEquals("5", info.minted());
    }

    @Test
    public void retriesOn429AndHostIsTheServersHost() throws IOException {
        FakeTokenTransport t = new FakeTokenTransport().answer(new OrdClient.HttpStatusException(429)).answer(fixture("junk20-balance-no-utxos.json"));
        FakeTokenTransport.Counting counting = new FakeTokenTransport.Counting();
        OrdJunkcoinTokenClient client = new OrdJunkcoinTokenClient(t, BASE, counting.limiter);
        Assertions.assertEquals(2, client.balances(ADDRESS).size());
        Assertions.assertEquals(1, counting.backoffs.get());
        Assertions.assertEquals("127.0.0.1", client.host());
    }
}
