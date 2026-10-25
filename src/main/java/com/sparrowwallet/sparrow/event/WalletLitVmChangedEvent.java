package com.sparrowwallet.sparrow.event;

import com.sparrowwallet.drongo.wallet.Wallet;

/**
 * A master wallet's LitVM link was added, removed or re-encrypted. Persisted by DbPersistence; the wallet tab
 * adds or removes its LitVM sub-tab.
 */
public class WalletLitVmChangedEvent extends WalletChangedEvent {
    public WalletLitVmChangedEvent(Wallet wallet) {
        super(wallet);
    }
}
