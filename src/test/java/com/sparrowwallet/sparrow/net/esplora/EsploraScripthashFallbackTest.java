package com.sparrowwallet.sparrow.net.esplora;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * jkc-testnet-api.s3na.xyz prints Junkcoin testnet P2SH outputs as "3..." (version 5, as junkcoin-core does) but
 * refuses that same address on lookup with HTTP 400 "Address on invalid network". Its scripthash routes answer for
 * the same output, so a refused address lookup is retried by scripthash.
 */
public class EsploraScripthashFallbackTest {
    //a914779fd6577c031bb0d7a9ac5a8755ccf76e30bdbf87, the 2-of-3 P2SH script from the Junkcoin multisig test
    private static final String ADDRESS = "3CbXp4Rwric5sib9gjXkw5reUc1vjQVu46";
    private static final String ELECTRUM_SCRIPTHASH = "3d121f3485c3230adc06df377f8bf4354a33bf19c7da48522ef04a3bca0f7d41";
    private static final String ESPLORA_SCRIPTHASH = "417d0fca3b4af02e5248dac719bf334a35f48b7f37df06dc0a23c385341f123d";
    private static final String TXID = "cb39cd6f3b692f94c01c4eac24446bc0295d015fc254c9ee371e9bb2b71f86ac";

    private HttpServer server;
    private EsploraClient client;

    @BeforeEach
    public void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int status;
            String body;
            if(path.startsWith("/address/" + ADDRESS)) {
                status = 400;
                body = "Address on invalid network";
            } else if(path.equals("/scripthash/" + ESPLORA_SCRIPTHASH + "/txs")) {
                status = 200;
                body = "[{\"txid\":\"" + TXID + "\",\"status\":{\"confirmed\":true,\"block_height\":258789}}]";
            } else {
                status = 404;
                body = "not found";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = new EsploraClient("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    public void stopServer() {
        server.stop(0);
    }

    @Test
    public void esploraScripthashIsTheElectrumOneUnreversed() {
        Assertions.assertEquals(ESPLORA_SCRIPTHASH, EsploraClient.toEsploraScripthash(ELECTRUM_SCRIPTHASH));
    }

    @Test
    public void refusedAddressHistoryIsFetchedByScripthash() throws EsploraException {
        List<EsploraClient.EsploraTransaction> txs = client.getAddressTransactions(ADDRESS, ELECTRUM_SCRIPTHASH);
        Assertions.assertEquals(1, txs.size());
        Assertions.assertEquals(TXID, txs.getFirst().txid());
        Assertions.assertEquals(258789, txs.getFirst().getHeight());
    }

    @Test
    public void otherFailuresAreNotRetried() {
        //A 404 is not the address-format refusal, so it surfaces as is
        Assertions.assertThrows(EsploraException.class, () -> client.getAddressTransactions("unknown", ELECTRUM_SCRIPTHASH));
    }
}
