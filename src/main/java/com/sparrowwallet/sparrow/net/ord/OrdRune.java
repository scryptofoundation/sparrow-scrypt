package com.sparrowwallet.sparrow.net.ord;

import com.sparrowwallet.drongo.ordinals.RuneId;

/** A rune as the ord server describes it: its id (needed in a runestone edict), name, divisibility and symbol. */
public record OrdRune(RuneId id, String spacedName, int divisibility, String symbol) {
}
