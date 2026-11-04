package com.sparrowwallet.sparrow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The app is only "online" once a connection has succeeded. A Litecoin wallet whose public server was down kept the
 * app from ever getting there, and when the Pepecoin wallet's tab came up the coin switch saw "offline", cancelled the
 * Litecoin attempt and connected nothing for Pepecoin, although its own server was up.
 */
public class CoinChangeReconnectTest {
    @Test
    public void stillConnectingAtStartupReconnectsForTheNewCoin() {
        assertTrue(AppServices.reconnectsOnCoinChange(false, true, Mode.ONLINE, true));
    }

    @Test
    public void retryingAfterAFailedConnectReconnectsForTheNewCoin() {
        //Between retries no task runs, but the app still means to be online
        assertTrue(AppServices.reconnectsOnCoinChange(false, false, Mode.ONLINE, true));
    }

    @Test
    public void connectedReconnectsForTheNewCoin() {
        assertTrue(AppServices.reconnectsOnCoinChange(true, false, Mode.ONLINE, true));
    }

    @Test
    public void offlineByChoiceStaysOffline() {
        assertFalse(AppServices.reconnectsOnCoinChange(false, false, Mode.OFFLINE, true));
    }

    @Test
    public void noServerConfiguredStaysOffline() {
        assertFalse(AppServices.reconnectsOnCoinChange(false, true, Mode.ONLINE, false));
    }
}
