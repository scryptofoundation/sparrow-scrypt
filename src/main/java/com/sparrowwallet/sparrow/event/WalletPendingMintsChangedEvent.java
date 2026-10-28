package com.sparrowwallet.sparrow.event;

import com.sparrowwallet.drongo.wallet.Wallet;

/**
 * A master wallet's pending mints were added or removed. Persisted by DbPersistence; the Collectibles view lists them.
 */
public class WalletPendingMintsChangedEvent extends WalletChangedEvent {
    public WalletPendingMintsChangedEvent(Wallet wallet) {
        super(wallet);
    }
}
