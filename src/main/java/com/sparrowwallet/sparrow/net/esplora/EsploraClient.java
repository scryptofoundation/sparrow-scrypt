package com.sparrowwallet.sparrow.net.esplora;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * A minimal client for the Esplora REST API, the backend the Nintondo-family chains (Pepecoin,
 * Luckycoin, Bellscoin, Dingocoin) expose instead of Electrum servers.
 *
 * These chains have no public Electrum servers, so a Sparrow build for them talks to their existing
 * public Esplora instances. Two dialects are in the wild - Blockstream-esplora (api.nintondo.io,
 * Dingocoin) and mempool.space (peppool.space, luckyscan.org). They diverge on block-header routes and
 * on scripthash support, but they agree on the address- and transaction-level endpoints used here, so
 * this client queries by address (which Sparrow always has) rather than by scripthash.
 *
 * The base URL includes any path prefix the instance uses, e.g. "https://api.nintondo.io" (no prefix)
 * or "https://peppool.space/api".
 *
 * This is the transport-agnostic core; wiring it into Sparrow's ElectrumServerRpc contract, the
 * connection/subscription lifecycle and per-coin server selection is layered on top of it.
 */
public class EsploraClient {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final String baseUrl;
    private final HttpClient httpClient;

    public EsploraClient(String baseUrl) {
        //Trim a trailing slash so path joins are predictable.
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    /** Current chain tip height. */
    public int getTipHeight() throws EsploraException {
        return Integer.parseInt(getString("/blocks/tip/height").trim());
    }

    /** Current chain tip block hash. */
    public String getTipHash() throws EsploraException {
        return getString("/blocks/tip/hash").trim();
    }

    /** Confirmed and mempool transactions affecting an address, newest first (Esplora returns up to 50). */
    public List<EsploraTransaction> getAddressTransactions(String address) throws EsploraException {
        return getList("/address/" + address + "/txs", EsploraTransaction[].class);
    }

    /** Unspent outputs for an address. */
    public List<EsploraUtxo> getAddressUtxos(String address) throws EsploraException {
        return getList("/address/" + address + "/utxo", EsploraUtxo[].class);
    }

    /** Raw transaction hex. */
    public String getTransactionHex(String txid) throws EsploraException {
        return getString("/tx/" + txid + "/hex").trim();
    }

    /** Verbose transaction (includes confirmation status and block height). */
    public EsploraTransaction getTransaction(String txid) throws EsploraException {
        return get("/tx/" + txid, EsploraTransaction.class);
    }

    /** The txids in a block (Blockstream-esplora only; mempool.space instances lack this route). */
    public List<String> getBlockTxids(String blockHash) throws EsploraException {
        return List.of(get("/block/" + blockHash + "/txids", String[].class));
    }

    /** Recent mempool transaction ids. Present on both dialects. */
    public List<String> getRecentMempoolTxids() throws EsploraException {
        MempoolRecentTx[] recent = get("/mempool/recent", MempoolRecentTx[].class);
        return java.util.Arrays.stream(recent).map(t -> t.txid).filter(java.util.Objects::nonNull).toList();
    }

    /**
     * Fee estimates as a map of target-blocks to sat/vB. Handles both dialects: Blockstream-esplora
     * serves /fee-estimates (target-blocks -> sat/vB), mempool.space serves /v1/fees/recommended
     * (named tiers). Never throws - fees are advisory and the caller falls back to a minimum relay fee
     * when this is empty.
     */
    public java.util.Map<Integer, Double> getFeeEstimates() {
        java.util.Map<Integer, Double> estimates = new java.util.TreeMap<>();
        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Double> raw = get("/fee-estimates", java.util.Map.class);
            for(java.util.Map.Entry<String, Double> entry : raw.entrySet()) {
                estimates.put(Integer.parseInt(entry.getKey()), entry.getValue());
            }
            if(!estimates.isEmpty()) {
                return estimates;
            }
        } catch(Exception e) {
            //Fall through to the mempool.space endpoint.
        }

        try {
            MempoolRecommendedFees fees = get("/v1/fees/recommended", MempoolRecommendedFees.class);
            if(fees.fastestFee != null) {
                estimates.put(1, (double)fees.fastestFee);
            }
            if(fees.halfHourFee != null) {
                estimates.put(3, (double)fees.halfHourFee);
            }
            if(fees.hourFee != null) {
                estimates.put(6, (double)fees.hourFee);
            }
            if(fees.economyFee != null) {
                estimates.put(25, (double)fees.economyFee);
            }
        } catch(Exception e) {
            //No fee source available; caller falls back to the minimum relay fee.
        }

        return estimates;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class MempoolRecentTx {
        public String txid;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class MempoolRecommendedFees {
        public Integer fastestFee;
        public Integer halfHourFee;
        public Integer hourFee;
        public Integer economyFee;
        public Integer minimumFee;
    }

    /** Broadcast a raw transaction; returns the txid. */
    public String broadcast(String txHex) throws EsploraException {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/tx"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString(txHex))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if(response.statusCode() != 200) {
                throw new EsploraException("Broadcast failed (HTTP " + response.statusCode() + "): " + response.body());
            }
            return response.body().trim();
        } catch(EsploraException e) {
            throw e;
        } catch(Exception e) {
            throw new EsploraException("Broadcast request failed", e);
        }
    }

    private String getString(String path) throws EsploraException {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(30)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if(response.statusCode() != 200) {
                throw new EsploraException("Request " + path + " failed (HTTP " + response.statusCode() + "): " + response.body());
            }
            return response.body();
        } catch(EsploraException e) {
            throw e;
        } catch(Exception e) {
            throw new EsploraException("Request " + path + " failed", e);
        }
    }

    private <T> T get(String path, Class<T> type) throws EsploraException {
        try {
            return objectMapper.readValue(getString(path), type);
        } catch(EsploraException e) {
            throw e;
        } catch(Exception e) {
            throw new EsploraException("Could not parse response from " + path, e);
        }
    }

    private <T> List<T> getList(String path, Class<T[]> arrayType) throws EsploraException {
        return List.of(get(path, arrayType));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EsploraStatus(boolean confirmed, Integer block_height, String block_hash, Long block_time) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EsploraOutput(String scriptpubkey_address, long value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EsploraTransaction(String txid, EsploraStatus status, List<EsploraOutput> vout) {
        public boolean isConfirmed() {
            return status != null && status.confirmed();
        }

        public int getHeight() {
            //Electrum convention: 0 for unconfirmed.
            return status != null && status.block_height() != null ? status.block_height() : 0;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EsploraUtxo(String txid, int vout, long value, EsploraStatus status) {
    }
}
