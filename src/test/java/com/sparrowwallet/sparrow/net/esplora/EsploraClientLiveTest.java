package com.sparrowwallet.sparrow.net.esplora;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Live smoke test against the public Esplora instances the Nintondo-family chains use. Network
 * dependent by design - proves the address-based read path works across both Esplora dialects
 * (Blockstream-esplora at api.nintondo.io, mempool.space at peppool.space) before the client is wired
 * into Sparrow's server layer. Run explicitly:
 *   ./gradlew :test --tests "com.sparrowwallet.sparrow.net.esplora.EsploraClientLiveTest"
 */
public class EsploraClientLiveTest {
    @Test
    public void bellscoinBlockstreamEsplora() throws EsploraException {
        exercise("Bellscoin", "https://api.nintondo.io");
    }

    @Test
    public void pepecoinMempoolSpace() throws EsploraException {
        exercise("Pepecoin", "https://peppool.space/api");
    }

    private void exercise(String coin, String baseUrl) throws EsploraException {
        EsploraClient client = new EsploraClient(baseUrl);

        int tipHeight = client.getTipHeight();
        System.out.println(coin + " tip height: " + tipHeight);
        Assertions.assertTrue(tipHeight > 0, coin + " tip height should be positive");

        String tipHash = client.getTipHash();
        System.out.println(coin + " tip hash: " + tipHash);
        Assertions.assertEquals(64, tipHash.length(), coin + " tip hash should be 32 bytes hex");

        //Fee estimates: may legitimately be empty on some instances.
        var fees = client.getFeeEstimates();
        System.out.println(coin + " fee estimates: " + fees);
        Assertions.assertNotNull(fees);

        //Bootstrap a real address from the tip block's coinbase output, then exercise the address path.
        String address = bootstrapAddress(client, tipHash);
        System.out.println(coin + " sample address: " + address);
        Assertions.assertNotNull(address, coin + " could not resolve a sample address from the tip block");

        List<EsploraClient.EsploraTransaction> txs = client.getAddressTransactions(address);
        System.out.println(coin + " address txs: " + txs.size());
        Assertions.assertFalse(txs.isEmpty(), coin + " sample address should have transactions");

        //Verify a transaction round-trips through hex and verbose.
        String txid = txs.getFirst().txid();
        String hex = client.getTransactionHex(txid);
        Assertions.assertTrue(hex.matches("[0-9a-fA-F]+"), coin + " tx hex should be valid hex");
        EsploraClient.EsploraTransaction verbose = client.getTransaction(txid);
        Assertions.assertEquals(txid, verbose.txid(), coin + " verbose tx txid mismatch");

        //A normal wallet address has few transactions; mempool.space instances return 422
        //"too_many_transactions" for pathologically busy addresses (e.g. exchange hot wallets), which a
        //bootstrap from the mempool may land on. That is a server limit on that address, not a fault of
        //the client, so it is tolerated here.
        try {
            List<EsploraClient.EsploraUtxo> utxos = client.getAddressUtxos(address);
            System.out.println(coin + " address utxos: " + utxos.size());
            Assertions.assertNotNull(utxos);
        } catch(EsploraException e) {
            if(e.getMessage() != null && e.getMessage().contains("too_many_transactions")) {
                System.out.println(coin + " address utxos: skipped (server limit on busy address)");
            } else {
                throw e;
            }
        }

        System.out.println(coin + " OK against " + baseUrl);
    }

    private String bootstrapAddress(EsploraClient client, String tipHash) throws EsploraException {
        //Blockstream-esplora exposes block txids; mempool.space instances do not, so fall back to the
        //mempool, which both dialects serve.
        List<String> txids;
        try {
            txids = client.getBlockTxids(tipHash);
        } catch(EsploraException e) {
            txids = client.getRecentMempoolTxids();
        }
        for(String txid : txids) {
            EsploraClient.EsploraTransaction tx = client.getTransaction(txid);
            if(tx.vout() != null) {
                for(EsploraClient.EsploraOutput output : tx.vout()) {
                    if(output.scriptpubkey_address() != null) {
                        return output.scriptpubkey_address();
                    }
                }
            }
        }
        return null;
    }
}
