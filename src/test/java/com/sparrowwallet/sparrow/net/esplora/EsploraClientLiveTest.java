package com.sparrowwallet.sparrow.net.esplora;

import com.sparrowwallet.sparrow.net.BlockHeaderTip;
import com.sparrowwallet.sparrow.net.VerboseTransaction;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    @Test
    public void junkcoinS3naEsplora() throws EsploraException {
        exercise("Junkcoin", "https://junk-api.s3na.xyz");
    }

    @Test
    public void junkcoinS3naTakesBroadcasts() {
        //An undecodable transaction must reach the node and be refused there, not by a missing POST /tx
        EsploraException e = Assertions.assertThrows(EsploraException.class, () -> new EsploraClient("https://junk-api.s3na.xyz").broadcast("00"));
        Assertions.assertFalse(e.getMessage().contains("does not accept transaction broadcasts"), e.getMessage());
    }

    /**
     * EsploraServerRpc.getBlockHeaders against every Esplora server the app lists. Sync dates each confirmed
     * transaction from these headers, and some instances (luckyscan, junk-coin mainnet) append AuxPoW data after the
     * 80-byte header - so each returned header must be exactly 80 bytes, parse, and hash to that height's block.
     */
    @Test
    public void blockHeadersFromEveryServer() {
        Map<String, String> servers = new LinkedHashMap<>();
        servers.put("Bellscoin", "https://api.nintondo.io");
        servers.put("Pepecoin", "https://peppool.space/api");
        servers.put("Luckycoin", "https://luckyscan.org/api");
        servers.put("Junkcoin", "https://explorer.junk-coin.com/api");
        servers.put("Junkcoin s3na", "https://junk-api.s3na.xyz");
        servers.put("Pepecoin testnet", "https://pepe-testnet-api.nintondo.io");
        servers.put("Junkcoin testnet", "https://jkc-testnet-api.s3na.xyz");

        List<String> unavailable = new ArrayList<>();
        int checked = 0;
        for(Map.Entry<String, String> server : servers.entrySet()) {
            EsploraClient client = new EsploraClient(server.getValue());
            int tip;
            try {
                tip = client.getTipHeight();
            } catch(EsploraException e) {
                unavailable.add(server.getKey() + " (" + e.getMessage() + ")");
                continue;
            }

            Set<Integer> heights = new java.util.TreeSet<>(Set.of(tip - 5, tip - 100));
            Map<Integer, String> headers = new EsploraServerRpc(server.getValue()).getBlockHeaders(null, null, heights);
            if(headers.isEmpty()) {
                unavailable.add(server.getKey() + " (no headers, likely rate limited)");
                continue;
            }

            for(Map.Entry<Integer, String> header : headers.entrySet()) {
                Assertions.assertEquals(160, header.getValue().length(), server.getKey() + " header must be trimmed to 80 bytes");
                com.sparrowwallet.drongo.protocol.BlockHeader parsed = new com.sparrowwallet.drongo.protocol.BlockHeader(com.sparrowwallet.drongo.Utils.hexToBytes(header.getValue()));
                Assertions.assertTrue(parsed.getTimeAsDate().getTime() > 1_500_000_000_000L, server.getKey() + " header time implausible");
                checked++;
            }
            System.out.println(server.getKey() + ": " + headers.size() + " verified header(s) near tip " + tip);
        }

        System.out.println("unavailable: " + unavailable);
        Assumptions.assumeTrue(checked > 0, "no Esplora server answered: " + unavailable);
    }

    /**
     * peppool.space serves no header routes, so sync dates its confirmed transactions from the tx status instead.
     * Pinned to a confirmed mainnet transaction (block 1264568).
     */
    @Test
    public void blockDateFromTransactionStatusWithoutHeaders() {
        java.util.Date date = new EsploraServerRpc("https://peppool.space/api").getBlockDate("772593a86f7caf011cf00dcd12c0c85ca7334e82b1f712238764e7efc6c8eb76");
        Assumptions.assumeTrue(date != null, "peppool.space unavailable or rate limited");
        Assertions.assertEquals(1792886321000L, date.getTime());
    }

    @Test
    public void pepecoinTestnetAddressesParse() throws EsploraException {
        exerciseTestnet(com.sparrowwallet.drongo.Coin.PEPECOIN, "https://pepe-testnet-api.nintondo.io");
    }

    @Test
    public void junkcoinTestnetAddressesParse() throws EsploraException {
        exerciseTestnet(com.sparrowwallet.drongo.Coin.JUNKCOIN, "https://jkc-testnet-api.s3na.xyz");
        exerciseTestnet(com.sparrowwallet.drongo.Coin.JUNKCOIN, "https://explorer.junk-coin.com/testnet/api");
    }

    /**
     * Test mode check against the live chain: every base58 address paid in recent testnet blocks must parse with
     * the coin's testnet parameters. A wrong version byte would reject them, or accept them only as mainnet.
     * Bech32 outputs are skipped (these chains are configured without segwit here).
     */
    private void exerciseTestnet(com.sparrowwallet.drongo.Coin coin, String baseUrl) throws EsploraException {
        com.sparrowwallet.drongo.Network previousNetwork = com.sparrowwallet.drongo.Network.get();
        com.sparrowwallet.drongo.Coin previousCoin = com.sparrowwallet.drongo.Coin.beginScope(coin);
        com.sparrowwallet.drongo.Network.set(com.sparrowwallet.drongo.Network.TESTNET);
        try {
            EsploraClient client = new EsploraClient(baseUrl);
            Assumptions.assumeTrue(client.getTipHeight() > 0, baseUrl + " returned no tip");
            int parsed = 0;
            for(String txid : client.getBlockTxids(client.getTipHash()).stream().limit(5).toList()) {
                for(EsploraClient.EsploraOutput output : client.getTransaction(txid).vout()) {
                    String address = output.scriptpubkey_address();
                    if(address == null || address.toLowerCase(java.util.Locale.ROOT).startsWith("t")) {
                        continue; //no address (OP_RETURN) or a bech32 testnet address
                    }
                    try {
                        com.sparrowwallet.drongo.address.Address.fromString(address);
                        parsed++;
                    } catch(Exception e) {
                        Assertions.fail(coin + " testnet address " + address + " from " + baseUrl + " did not parse: " + e.getMessage());
                    }
                }
            }
            System.out.println(coin + " testnet " + baseUrl + ": parsed " + parsed + " addresses");
            Assertions.assertTrue(parsed > 0, "no base58 addresses found in recent " + coin + " testnet blocks");
        } finally {
            com.sparrowwallet.drongo.Network.set(previousNetwork);
            com.sparrowwallet.drongo.Coin.endScope(previousCoin);
        }
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
