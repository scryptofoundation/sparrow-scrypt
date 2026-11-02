package com.sparrowwallet.sparrow.net.ord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.net.HostAndPort;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.net.HttpClientService;
import com.sparrowwallet.tern.http.client.HttpUsage;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpMethod;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * A client for an ord-litecoin server's JSON API: which inscriptions and runes sit on an output, inscription
 * details and content. Every response is untrusted: bodies are size-capped, ids and rune names are checked before
 * they are kept or put into a URL, and nothing fetched is ever executed or shown in a web view. For Nintondo's indexer
 * (Bellscoin, Dogecoin, Pepecoin) status and outputs come from its own endpoints, in the same shapes.
 */
public class OrdClient {
    public static final int MAX_JSON_CHARS = 4_000_000;
    public static final int MAX_CONTENT_BYTES = 2_000_000;
    private static final int BATCH_SIZE = 100;
    private static final int TIMEOUT_SECONDS = 30;
    private static final Pattern INSCRIPTION_ID = Pattern.compile("[0-9a-f]{64}i\\d{1,10}");
    private static final Pattern OUTPOINT = Pattern.compile("[0-9a-f]{64}:\\d{1,10}");
    private static final Pattern RUNE_NAME = Pattern.compile("[A-Z•]{1,40}");
    private static final Pattern DIGITS = Pattern.compile("\\d{1,40}");
    private static final int MAX_DIVISIBILITY = 38;
    private static final String NO_SYMBOL = "¤";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final String baseUrl;
    private final HttpClient httpClient;
    //The app's client carrying Tor or the user's SOCKS proxy (the JDK client cannot use SOCKS); null for direct
    private final HttpClientService proxiedClient;

    public OrdClient(String baseUrl) {
        this(baseUrl, null);
    }

