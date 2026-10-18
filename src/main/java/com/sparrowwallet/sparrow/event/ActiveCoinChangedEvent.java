package com.sparrowwallet.sparrow.event;

import com.sparrowwallet.drongo.Coin;

/**
 * Posted when focusing a wallet tab changes the active coin (one app, per-wallet coin).
 * The server connection follows the active coin, so subscribers reconnect and refresh coin-derived UI.
 */
public class ActiveCoinChangedEvent {
    private final Coin coin;

    public ActiveCoinChangedEvent(Coin coin) {
        this.coin = coin;
    }

    public Coin getCoin() {
        return coin;
    }
}
