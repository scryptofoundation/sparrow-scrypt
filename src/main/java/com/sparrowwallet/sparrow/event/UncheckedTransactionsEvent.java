package com.sparrowwallet.sparrow.event;

import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.wallet.Wallet;

import java.util.Set;

/**
 * Posted where the connected server reported transactions as confirmed and spending from a wallet that could not be checked, because the server did
 * not provide a transaction they depend on (a Taproot spend with another party's input, as in a payjoin or coinjoin). They have been kept in its
 * history, since they may be real. Once per transaction per session, apart from UnsignedTransactionsEvent, which is for spends shown not to be signed.
 * <p>
 * Dispatched on the thread that fetched the history, so a handler must hop to the application thread itself.
 */
public class UncheckedTransactionsEvent {
    private final Wallet wallet;
    private final Set<Sha256Hash> txids;

    public UncheckedTransactionsEvent(Wallet wallet, Set<Sha256Hash> txids) {
        this.wallet = wallet;
        this.txids = txids;
    }

    public Wallet getWallet() {
        return wallet;
    }

    public Set<Sha256Hash> getTxids() {
        return txids;
    }
}