    OrdClient(String baseUrl, HttpClientService proxiedClient) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        this.proxiedClient = proxiedClient;
    }

    /**
     * A client following the app's proxy settings. With a proxy or Tor wanted, it never connects directly, and plain
     * http is refused unless the server is an onion service (an exit node could read or forge the answers).
     */
    public static OrdClient forApp(String baseUrl) throws IOException {
        boolean proxyWanted = Config.get().isUseProxy() || AppServices.isTorRunning() || Config.get().requiresInternalTor();
        if(!proxyWanted) {
            return new OrdClient(baseUrl, null);
        }
        HostAndPort proxy = AppServices.getTorProxy();
        if(proxy == null) {
            throw new IOException("A proxy or Tor is enabled but not available, so the inscriptions server is not contacted directly.");
        }
        if(baseUrl.startsWith("http://") && !OrdServer.isOnion(baseUrl)) {
            throw new IOException("This inscriptions server uses plain http, which is not safe through a proxy or Tor. Use an https or .onion server.");
        }
        return new OrdClient(baseUrl, AppServices.getHttpClientService());
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getHost() {
        return URI.create(baseUrl).getHost();
    }

    public OrdStatus status() throws IOException {
        if(OrdServer.isNintondo(baseUrl)) {
            return nintondoStatus(json("/status", null), com.sparrowwallet.drongo.Coin.get());
        }
        return parseStatus(json("/status", null));
    }

    /**
     * Nintondo's status names the chain, "Pepecoin" or "Pepecoin Testnet"; one for another coin is refused. It indexes
     * inscriptions, which is all those chains put on outputs, so it counts as a full index.
     */
    static OrdStatus nintondoStatus(String json, com.sparrowwallet.drongo.Coin coin) throws IOException {
        JsonNode node = readTree(json);
        String network = node.path("network").asText("");
        if(!network.equals(coin.getCapitalizedName()) && !network.equals(coin.getCapitalizedName() + " Testnet")) {
            throw new IOException(network.isEmpty() ? "Not a Nintondo indexer" : "Inscriptions server is for " + network + ", wallet is " + coin.getCapitalizedName());
        }
        return new OrdStatus(network.endsWith(" Testnet") ? "testnet" : "mainnet", true, node.path("ord_height").asLong(0), null);
    }

    public void requireChain(Network network) throws IOException {
        requireChain(status(), network);
    }

    public static void requireChain(OrdStatus status, Network network) throws IOException {
        String expected = switch(network) {
            case MAINNET -> "mainnet";
            case TESTNET -> "testnet";
            case TESTNET4 -> "testnet4";
            case SIGNET -> "signet";
            case REGTEST -> "regtest";
        };
        if(!expected.equals(status.chain())) {
            throw new IOException("Inscriptions server is on " + status.chain() + ", wallet is on " + network.toDisplayString());
        }
    }

    /** The server's view of each outpoint, in request order. */
    public List<OrdOutput> outputs(List<String> outpoints) throws IOException {
        for(String outpoint : outpoints) {
            if(!OUTPOINT.matcher(outpoint).matches()) {
                throw new IllegalArgumentException("Invalid outpoint " + outpoint);
            }
        }

        List<OrdOutput> results = new ArrayList<>();
        if(OrdServer.isNintondo(baseUrl)) {
            for(String outpoint : outpoints) {
                results.add(output(outpoint));
            }
            return results;
        }
        for(int i = 0; i < outpoints.size(); i += BATCH_SIZE) {
            List<String> batch = outpoints.subList(i, Math.min(outpoints.size(), i + BATCH_SIZE));
            try {
                results.addAll(parseOutputs(batch, json("/outputs", objectMapper.writeValueAsString(batch))));
            } catch(HttpStatusException e) {
                if(e.getStatus() != 404 && e.getStatus() != 405) {
                    throw e;
                }
                //Older servers have no batch endpoint
                for(String outpoint : batch) {
                    results.add(output(outpoint));
                }
            }
        }
        return results;
    }

    public OrdOutput output(String outpoint) throws IOException {
        if(!OUTPOINT.matcher(outpoint).matches()) {
            throw new IllegalArgumentException("Invalid outpoint " + outpoint);
        }
        try {
            if(OrdServer.isNintondo(baseUrl)) {
                //find_meta takes the outpoint as <txid>i<vout>
                return parseNintondoMeta(outpoint, json("/find_meta/" + outpoint.replace(':', 'i'), null));
            }
            return parseOutput(outpoint, json("/output/" + outpoint, null));
        } catch(HttpStatusException e) {
            //An output the indexer does not know is unknown, never clean
            if(e.getStatus() == 404) {
                return OrdOutput.unknown(outpoint);
            }
            throw e;
        }
    }

    /** find_meta's answer: an array of the inscriptions on the output, each with its genesis id; empty when clean. */
    static OrdOutput parseNintondoMeta(String outpoint, String json) throws IOException {
        JsonNode node = readTree(json);
        if(!node.isArray()) {
            throw new IOException("Unexpected answer from Nintondo indexer");
        }
        boolean unlisted = false;
        List<String> inscriptions = new ArrayList<>();
        for(JsonNode entry : node) {
            JsonNode genesis = entry.path("genesis");
            if(genesis.isTextual() && INSCRIPTION_ID.matcher(genesis.asText()).matches()) {
                inscriptions.add(genesis.asText());
            } else {
                unlisted = true;
            }
        }
        return new OrdOutput(outpoint, true, List.copyOf(inscriptions), Map.of(), 0, false, unlisted);
    }

    public OrdInscription inscription(String id) throws IOException {
        requireId(id);
        return parseInscription(json("/inscription/" + id, null));
    }

    /** A rune by its spaced name, as shown in output balances. */
    public OrdRune rune(String spacedName) throws IOException {
        if(spacedName == null || !RUNE_NAME.matcher(spacedName).matches()) {
            throw new IllegalArgumentException("Invalid rune name " + spacedName);
        }
        return parseRune(json("/rune/" + java.net.URLEncoder.encode(spacedName, StandardCharsets.UTF_8), null));
    }

    /** The inscription's content, at most MAX_CONTENT_BYTES. */
    public byte[] content(String id) throws IOException {
        requireId(id);
        return request("/content/" + id, null, "*/*", MAX_CONTENT_BYTES);
    }

    public String explorerUrl(String id) {
        requireId(id);
        return baseUrl + "/inscription/" + id;
    }

    private static void requireId(String id) {
        if(id == null || !INSCRIPTION_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid inscription id " + id);
        }
    }

    private String json(String path, String postBody) throws IOException {
        return new String(request(path, postBody, "application/json", MAX_JSON_CHARS), StandardCharsets.UTF_8);
    }

    private byte[] request(String path, String jsonBody, String accept, int maxBytes) throws IOException {
        String url = baseUrl + path;
        if(proxiedClient != null) {
            return proxiedRequest(url, jsonBody, accept, maxBytes);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(TIMEOUT_SECONDS)).header("Accept", accept);
        HttpRequest request = jsonBody == null ? builder.GET().build()
                : builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try(InputStream in = response.body()) {
                if(response.statusCode() / 100 != 2) {
                    throw new HttpStatusException(response.statusCode());
                }
                return readCapped(in, maxBytes, TIMEOUT_SECONDS * 1000L);
            }
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted contacting " + getHost(), e);
        }
    }

    private byte[] proxiedRequest(String url, String jsonBody, String accept, int maxBytes) throws IOException {
        //The shared client's proxy can be cleared elsewhere (Tor restarting); never fall through to a direct connection
        if(proxiedClient.getTorProxy() == null) {
            throw new IOException("No proxy available for " + getHost() + "; not connecting directly.");
        }
        try {
            org.eclipse.jetty.client.HttpClient jetty = proxiedClient.getHttpClient(HttpUsage.DEFAULT).getJettyHttpClient();
            Request request = jetty.newRequest(url).method(jsonBody == null ? HttpMethod.GET : HttpMethod.POST)
                    .header("Accept", accept).timeout(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if(jsonBody != null) {
                request.content(new StringContentProvider("application/json", jsonBody, StandardCharsets.UTF_8));
            }
            InputStreamResponseListener listener = new InputStreamResponseListener();
            request.send(listener);
            Response response = listener.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            try(InputStream in = listener.getInputStream()) {
                if(response.getStatus() / 100 != 2) {
                    throw new HttpStatusException(response.getStatus());
                }
                return readCapped(in, maxBytes, TIMEOUT_SECONDS * 1000L);
            }
        } catch(IOException e) {
            throw e;
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted contacting " + getHost(), e);
        } catch(Exception e) {
            throw new IOException("Could not reach " + getHost() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Reads at most maxBytes, giving up after timeoutMillis. A request timeout only covers the wait for headers, so a
     * server that sends headers and then trickles the body would otherwise hold the single checking thread forever.
     */
    static byte[] readCapped(InputStream in, int maxBytes, long timeoutMillis) throws IOException {
        java.util.concurrent.atomic.AtomicBoolean timedOut = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.ScheduledFuture<?> watchdog = WATCHDOG.schedule(() -> {
            timedOut.set(true);
            try {
                in.close();
            } catch(IOException e) {
                //Closing is only to unblock the read
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);
        try {
            byte[] data = in.readNBytes(maxBytes + 1);
            if(timedOut.get()) {
                throw new IOException("Inscriptions server took too long to answer");
            }
            if(data.length > maxBytes) {
                throw new ContentTooLargeException(maxBytes);
            }
            return data;
        } catch(IOException e) {
            if(timedOut.get() && !(e instanceof ContentTooLargeException)) {
                throw new IOException("Inscriptions server took too long to answer", e);
            }
            throw e;
        } finally {
            watchdog.cancel(false);
        }
    }

    private static final java.util.concurrent.ScheduledExecutorService WATCHDOG = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Inscriptions server watchdog");
        thread.setDaemon(true);
        return thread;
    });

    private static JsonNode readTree(String json) throws IOException {
        if(json.length() > MAX_JSON_CHARS) {
            throw new IOException("Inscriptions server response too large");
        }
        try {
            return objectMapper.readTree(json);
        } catch(Exception e) {
            throw new IOException("Unreadable inscriptions server response", e);
        }
    }

    public static OrdStatus parseStatus(String json) throws IOException {
        JsonNode node = readTree(json);
        if(!node.path("chain").isTextual()) {
            throw new IOException("Not an ord server status");
        }
        JsonNode minimum = node.path("minimum_rune_for_next_block");
        String minimumRune = minimum.isTextual() && RUNE_NAME.matcher(minimum.asText()).matches() ? minimum.asText() : null;
        return new OrdStatus(node.path("chain").asText(), node.path("rune_index").asBoolean(false), node.path("height").asLong(0), minimumRune);
    }

    static List<OrdOutput> parseOutputs(List<String> outpoints, String json) throws IOException {
        JsonNode node = readTree(json);
        //The answers carry no output index, so they can only be matched by position
        if(!node.isArray() || node.size() != outpoints.size()) {
            throw new IOException("Inscriptions server answered " + (node.isArray() ? node.size() : 0) + " outputs for " + outpoints.size() + " asked");
        }
        List<OrdOutput> outputs = new ArrayList<>();
        for(int i = 0; i < outpoints.size(); i++) {
            outputs.add(parseOutput(outpoints.get(i), node.get(i)));
        }
        return outputs;
    }

    static OrdOutput parseOutput(String outpoint, String json) throws IOException {
        return parseOutput(outpoint, readTree(json));
    }

    private static OrdOutput parseOutput(String outpoint, JsonNode node) throws IOException {
        if(!node.isObject()) {
            throw new IOException("Unexpected output from inscriptions server");
        }
        boolean unlisted = false;
        List<String> inscriptions = new ArrayList<>();
        for(JsonNode id : node.path("inscriptions")) {
            if(id.isTextual() && INSCRIPTION_ID.matcher(id.asText()).matches()) {
                inscriptions.add(id.asText());
            } else {
                unlisted = true;
            }
        }

        Map<String, RuneBalance> runes = new LinkedHashMap<>();
        JsonNode runesNode = node.path("runes");
        //ord writes runes as an object keyed by name; some versions write an array of [name, balance] pairs
        List<Map.Entry<JsonNode, JsonNode>> entries = new ArrayList<>();
        if(runesNode.isObject()) {
            runesNode.fields().forEachRemaining(field -> entries.add(Map.entry(objectMapper.getNodeFactory().textNode(field.getKey()), field.getValue())));
        } else if(runesNode.isArray()) {
            for(JsonNode pair : runesNode) {
                if(pair.isArray() && pair.size() == 2) {
                    entries.add(Map.entry(pair.get(0), pair.get(1)));
                } else {
                    unlisted = true;
                }
            }
        }
        for(Map.Entry<JsonNode, JsonNode> entry : entries) {
            RuneBalance balance = parseBalance(entry.getValue());
            String name = entry.getKey().isTextual() ? entry.getKey().asText() : null;
            if(name == null || !RUNE_NAME.matcher(name).matches() || balance == null) {
                unlisted = true;
            } else {
                runes.put(name, balance);
            }
        }

        return new OrdOutput(outpoint, node.path("indexed").asBoolean(false), List.copyOf(inscriptions), Collections.unmodifiableMap(runes),
                node.path("value").asLong(0), node.path("spent").asBoolean(false), unlisted);
    }

    private static RuneBalance parseBalance(JsonNode node) {
        JsonNode amountNode = node.path("amount");
        BigInteger amount;
        if(amountNode.isIntegralNumber()) {
            amount = amountNode.bigIntegerValue();
        } else if(amountNode.isTextual() && DIGITS.matcher(amountNode.asText()).matches()) {
            amount = new BigInteger(amountNode.asText());
        } else {
            return null;
        }
        JsonNode divisibilityNode = node.path("divisibility");
        if(amount.signum() < 0 || !divisibilityNode.isInt() || divisibilityNode.asInt() < 0 || divisibilityNode.asInt() > MAX_DIVISIBILITY) {
            return null;
        }
        return new RuneBalance(amount, divisibilityNode.asInt(), symbol(node.path("symbol")));
    }

    private static String symbol(JsonNode node) {
        if(!node.isTextual() || node.asText().codePointCount(0, node.asText().length()) != 1) {
            return NO_SYMBOL;
        }
        int codePoint = node.asText().codePointAt(0);
        int type = Character.getType(codePoint);
        if(Character.isISOControl(codePoint) || type == Character.FORMAT || type == Character.UNASSIGNED || type == Character.PRIVATE_USE || type == Character.SURROGATE) {
            return NO_SYMBOL;
        }
        return node.asText();
    }

    private static com.sparrowwallet.drongo.ordinals.Runestone.Terms terms(JsonNode terms) {
        if(!terms.isObject()) {
            return null;
        }
        return new com.sparrowwallet.drongo.ordinals.Runestone.Terms(unsigned(terms.path("amount")), unsigned(terms.path("cap")),
                height(terms.path("height").path(0)), height(terms.path("height").path(1)), height(terms.path("offset").path(0)), height(terms.path("offset").path(1)));
    }

    private static java.math.BigInteger unsigned(JsonNode node) {
        return node.isIntegralNumber() && node.bigIntegerValue().signum() >= 0 ? node.bigIntegerValue() : null;
    }

    private static Long height(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToLong() && node.asLong() >= 0 ? node.asLong() : null;
    }

    public static OrdRune parseRune(String json) throws IOException {
        JsonNode node = readTree(json);
        JsonNode entry = node.path("entry");
        String name = entry.path("spaced_rune").asText("");
        JsonNode divisibility = entry.path("divisibility");
        if(!RUNE_NAME.matcher(name).matches() || !divisibility.isInt() || divisibility.asInt() < 0 || divisibility.asInt() > MAX_DIVISIBILITY) {
            throw new IOException("Unexpected rune from inscriptions server");
        }
        try {
            return new OrdRune(com.sparrowwallet.drongo.ordinals.RuneId.parse(node.path("id").asText("")), name, divisibility.asInt(), symbol(entry.path("symbol")),
                    node.path("mintable").asBoolean(false), terms(entry.path("terms")), unsigned(entry.path("mints")));
        } catch(IllegalArgumentException e) {
            throw new IOException("Unexpected rune id from inscriptions server");
        }
    }

    static OrdInscription parseInscription(String json) throws IOException {
        JsonNode node = readTree(json);
        String id = node.path("id").asText("");
        if(!INSCRIPTION_ID.matcher(id).matches()) {
            throw new IOException("Unexpected inscription from inscriptions server");
        }
        String contentType = node.path("content_type").isTextual() ? node.path("content_type").asText() : null;
        long contentLength = node.path("content_length").isIntegralNumber() ? node.path("content_length").asLong() : -1;
        String satpoint = node.path("satpoint").isTextual() ? node.path("satpoint").asText() : null;
        return new OrdInscription(id, node.path("number").asLong(0), contentType, contentLength, satpoint, node.path("value").asLong(0));
    }

    public static class HttpStatusException extends IOException {
        private final int status;

        public HttpStatusException(int status) {
            super("Inscriptions server returned HTTP " + status);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }

    public static class ContentTooLargeException extends IOException {
        public ContentTooLargeException(int maxBytes) {
            super("Content larger than " + maxBytes + " bytes");
        }
    }
}
