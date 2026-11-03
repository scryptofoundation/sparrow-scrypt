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
 * they are kept or put into a URL, and nothing fetched is ever executed or shown in a web view. The API differs by server,\n * so the paths and parsers live in an OrdDialect (ord 0.20, or Nintondo's indexer for Bellscoin, Dogecoin and Pepecoin);\n * this class keeps the transport: requests, size caps and proxy rules, for every host a dialect talks to.\n */
public class OrdClient {
    public static final int MAX_JSON_CHARS = 4_000_000;
    public static final int MAX_CONTENT_BYTES = 2_000_000;
    private static final int TIMEOUT_SECONDS = 30;
    static final Pattern INSCRIPTION_ID = Pattern.compile("[0-9a-f]{64}i\\d{1,10}");
    private static final Pattern OUTPOINT = Pattern.compile("[0-9a-f]{64}:\\d{1,10}");
    static final Pattern RUNE_NAME = Pattern.compile("[A-Z•]{1,40}");
    static final Pattern DIGITS = Pattern.compile("\\d{1,40}");
    static final int MAX_DIVISIBILITY = 38;
    private static final String NO_SYMBOL = "¤";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final String baseUrl;
    private final HttpClient httpClient;
    //The app's client carrying Tor or the user's SOCKS proxy (the JDK client cannot use SOCKS); null for direct
    private final HttpClientService proxiedClient;
    private final OrdDialect dialect;
    private final Transport transport = new Transport() {
        @Override
        public String json(String url, String postBody) throws IOException {
            return OrdClient.this.json(url, postBody);
        }

        @Override
        public byte[] bytes(String url, String accept, int maxBytes) throws IOException {
            return request(url, null, accept, maxBytes);
        }
    };

    public OrdClient(String baseUrl) {
        this(baseUrl, null);
    }

    OrdClient(String baseUrl, HttpClientService proxiedClient) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        this.proxiedClient = proxiedClient;
        //Until detection exists, Nintondo's hosts are known by name and everything else is taken to be ord 0.20
        this.dialect = OrdServer.isNintondo(this.baseUrl) ? new NintondoDialect(this.baseUrl) : new Ord020Dialect(this.baseUrl);
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

    /** The name of the API dialect this client speaks, for messages. */
    public String dialect() {
        return dialect.name();
    }

    public OrdStatus status() throws IOException {
        return dialect.status(transport);
    }

    static OrdStatus nintondoStatus(String json, com.sparrowwallet.drongo.Coin coin) throws IOException {
        return NintondoDialect.parseStatus(json, coin);
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

        return dialect.outputs(transport, outpoints);
    }

    public OrdOutput output(String outpoint) throws IOException {
        if(!OUTPOINT.matcher(outpoint).matches()) {
            throw new IllegalArgumentException("Invalid outpoint " + outpoint);
        }
        return dialect.output(transport, outpoint);
    }

    static OrdOutput parseNintondoMeta(String outpoint, String json) throws IOException {
        return NintondoDialect.parseMeta(outpoint, json);
    }

    public OrdInscription inscription(String id) throws IOException {
        requireId(id);
        return dialect.inscription(transport, id);
    }

    /** A rune by its spaced name, as shown in output balances. */
    public OrdRune rune(String spacedName) throws IOException {
        if(spacedName == null || !RUNE_NAME.matcher(spacedName).matches()) {
            throw new IllegalArgumentException("Invalid rune name " + spacedName);
        }
        return parseRune(json(baseUrl + "/rune/" + java.net.URLEncoder.encode(spacedName, StandardCharsets.UTF_8), null));
    }

    /** The inscription's content, at most MAX_CONTENT_BYTES. */
    public byte[] content(String id) throws IOException {
        requireId(id);
        return dialect.content(transport, id);
    }

    public String explorerUrl(String id) {
        requireId(id);
        return dialect.explorerUrl(baseUrl, id);
    }

    private static void requireId(String id) {
        if(id == null || !INSCRIPTION_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid inscription id " + id);
        }
    }

    private String json(String url, String postBody) throws IOException {
        return new String(request(url, postBody, "application/json", MAX_JSON_CHARS), StandardCharsets.UTF_8);
    }

    /** One request to an absolute URL; a dialect may use several hosts, and each takes the same proxy route. */
    private byte[] request(String url, String jsonBody, String accept, int maxBytes) throws IOException {
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

    static JsonNode readTree(String json) throws IOException {
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
        return Ord020Dialect.parseStatus(json);
    }

    static List<OrdOutput> parseOutputs(List<String> outpoints, String json) throws IOException {
        return Ord020Dialect.parseOutputs(outpoints, json);
    }

    static OrdOutput parseOutput(String outpoint, String json) throws IOException {
        return Ord020Dialect.parseOutput(outpoint, json);
    }

    static OrdInscription parseInscription(String json) throws IOException {
        return Ord020Dialect.parseInscription(json);
    }
    static String symbol(JsonNode node) {
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
