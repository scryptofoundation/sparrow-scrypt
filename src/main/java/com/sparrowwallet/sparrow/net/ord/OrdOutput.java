package com.sparrowwallet.sparrow.net.ord;

import java.util.List;
import java.util.Map;

/**
 * What the ord server knows about one output. indexed is false when the server has not seen it yet, which means
 * unknown, not empty. unlisted is true when the server reported inscriptions or runes that were not safe to keep
 * (a malformed id or rune name): the output still carries something.
 */
public record OrdOutput(String outpoint, boolean indexed, List<String> inscriptions, Map<String, RuneBalance> runes, long value,
                        boolean spent, boolean unlisted) {
    public boolean carries() {
        return !inscriptions.isEmpty() || !runes.isEmpty() || unlisted;
    }

    /**
     * This answer as far as the server can vouch for it. A server without a rune index reports no runes on every
     * output, so an answer showing nothing at all counts as not indexed (unknown) rather than clean.
     */
    public OrdOutput forServer(boolean runeIndex) {
        if(runeIndex || carries() || !indexed) {
            return this;
        }
        return new OrdOutput(outpoint, false, inscriptions, runes, value, spent, unlisted);
    }

    public static OrdOutput unknown(String outpoint) {
        return new OrdOutput(outpoint, false, List.of(), Map.of(), 0, false, false);
    }
}
