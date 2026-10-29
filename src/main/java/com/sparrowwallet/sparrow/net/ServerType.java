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

    /**
     * The type the server settings page should select: the preferred one if the page offers it, otherwise Private
     * Electrum. The page has no Core button, so preferring Core (the old default where public servers are not offered,
     * as on regtest) selected nothing and left every server form showing.
     */
    public static ServerType offeredOrElectrum(ServerType preferred, java.util.Collection<ServerType> offered) {
        return preferred != null && offered.contains(preferred) ? preferred : ELECTRUM_SERVER;
    }
}
