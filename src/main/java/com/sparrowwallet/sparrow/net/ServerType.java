package com.sparrowwallet.sparrow.net;

import com.sparrowwallet.drongo.Coin;

public enum ServerType {
    //The core node name follows the active coin ("Dogecoin Core" etc), so it is resolved at call time
    //rather than fixed here.
    BITCOIN_CORE(null), ELECTRUM_SERVER("Private Electrum"), PUBLIC_ELECTRUM_SERVER("Public Electrum");

    private final String name;

    ServerType(String name) {
        this.name = name;
    }

    public String getName() {
        return name == null ? Coin.get().getCoreName() : name;
    }
}
