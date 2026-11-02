package com.sparrowwallet.sparrow.net.ord;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.Network;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class OrdClientParseTest {
    private static final String OUTPOINT = "3b6fb513db37f827cea7c89085a41605ed2776e42e71348156bec9739d3b6c64:0";
    private static final String INSCRIPTION = "3b6fb513db37f827cea7c89085a41605ed2776e42e71348156bec9739d3b6c64i0";

    private static String fixture(String name) throws IOException {
        try(InputStream in = OrdClientParseTest.class.getResourceAsStream(name)) {
            Assertions.assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Nintondo's find_meta lists the inscriptions on an output (following them as they move); empty means clean. */
    @Test
    public void parsesNintondoFindMeta() throws IOException {
        OrdOutput carrying = OrdClient.parseNintondoMeta(OUTPOINT,
                "[{\"number\":47176483,\"owner\":\"DLXoypYdoWu1qodJwEsANXTRAJzvbtm1pn\",\"height\":4999503,\"genesis\":\"" + INSCRIPTION + "\"}]");
        Assertions.assertTrue(carrying.indexed());
        Assertions.assertEquals(List.of(INSCRIPTION), carrying.inscriptions());
        Assertions.assertTrue(carrying.carries());

        OrdOutput clean = OrdClient.parseNintondoMeta(OUTPOINT, "[]");
        Assertions.assertTrue(clean.indexed());
        Assertions.assertFalse(clean.carries());

        OrdOutput odd = OrdClient.parseNintondoMeta(OUTPOINT, "[{\"genesis\":\"not an id\"}]");
        Assertions.assertTrue(odd.carries(), "an entry without a usable id still carries something");
        Assertions.assertThrows(IOException.class, () -> OrdClient.parseNintondoMeta(OUTPOINT, "{\"error\":1}"));
    }

    /** Nintondo's status names the chain ("Pepecoin Testnet"); a server for another coin is refused. */
    @Test
    public void nintondoStatusNamesCoinAndNetwork() throws IOException {
        Assertions.assertEquals(new OrdStatus("mainnet", true, 1300750, null),
                OrdClient.nintondoStatus("{\"network\":\"Bellscoin\",\"ord_tip\":\"ab\",\"ord_height\":1300750,\"tip\":\"ab\",\"tip_height\":1300751}", Coin.BELLSCOIN));
        Assertions.assertEquals("testnet", OrdClient.nintondoStatus("{\"network\":\"Pepecoin Testnet\",\"ord_height\":1200}", Coin.PEPECOIN).chain());
        Assertions.assertThrows(IOException.class, () -> OrdClient.nintondoStatus("{\"network\":\"Dogecoin\",\"ord_height\":5}", Coin.PEPECOIN));
        Assertions.assertThrows(IOException.class, () -> OrdClient.nintondoStatus("<html>", Coin.DOGECOIN));
    }

    @Test
    public void parsesRuneEntry() throws IOException {
        OrdRune rune = OrdClient.parseRune(fixture("rune-regtest.json"));
        Assertions.assertEquals(new com.sparrowwallet.drongo.ordinals.RuneId(111, 1), rune.id());
        Assertions.assertEquals("TESTRUNE•LITECOIN", rune.spacedName());
        Assertions.assertEquals(2, rune.divisibility());
        Assertions.assertEquals("T", rune.symbol());
    }

    @Test
    public void runeNameIsValidatedBeforeRequest() {
        //No server listens here; a name failing validation must throw before any connection is tried
        OrdClient client = new OrdClient("http://127.0.0.1:9");
        Assertions.assertThrows(IllegalArgumentException.class, () -> client.rune("not/a/rune"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> client.rune(""));
    }

    @Test
    public void malformedRuneIdRejected() {
        Assertions.assertThrows(IOException.class, () -> OrdClient.parseRune("{\"entry\":{\"spaced_rune\":\"AB\",\"divisibility\":0},\"id\":\"x\"}"));
        Assertions.assertThrows(IOException.class, () -> OrdClient.parseRune("{\"entry\":{\"spaced_rune\":\"AB\",\"divisibility\":99},\"id\":\"1:1\"}"));
        Assertions.assertThrows(IOException.class, () -> OrdClient.parseRune("{\"entry\":{\"spaced_rune\":\"a b\",\"divisibility\":0},\"id\":\"1:1\"}"));
    }

    @Test
    public void mainnetOutputWithInscription() throws IOException {
        OrdOutput output = OrdClient.parseOutput(OUTPOINT, fixture("output-inscription-mainnet.json"));
        Assertions.assertEquals(OUTPOINT, output.outpoint());
        Assertions.assertTrue(output.indexed());
        Assertions.assertEquals(List.of(INSCRIPTION), output.inscriptions());
        Assertions.assertTrue(output.runes().isEmpty());
        Assertions.assertEquals(10000, output.value());
        Assertions.assertTrue(output.carries());
    }

    @Test
    public void postOutputsMatchesByOrder() throws IOException {
        String json = fixture("outputs-mainnet.json");
        List<OrdOutput> outputs = OrdClient.parseOutputs(List.of(OUTPOINT), json);
        Assertions.assertEquals(1, outputs.size());
        Assertions.assertEquals(OUTPOINT, outputs.getFirst().outpoint());
        String other = "00".repeat(32) + ":1";
        Assertions.assertThrows(IOException.class, () -> OrdClient.parseOutputs(List.of(OUTPOINT, other), json));
    }

    @Test
    public void runesAsPairArray() throws IOException {
        String json = "{\"indexed\":true,\"inscriptions\":[],\"runes\":[[\"A•B\",{\"amount\":12345,\"divisibility\":2,\"symbol\":\"x\"}]],\"value\":546,\"spent\":false}";
        OrdOutput output = OrdClient.parseOutput(OUTPOINT, json);
        Assertions.assertEquals("123.45", output.runes().get("A•B").format());
        Assertions.assertEquals("x", output.runes().get("A•B").symbol());
        Assertions.assertTrue(output.carries());
    }

    @Test
    public void runesAsObject() throws IOException {
        String json = "{\"indexed\":true,\"inscriptions\":[],\"runes\":{\"THE•OMEGA\":{\"amount\":25,\"divisibility\":0,\"symbol\":\"B\"}},\"value\":546,\"spent\":false}";
        OrdOutput output = OrdClient.parseOutput(OUTPOINT, json);
        Assertions.assertEquals("25", output.runes().get("THE•OMEGA").format());
    }

    @Test
    public void regtestRunes() throws IOException {
        String outpoint = "4cd2a10d346b8aa6df179405e0e96f66dcbbfed266466e8bc7b1145e6fe95f7e:1";
        OrdOutput output = OrdClient.parseOutput(outpoint, fixture("output-runes-regtest.json"));
        RuneBalance balance = output.runes().get("TESTRUNE•LITECOIN");
        Assertions.assertEquals(2, balance.divisibility());
        Assertions.assertEquals("T", balance.symbol());
        Assertions.assertEquals("1000.00", balance.format());
        Assertions.assertTrue(output.inscriptions().isEmpty());
        Assertions.assertTrue(output.carries());
    }

    /** ord answers an output of a transaction not yet mined with indexed false (and spent true): unknown, not empty. */
    @Test
    public void regtestUnminedIsUnindexed() throws IOException {
        OrdOutput output = OrdClient.parseOutput("9a9d6ab0b0ecd9979559bcd0fbf03198080b7d906cfaf8b611a60b73511331e6:0", fixture("output-unindexed-regtest.json"));
        Assertions.assertFalse(output.indexed());
        Assertions.assertFalse(output.carries());
    }

    @Test
    public void regtestStatus() throws IOException {
        OrdStatus status = OrdClient.parseStatus(fixture("status-regtest.json"));
        Assertions.assertEquals("regtest", status.chain());
        Assertions.assertTrue(status.runeIndex());
        Assertions.assertDoesNotThrow(() -> OrdClient.requireChain(status, Network.REGTEST));
        Assertions.assertThrows(IOException.class, () -> OrdClient.requireChain(status, Network.MAINNET));
    }

    @Test
    public void unindexedIsUnknown() throws IOException {
        OrdOutput output = OrdClient.parseOutput(OUTPOINT, "{\"indexed\":false,\"inscriptions\":[],\"runes\":{},\"value\":1000,\"spent\":false}");
        Assertions.assertFalse(output.indexed());
        Assertions.assertFalse(OrdOutput.unknown(OUTPOINT).indexed());
        Assertions.assertFalse(OrdOutput.unknown(OUTPOINT).carries());
    }

    @Test
    public void statusChains() throws IOException {
        OrdStatus status = OrdClient.parseStatus(fixture("status-mainnet.json"));
        Assertions.assertEquals("mainnet", status.chain());
        Assertions.assertTrue(status.runeIndex());
        Assertions.assertTrue(status.height() > 3_000_000);
    }

    @Test
    public void chainMismatchIsRefused() throws IOException {
        OrdStatus status = OrdClient.parseStatus(fixture("status-mainnet.json"));
        Assertions.assertDoesNotThrow(() -> OrdClient.requireChain(status, Network.MAINNET));
        Assertions.assertThrows(IOException.class, () -> OrdClient.requireChain(status, Network.REGTEST));
    }

    @Test
    public void inscriptionDetails() throws IOException {
        OrdInscription inscription = OrdClient.parseInscription(fixture("inscription-text-mainnet.json"));
        Assertions.assertEquals(INSCRIPTION, inscription.id());
        Assertions.assertEquals(26845519L, inscription.number());
        Assertions.assertEquals("text/plain;charset=utf-8", inscription.contentType());
        Assertions.assertEquals(63, inscription.contentLength());
        Assertions.assertEquals(OUTPOINT + ":0", inscription.satpoint());
    }

    @Test
    public void badIdIsRejected() {
        OrdClient client = new OrdClient("http://127.0.0.1:1");
        Assertions.assertThrows(IllegalArgumentException.class, () -> client.inscription("../status"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> client.content(INSCRIPTION + "/../x"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> client.outputs(List.of("abc:0")));
    }

    /** A server that sends headers and then nothing must not hold the checks up forever. */
    @Test
    public void stalledBodyTimesOut() {
        java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
        InputStream stalled = new InputStream() {
            @Override
            public int read() throws IOException {
                try {
                    closed.await();
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IOException("closed");
            }

            @Override
            public void close() {
                closed.countDown();
            }
        };
        long start = System.nanoTime();
        Assertions.assertThrows(IOException.class, () -> OrdClient.readCapped(stalled, 1000, 300));
        Assertions.assertTrue(System.nanoTime() - start < 5_000_000_000L);
    }

    @Test
    public void bodyWithinDeadlineIsRead() throws IOException {
        Assertions.assertEquals(3, OrdClient.readCapped(new java.io.ByteArrayInputStream(new byte[3]), 10, 1000).length);
        Assertions.assertThrows(IOException.class, () -> OrdClient.readCapped(new java.io.ByteArrayInputStream(new byte[11]), 10, 1000));
    }

    @Test
    public void oversizedJsonIsRejected() {
        String json = "{\"indexed\":true,\"pad\":\"" + "a".repeat(OrdClient.MAX_JSON_CHARS) + "\"}";
        Assertions.assertThrows(IOException.class, () -> OrdClient.parseOutput(OUTPOINT, json));
    }

    @Test
    public void hostileRuneNameDropped() throws IOException {
        String json = "{\"indexed\":true,\"inscriptions\":[],\"runes\":{\"<script>\":{\"amount\":1,\"divisibility\":0,\"symbol\":\"\\u0007\"},\"OK\":{\"amount\":1,\"divisibility\":0,\"symbol\":\"\\u0007\"}},\"value\":546,\"spent\":false}";
        OrdOutput output = OrdClient.parseOutput(OUTPOINT, json);
        Assertions.assertFalse(output.runes().containsKey("<script>"));
        Assertions.assertEquals("¤", output.runes().get("OK").symbol());
        //A dropped rune still means the output carries something
        Assertions.assertTrue(output.carries());
    }

    @Test
    public void hostileInscriptionIdDropped() throws IOException {
        String json = "{\"indexed\":true,\"inscriptions\":[\"../../x\"],\"runes\":{},\"value\":546,\"spent\":false}";
        OrdOutput output = OrdClient.parseOutput(OUTPOINT, json);
        Assertions.assertTrue(output.inscriptions().isEmpty());
        Assertions.assertTrue(output.carries());
    }
}
