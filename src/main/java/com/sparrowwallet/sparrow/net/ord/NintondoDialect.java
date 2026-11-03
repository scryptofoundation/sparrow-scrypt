package com.sparrowwallet.sparrow.net.ord;

import com.fasterxml.jackson.databind.JsonNode;
import com.sparrowwallet.drongo.Coin;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nintondo's indexer for Bellscoin, Dogecoin and Pepecoin. It answers on three hosts that share a prefix:
 * <code>X-api.nintondo.io</code> (status, find_meta, location), <code>X-search.nintondo.io/pub</code> (details such as
 * number, mime and size) and <code>X-content.nintondo.io</code> (the content itself). The configured server is the api
 * host; the others are derived from it with the same scheme and port, so whatever rules apply to the one apply to all.
 * Those chains have no runes, so empty answers count as clean, except for outputs of a runestone or dunestone
 * transaction (Dogecoin's Dunes), which CollectiblesManager.vouchedFor keeps unknown.
 */
final class NintondoDialect implements OrdDialect {
    static final String NAME = "Nintondo";
    private static final String API_SUFFIX = "-api";
    private static final String EXPLORER = "https://nintondo.io";
    //id, then the inscription's current output (txid i vout), offset and number: <txid>i<vout>i<offset>i<number>
    private static final Pattern LOCATION = Pattern.compile("([0-9a-f]{64})i(\\d{1,10})i(\\d{1,19})i(\\d{1,18})");
    private static final Pattern MAINNET_API_LABEL = Pattern.compile("(doge|pepe|bells)-mainnet-api");

    private final String baseUrl;

    NintondoDialect(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public OrdStatus status(Transport t) throws IOException {
        return parseStatus(t.json(baseUrl + "/status", null), Coin.get());
    }

    /**
     * Nintondo's status names the chain, "Pepecoin" or "Pepecoin Testnet"; one for another coin is refused. It indexes
     * inscriptions only.
     */
    static OrdStatus parseStatus(String json, Coin coin) throws IOException {
        JsonNode node = OrdClient.readTree(json);
        String network = node.path("network").asText("");
        if(!network.equals(coin.getCapitalizedName()) && !network.equals(coin.getCapitalizedName() + " Testnet")) {
            throw new IOException(network.isEmpty() ? "Not a Nintondo indexer" : "Inscriptions server is for " + network + ", wallet is " + coin.getCapitalizedName());
        }
        return new OrdStatus(network.endsWith(" Testnet") ? "testnet" : "mainnet", true, node.path("ord_height").asLong(0), null);
    }

    @Override
    public List<OrdOutput> outputs(Transport t, List<String> outpoints) throws IOException {
        List<OrdOutput> results = new ArrayList<>();
        for(String outpoint : outpoints) {
            results.add(output(t, outpoint));
        }
        return results;
    }

    @Override
    public OrdOutput output(Transport t, String outpoint) throws IOException {
        try {
            //find_meta takes the outpoint as <txid>i<vout>
            return parseMeta(outpoint, t.json(baseUrl + "/find_meta/" + outpoint.replace(':', 'i'), null));
        } catch(OrdClient.HttpStatusException e) {
            //An output the indexer does not know is unknown, never clean
            if(e.getStatus() == 404) {
                return OrdOutput.unknown(outpoint);
            }
            throw e;
        }
    }

    /** find_meta's answer: an array of the inscriptions on the output, each with its genesis id; empty when clean. */
    static OrdOutput parseMeta(String outpoint, String json) throws IOException {
        JsonNode node = OrdClient.readTree(json);
        if(!node.isArray()) {
            throw new IOException("Unexpected answer from Nintondo indexer");
        }
        boolean unlisted = false;
        List<String> inscriptions = new ArrayList<>();
        for(JsonNode entry : node) {
            JsonNode genesis = entry.path("genesis");
            if(genesis.isTextual() && OrdClient.INSCRIPTION_ID.matcher(genesis.asText()).matches()) {
                inscriptions.add(genesis.asText());
            } else {
                unlisted = true;
            }
        }
        return new OrdOutput(outpoint, true, List.copyOf(inscriptions), Map.of(), 0, false, unlisted);
    }

    /**
     * Where the inscription is now comes from the api host's location, and its number, type and size from the search
     * host's info; neither is trusted until checked (the info must be for the id asked, the location well formed).
     */
    @Override
    public OrdInscription inscription(Transport t, String id) throws IOException {
        String search = searchBase(baseUrl);
        if(search == null) {
            throw new IOException("Not a Nintondo api server: " + baseUrl);
        }
        JsonNode location = OrdClient.readTree(t.json(baseUrl + "/location/" + id, null));
        Matcher matcher = LOCATION.matcher(location.path("location").asText(""));
        if(!matcher.matches()) {
            throw new IOException("Unexpected location from Nintondo indexer");
        }
        JsonNode info = OrdClient.readTree(t.json(search + "/" + id + "/info", null));
        if(!info.isObject() || !id.equals(info.path("id").asText(id))) {
            throw new IOException("Unexpected inscription info from Nintondo indexer");
        }
        long number = info.path("number").isIntegralNumber() ? info.path("number").asLong() : Long.parseLong(matcher.group(4));
        String contentType = info.path("mime").isTextual() ? info.path("mime").asText() : null;
        long contentLength = info.path("file_size").isIntegralNumber() ? info.path("file_size").asLong() : -1;
        String satpoint = matcher.group(1) + ":" + matcher.group(2) + ":" + matcher.group(3);
        return new OrdInscription(id, number, contentType, contentLength, satpoint, location.path("value").asLong(0));
    }

    @Override
    public byte[] content(Transport t, String id) throws IOException {
        String content = contentBase(baseUrl);
        if(content == null) {
            throw new IOException("Not a Nintondo api server: " + baseUrl);
        }
        return t.bytes(content + "/content/" + id, "*/*", OrdClient.MAX_CONTENT_BYTES);
    }

    /**
     * Nintondo's own page for a mainnet inscription (nintondo.io/doge/inscriptions/id answers 200 for a real id and 404
     * otherwise). It has none known for testnet, where the content host's rendered view stands in.
     */
    @Override
    public String explorerUrl(String baseUrl, String id) {
        String label = firstLabel(baseUrl);
        if(label != null && MAINNET_API_LABEL.matcher(label).matches()) {
            return EXPLORER + "/" + label.substring(0, label.indexOf('-')) + "/inscriptions/" + id;
        }
        String content = contentBase(baseUrl);
        return content == null ? EXPLORER : content + "/html/" + id;
    }

    /** The search host's base for an api host, with its /pub prefix, or null when the host is not an X-api host. */
    static String searchBase(String apiUrl) {
        String base = sibling(apiUrl, "-search");
        return base == null ? null : base + "/pub";
    }

    /** The content host's base for an api host, or null when the host is not an X-api host. */
    static String contentBase(String apiUrl) {
        return sibling(apiUrl, "-content");
    }

    private static String firstLabel(String url) {
        String host = URI.create(url).getHost();
        return host == null ? null : host.substring(0, host.indexOf('.') < 0 ? host.length() : host.indexOf('.'));
    }

    private static String sibling(String apiUrl, String replacement) {
        URI uri = URI.create(apiUrl);
        String host = uri.getHost();
        String label = firstLabel(apiUrl);
        if(host == null || label == null || !label.endsWith(API_SUFFIX) || label.length() == API_SUFFIX.length()) {
            return null;
        }
        String siblingHost = label.substring(0, label.length() - API_SUFFIX.length()) + replacement + host.substring(label.length());
        return uri.getScheme() + "://" + siblingHost + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
    }
}
