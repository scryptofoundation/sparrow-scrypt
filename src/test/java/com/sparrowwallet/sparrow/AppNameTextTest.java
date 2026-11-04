package com.sparrowwallet.sparrow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The welcome and About screens come from Sparrow-LTC and said "Sparrow-LTC is a Litecoin wallet" in every build,
 * the multi-coin app included.
 */
public class AppNameTextTest {
    @Test
    public void multiCoinAppNamesItselfAndItsCoins() {
        String text = AppNameText.apply("Sparrow-LTC is a Litecoin wallet with a focus on security and usability.", "Sparrow-Scrypt", true);
        assertEquals("Sparrow-Scrypt is a wallet for Litecoin, Dogecoin, Pepecoin, Junkcoin, Luckycoin, Bellscoin and Dingocoin with a focus on security and usability.", text);
    }

    @Test
    public void multiCoinAppDropsLitecoinOnlyWording() {
        assertEquals("the easiest way to connect Sparrow-Scrypt to each coin's blockchain is",
                AppNameText.apply("the easiest way to connect Sparrow-LTC to the Litecoin blockchain is", "Sparrow-Scrypt", true));
        assertEquals("Because these servers index all transactions equally",
                AppNameText.apply("Because these servers index all Litecoin transactions equally", "Sparrow-Scrypt", true));
    }

    @Test
    public void singleCoinFlavorOnlyChangesTheName() {
        assertEquals("Sparrow-DOGE can operate in both an online and offline mode.",
                AppNameText.apply("Sparrow-LTC can operate in both an online and offline mode.", "Sparrow-DOGE", false));
    }

    @Test
    public void litecoinFlavorIsUnchanged() {
        String text = "Sparrow-LTC is a Litecoin wallet with a focus on security and usability.";
        assertEquals(text, AppNameText.apply(text, "Sparrow-LTC", false));
    }

    @Test
    public void nullStaysNull() {
        assertNull(AppNameText.apply(null, "Sparrow-Scrypt", true));
    }
}
