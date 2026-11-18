package com.sparrowwallet.sparrow.net.ord;

import com.google.gson.annotations.SerializedName;
import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.Network;

/**
 * Where inscription answers come from: the coin's public indexer, the user's own, or only the app's built-in on-chain
 * tracker. The built-in tracker runs in every mode and checks what an indexer says. Settings written as OFF by older
 * builds read as BUILT_IN.
 */
public enum InscriptionServerMode {
    PUBLIC("Public"),
    CUSTOM("Your own"),
    @SerializedName(value = "BUILT_IN", alternate = {"OFF"})
    BUILT_IN("Built-in");

    private final String label;

    InscriptionServerMode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** Public where the coin has a public indexer on this network, Built-in otherwise. */
    public static InscriptionServerMode defaultFor(Coin coin, Network network) {
        return OrdServer.publicUrl(coin, network) != null ? PUBLIC : BUILT_IN;
    }
}
