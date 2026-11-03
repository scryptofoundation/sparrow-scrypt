package com.sparrowwallet.sparrow.net;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.sparrow.io.Server;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A task is scoped to the coin that was focused when it was created, but the connection belongs to the coin it was
 * opened for. A block summary task made while Litecoin's Electrum connection was live, then run under Pepecoin's
 * settings, tried to open a socket to Pepecoin's https Esplora server.
 */
public class ElectrumServerCoinTransportTest {
    private static final Server LTC_ELECTRUM = new Server("ssl://electrum-ltc.bysh.me:51002");
    private static final Server PEP_ESPLORA = new Server("https://pepe-testnet-api.nintondo.io");

    @Test
    public void taskForAnotherCoinMustNotUseTheLiveConnection() {
        Optional<String> problem = ElectrumServer.transportProblem(Coin.LITECOIN, Coin.PEPECOIN, true, PEP_ESPLORA);
        assertTrue(problem.isPresent());
        assertTrue(problem.get().contains("Litecoin"), problem.get());
        assertTrue(problem.get().contains("Pepecoin"), problem.get());

        assertTrue(ElectrumServer.transportProblem(Coin.LITECOIN, Coin.DOGECOIN, true, LTC_ELECTRUM).isPresent());
    }

    @Test
    public void taskForTheConnectedCoinUsesIt() {
        assertEquals(Optional.empty(), ElectrumServer.transportProblem(Coin.LITECOIN, Coin.LITECOIN, true, LTC_ELECTRUM));
    }

    @Test
    public void esploraServerIsNeverOpenedAsASocket() {
        assertTrue(ElectrumServer.transportProblem(null, Coin.PEPECOIN, false, PEP_ESPLORA).isPresent());
        assertTrue(ElectrumServer.transportProblem(Coin.LITECOIN, Coin.PEPECOIN, false, PEP_ESPLORA).isPresent());
    }

    @Test
    public void electrumServerMayBeOpenedWhenNothingIsLive() {
        //The connect path: no live connection yet, possibly a previous coin's connection already closed
        assertEquals(Optional.empty(), ElectrumServer.transportProblem(null, Coin.LITECOIN, false, LTC_ELECTRUM));
        assertEquals(Optional.empty(), ElectrumServer.transportProblem(Coin.PEPECOIN, Coin.LITECOIN, false, LTC_ELECTRUM));
    }
}
