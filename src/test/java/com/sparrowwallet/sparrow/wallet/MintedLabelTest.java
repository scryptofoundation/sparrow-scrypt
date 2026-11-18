package com.sparrowwallet.sparrow.wallet;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;

public class MintedLabelTest {
    @Test
    public void mintedLabel() {
        long time = LocalDate.of(2026, 11, 5).atTime(15, 30).toEpochSecond(ZoneOffset.UTC);
        Assertions.assertEquals("Minted in block 512,340 (2026-11-05)", CollectiblesController.mintedLabel(512_340, time));
        Assertions.assertEquals("Minted in block 7 (1970-01-01)", CollectiblesController.mintedLabel(7, 0));
    }
}
