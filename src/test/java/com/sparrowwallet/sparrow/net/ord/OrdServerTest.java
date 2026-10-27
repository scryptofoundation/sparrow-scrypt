package com.sparrowwallet.sparrow.net.ord;

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

    @Test
    public void customRejectsOtherSchemes() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> OrdServer.resolveUrl(InscriptionServerMode.CUSTOM, "ftp://x", Network.MAINNET));
        Assertions.assertThrows(IllegalArgumentException.class, () -> OrdServer.resolveUrl(InscriptionServerMode.CUSTOM, "", Network.MAINNET));
    }
}
