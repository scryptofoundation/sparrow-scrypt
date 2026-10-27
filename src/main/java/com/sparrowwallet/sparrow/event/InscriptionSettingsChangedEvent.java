package com.sparrowwallet.sparrow.event;

import com.sparrowwallet.drongo.Coin;

/** The inscriptions server setting for a coin changed, so its wallets are checked again. */
public class InscriptionSettingsChangedEvent {
    private final Coin coin;

    public InscriptionSettingsChangedEvent(Coin coin) {
        this.coin = coin;
    }

    public Coin getCoin() {
        return coin;
    }
}
