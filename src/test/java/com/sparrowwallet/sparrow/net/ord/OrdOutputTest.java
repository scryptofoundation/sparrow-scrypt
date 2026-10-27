package com.sparrowwallet.sparrow.net.ord;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

public class OrdOutputTest {
    private static final String OUTPOINT = "00".repeat(32) + ":0";

    /** A server without a rune index answers "no runes" for every output, so an empty answer proves nothing. */
    @Test
    public void emptyAnswerFromServerWithoutRuneIndexIsUnknown() {
        OrdOutput empty = new OrdOutput(OUTPOINT, true, List.of(), Map.of(), 546, false, false);
        Assertions.assertTrue(empty.forServer(true).indexed());
        Assertions.assertFalse(empty.forServer(false).indexed());
    }

    @Test
    public void carryingAnswerStaysIndexedWithoutRuneIndex() {
        OrdOutput inscribed = new OrdOutput(OUTPOINT, true, List.of("00".repeat(32) + "i0"), Map.of(), 546, false, false);
        Assertions.assertTrue(inscribed.forServer(false).indexed());
        Assertions.assertTrue(inscribed.forServer(false).carries());
        OrdOutput runes = new OrdOutput(OUTPOINT, true, List.of(), Map.of("A", new RuneBalance(BigInteger.ONE, 0, "a")), 546, false, false);
        Assertions.assertTrue(runes.forServer(false).indexed());
    }
}
