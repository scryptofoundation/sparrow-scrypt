package com.sparrowwallet.sparrow.net.ord;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * The ord 0.5 API of ord-junkcoin (the wonky-ord line, version 0.5.1): /status, /block-count, /outputs/a,b,c,
 * /inscription/id?json=true and /content/id. It names no chain, so its status reports the chain as "unknown" and the
 * caller compares heights instead (OrdClient.requireChain). It indexes inscriptions and junes (its runes); a june
 * balance keeps an output held, the same as an unusable id does. Its /outputs loads unknown transactions from the node,
 * so indexed=true here means "the server answered", not "the server has indexed that block"; CollectiblesManager
 * applies the height rule (indexedBy) to these answers.
 */
final class LegacyOrdDialect implements OrdDialect {
    static final String NAME = "ord 0.5";
    static final String UNKNOWN_CHAIN = "unknown";
    private static final int BATCH_SIZE = 50;
    private static final Pattern TXID = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SATPOINT = Pattern.compile("[0-9a-f]{64}:\\d{1,10}:\\d{1,19}");
    private static final Pattern COUNT = Pattern.compile("\\d{1,10}");
    private static final int MAX_CONTENT_TYPE = 255;

    private final String baseUrl;

    LegacyOrdDialect(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * /status (server.rs line 355) answers the text OK and nothing else; ord-junkcoin has no /blockheight route, and the
     * closest is /block-count (line 288), the number of indexed blocks (rtx.rs: tip height plus one), so the tip is one less.
     */
    @Override
    public OrdStatus status(Transport t) throws IOException {
        if(!"OK".equals(t.json(baseUrl + "/status", null).trim())) {
            throw new IOException("Inscriptions server status is not OK");
        }
        String count = t.json(baseUrl + "/block-count", null).trim();
        if(!COUNT.matcher(count).matches()) {
            throw new IOException("Unexpected block count from inscriptions server");
        }
        return new OrdStatus(UNKNOWN_CHAIN, true, Math.max(0, Long.parseLong(count) - 1), null);
    }

    /**
     * An answer names only the transaction, never the output index, so outputs of one transaction would be told apart
     * by position alone. They are therefore never asked in the same request. A request naming an output the server does
     * not hold fails as a whole with 404 (server.rs line 1244), so that case is asked again one output at a time.
     */
    @Override
    public List<OrdOutput> outputs(Transport t, List<String> outpoints) throws IOException {
        List<List<String>> batches = new ArrayList<>();
        List<Set<String>> txids = new ArrayList<>();
        for(String outpoint : outpoints) {
            String txid = outpoint.substring(0, outpoint.indexOf(':'));
            int i = 0;
            while(i < batches.size() && (batches.get(i).size() >= BATCH_SIZE || txids.get(i).contains(txid))) {
                i++;
            }
            if(i == batches.size()) {
                batches.add(new ArrayList<>());
                txids.add(new HashSet<>());
            }
            batches.get(i).add(outpoint);
            txids.get(i).add(txid);
        }

        Map<String, OrdOutput> found = new HashMap<>();
        for(List<String> batch : batches) {
            for(OrdOutput output : batchOutputs(t, batch)) {
                found.put(output.outpoint(), output);
            }
        }
        List<OrdOutput> results = new ArrayList<>();
        for(String outpoint : outpoints) {
            results.add(found.get(outpoint));
        }
        return results;
    }

    private List<OrdOutput> batchOutputs(Transport t, List<String> batch) throws IOException {
        try {
            return parseOutputs(batch, t.json(baseUrl + "/outputs/" + String.join(",", batch), null));
        } catch(OrdClient.HttpStatusException e) {
            if(e.getStatus() != 404) {
                throw e;
            }
            if(batch.size() == 1) {
                return List.of(OrdOutput.unknown(batch.getFirst()));
            }
            List<OrdOutput> results = new ArrayList<>();
            for(String outpoint : batch) {
                results.addAll(batchOutputs(t, List.of(outpoint)));
            }
            return results;
        }
    }

    /**
     * The answer is an array of OutputJson (templates/output.rs line 35): address, inscriptions, junes, script_pubkey,
     * transaction and value, in request order. Each entry is matched to its outpoint by transaction id; an outpoint with
     * no entry, or whose transaction id several entries or several outpoints share, is unknown.
     */
    static List<OrdOutput> parseOutputs(List<String> outpoints, String json) throws IOException {
        JsonNode node = OrdClient.readTree(json);
        if(!node.isArray()) {
            throw new IOException("Unexpected outputs from inscriptions server");
        }
        Map<String, JsonNode> byTxid = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for(JsonNode entry : node) {
            String txid = entry.path("transaction").asText("");
            if(!entry.isObject() || !TXID.matcher(txid).matches()) {
                continue;
            }
            if(byTxid.put(txid, entry) != null) {
                ambiguous.add(txid);
            }
        }
        Map<String, Integer> asked = new HashMap<>();
        for(String outpoint : outpoints) {
            asked.merge(outpoint.substring(0, outpoint.indexOf(':')), 1, Integer::sum);
        }

        List<OrdOutput> outputs = new ArrayList<>();
        for(String outpoint : outpoints) {
            String txid = outpoint.substring(0, outpoint.indexOf(':'));
            JsonNode entry = byTxid.get(txid);
            outputs.add(entry == null || ambiguous.contains(txid) || asked.get(txid) > 1 ? OrdOutput.unknown(outpoint) : parseOutput(outpoint, entry));
        }
        return outputs;
    }

    private static OrdOutput parseOutput(String outpoint, JsonNode node) {
        boolean unlisted = node.path("junes").isArray() ? !node.path("junes").isEmpty() : !node.path("junes").isMissingNode();
        List<String> inscriptions = new ArrayList<>();
        for(JsonNode id : node.path("inscriptions")) {
            if(id.isTextual() && OrdClient.INSCRIPTION_ID.matcher(id.asText()).matches()) {
                inscriptions.add(id.asText());
            } else {
                unlisted = true;
            }
        }
        if(!node.path("inscriptions").isArray()) {
            unlisted = true;
        }
        long value = node.path("value").isIntegralNumber() ? node.path("value").asLong() : 0;
        return new OrdOutput(outpoint, true, List.copyOf(inscriptions), Map.of(), value, false, unlisted);
    }

    @Override
    public OrdInscription inscription(Transport t, String id) throws IOException {
        OrdInscription inscription = parseInscription(t.json(baseUrl + "/inscription/" + id + "?json=true", null));
        if(!id.equals(inscription.id())) {
            throw new IOException("Inscriptions server answered for another inscription");
        }
        return inscription;
    }

    /**
     * JunkscriptionJson (templates/inscription.rs line 23, served from server.rs line 2486): inscription_id,
     * inscription_number, satpoint (a string, sat_point.rs line 38), output.value, and inscription with body and
     * content_type as arrays of byte values. There is no content length, so it is the body's size.
     */
    static OrdInscription parseInscription(String json) throws IOException {
        JsonNode node = OrdClient.readTree(json);
        String id = node.path("inscription_id").asText("");
        if(!OrdClient.INSCRIPTION_ID.matcher(id).matches()) {
            throw new IOException("Unexpected inscription from inscriptions server");
        }
        String satpoint = node.path("satpoint").asText("");
        if(!SATPOINT.matcher(satpoint).matches()) {
            throw new IOException("Unexpected satpoint from inscriptions server");
        }
        JsonNode inscription = node.path("inscription");
        JsonNode body = inscription.path("body");
        long contentLength = body.isArray() ? body.size() : body.isNull() ? 0 : -1;
        long number = node.path("inscription_number").isIntegralNumber() ? node.path("inscription_number").asLong() : 0;
        long value = node.path("output").path("value").isIntegralNumber() ? node.path("output").path("value").asLong() : 0;
        return new OrdInscription(id, number, contentType(inscription.path("content_type")), contentLength, satpoint, value);
    }

    /** The content type is bytes; only printable ASCII is kept, anything else is shown as unknown. */
    private static String contentType(JsonNode node) {
        if(!node.isArray() || node.isEmpty() || node.size() > MAX_CONTENT_TYPE) {
            return null;
        }
        byte[] bytes = new byte[node.size()];
        for(int i = 0; i < bytes.length; i++) {
            JsonNode b = node.get(i);
            if(!b.isIntegralNumber() || b.asInt() < 0x20 || b.asInt() > 0x7e) {
                return null;
            }
            bytes[i] = (byte)b.asInt();
        }
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    @Override
    public byte[] content(Transport t, String id) throws IOException {
        return t.bytes(baseUrl + "/content/" + id, "*/*", OrdClient.MAX_CONTENT_BYTES);
    }

    @Override
    public String explorerUrl(String baseUrl, String id) {
        return baseUrl + "/inscription/" + id;
    }
}
