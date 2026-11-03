package com.sparrowwallet.sparrow.wallet.collectibles;

import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.Status;
import com.sparrowwallet.sparrow.net.ord.OrdClient;
import com.sparrowwallet.sparrow.net.ord.OrdOutput;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

public class CollectiblesManagerTest {
    private static final String ID = "3b6fb513db37f827cea7c89085a41605ed2776e42e71348156bec9739d3b6c64i0";

    private static BlockTransactionHashIndex utxo(Status status) {
        BlockTransactionHashIndex txo = new BlockTransactionHashIndex(Sha256Hash.ZERO_HASH, 100, new Date(), 0L, 0, 10000);
        txo.setStatus(status);
        return txo;
    }

    /** A history sync can replace a node's output objects while the server is asked; protection lands on the live one. */
    @Test
    public void protectionLandsOnLiveOutputs() {
        BlockTransactionHashIndex snapshotCopy = utxo(null);
        BlockTransactionHashIndex live = utxo(null);
        live.setLabel("mine");
        Map<String, CollectibleState> states = Map.of(live.toString(), CollectibleState.CARRIES);
        Map<String, OrdOutput> answers = Map.of(live.toString(), new OrdOutput(live.toString(), true, List.of(ID), Map.of(), 10000, false, false));

        List<BlockTransactionHashIndex> changed = CollectiblesManager.protect(List.of(live), states, answers);

        Assertions.assertEquals(List.of(live), changed);
        Assertions.assertEquals(Status.PROTECTED, live.getStatus());
        Assertions.assertEquals("Inscription 3b6fb513…i0 mine", live.getLabel());
        Assertions.assertNull(snapshotCopy.getStatus());
    }

    /** Until an output known to carry something is actually marked, a send treats it as unchecked. */
    @Test
    public void carryingButUnmarkedOutputCountsAsUnknown() {
        BlockTransactionHashIndex unmarked = utxo(null);
        BlockTransactionHashIndex released = utxo(Status.RELEASED);
        CollectiblesManager.Snapshot snapshot = new CollectiblesManager.Snapshot(Map.of(unmarked.toString(), CollectibleState.CARRIES), Map.of(), Instant.now(),
                "http://127.0.0.1:8085", null, true, true);
        Assertions.assertEquals(List.of(unmarked), CollectiblesManager.unknownIn(snapshot, List.of(unmarked)));
        Assertions.assertTrue(CollectiblesManager.unknownIn(snapshot, List.of(released)).isEmpty());
    }

    /** Nintondo indexes inscriptions only: its "clean" for an output of a dunestone or runestone transaction proves nothing. */
    @Test
    public void inscriptionOnlyIndexerCannotClearTokenOutputs() {
        com.sparrowwallet.drongo.protocol.Transaction dunes = new com.sparrowwallet.drongo.protocol.Transaction();
        dunes.addInput(Sha256Hash.ZERO_HASH, 0, new com.sparrowwallet.drongo.protocol.Script(new byte[0]));
        dunes.addOutput(100000, new com.sparrowwallet.drongo.protocol.Script(com.sparrowwallet.drongo.Utils.hexToBytes("76a914" + "00".repeat(20) + "88ac")));
        dunes.addOutput(0, new com.sparrowwallet.drongo.protocol.Script(com.sparrowwallet.drongo.Utils.hexToBytes("6a014400")));
        String outpoint = dunes.getTxId() + ":0";
        OrdOutput clean = new OrdOutput(outpoint, true, List.of(), Map.of(), 0, false, false);

        Assertions.assertFalse(CollectiblesManager.vouchedFor(clean, dunes, false).indexed());
        Assertions.assertTrue(CollectiblesManager.vouchedFor(clean, dunes, true).indexed(), "ord with a rune index vouches for it");
        Assertions.assertTrue(CollectiblesManager.vouchedFor(clean, null, false).indexed());
        OrdOutput carrying = new OrdOutput(outpoint, true, List.of(ID), Map.of(), 0, false, false);
        Assertions.assertSame(carrying, CollectiblesManager.vouchedFor(carrying, dunes, false));
    }

    /** ord 0.5 servers are not trusted for tokens either: their clean answer for an output of a dunestone transaction stays unknown. */
    @Test
    public void legacyServerCannotClearTokenOutputs() {
        Assertions.assertFalse(CollectiblesManager.tokensIndexed(OrdClient.LEGACY_DIALECT, false));
        Assertions.assertFalse(CollectiblesManager.tokensIndexed("Nintondo", true));
        Assertions.assertTrue(CollectiblesManager.tokensIndexed("ord 0.20", false));

        com.sparrowwallet.drongo.protocol.Transaction dunes = new com.sparrowwallet.drongo.protocol.Transaction();
        dunes.addInput(Sha256Hash.ZERO_HASH, 0, new com.sparrowwallet.drongo.protocol.Script(new byte[0]));
        dunes.addOutput(100000, new com.sparrowwallet.drongo.protocol.Script(com.sparrowwallet.drongo.Utils.hexToBytes("76a914" + "00".repeat(20) + "88ac")));
        dunes.addOutput(0, new com.sparrowwallet.drongo.protocol.Script(com.sparrowwallet.drongo.Utils.hexToBytes("6a014400")));
        OrdOutput clean = cleanAnswer(dunes.getTxId() + ":0");
        OrdOutput answer = CollectiblesManager.answered(clean, true, dunes, CollectiblesManager.tokensIndexed(OrdClient.LEGACY_DIALECT, false), true, 100, 200);
        Assertions.assertFalse(answer.indexed());
    }

