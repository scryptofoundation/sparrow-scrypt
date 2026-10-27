package com.sparrowwallet.sparrow.wallet;

import com.sparrowwallet.drongo.wallet.Status;

/**
 * Which status changes a user action may make. Freezing and unfreezing only ever move between no status and
 * FROZEN, so a PROTECTED output (one carrying an inscription or runes) can never be exposed by an unfreeze.
 */
public final class StatusTransitions {
    private StatusTransitions() {
    }

    public static boolean canFreeze(Status status) {
        return status == null || status == Status.RELEASED;
    }

    public static boolean canUnfreeze(Status status) {
        return status == Status.FROZEN;
    }

    /** The status after importing a BIP329 "spendable" flag; PROTECTED is never changed by an import. */
    public static Status fromBip329(Status current, boolean spendable) {
        if(!spendable && canFreeze(current)) {
            return Status.FROZEN;
        }
        if(spendable && canUnfreeze(current)) {
            return null;
        }
        return current;
    }
}
