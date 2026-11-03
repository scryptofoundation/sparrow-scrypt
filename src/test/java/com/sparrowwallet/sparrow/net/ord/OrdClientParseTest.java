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

    /** Serves recorded Nintondo answers by absolute URL and remembers what was asked, so the three-host flow is pinned. */
    private static class FixtureTransport implements Transport {
        final java.util.Map<String, byte[]> answers = new java.util.LinkedHashMap<>();
        final List<String> asked = new java.util.ArrayList<>();

        FixtureTransport answer(String url, String resource) throws IOException {
            try(InputStream in = OrdClientParseTest.class.getResourceAsStream(resource)) {
                Assertions.assertNotNull(in, resource);
                answers.put(url, in.readAllBytes());
            }
            return this;
        }

        @Override
        public String json(String url, String postBody) throws IOException {
            return new String(bytes(url, "application/json", OrdClient.MAX_JSON_CHARS), StandardCharsets.UTF_8);
        }

        @Override
        public byte[] bytes(String url, String accept, int maxBytes) throws IOException {
            asked.add(url);
            byte[] answer = answers.get(url);
            if(answer == null) {
                throw new OrdClient.HttpStatusException(404);
            }
            return answer;
        }
    }

    private static final String DOGE_ID = "b5f9d2759c65df72dff76bbab840ff3d34d99c6679bad26148cb18344ff03f71i0";
    private static final String PEPE_ID = "d089eab0d91c5889eb2789804498ab51229048451eb9d9f9510ac2b84416c546i0";
    private static final String BELLS_ID = "554a01b993871859e622b2c6391da63e74393b8cab2958ca09eebf4688af7773i0";

    private static FixtureTransport nintondo(String coin, String id) throws IOException {
        return new FixtureTransport()
                .answer("https://" + coin + "-mainnet-api.nintondo.io/location/" + id, "nintondo-" + coin + "-location.json")
                .answer("https://" + coin + "-mainnet-search.nintondo.io/pub/" + id + "/info", "nintondo-" + coin + "-info.json");
    }

    /** Location gives txid, vout and offset (the satpoint), info gives number, mime and size; both are real recordings. */
    @Test
    public void nintondoLocationGivesSatpoint() throws IOException {
        FixtureTransport transport = nintondo("doge", DOGE_ID);
        OrdInscription inscription = new NintondoDialect("https://doge-mainnet-api.nintondo.io").inscription(transport, DOGE_ID);
        Assertions.assertEquals(DOGE_ID, inscription.id());
        Assertions.assertEquals("b5f9d2759c65df72dff76bbab840ff3d34d99c6679bad26148cb18344ff03f71:0:0", inscription.satpoint());
        Assertions.assertEquals(180433203L, inscription.number());
        Assertions.assertEquals("text/plain; charset=utf-8", inscription.contentType());
        Assertions.assertEquals(15, inscription.contentLength());
        Assertions.assertEquals(100000, inscription.value());
        Assertions.assertEquals(List.of("https://doge-mainnet-api.nintondo.io/location/" + DOGE_ID,
                "https://doge-mainnet-search.nintondo.io/pub/" + DOGE_ID + "/info"), transport.asked);
    }

    @Test
    public void nintondoPepecoinAndBellscoinFixturesParse() throws IOException {
        OrdInscription pepe = new NintondoDialect("https://pepe-mainnet-api.nintondo.io").inscription(nintondo("pepe", PEPE_ID), PEPE_ID);
        Assertions.assertEquals("77405cbde12fbd5939fc54f9e041868aabb1a25b9a0b48edad47f051af3f9635:0:0", pepe.satpoint());
        Assertions.assertEquals("text/html; charset=utf-8", pepe.contentType());
        Assertions.assertEquals(14346, pepe.contentLength());
        OrdInscription bells = new NintondoDialect("https://bells-mainnet-api.nintondo.io").inscription(nintondo("bells", BELLS_ID), BELLS_ID);
        Assertions.assertEquals("554a01b993871859e622b2c6391da63e74393b8cab2958ca09eebf4688af7773:0:0", bells.satpoint());
        Assertions.assertEquals(276671008L, bells.number());
        Assertions.assertEquals(1000, bells.value());
    }

    @Test
    public void nintondoOutputFixturesParse() throws IOException {
        for(String[] coin : new String[][] {{"doge", DOGE_ID}, {"pepe", PEPE_ID}, {"bells", BELLS_ID}}) {
            String api = "https://" + coin[0] + "-mainnet-api.nintondo.io";
            String txid = coin[1].substring(0, 64);
            FixtureTransport transport = new FixtureTransport().answer(api + "/find_meta/" + txid + "i0", "nintondo-" + coin[0] + "-find-meta.json");
            OrdOutput output = new NintondoDialect(api).outputs(transport, List.of(txid + ":0")).getFirst();
            Assertions.assertEquals(List.of(coin[1]), output.inscriptions(), coin[0]);
            Assertions.assertTrue(output.indexed());
        }
    }

    @Test
    public void nintondoUnknownOutputIsUnknownNotClean() throws IOException {
        String api = "https://doge-mainnet-api.nintondo.io";
        OrdOutput output = new NintondoDialect(api).outputs(new FixtureTransport(), List.of(OUTPOINT)).getFirst();
        Assertions.assertFalse(output.indexed());
        Assertions.assertFalse(output.carries());
    }

    @Test
    public void nintondoHostsDerivedFromApi() {
        Assertions.assertEquals("https://doge-mainnet-search.nintondo.io/pub", NintondoDialect.searchBase("https://doge-mainnet-api.nintondo.io"));
        Assertions.assertEquals("https://doge-mainnet-content.nintondo.io", NintondoDialect.contentBase("https://doge-mainnet-api.nintondo.io"));
        Assertions.assertEquals("https://bells-testnet-content.nintondo.io", NintondoDialect.contentBase("https://bells-testnet-api.nintondo.io"));
        //The scheme and port follow the configured server, so the proxy rules for it cover the other hosts too
        Assertions.assertEquals("http://pepe-mainnet-search.nintondo.io:8080/pub", NintondoDialect.searchBase("http://pepe-mainnet-api.nintondo.io:8080"));
        Assertions.assertNull(NintondoDialect.searchBase("https://nintondo.io"));
        Assertions.assertNull(NintondoDialect.contentBase("https://doge-mainnet-search.nintondo.io"));
    }

    @Test
    public void nintondoContentComesFromContentHost() throws IOException {
        FixtureTransport transport = new FixtureTransport().answer("https://doge-mainnet-content.nintondo.io/content/" + DOGE_ID, "nintondo-doge-content.bin");
        byte[] content = new NintondoDialect("https://doge-mainnet-api.nintondo.io").content(transport, DOGE_ID);
        Assertions.assertEquals(15, content.length);
    }

    @Test
    public void nintondoExplorerUrl() {
        Assertions.assertEquals("https://nintondo.io/doge/inscriptions/" + DOGE_ID, new NintondoDialect("https://doge-mainnet-api.nintondo.io").explorerUrl("https://doge-mainnet-api.nintondo.io", DOGE_ID));
        Assertions.assertEquals("https://nintondo.io/pepe/inscriptions/" + PEPE_ID, new NintondoDialect("https://pepe-mainnet-api.nintondo.io").explorerUrl("https://pepe-mainnet-api.nintondo.io", PEPE_ID));
        Assertions.assertEquals("https://nintondo.io/bells/inscriptions/" + BELLS_ID, new NintondoDialect("https://bells-mainnet-api.nintondo.io").explorerUrl("https://bells-mainnet-api.nintondo.io", BELLS_ID));
        //No page is known for testnet, so the content host's rendered view stands in
        Assertions.assertEquals("https://bells-testnet-content.nintondo.io/html/" + BELLS_ID, new NintondoDialect("https://bells-testnet-api.nintondo.io").explorerUrl("https://bells-testnet-api.nintondo.io", BELLS_ID));
        Assertions.assertEquals("https://ordinalslite.com/inscription/" + INSCRIPTION, new Ord020Dialect("https://ordinalslite.com").explorerUrl("https://ordinalslite.com", INSCRIPTION));
    }

    @Test
    public void nintondoHostileAnswersAreRefused() throws IOException {
        String api = "https://doge-mainnet-api.nintondo.io";
        String locationUrl = api + "/location/" + DOGE_ID;
        String infoUrl = "https://doge-mainnet-search.nintondo.io/pub/" + DOGE_ID + "/info";
        String info = new String(OrdClientParseTest.class.getResourceAsStream("nintondo-doge-info.json").readAllBytes(), StandardCharsets.UTF_8);
        for(String location : List.of("{\"location\":\"../../x\",\"value\":1}", "{\"location\":\"" + DOGE_ID + "i0\",\"value\":1}", "{\"value\":1}", "[]",
                "{\"location\":\"" + "g".repeat(64) + "i0i0i1\",\"value\":1}")) {
            FixtureTransport transport = new FixtureTransport();
            transport.answers.put(locationUrl, location.getBytes(StandardCharsets.UTF_8));
            transport.answers.put(infoUrl, info.getBytes(StandardCharsets.UTF_8));
            Assertions.assertThrows(IOException.class, () -> new NintondoDialect(api).inscription(transport, DOGE_ID), location);
        }
        //Info for a different inscription than asked is refused
        FixtureTransport mismatched = nintondo("doge", DOGE_ID);
        mismatched.answers.put(infoUrl, info.replace(DOGE_ID, PEPE_ID).getBytes(StandardCharsets.UTF_8));
        Assertions.assertThrows(IOException.class, () -> new NintondoDialect(api).inscription(mismatched, DOGE_ID));
        //Hosts that are not Nintondo's api shape cannot reach info or content
        Assertions.assertThrows(IOException.class, () -> new NintondoDialect("https://nintondo.io").inscription(nintondo("doge", DOGE_ID), DOGE_ID));
    }

    /** The client picks the dialect from the server URL: Nintondo's hosts get Nintondo's, everything else ord 0.20. */
    @Test
    public void clientPicksDialectFromUrl() {
        Assertions.assertEquals(NintondoDialect.NAME, new OrdClient("https://pepe-mainnet-api.nintondo.io").dialect());
        Assertions.assertEquals(Ord020Dialect.NAME, new OrdClient("https://ordinalslite.com").dialect());
    }

    /** Nintondo content is never rendered as a web page: html shows the placeholder (existing behaviour, pinned for its content). */
    @Test
    public void htmlContentNotRendered() throws IOException {
        byte[] html;
        try(InputStream in = OrdClientParseTest.class.getResourceAsStream("nintondo-pepe-content.html")) {
            html = in.readAllBytes();
        }
        Assertions.assertEquals(com.sparrowwallet.sparrow.wallet.collectibles.InscriptionPreview.Kind.PLACEHOLDER,
                com.sparrowwallet.sparrow.wallet.collectibles.InscriptionPreview.kind("text/html; charset=utf-8", html));
    }

    private static final String LEGACY_A = "1a".repeat(32);
    private static final String LEGACY_B = "2b".repeat(32);
    private static final String LEGACY_C = "3c".repeat(32);
    private static final String LEGACY_BASE = "https://jkc.example";

    /** The first answer decides the dialect: Nintondo by host, JSON naming a chain is ord 0.20, a bare OK is ord 0.5. */
    @Test
    public void detectsDialects() throws IOException {
        Assertions.assertEquals(NintondoDialect.NAME, OrdClient.detect("https://doge-mainnet-api.nintondo.io", "{}").name());
        Assertions.assertEquals(Ord020Dialect.NAME, OrdClient.detect("https://x", "{\"chain\":\"mainnet\"}").name());
        Assertions.assertEquals(LegacyOrdDialect.NAME, OrdClient.detect("https://x", "OK\n").name());
        Assertions.assertEquals(LegacyOrdDialect.NAME, OrdClient.LEGACY_DIALECT);
        Assertions.assertThrows(IOException.class, () -> OrdClient.detect("https://x", "<html>"));
        Assertions.assertThrows(IOException.class, () -> OrdClient.detect("https://x", "{\"chain\":5}"));
        Assertions.assertThrows(IOException.class, () -> OrdClient.detect("https://x", ""));
    }

    /** ord-junkcoin's /outputs answers in request order but names only the transaction, so entries are matched by txid. */
    @Test
    public void legacyOutputsMatchByOutpoint() throws IOException {
        String json = fixture("legacy-outputs.json");
        List<OrdOutput> outputs = LegacyOrdDialect.parseOutputs(List.of(LEGACY_A + ":0", LEGACY_B + ":1", LEGACY_C + ":0"), json);
        Assertions.assertEquals(3, outputs.size());
        Assertions.assertEquals(LEGACY_A + ":0", outputs.get(0).outpoint());
        Assertions.assertEquals(List.of(LEGACY_A + "i0"), outputs.get(0).inscriptions());
        Assertions.assertEquals(100000, outputs.get(0).value());
        Assertions.assertTrue(outputs.get(0).indexed());
        Assertions.assertEquals(LEGACY_B + ":1", outputs.get(1).outpoint());
        Assertions.assertTrue(outputs.get(1).inscriptions().isEmpty());
        Assertions.assertEquals(5000, outputs.get(1).value());
        Assertions.assertTrue(outputs.get(1).indexed());
        Assertions.assertFalse(outputs.get(1).carries());
        Assertions.assertEquals(OrdOutput.unknown(LEGACY_C + ":0"), outputs.get(2));
        Assertions.assertThrows(IOException.class, () -> LegacyOrdDialect.parseOutputs(List.of(LEGACY_A + ":0"), "{}"));
    }

    /** A june (rune-like) balance, or an id that is not one, keeps the output held. */
    @Test
    public void legacyOutputWithJunesOrOddIdsCarries() throws IOException {
        String junes = "[{\"inscriptions\":[],\"junes\":[[\"AB\",{\"amount\":5,\"divisibility\":0,\"symbol\":null}]],\"transaction\":\"" + LEGACY_A + "\",\"value\":1}]";
        Assertions.assertTrue(LegacyOrdDialect.parseOutputs(List.of(LEGACY_A + ":0"), junes).getFirst().carries());
        String odd = "[{\"inscriptions\":[\"nope\"],\"junes\":[],\"transaction\":\"" + LEGACY_A + "\",\"value\":1}]";
        OrdOutput output = LegacyOrdDialect.parseOutputs(List.of(LEGACY_A + ":0"), odd).getFirst();
        Assertions.assertTrue(output.carries());
        Assertions.assertTrue(output.inscriptions().isEmpty());
    }

    /** Outputs of one transaction cannot be told apart in an answer, so they never share a request; a 404 means one is unknown. */
    /** wonky-ord servers for other coins report token balances under dunes or runes, not junes: any of them keeps the output held. */
    @Test
    public void legacyOutputWithDunesOrRunesCarries() throws IOException {
        for(String field : List.of("dunes", "runes")) {
            String json = "[{\"inscriptions\":[],\"" + field + "\":[[\"AB\",{\"amount\":5,\"divisibility\":0,\"symbol\":null}]],\"transaction\":\"" + LEGACY_A + "\",\"value\":1}]";
            Assertions.assertTrue(LegacyOrdDialect.parseOutputs(List.of(LEGACY_A + ":0"), json).getFirst().carries(), field);
            String empty = "[{\"inscriptions\":[],\"" + field + "\":[],\"transaction\":\"" + LEGACY_A + "\",\"value\":1}]";
            Assertions.assertFalse(LegacyOrdDialect.parseOutputs(List.of(LEGACY_A + ":0"), empty).getFirst().carries(), field);
        }
    }

    @Test
    public void legacyOutputsSplitSameTransactionAndFallBackOnNotFound() throws IOException {
        String entryA = "{\"inscriptions\":[],\"junes\":[],\"transaction\":\"" + LEGACY_A + "\",\"value\":%d}";
        String entryB = "{\"inscriptions\":[],\"junes\":[],\"transaction\":\"" + LEGACY_B + "\",\"value\":7}";
        FixtureTransport transport = new FixtureTransport();
        transport.answers.put(LEGACY_BASE + "/outputs/" + LEGACY_A + ":0," + LEGACY_B + ":0", ("[" + entryA.formatted(11) + "," + entryB + "]").getBytes(StandardCharsets.UTF_8));
        transport.answers.put(LEGACY_BASE + "/outputs/" + LEGACY_A + ":1", ("[" + entryA.formatted(22) + "]").getBytes(StandardCharsets.UTF_8));
        List<OrdOutput> outputs = new LegacyOrdDialect(LEGACY_BASE).outputs(transport, List.of(LEGACY_A + ":0", LEGACY_A + ":1", LEGACY_B + ":0"));
        Assertions.assertEquals(List.of(11L, 22L, 7L), outputs.stream().map(OrdOutput::value).toList());
        Assertions.assertEquals(2, transport.asked.size());

        //One output the server does not know makes it answer 404 for the whole request: ask again one by one
        FixtureTransport missing = new FixtureTransport();
        missing.answers.put(LEGACY_BASE + "/outputs/" + LEGACY_A + ":0", ("[" + entryA.formatted(11) + "]").getBytes(StandardCharsets.UTF_8));
        List<OrdOutput> partial = new LegacyOrdDialect(LEGACY_BASE).outputs(missing, List.of(LEGACY_A + ":0", LEGACY_C + ":0"));
        Assertions.assertEquals(11, partial.get(0).value());
        Assertions.assertEquals(OrdOutput.unknown(LEGACY_C + ":0"), partial.get(1));
    }

    @Test
    public void legacyOutputsAreBatchedBy50() throws IOException {
        List<String> outpoints = new java.util.ArrayList<>();
        for(int i = 0; i < 120; i++) {
            outpoints.add(String.format("%064x", i + 1) + ":0");
        }
        List<String> asked = new java.util.ArrayList<>();
        Transport transport = new Transport() {
            @Override
            public String json(String url, String postBody) {
                asked.add(url);
                StringBuilder sb = new StringBuilder("[");
                String[] list = url.substring((LEGACY_BASE + "/outputs/").length()).split(",");
                for(String outpoint : list) {
                    sb.append(sb.length() > 1 ? "," : "").append("{\"inscriptions\":[],\"junes\":[],\"transaction\":\"").append(outpoint.substring(0, 64)).append("\",\"value\":1}");
                }
                return sb.append("]").toString();
            }

            @Override
            public byte[] bytes(String url, String accept, int maxBytes) {
                throw new UnsupportedOperationException();
            }
        };
        List<OrdOutput> outputs = new LegacyOrdDialect(LEGACY_BASE).outputs(transport, outpoints);
        Assertions.assertEquals(3, asked.size());
        Assertions.assertEquals(outpoints, outputs.stream().map(OrdOutput::outpoint).toList());
        Assertions.assertTrue(outputs.stream().allMatch(OrdOutput::indexed));
    }

    @Test
    public void legacyInscriptionGivesSatpoint() throws IOException {
        String id = LEGACY_A + "i0";
        FixtureTransport transport = new FixtureTransport().answer(LEGACY_BASE + "/inscription/" + id + "?json=true", "legacy-inscription.json");
        OrdInscription inscription = new LegacyOrdDialect(LEGACY_BASE).inscription(transport, id);
        Assertions.assertEquals(id, inscription.id());
        Assertions.assertEquals(12345, inscription.number());
        Assertions.assertEquals("text/plain;charset=utf-8", inscription.contentType());
        Assertions.assertEquals(10, inscription.contentLength());
        Assertions.assertEquals(LEGACY_B + ":1:0", inscription.satpoint());
        Assertions.assertEquals(100000, inscription.value());
        //An answer for another inscription, or with a malformed satpoint, is refused
        Assertions.assertThrows(IOException.class, () -> new LegacyOrdDialect(LEGACY_BASE).inscription(transport, LEGACY_B + "i0"));
        String json = fixture("legacy-inscription.json");
        for(String bad : List.of(LEGACY_B + ":1", "../x:1:0", LEGACY_B + ":1:-1", LEGACY_B.toUpperCase() + ":1:0")) {
            Assertions.assertThrows(IOException.class, () -> LegacyOrdDialect.parseInscription(json.replace(LEGACY_B + ":1:0", bad)), bad);
        }
        Assertions.assertEquals("https://jkc.example/inscription/" + id, new LegacyOrdDialect(LEGACY_BASE).explorerUrl(LEGACY_BASE, id));
    }

    @Test
    public void legacyStatusReadsOkAndIndexedBlockCount() throws IOException {
        FixtureTransport transport = new FixtureTransport();
        transport.answers.put(LEGACY_BASE + "/status", "OK".getBytes(StandardCharsets.UTF_8));
        transport.answers.put(LEGACY_BASE + "/block-count", "1119951\n".getBytes(StandardCharsets.UTF_8));
        //block-count is the number of indexed blocks, so the tip is one less
        Assertions.assertEquals(new OrdStatus("unknown", true, 1119950, null), new LegacyOrdDialect(LEGACY_BASE).status(transport));
        transport.answers.put(LEGACY_BASE + "/block-count", "<html>".getBytes(StandardCharsets.UTF_8));
        Assertions.assertThrows(IOException.class, () -> new LegacyOrdDialect(LEGACY_BASE).status(transport));
        transport.answers.put(LEGACY_BASE + "/status", "unrecoverable reorg detected, please rebuild the database.".getBytes(StandardCharsets.UTF_8));
        Assertions.assertThrows(IOException.class, () -> new LegacyOrdDialect(LEGACY_BASE).status(transport));
    }

    /** A server that cannot name its chain is checked by height; Nintondo's and ord's own chain rules stay. */
    @Test
    public void wrongCoinServerRefused() throws IOException {
        OrdStatus far = new OrdStatus("unknown", false, 1_000, null);
        IOException e = Assertions.assertThrows(IOException.class, () -> OrdClient.requireChain(far, Network.MAINNET, 1_120_000));
        Assertions.assertTrue(e.getMessage().contains("1000") && e.getMessage().contains("1120000"), e.getMessage());
        Assertions.assertDoesNotThrow(() -> OrdClient.requireChain(new OrdStatus("unknown", false, 1_119_997, null), Network.MAINNET, 1_120_000));
        Assertions.assertDoesNotThrow(() -> OrdClient.requireChain(new OrdStatus("unknown", false, 1_120_003, null), Network.MAINNET, 1_120_000));
        Assertions.assertThrows(IOException.class, () -> OrdClient.requireChain(new OrdStatus("unknown", false, 1_120_004, null), Network.MAINNET, 1_120_000));
        Assertions.assertDoesNotThrow(() -> OrdClient.requireChain(far, Network.MAINNET, null));
        Assertions.assertDoesNotThrow(() -> OrdClient.requireChain(new OrdStatus("mainnet", true, 5, null), Network.MAINNET, 1_120_000));
        Assertions.assertThrows(IOException.class, () -> OrdClient.requireChain(new OrdStatus("regtest", true, 5, null), Network.MAINNET, 1_120_000));
        Assertions.assertThrows(IOException.class, () -> OrdClient.nintondoStatus("{\"network\":\"Dogecoin\",\"ord_height\":5}", Coin.JUNKCOIN));
    }
}