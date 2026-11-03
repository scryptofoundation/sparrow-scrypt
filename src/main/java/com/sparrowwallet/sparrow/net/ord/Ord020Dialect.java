package com.sparrowwallet.sparrow.net.ord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.*;

/** The ord 0.20 API (ord-litecoin, as at ordinalslite.com): /status, /outputs and /output, /inscription, /content. */
final class Ord020Dialect implements OrdDialect {
    static final String NAME = "ord 0.20";
    private static final int BATCH_SIZE = 100;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final String baseUrl;

    Ord020Dialect(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public OrdStatus status(Transport t) throws IOException {
        return parseStatus(t.json(baseUrl + "/status", null));
    }

    @Override
    public List<OrdOutput> outputs(Transport t, List<String> outpoints) throws IOException {
        List<OrdOutput> results = new ArrayList<>();
        for(int i = 0; i < outpoints.size(); i += BATCH_SIZE) {
            List<String> batch = outpoints.subList(i, Math.min(outpoints.size(), i + BATCH_SIZE));
            try {
                results.addAll(parseOutputs(batch, t.json(baseUrl + "/outputs", objectMapper.writeValueAsString(batch))));
            } catch(OrdClient.HttpStatusException e) {
                if(e.getStatus() != 404 && e.getStatus() != 405) {
                    throw e;
                }
                //Older servers have no batch endpoint
                for(String outpoint : batch) {
                    results.add(output(t, outpoint));
                }
            }
        }
        return results;
    }

    @Override
    public OrdOutput output(Transport t, String outpoint) throws IOException {
        try {
            return parseOutput(outpoint, t.json(baseUrl + "/output/" + outpoint, null));
        } catch(OrdClient.HttpStatusException e) {
            //An output the indexer does not know is unknown, never clean
            if(e.getStatus() == 404) {
                return OrdOutput.unknown(outpoint);
            }
            throw e;
        }
    }

    @Override
    public OrdInscription inscription(Transport t, String id) throws IOException {
        return parseInscription(t.json(baseUrl + "/inscription/" + id, null));
    }

    @Override
    public byte[] content(Transport t, String id) throws IOException {
        return t.bytes(baseUrl + "/content/" + id, "*/*", OrdClient.MAX_CONTENT_BYTES);
    }

    @Override
    public String explorerUrl(String baseUrl, String id) {
        return baseUrl + "/inscription/" + id;
    }

    static OrdStatus parseStatus(String json) throws IOException {
        JsonNode node = OrdClient.readTree(json);
        if(!node.path("chain").isTextual()) {
            throw new IOException("Not an ord server status");
        }
        JsonNode minimum = node.path("minimum_rune_for_next_block");
        String minimumRune = minimum.isTextual() && OrdClient.RUNE_NAME.matcher(minimum.asText()).matches() ? minimum.asText() : null;
        return new OrdStatus(node.path("chain").asText(), node.path("rune_index").asBoolean(false), node.path("height").asLong(0), minimumRune);
    }

    static List<OrdOutput> parseOutputs(List<String> outpoints, String json) throws IOException {
        JsonNode node = OrdClient.readTree(json);
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
        return parseOutput(outpoint, OrdClient.readTree(json));
    }

    private static OrdOutput parseOutput(String outpoint, JsonNode node) throws IOException {
        if(!node.isObject()) {
            throw new IOException("Unexpected output from inscriptions server");
        }
        boolean unlisted = false;
        List<String> inscriptions = new ArrayList<>();
        for(JsonNode id : node.path("inscriptions")) {
            if(id.isTextual() && OrdClient.INSCRIPTION_ID.matcher(id.asText()).matches()) {
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
            if(name == null || !OrdClient.RUNE_NAME.matcher(name).matches() || balance == null) {
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
        java.math.BigInteger amount;
        if(amountNode.isIntegralNumber()) {
            amount = amountNode.bigIntegerValue();
        } else if(amountNode.isTextual() && OrdClient.DIGITS.matcher(amountNode.asText()).matches()) {
            amount = new java.math.BigInteger(amountNode.asText());
        } else {
            return null;
        }
        JsonNode divisibilityNode = node.path("divisibility");
        if(amount.signum() < 0 || !divisibilityNode.isInt() || divisibilityNode.asInt() < 0 || divisibilityNode.asInt() > OrdClient.MAX_DIVISIBILITY) {
            return null;
        }
        return new RuneBalance(amount, divisibilityNode.asInt(), OrdClient.symbol(node.path("symbol")));
    }

    static OrdInscription parseInscription(String json) throws IOException {
        JsonNode node = OrdClient.readTree(json);
        String id = node.path("id").asText("");
        if(!OrdClient.INSCRIPTION_ID.matcher(id).matches()) {
            throw new IOException("Unexpected inscription from inscriptions server");
        }
        String contentType = node.path("content_type").isTextual() ? node.path("content_type").asText() : null;
        long contentLength = node.path("content_length").isIntegralNumber() ? node.path("content_length").asLong() : -1;
        String satpoint = node.path("satpoint").isTextual() ? node.path("satpoint").asText() : null;
        return new OrdInscription(id, node.path("number").asLong(0), contentType, contentLength, satpoint, node.path("value").asLong(0));
    }
}
