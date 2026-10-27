package com.sparrowwallet.sparrow.net.ord;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

/** Read-only queries against the public server; no wallet is involved. Run with ORD_LIVE=1. */
@EnabledIfEnvironmentVariable(named = "ORD_LIVE", matches = "1")
public class OrdClientLiveTest {
    private static final String OUTPOINT = "3b6fb513db37f827cea7c89085a41605ed2776e42e71348156bec9739d3b6c64:0";
    private static final String INSCRIPTION = "3b6fb513db37f827cea7c89085a41605ed2776e42e71348156bec9739d3b6c64i0";

    @Test
    public void publicServerAnswers() throws Exception {
        OrdClient client = new OrdClient(OrdServer.PUBLIC_MAINNET_URL);
        Assertions.assertEquals("mainnet", client.status().chain());
        List<OrdOutput> outputs = client.outputs(List.of(OUTPOINT));
        Assertions.assertEquals(1, outputs.size());
        Assertions.assertTrue(outputs.getFirst().inscriptions().contains(INSCRIPTION));
        Assertions.assertEquals(63, client.content(INSCRIPTION).length);
    }
}
