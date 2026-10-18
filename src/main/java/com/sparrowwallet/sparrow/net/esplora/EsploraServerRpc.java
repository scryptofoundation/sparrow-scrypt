package com.sparrowwallet.sparrow.net.esplora;

import com.github.arteam.simplejsonrpc.client.Transport;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletNode;
import com.sparrowwallet.sparrow.net.BlockHeaderTip;
import com.sparrowwallet.sparrow.net.BlockStats;
import com.sparrowwallet.sparrow.net.ElectrumServerRpc;
import com.sparrowwallet.sparrow.net.ElectrumServerRpcException;
import com.sparrowwallet.sparrow.net.ScriptHashTx;
import com.sparrowwallet.sparrow.net.VerboseTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * An {@link ElectrumServerRpc} backed by an Esplora REST API rather than the Electrum JSON-RPC protocol.
 *
 * The Nintondo-family chains (Pepecoin, Luckycoin, Bellscoin, Dingocoin) have no public Electrum servers,
 * only Esplora, so a Sparrow build for them talks to those existing public instances via {@link
 * EsploraClient}. The {@link Transport} passed to each method is unused; requests go over HTTP.
 *
 * Esplora indexes by address, not scripthash, and Sparrow's history/subscription requests are keyed by
 * derivation path, so each path is resolved to its wallet address before querying. Esplora also has no
 * push subscriptions - {@link #subscribeScriptHashes} returns the current status computed from history,
 * and change detection is driven by polling in the connecting layer rather than server notifications.
 */
public class EsploraServerRpc implements ElectrumServerRpc {
    private static final Logger log = LoggerFactory.getLogger(EsploraServerRpc.class);

    private final EsploraClient client;
    //The coin this connection was opened for. Captured here rather than read per call: the fee refresh runs
    //later on the polling thread, and a coin change tears this connection down and builds a new adapter.
    private final com.sparrowwallet.drongo.Coin coin;

    public EsploraServerRpc(String baseUrl) {
        this.client = new EsploraClient(baseUrl);
        this.coin = com.sparrowwallet.drongo.Coin.get();
    }

    /** sat/vB as the chain measures it, converted to the BTC/kB the Electrum protocol reports. */
    private static double toBtcPerKb(double satPerVb) {
        return satPerVb * 1000.0 / Transaction.SATOSHIS_PER_BITCOIN;
    }

    @Override
    public void ping(Transport transport) {
        try {
            client.getTipHeight();
        } catch(EsploraException e) {
            throw new ElectrumServerRpcException("Esplora server unreachable", e);
        }
    }

    @Override
    public List<String> getServerVersion(Transport transport, String clientName, String[] supportedVersions) {
        //Not an Electrum server; report a synthetic identity so capability detection uses defaults.
        return List.of("Esplora", "1.4");
    }

    @Override
    public String getServerBanner(Transport transport) {
        return "Esplora";
    }

    @Override
    public BlockHeaderTip subscribeBlockHeaders(Transport transport) {
        try {
            BlockHeaderTip tip = new BlockHeaderTip();
            tip.height = client.getTipHeight();
            //Header hex is left null: it is not available uniformly across dialects, and the tip is used
            //for its height. BlockHeaderTip.getBlockHeader() tolerates a null hex.
            return tip;
        } catch(EsploraException e) {
            throw new ElectrumServerRpcException("Could not retrieve chain tip", e);
        }
    }

    @Override
    public Map<String, ScriptHashTx[]> getScriptHashHistory(Transport transport, Wallet wallet, Map<String, String> pathScriptHashes, boolean failOnError) {
        return getHistory(wallet, pathScriptHashes, failOnError, false);
    }

    @Override
    public Map<String, ScriptHashTx[]> getScriptHashMempool(Transport transport, Wallet wallet, Map<String, String> pathScriptHashes, boolean failOnError) {
        return getHistory(wallet, pathScriptHashes, failOnError, true);
    }

    private Map<String, ScriptHashTx[]> getHistory(Wallet wallet, Map<String, String> pathScriptHashes, boolean failOnError, boolean mempoolOnly) {
        Map<String, String> pathAddresses = getPathAddresses(wallet);
        Map<String, ScriptHashTx[]> result = new LinkedHashMap<>();

        for(String path : pathScriptHashes.keySet()) {
            String address = pathAddresses.get(path);
            if(address == null) {
                //Without the wallet we cannot map a scripthash back to an address. This happens on the
                //subscription-driven refetch path, which the Esplora connection layer handles by polling.
                if(failOnError) {
                    throw new ElectrumServerRpcException("Cannot resolve address for path " + path + " without a wallet");
                }
                result.put(path, new ScriptHashTx[] {ScriptHashTx.ERROR_TX});
                continue;
            }

            try {
                List<EsploraClient.EsploraTransaction> txs = client.getAddressTransactions(address);
                List<ScriptHashTx> scriptHashTxes = new ArrayList<>();
                for(EsploraClient.EsploraTransaction tx : txs) {
                    if(mempoolOnly && tx.isConfirmed()) {
                        continue;
                    }
                    scriptHashTxes.add(new ScriptHashTx(tx.getHeight(), tx.txid(), tx.getFee()));
                }
                result.put(path, scriptHashTxes.toArray(new ScriptHashTx[0]));
            } catch(EsploraException e) {
                if(failOnError) {
                    throw new ElectrumServerRpcException("Failed to retrieve history for " + address, e);
                }
                log.warn("Failed to retrieve history for " + address + " (" + e.getMessage() + ")");
                result.put(path, new ScriptHashTx[] {ScriptHashTx.ERROR_TX});
            }
        }

        return result;
    }

    @Override
    public Map<String, String> subscribeScriptHashes(Transport transport, Wallet wallet, Map<String, String> pathScriptHashes) {
        Map<String, String> pathAddresses = getPathAddresses(wallet);
        Map<String, String> statuses = new LinkedHashMap<>();

        for(String path : pathScriptHashes.keySet()) {
            String address = pathAddresses.get(path);
            if(address == null) {
                statuses.put(path, null);
                continue;
            }

            try {
                List<EsploraClient.EsploraTransaction> txs = client.getAddressTransactions(address);
                statuses.put(path, computeStatus(txs));
            } catch(EsploraException e) {
                log.warn("Failed to compute status for " + address + " (" + e.getMessage() + ")");
                statuses.put(path, null);
            }
        }

        return statuses;
    }

    /**
     * The Electrum scripthash status: the SHA256 of the concatenated "tx_hash:height:" of the address
     * history, ordered confirmed-ascending then unconfirmed. Matches ElectrumServer's local computation,
     * so change detection behaves as with an Electrum server.
     */
    private String computeStatus(List<EsploraClient.EsploraTransaction> txs) {
        if(txs.isEmpty()) {
            return null;
        }

        List<EsploraClient.EsploraTransaction> ordered = new ArrayList<>(txs);
        ordered.sort(Comparator.comparingInt(tx -> tx.getHeight() <= 0 ? Integer.MAX_VALUE : tx.getHeight()));

        StringBuilder status = new StringBuilder();
        for(EsploraClient.EsploraTransaction tx : ordered) {
            status.append(tx.txid()).append(":").append(tx.getHeight()).append(":");
        }

        return Utils.bytesToHex(Sha256Hash.hash(status.toString().getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public Map<String, Boolean> unsubscribeScriptHashes(Transport transport, Set<String> scriptHashes) {
        //No server-side subscriptions to release.
        Map<String, Boolean> result = new HashMap<>();
        for(String scriptHash : scriptHashes) {
            result.put(scriptHash, Boolean.TRUE);
        }
        return result;
    }

    @Override
    public Map<Integer, String> getBlockHeaders(Transport transport, Wallet wallet, Set<Integer> blockHeights) {
        //Block header hex is not available uniformly across Esplora dialects. Transaction timestamps come
        //from the tx status (block_time) instead, so this returns nothing and callers fall back.
        return Collections.emptyMap();
    }

    @Override
    public Map<Integer, BlockStats> getBlockStats(Transport transport, Set<Integer> blockHeights) {
        return Collections.emptyMap();
    }

    @Override
    public Map<String, String> getTransactions(Transport transport, Wallet wallet, Set<String> txids) {
        Map<String, String> result = new LinkedHashMap<>();
        for(String txid : txids) {
            try {
                result.put(txid, client.getTransactionHex(txid));
            } catch(EsploraException e) {
                throw new ElectrumServerRpcException("Failed to retrieve transaction " + txid, e);
            }
        }
        return result;
    }

    @Override
    public Map<String, VerboseTransaction> getVerboseTransactions(Transport transport, Set<String> txids, String scriptHash) {
        Map<String, VerboseTransaction> result = new LinkedHashMap<>();
        int tipHeight;
        try {
            tipHeight = client.getTipHeight();
        } catch(EsploraException e) {
            throw new ElectrumServerRpcException("Could not retrieve chain tip", e);
        }

        for(String txid : txids) {
            try {
                EsploraClient.EsploraTransaction tx = client.getTransaction(txid);
                VerboseTransaction verbose = new VerboseTransaction();
                verbose.txid = txid;
                verbose.hash = txid;
                verbose.hex = client.getTransactionHex(txid);
                verbose.blocktime = tx.getBlockTime();
                verbose.blockhash = tx.status() != null ? tx.status().block_hash() : null;
                verbose.confirmations = tx.isConfirmed() ? (tipHeight - tx.getHeight() + 1) : 0;
                result.put(txid, verbose);
            } catch(EsploraException e) {
                throw new ElectrumServerRpcException("Failed to retrieve transaction " + txid, e);
            }
        }
        return result;
    }

    @Override
    public Map<Integer, Double> getFeeEstimates(Transport transport, List<Integer> targetBlocks) {
        //Esplora reports sat/vB; the Electrum protocol (and Sparrow) expect BTC/kB.
        Map<Integer, Double> satPerVb = client.getFeeEstimates();
        //Floored at the chain's recommended rate, which is also its miners' blockmintxfee: these instances
        //report Bitcoin-scale rates (peppool.space advertises 1 sat/vB against a chain whose own mempool
        //runs at ~1000), and a transaction built under blockmintxfee relays but is never mined.
        double minimum = coin.getRecommendedFeeRate();
        Map<Integer, Double> btcPerKb = new TreeMap<>();
        for(Integer target : targetBlocks) {
            Double rate = nearestEstimate(satPerVb, target);
            btcPerKb.put(target, toBtcPerKb(rate == null ? minimum : Math.max(rate, minimum)));
        }
        return btcPerKb;
    }

    private Double nearestEstimate(Map<Integer, Double> satPerVb, int target) {
        if(satPerVb.isEmpty()) {
            return null;
        }
        //Use the estimate for the closest available target at or below the requested one, else the coarsest.
        Double best = null;
        int bestTarget = Integer.MIN_VALUE;
        for(Map.Entry<Integer, Double> entry : satPerVb.entrySet()) {
            if(entry.getKey() <= target && entry.getKey() > bestTarget) {
                bestTarget = entry.getKey();
                best = entry.getValue();
            }
        }
        return best != null ? best : Collections.max(satPerVb.entrySet(), Map.Entry.comparingByKey()).getValue();
    }

    @Override
    public Map<Double, Long> getFeeRateHistogram(Transport transport) {
        return Collections.emptyMap();
    }

    @Override
    public Double getMinimumRelayFee(Transport transport) {
        //Esplora has no relayfee route, so the chain's own DEFAULT_MIN_RELAY_TX_FEE stands in. This is a
        //hundred times Bitcoin's floor on the Dogecoin-derived chains.
        return toBtcPerKb(coin.getMinRelayFeeRate());
    }

    @Override
    public String broadcastTransaction(Transport transport, String txHex) {
        try {
            return client.broadcast(txHex);
        } catch(EsploraException e) {
            throw new ElectrumServerRpcException(e.getMessage(), e);
        }
    }

    @Override
    public long getIdCounterValue() {
        return 0;
    }

    /**
     * Maps each derived node's derivation path to its address, so a scripthash-keyed request (keyed by
     * path) can be answered by an address query. Null-safe on a null wallet.
     */
    private Map<String, String> getPathAddresses(Wallet wallet) {
        Map<String, String> pathAddresses = new HashMap<>();
        if(wallet == null) {
            return pathAddresses;
        }

        for(WalletNode purposeNode : wallet.getPurposeNodes()) {
            for(WalletNode addressNode : purposeNode.getChildren()) {
                if(addressNode.getAddress() != null) {
                    pathAddresses.put(addressNode.getDerivationPath(), addressNode.getAddress().toString());
                }
            }
        }

        return pathAddresses;
    }
}
