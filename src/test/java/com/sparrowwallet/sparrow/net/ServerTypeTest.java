package com.sparrowwallet.sparrow.net;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class ServerTypeTest {
    @Test
    public void keepsAnOfferedType() {
        Assertions.assertEquals(ServerType.PUBLIC_ELECTRUM_SERVER,
                ServerType.offeredOrElectrum(ServerType.PUBLIC_ELECTRUM_SERVER, List.of(ServerType.PUBLIC_ELECTRUM_SERVER, ServerType.ELECTRUM_SERVER)));
        Assertions.assertEquals(ServerType.ELECTRUM_SERVER,
                ServerType.offeredOrElectrum(ServerType.ELECTRUM_SERVER, List.of(ServerType.ELECTRUM_SERVER)));
    }

    /**
     * The settings page has no Core button. On regtest, where public servers are not offered, the page's default fell
     * back to Core, matched no button, and left every server form showing.
     */
    @Test
    public void fallsBackToPrivateElectrumWhenThePreferredTypeHasNoButton() {
        Assertions.assertEquals(ServerType.ELECTRUM_SERVER,
                ServerType.offeredOrElectrum(ServerType.BITCOIN_CORE, List.of(ServerType.ELECTRUM_SERVER)));
        Assertions.assertEquals(ServerType.ELECTRUM_SERVER,
                ServerType.offeredOrElectrum(ServerType.PUBLIC_ELECTRUM_SERVER, List.of(ServerType.ELECTRUM_SERVER)));
        Assertions.assertEquals(ServerType.ELECTRUM_SERVER,
                ServerType.offeredOrElectrum(null, List.of(ServerType.PUBLIC_ELECTRUM_SERVER, ServerType.ELECTRUM_SERVER)));
    }
}