    @Test
    public void noSnapshotMeansEverythingUnknown() {
        BlockTransactionHashIndex txo = utxo(null);
        Assertions.assertEquals(List.of(txo), CollectiblesManager.unknownIn(null, List.of(txo)));
    }

    private static OrdOutput cleanAnswer(String outpoint) {
        return new OrdOutput(outpoint, true, List.of(), Map.of(), 0, false, false);
    }

    @Test
    public void newerThanIndexIsUnknown() {
        OrdOutput clean = cleanAnswer("aa:0");
        Assertions.assertFalse(CollectiblesManager.indexedBy(clean, 1_301_200, 1_301_188).indexed());
        Assertions.assertSame(clean, CollectiblesManager.indexedBy(clean, 1_301_100, 1_301_188));
        Assertions.assertSame(clean, CollectiblesManager.indexedBy(clean, 1_301_188, 1_301_188));
    }

    @Test
    public void unconfirmedCleanIsUnknown() {
        Assertions.assertFalse(CollectiblesManager.indexedBy(cleanAnswer("aa:0"), 0, 1_301_188).indexed());
    }

    @Test
    public void carrierStaysCarrier() {
        OrdOutput carrying = new OrdOutput("aa:0", true, List.of(ID), Map.of(), 0, false, false);
        Assertions.assertSame(carrying, CollectiblesManager.indexedBy(carrying, 1_301_200, 1_301_188));
        Assertions.assertSame(carrying, CollectiblesManager.indexedBy(carrying, 0, 1_301_188));
    }

    /** ord-junkcoin and Nintondo answer "clean" for what they have not indexed yet: the height rule applies to them, and the answer is not cached. */
    @Test
    public void lagRuleAppliesToNintondoAndLegacyOnly() {
        OrdOutput clean = cleanAnswer("aa:0");
        for(String dialect : new String[] {OrdClient.LEGACY_DIALECT, "Nintondo"}) {
            OrdOutput answer = CollectiblesManager.answered(clean, true, null, true, lagged(dialect), 1_301_200, 1_301_188);
            Assertions.assertFalse(answer.indexed(), dialect);
            Assertions.assertFalse(CollectiblesManager.cacheable(answer, 1_301_200), dialect);
        }
        OrdOutput old = CollectiblesManager.answered(clean, true, null, true, true, 1_301_100, 1_301_188);
        Assertions.assertTrue(old.indexed());
        Assertions.assertTrue(CollectiblesManager.cacheable(old, 1_301_100));
        //ord 0.20 reports a real indexed flag, so the height rule is not applied to it
        OrdOutput ord = CollectiblesManager.answered(clean, true, null, true, false, 1_301_200, 1_301_188);
        Assertions.assertTrue(ord.indexed());
    }

    /** Spec section 4: when the indexer is behind the coin's tip, the tab says by how much and why newer coins are held. */
    @Test
    public void lagNoticeSaysHowFarBehind() {
        Assertions.assertEquals("The inscriptions server is 12 blocks behind; newer coins stay held until it catches up.", snapshot(1_301_188, 1_301_200).lagNotice());
        Assertions.assertEquals("The inscriptions server is 1 block behind; newer coins stay held until it catches up.", snapshot(1_301_199, 1_301_200).lagNotice());
        Assertions.assertNull(snapshot(1_301_200, 1_301_200).lagNotice());
        Assertions.assertNull(snapshot(1_301_201, 1_301_200).lagNotice(), "an indexer ahead of the app's tip is not behind");
        Assertions.assertNull(snapshot(1_301_188, null).lagNotice(), "no tip for the coin, nothing to compare");
        Assertions.assertNull(snapshot(-1, 1_301_200).lagNotice(), "ord 0.20 reports its own indexed flag");
    }

    private static CollectiblesManager.Snapshot snapshot(long indexedHeight, Integer tip) {
        return new CollectiblesManager.Snapshot(Map.of(), Map.of(), Instant.now(), "https://doge-mainnet-api.nintondo.io", null, true, true, null, indexedHeight, tip);
    }

    private static boolean lagged(String dialect) {
        return CollectiblesManager.laggingDialect(dialect, dialect.equals("Nintondo"));
    }}
