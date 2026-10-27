package com.sparrowwallet.sparrow.control;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

public class LitVmTokenDialogsTest {
    @Test
    public void amountsAreWholeTokensWithEighteenDecimals() {
        Assertions.assertEquals(BigInteger.TEN.pow(18), LitVmCreateTokenDialog.units("1"));
        Assertions.assertEquals(new BigInteger("1500000000000000000000000"), LitVmCreateTokenDialog.units("1,500,000"));
        Assertions.assertEquals(BigInteger.ONE, LitVmCreateTokenDialog.units("0.000000000000000001"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> LitVmCreateTokenDialog.units("0.0000000000000000001"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> LitVmCreateTokenDialog.units("1e999999999"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> LitVmCreateTokenDialog.units("-1"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> LitVmCreateTokenDialog.units(""));
    }

    @Test
    public void templateKeysAreDescribedInWords() {
        Assertions.assertEquals("fixed supply", LitVmManageTokenDialog.describe("fixed"));
        Assertions.assertEquals("mintable up to a cap, burnable, pausable, permit, votes, flash minting, ERC-1363", LitVmManageTokenDialog.describe("capped_bpevfc"));
    }
}
