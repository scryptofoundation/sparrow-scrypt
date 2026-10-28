package com.sparrowwallet.sparrow.net.ord;

import com.sparrowwallet.drongo.ordinals.RuneId;
import com.sparrowwallet.drongo.ordinals.Runestone;

import java.math.BigInteger;

/**
 * A rune as the ord server describes it: its id (needed in a runestone edict or mint), name, divisibility and symbol,
 * and its open mint terms (null when it has none), how many times it was minted, and whether it can be minted now.
 */
public record OrdRune(RuneId id, String spacedName, int divisibility, String symbol, boolean mintable, Runestone.Terms terms, BigInteger mints) {
    public BigInteger remainingMints() {
        if(terms == null || terms.cap() == null) {
            return BigInteger.ZERO;
        }
        return terms.cap().subtract(mints == null ? BigInteger.ZERO : mints).max(BigInteger.ZERO);
    }
}