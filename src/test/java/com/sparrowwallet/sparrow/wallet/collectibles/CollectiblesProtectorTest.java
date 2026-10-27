package com.sparrowwallet.sparrow.wallet.collectibles;

import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.Status;
import com.sparrowwallet.sparrow.net.ord.OrdOutput;
import com.sparrowwallet.sparrow.net.ord.RuneBalance;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Date;
import java.util.List;
import java.util.Map;

public class CollectiblesProtectorTest {
    private static final String ID = "3b6fb513db37f827cea7c89085a41605ed2776e42e71348156bec9739d3b6c64i0";

    private static BlockTransactionHashIndex utxo(Status status) {
        BlockTransactionHashIndex txo = new BlockTransactionHashIndex(Sha256Hash.ZERO_HASH, 100, new Date(), 0L, 0, 10000);
        txo.setStatus(status);
        return txo;
    }

    private static Map<String, CollectibleState> carries(BlockTransactionHashIndex txo) {
        return Map.of(txo.toString(), CollectibleState.CARRIES);
    }

    @Test
    public void carriesBecomesProtected() {
        BlockTransactionHashIndex txo = utxo(null);
        Assertions.assertEquals(List.of(txo), CollectiblesProtector.apply(List.of(txo), carries(txo)));
        Assertions.assertEquals(Status.PROTECTED, txo.getStatus());
    }

    @Test
    public void frozenCarriesBecomesProtected() {
        BlockTransactionHashIndex txo = utxo(Status.FROZEN);
        CollectiblesProtector.apply(List.of(txo), carries(txo));
        Assertions.assertEquals(Status.PROTECTED, txo.getStatus());
    }

    @Test
    public void releasedStaysReleased() {
        BlockTransactionHashIndex txo = utxo(Status.RELEASED);
        Assertions.assertTrue(CollectiblesProtector.apply(List.of(txo), carries(txo)).isEmpty());
        Assertions.assertEquals(Status.RELEASED, txo.getStatus());
    }

    @Test
    public void cleanAndUnknownAreLeftAlone() {
        BlockTransactionHashIndex txo = utxo(null);
        Assertions.assertTrue(CollectiblesProtector.apply(List.of(txo), Map.of(txo.toString(), CollectibleState.UNKNOWN)).isEmpty());
        Assertions.assertNull(txo.getStatus());
    }

    @Test
    public void secondApplyChangesNothing() {
        BlockTransactionHashIndex txo = utxo(null);
        CollectiblesProtector.apply(List.of(txo), carries(txo));
        Assertions.assertTrue(CollectiblesProtector.apply(List.of(txo), carries(txo)).isEmpty());
    }

    @Test
    public void labelPrefixForOneInscription() {
        OrdOutput output = new OrdOutput("x:0", true, List.of(ID), Map.of(), 10000, false, false);
        Assertions.assertEquals("Inscription 3b6fb513…i0", CollectiblesProtector.labelPrefix(output));
        Assertions.assertEquals("Inscription 3b6fb513…i0 my note", CollectiblesProtector.newLabel("my note", output));
        Assertions.assertEquals("Inscription 3b6fb513…i0", CollectiblesProtector.newLabel(null, output));
    }

    @Test
    public void labelPrefixForRunes() {
        OrdOutput output = new OrdOutput("x:0", true, List.of(), Map.of("TESTRUNE•ONE", new RuneBalance(BigInteger.TEN, 2, "T")), 546, false, false);
        Assertions.assertEquals("Runes TESTRUNE•ONE", CollectiblesProtector.labelPrefix(output));
        OrdOutput both = new OrdOutput("x:0", true, List.of(ID, ID.replace("i0", "i1")), Map.of("A", new RuneBalance(BigInteger.ONE, 0, "a"), "B", new RuneBalance(BigInteger.ONE, 0, "b")), 546, false, false);
        Assertions.assertTrue(CollectiblesProtector.labelPrefix(both).startsWith("2 inscriptions, Runes "));
        Assertions.assertTrue(CollectiblesProtector.labelPrefix(both).endsWith(" +1"));
    }

    @Test
    public void labelIsCut() {
        OrdOutput output = new OrdOutput("x:0", true, List.of(ID), Map.of(), 10000, false, false);
        Assertions.assertEquals(255, CollectiblesProtector.newLabel("a".repeat(300), output).length());
    }
}
