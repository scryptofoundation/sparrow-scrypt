package com.sparrowwallet.sparrow.net;

import com.sparrowwallet.drongo.Coin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class BroadcastFallbackTest {
    @Test
    public void litecoinFallsBackToMwebWhenRunning() {
        assertTrue(ElectrumServer.isMwebBroadcastFallback(Coin.LITECOIN, true));
    }

    @Test
    public void noFallbackWithoutMwebServer() {
        assertFalse(ElectrumServer.isMwebBroadcastFallback(Coin.LITECOIN, false));
    }

    @Test
    public void otherCoinsNeverFallBackToMweb() {
        for(Coin coin : Coin.values()) {
            if(coin != Coin.LITECOIN) {
                assertFalse(ElectrumServer.isMwebBroadcastFallback(coin, true), coin.name());
            }
        }
    }
}
