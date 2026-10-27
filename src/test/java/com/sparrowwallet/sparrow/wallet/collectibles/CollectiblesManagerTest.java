package com.sparrowwallet.sparrow.wallet.collectibles;

import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.Status;
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

    @Test
    public void noSnapshotMeansEverythingUnknown() {
        BlockTransactionHashIndex txo = utxo(null);
        Assertions.assertEquals(List.of(txo), CollectiblesManager.unknownIn(null, List.of(txo)));
    }
}
