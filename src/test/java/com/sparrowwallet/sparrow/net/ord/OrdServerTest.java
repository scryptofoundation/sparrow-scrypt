package com.sparrowwallet.sparrow.net.ord;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.Network;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class OrdServerTest {
    @Test
    public void offResolvesToNull() {
        Assertions.assertNull(OrdServer.resolveUrl(InscriptionServerMode.OFF, "http://127.0.0.1:8085", Network.MAINNET));
    }

    @Test
    public void publicOnMainnetIsOrdinalsLite() {
        Assertions.assertEquals("https://ordinalslite.com", OrdServer.resolveUrl(InscriptionServerMode.PUBLIC, null, Network.MAINNET));
    }

    @Test
    public void publicOnRegtestIsNull() {
        Assertions.assertNull(OrdServer.resolveUrl(InscriptionServerMode.PUBLIC, null, Network.REGTEST));
    }

    @Test
    public void customTrimsTrailingSlash() {
        Assertions.assertEquals("http://127.0.0.1:8085", OrdServer.resolveUrl(InscriptionServerMode.CUSTOM, "http://127.0.0.1:8085/", Network.REGTEST));
    }

    /** Choosing Custom without a usable URL stores no URL, so checks stop rather than staying on the previous server. */
    @Test
    public void customWithoutUsableUrlSavesBlank() {
        Assertions.assertEquals("", OrdServer.customUrlToSave("", Network.MAINNET));
        Assertions.assertEquals("", OrdServer.customUrlToSave("ftp://x", Network.MAINNET));
        Assertions.assertEquals("http://127.0.0.1:8085", OrdServer.customUrlToSave(" http://127.0.0.1:8085/ ", Network.MAINNET));
        //A blank stored URL does not resolve; Config.getInscriptionServerUrl turns that into no server, so nothing is asked
        Assertions.assertThrows(IllegalArgumentException.class, () -> OrdServer.resolveUrl(InscriptionServerMode.CUSTOM, OrdServer.customUrlToSave("", Network.MAINNET), Network.MAINNET));
    }

    /** Bellscoin, Dogecoin and Pepecoin use Nintondo's indexer, which follows inscriptions as they move. */
    @Test
    public void publicServerPerCoin() {
        Assertions.assertEquals("https://ordinalslite.com", OrdServer.publicUrl(Coin.LITECOIN, Network.MAINNET));
        Assertions.assertEquals("https://bells-mainnet-api.nintondo.io", OrdServer.publicUrl(Coin.BELLSCOIN, Network.MAINNET));
        Assertions.assertEquals("https://bells-testnet-api.nintondo.io", OrdServer.publicUrl(Coin.BELLSCOIN, Network.TESTNET));
        Assertions.assertEquals("https://doge-mainnet-api.nintondo.io", OrdServer.publicUrl(Coin.DOGECOIN, Network.MAINNET));
        Assertions.assertEquals("https://pepe-mainnet-api.nintondo.io", OrdServer.publicUrl(Coin.PEPECOIN, Network.MAINNET));
        Assertions.assertEquals("https://pepe-testnet-api.nintondo.io", OrdServer.publicUrl(Coin.PEPECOIN, Network.TESTNET));
        Assertions.assertNull(OrdServer.publicUrl(Coin.LITECOIN, Network.TESTNET));
        Assertions.assertNull(OrdServer.publicUrl(Coin.JUNKCOIN, Network.MAINNET));
        Assertions.assertNull(OrdServer.publicUrl(Coin.LUCKYCOIN, Network.MAINNET));
        Assertions.assertNull(OrdServer.publicUrl(Coin.DINGOCOIN, Network.MAINNET));
        Assertions.assertEquals("https://doge-mainnet-api.nintondo.io", OrdServer.resolveUrl(InscriptionServerMode.PUBLIC, null, Coin.DOGECOIN, Network.MAINNET));
        Assertions.assertTrue(OrdServer.isNintondo("https://pepe-mainnet-api.nintondo.io"));
        Assertions.assertFalse(OrdServer.isNintondo("https://ordinalslite.com"));
    }

    @Test
    public void customRejectsOtherSchemes() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> OrdServer.resolveUrl(InscriptionServerMode.CUSTOM, "ftp://x", Network.MAINNET));
        Assertions.assertThrows(IllegalArgumentException.class, () -> OrdServer.resolveUrl(InscriptionServerMode.CUSTOM, "", Network.MAINNET));
    }
}
