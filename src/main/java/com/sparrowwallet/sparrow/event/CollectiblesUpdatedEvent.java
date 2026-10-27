package com.sparrowwallet.sparrow.event;

import com.sparrowwallet.drongo.wallet.Wallet;

/** A wallet's outputs were checked for inscriptions and runes (or checks were turned off). */
public class CollectiblesUpdatedEvent extends WalletChangedEvent {
    public CollectiblesUpdatedEvent(Wallet wallet) {
        super(wallet);
    }
}
