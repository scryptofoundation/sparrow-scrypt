package com.sparrowwallet.sparrow.net.ord;

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
