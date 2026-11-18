package com.sparrowwallet.sparrow.io;

import com.google.gson.Gson;
import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.sparrow.net.ord.InscriptionServerMode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class InscriptionModeConfigTest {
    @Test
    public void storedOffReadsAsBuiltIn() {
        Assertions.assertEquals(InscriptionServerMode.BUILT_IN, new Gson().fromJson("\"OFF\"", InscriptionServerMode.class));
        Assertions.assertEquals(InscriptionServerMode.BUILT_IN, new Gson().fromJson("\"BUILT_IN\"", InscriptionServerMode.class));
        Assertions.assertEquals("\"BUILT_IN\"", new Gson().toJson(InscriptionServerMode.BUILT_IN));
    }

    @Test
    public void defaultIsPublicWhereOneExists() {
        Assertions.assertEquals(InscriptionServerMode.PUBLIC, InscriptionServerMode.defaultFor(Coin.LITECOIN, Network.MAINNET));
        Assertions.assertEquals(InscriptionServerMode.PUBLIC, InscriptionServerMode.defaultFor(Coin.DOGECOIN, Network.MAINNET));
        Assertions.assertEquals(InscriptionServerMode.BUILT_IN, InscriptionServerMode.defaultFor(Coin.JUNKCOIN, Network.MAINNET));
        Assertions.assertEquals(InscriptionServerMode.BUILT_IN, InscriptionServerMode.defaultFor(Coin.LITECOIN, Network.REGTEST));
    }

    @Test
    public void orderAndLabels() {
        Assertions.assertArrayEquals(new InscriptionServerMode[] {InscriptionServerMode.PUBLIC, InscriptionServerMode.CUSTOM, InscriptionServerMode.BUILT_IN},
                InscriptionServerMode.values());
        Assertions.assertEquals("Public", InscriptionServerMode.PUBLIC.getLabel());
        Assertions.assertEquals("Your own", InscriptionServerMode.CUSTOM.getLabel());
        Assertions.assertEquals("Built-in", InscriptionServerMode.BUILT_IN.getLabel());
    }
}
