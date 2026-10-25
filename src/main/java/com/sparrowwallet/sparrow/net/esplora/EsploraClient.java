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
    //The app's tern client, carrying the bundled Tor or the user's SOCKS proxy. The JDK HttpClient cannot use a
    //SOCKS proxy at all, so when one is in use every request goes through this instead (hostnames are resolved by
    //the proxy, not locally). Null for a direct connection.
    private final com.sparrowwallet.sparrow.net.HttpClientService proxiedClient;

    public EsploraClient(String baseUrl) {
        this(baseUrl, null);
    }

    public EsploraClient(String baseUrl, com.sparrowwallet.sparrow.net.HttpClientService proxiedClient) {
        //Trim a trailing slash so path joins are predictable.
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        this.proxiedClient = proxiedClient;
    }

    public boolean isProxied() {
        return proxiedClient != null;
    }

    private record Response(int status, String body) {}

    /** One request through whichever transport this client uses. A null body is a GET, otherwise a text/plain POST. */
    private Response send(String path, String postBody) throws Exception {
        if(proxiedClient != null) {
            //The app's client is shared and its proxy can be changed or cleared elsewhere (Tor restarting, settings
            //changes). A client built to be proxied must never fall through to a direct connection: refuse instead.
            if(proxiedClient.getTorProxy() == null) {
                throw new EsploraException("No proxy available for " + baseUrl + "; not connecting directly.");
            }
            try {
                String body = postBody == null
                        ? proxiedClient.requestJson(baseUrl + path, String.class, null) //String.class: tern returns the raw body
                        : proxiedClient.postString(baseUrl + path, null, "text/plain", postBody);
                return new Response(200, body);
            } catch(Exception e) {
                com.sparrowwallet.tern.http.client.HttpResponseException status = findStatus(e);
                if(status != null) {
                    return new Response(status.getStatusCode(), status.getResponseBody());
                }
                throw e;
            }
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30));
        HttpRequest request = postBody == null ? builder.GET().build()
                : builder.header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString(postBody)).build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private static com.sparrowwallet.tern.http.client.HttpResponseException findStatus(Throwable e) {
        for(Throwable t = e; t != null; t = t.getCause()) {
            if(t instanceof com.sparrowwallet.tern.http.client.HttpResponseException status) {
                return status;
            }
        }
        return null;
    }

    /** Current chain tip height. */
    public int getTipHeight() throws EsploraException {
        return Integer.parseInt(getPlainString("/blocks/tip/height"));
    }

    /** Current chain tip block hash. */
    public String getTipHash() throws EsploraException {
        return getPlainString("/blocks/tip/hash");
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
        return getPlainString("/tx/" + txid + "/hex");
    }

    /**
     * A response Esplora defines as bare text, tolerant of an instance returning it as a JSON string.
     *
     * explorer.junk-coin.com quotes these: its tip hash arrives as 66 characters and its raw transactions
     * with a leading quote, which makes every one of them unparseable hex. Stripping one wrapping pair here
     * costs nothing on the instances that answer correctly - a bare hash or hex string never starts with a
     * quote. Only the text routes go through this; JSON responses are parsed by {@link #get} untouched.
     */
    /** The hash of the block at the given height on the server's best chain. */
    public String getBlockHash(int height) throws EsploraException {
        return getPlainString("/block-height/" + height);
    }

    /**
     * The block header hex. Some instances (luckyscan, junk-coin mainnet) append the merge-mining AuxPoW data to
     * the 80-byte header; callers that need only the header take its first 80 bytes.
     */
    public String getBlockHeaderHex(String blockHash) throws EsploraException {
        return getPlainString("/block/" + blockHash + "/header");
    }

    private String getPlainString(String path) throws EsploraException {
        return unquote(getString(path));
    }

    private static String unquote(String value) {
        String trimmed = value.trim();
        if(trimmed.length() > 1 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }

        return trimmed;
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
     * Fee estimates as a map of target-blocks to sat/vB. Handles all three dialects seen on these chains:
     * Blockstream-esplora serves /fee-estimates (target-blocks -> sat/vB), mempool.space serves
     * /v1/fees/recommended (named tiers), and the Nintondo-family forks serve the same tiers unversioned
     * at /fees/recommended (peppool.space, luckyscan.org). Never throws - fees are advisory and the caller
     * floors them at the chain's own rates when this is empty.
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
            //Fall through to the mempool.space endpoints.
        }

        for(String path : List.of("/v1/fees/recommended", "/fees/recommended")) {
            try {
                MempoolRecommendedFees fees = get(path, MempoolRecommendedFees.class);
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
                if(!estimates.isEmpty()) {
                    return estimates;
                }
            } catch(Exception e) {
                //Try the next dialect; if none answer the caller uses the chain's own rates.
            }
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
            Response response = send("/tx", txHex);
            if(response.status() == 404 || response.status() == 405) {
                //Not every public instance exposes the write half of the API - explorer.junk-coin.com serves
                //the whole read path but has no POST /tx, so a wallet on it can sync but never send.
                throw new EsploraException("The server at " + baseUrl + " does not accept transaction broadcasts. " +
                        "Configure a server that does in order to send from this wallet.");
            }
            if(response.status() != 200) {
                String reason = extractRejectReason(response.body());
                //The node already has it: a retried POST whose first attempt got through (the proxied client retries
                //once on a network error), or a re-broadcast. The transaction is out, so report its txid, not a failure.
                String lower = reason.toLowerCase(java.util.Locale.ROOT);
                if(lower.contains("already known") || lower.contains("already-known") || lower.contains("already in")
                        || lower.contains("already exists") || lower.contains("txn-already")) {
                    return new com.sparrowwallet.drongo.protocol.Transaction(com.sparrowwallet.drongo.Utils.hexToBytes(txHex)).getTxId().toString();
                }
                throw new EsploraException(reason);
            }
            //The returned txid is subject to the same quoting as the other text responses
            return unquote(response.body());
        } catch(EsploraException e) {
            throw e;
        } catch(Exception e) {
            throw new EsploraException("Broadcast request failed", e);
        }
    }

    /**
     * The node's own reject reason, dug out of the wrapping the instance put around it.
     *
     * Each dialect wraps differently and peppool.space wraps twice - the node's RPC error JSON arrives
     * embedded in the message field of the instance's own JSON error. Unwrapping matters beyond
     * readability: the send flow recognises a failure by the reason's leading text ("min relay fee not
     * met", "dust"), which it never sees while the reason is buried in two layers of JSON.
     */
    String extractRejectReason(String body) {
        String reason = body == null ? "" : body.trim();
        for(int i = 0; i < 4; i++) {
            String unwrapped = unwrapJsonMessage(reason);
            if(unwrapped == null || unwrapped.equals(reason)) {
                break;
            }
            reason = unwrapped;
        }

        return reason.isEmpty() ? "Broadcast rejected" : reason;
    }

    /** One layer: the message or error of the outermost JSON object in the given text, else null. */
    String unwrapJsonMessage(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if(start < 0 || end <= start) {
            return null;
        }

        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(text.substring(start, end + 1));
            com.fasterxml.jackson.databind.JsonNode error = node.get("error");
            if(error != null && error.isObject() && error.get("message") != null) {
                return error.get("message").asText().trim();
            }
            if(node.get("message") != null) {
                return node.get("message").asText().trim();
            }
            if(error != null && error.isTextual()) {
                return error.asText().trim();
            }
        } catch(Exception e) {
            //Not JSON - some instances return the node's error as plain text, which is already the reason
        }

        return null;
    }

    private String getString(String path) throws EsploraException {
        try {
            Response response = send(path, null);
            if(response.status() != 200) {
                throw new EsploraException("Request " + path + " failed (HTTP " + response.status() + "): " + response.body());
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
    public record EsploraTransaction(String txid, EsploraStatus status, Long fee, List<EsploraOutput> vout) {
        public boolean isConfirmed() {
            return status != null && status.confirmed();
        }

        public int getHeight() {
            //Electrum convention: 0 for unconfirmed.
            return status != null && status.block_height() != null ? status.block_height() : 0;
        }

        public long getFee() {
            return fee != null ? fee : 0L;
        }

        public long getBlockTime() {
            return status != null && status.block_time() != null ? status.block_time() : 0L;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EsploraUtxo(String txid, int vout, long value, EsploraStatus status) {
    }
}
