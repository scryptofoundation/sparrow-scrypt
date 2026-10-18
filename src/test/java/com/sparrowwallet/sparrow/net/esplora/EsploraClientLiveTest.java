package com.sparrowwallet.sparrow.net.esplora;

import com.sparrowwallet.sparrow.net.BlockHeaderTip;
import com.sparrowwallet.sparrow.net.VerboseTransaction;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

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

    @Test
    public void luckycoinMempoolSpace() throws EsploraException {
        exercise("Luckycoin", "https://luckyscan.org/api");
    }

    @Test
    public void junkcoinEsplora() throws EsploraException {
        //Read path only - this instance serves no POST /tx, so a Junkcoin wallet on it cannot broadcast
        exercise("Junkcoin", "https://explorer.junk-coin.com/api");
    }

    /**
     * Verifies the EsploraServerRpc adapter layer (not just the client): the tip, the sat/vB -> BTC/kB
     * fee conversion, and VerboseTransaction construction, against live Bellscoin.
     */
    @Test
    public void serverRpcAdapterAgainstBellscoin() throws EsploraException {
        String baseUrl = "https://api.nintondo.io";
        EsploraServerRpc rpc = new EsploraServerRpc(baseUrl);

        BlockHeaderTip tip = rpc.subscribeBlockHeaders(null);
        System.out.println("adapter tip height: " + tip.height);
        Assertions.assertTrue(tip.height > 0);

        Map<Integer, Double> fees = rpc.getFeeEstimates(null, List.of(1, 3, 6));
        System.out.println("adapter fee estimates (BTC/kB): " + fees);
        Assertions.assertNotNull(fees);
        //Any returned rate must be a plausible BTC/kB value (a few sat/vB converts to a tiny fraction).
        fees.values().forEach(rate -> Assertions.assertTrue(rate > 0 && rate < 1.0, "fee rate out of range: " + rate));

        //Bootstrap a real txid and verify the verbose transaction is built correctly.
        EsploraClient client = new EsploraClient(baseUrl);
        String txid = client.getBlockTxids(client.getTipHash()).getFirst();
        Map<String, VerboseTransaction> verbose = rpc.getVerboseTransactions(null, Set.of(txid), null);
        VerboseTransaction vt = verbose.get(txid);
        Assertions.assertNotNull(vt, "verbose transaction missing");
        Assertions.assertEquals(txid, vt.txid);
        Assertions.assertTrue(vt.hex != null && vt.hex.matches("[0-9a-fA-F]+"), "verbose tx hex invalid");
        Assertions.assertTrue(vt.confirmations >= 0, "confirmations should be non-negative");
        System.out.println("adapter verbose tx " + txid + ": confirmations=" + vt.confirmations + ", blocktime=" + vt.blocktime);
    }

    /**
     * These instances rate limit, peppool.space visibly so. That is a fact about the server rather than a
     * fault in the client, so it is reported as a skip - a failure here should mean the read path is
     * broken, not that the run was throttled.
     */
    private void exercise(String coin, String baseUrl) throws EsploraException {
        try {
            exerciseInstance(coin, baseUrl);
        } catch(EsploraException e) {
            if(e.getMessage() != null && (e.getMessage().contains("HTTP 429") || e.getMessage().contains("Too Many"))) {
                Assumptions.abort(coin + " skipped: " + baseUrl + " is rate limiting (HTTP 429)");
            }
            throw e;
        }
    }

    private void exerciseInstance(String coin, String baseUrl) throws EsploraException {
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

        //Reported, not asserted: the wallet never calls this. Sparrow derives unspent outputs from the
        //transaction history above, as it does against an Electrum server, so the utxo route is not part of
        //the sync path - which is just as well, since luckyscan.org and explorer.junk-coin.com both answer
        //it with a 500 for every address. mempool.space instances also refuse it with 422
        //"too_many_transactions" for pathologically busy addresses, which a mempool bootstrap can land on.
        try {
            List<EsploraClient.EsploraUtxo> utxos = client.getAddressUtxos(address);
            System.out.println(coin + " address utxos: " + utxos.size());
        } catch(EsploraException e) {
            System.out.println(coin + " address utxos: unsupported by this instance (" + e.getMessage() + ")");
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
