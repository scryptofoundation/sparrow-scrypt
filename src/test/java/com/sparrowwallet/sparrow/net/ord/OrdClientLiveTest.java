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

    /** Nintondo follows inscriptions as they move: each moved one is found where it sits now, far from its reveal. */
    @Test
    public void nintondoFindsMovedInscriptions() throws Exception {
        record Case(com.sparrowwallet.drongo.Coin coin, com.sparrowwallet.drongo.Network network, String carrying, String genesis, String clean) {}
        List<Case> cases = List.of(
                new Case(com.sparrowwallet.drongo.Coin.BELLSCOIN, com.sparrowwallet.drongo.Network.MAINNET,
                        "4480949bfa1583ab6c6b0155e9a4c8c939a2dd81b907cb8b7a46b602ddbce8fa:1", "de3d407620a94a79dde0946937a211d9a7624a911b308f4d455237696feb3f75i0",
                        "d26807f28e2ae6a31f18832df1030c262ed45c0713cda33d15106d5fd869d780:1"),
                new Case(com.sparrowwallet.drongo.Coin.DOGECOIN, com.sparrowwallet.drongo.Network.MAINNET,
                        "63c84fba5b204542ce94045cf77409edbb3eac1200590ecec5b063041e6afbc7:0", "62ce343f4cd591055189b6a999d21759d16f918bc3f2d017c8566fce7a548267i0",
                        "cf12d5e9104afdb53e1455d8a9813de34f1c6c5deaac72cbc505c88389660e03:0"),
                new Case(com.sparrowwallet.drongo.Coin.PEPECOIN, com.sparrowwallet.drongo.Network.MAINNET,
                        "3810d5e9628537473d82c32e2e3d1e5f4a232b449fadf37e1dea188a677c769a:0", "b47571537ac6d62be3ebe2d2e686d58460c5a47dd032cb98bb95ad3cbe3afdc5i0",
                        "214533ae61d6fff22f23fe069410756d279698e170f49e0e3b5cacf568dbb906:1"),
                new Case(com.sparrowwallet.drongo.Coin.PEPECOIN, com.sparrowwallet.drongo.Network.TESTNET,
                        "9412bd71c12b967140cbbfb40713bb9995fb28285f7d63aa99c1f6d438bfb890:0", "f44009765d6a7f4997a7dea7978c9417449deb5fa5262e174190326fcd583c0fi0", null));
        for(Case c : cases) {
            com.sparrowwallet.drongo.Coin previous = com.sparrowwallet.drongo.Coin.beginScope(c.coin());
            try {
                OrdClient client = new OrdClient(OrdServer.publicUrl(c.coin(), c.network()));
                OrdClient.requireChain(client.status(), c.network());
                List<OrdOutput> outputs = client.outputs(c.clean() == null ? List.of(c.carrying()) : List.of(c.carrying(), c.clean()));
                Assertions.assertEquals(List.of(c.genesis()), outputs.get(0).inscriptions(), c.coin() + " " + c.network());
                if(c.clean() != null) {
                    Assertions.assertTrue(outputs.get(1).indexed() && !outputs.get(1).carries(), c.coin() + " clean");
                }
            } finally {
                com.sparrowwallet.drongo.Coin.endScope(previous);
            }
        }
    }

    /**
     * Each coin's fixture inscription, read live: the satpoint's output must list the same id in find_meta, and the
     * content served must be exactly the size the info reports. The inscription may have moved since the fixture was
     * recorded, so the output is derived from the live location, not the fixture.
     */
    @Test
    public void nintondoInscriptionDetails() throws Exception {
        record Case(com.sparrowwallet.drongo.Coin coin, String id) {}
        List<Case> cases = List.of(
                new Case(com.sparrowwallet.drongo.Coin.DOGECOIN, "b5f9d2759c65df72dff76bbab840ff3d34d99c6679bad26148cb18344ff03f71i0"),
                new Case(com.sparrowwallet.drongo.Coin.PEPECOIN, "d089eab0d91c5889eb2789804498ab51229048451eb9d9f9510ac2b84416c546i0"),
                new Case(com.sparrowwallet.drongo.Coin.BELLSCOIN, "554a01b993871859e622b2c6391da63e74393b8cab2958ca09eebf4688af7773i0"));
        for(Case c : cases) {
            com.sparrowwallet.drongo.Coin previous = com.sparrowwallet.drongo.Coin.beginScope(c.coin());
            try {
                OrdClient client = new OrdClient(OrdServer.publicUrl(c.coin(), com.sparrowwallet.drongo.Network.MAINNET));
                OrdInscription inscription = client.inscription(c.id());
                String label = c.coin().toString();
                System.out.println(label + " number=" + inscription.number() + " type=" + inscription.contentType()
                        + " size=" + inscription.contentLength() + " satpoint=" + inscription.satpoint() + " value=" + inscription.value());
                Assertions.assertEquals(c.id(), inscription.id(), label);
                String[] satpoint = inscription.satpoint().split(":");
                Assertions.assertEquals(3, satpoint.length, label + " satpoint " + inscription.satpoint());
                String outpoint = satpoint[0] + ":" + satpoint[1];
                OrdOutput output = client.output(outpoint);
                Assertions.assertTrue(output.indexed(), label + " output indexed");
                Assertions.assertTrue(output.inscriptions().contains(c.id()), label + " find_meta lists " + c.id() + " on " + outpoint + ": " + output.inscriptions());
                Assertions.assertTrue(inscription.contentLength() >= 0, label + " size known");
                byte[] content = client.content(c.id());
                Assertions.assertEquals(inscription.contentLength(), content.length, label + " served bytes vs info file_size");
            } finally {
                com.sparrowwallet.drongo.Coin.endScope(previous);
            }
        }
    }
}