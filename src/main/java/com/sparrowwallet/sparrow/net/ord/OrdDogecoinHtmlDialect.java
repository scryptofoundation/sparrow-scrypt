package com.sparrowwallet.sparrow.net.ord;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ord-dogecoin (apezord, an ord 0.5 fork), the only indexer a person can run for Dogecoin. It serves HTML pages only
 * and calls inscriptions shibescriptions: /status (OK), /block-count, /output/txid:vout, /shibescription/id and
 * /content/id. An output it does not hold answers HTTP 500. Every page is untrusted: an output page counts only when
 * its heading names the asked outpoint, and only ids of the inscription id form are kept.
 */
final class OrdDogecoinHtmlDialect implements OrdDialect {
    static final String NAME = "ord-dogecoin";
    private static final Pattern SHIBESCRIPTION_LINK = Pattern.compile("/shibescription/([^\\s>\"'<]+)");
    private static final Pattern VALUE = Pattern.compile("<dt>value</dt>\\s*<dd>(\\d{1,19})</dd>");
    private static final Pattern NUMBER = Pattern.compile("<h1>Shibescription (-?\\d{1,19})</h1>");
    private static final Pattern SATPOINT = Pattern.compile("[0-9a-f]{64}:\\d{1,10}:\\d{1,19}");
    private static final Pattern LENGTH = Pattern.compile("(\\d{1,19}) bytes");
    private static final int MAX_CONTENT_TYPE = 255;

    private final String baseUrl;
    private final LegacyOrdDialect legacy;

    OrdDogecoinHtmlDialect(String baseUrl) {
        this.baseUrl = baseUrl;
        this.legacy = new LegacyOrdDialect(baseUrl);
    }

    @Override
    public String name() {
        return NAME;
    }

    /** The same /status and /block-count as ord 0.5. */
    @Override
    public OrdStatus status(Transport t) throws IOException {
        return legacy.status(t);
    }

    @Override
    public List<OrdOutput> outputs(Transport t, List<String> outpoints) throws IOException {
        List<OrdOutput> outputs = new ArrayList<>();
        for(String outpoint : outpoints) {
            String page;
            try {
                page = t.json(baseUrl + "/output/" + outpoint, null);
            } catch(OrdClient.HttpStatusException e) {
                if(e.getStatus() != 404 && e.getStatus() != 500) {
                    throw e;
                }
                outputs.add(OrdOutput.unknown(outpoint));
                continue;
            }
            outputs.add(parseOutput(outpoint, page));
        }
        return outputs;
    }

    static OrdOutput parseOutput(String outpoint, String page) {
        if(page == null || !page.contains("<h1>Output <span class=monospace>" + outpoint + "</span></h1>")) {
            return OrdOutput.unknown(outpoint);
        }
        List<String> inscriptions = new ArrayList<>();
        boolean unlisted = false;
        Matcher link = SHIBESCRIPTION_LINK.matcher(page);
        while(link.find()) {
            String id = link.group(1);
            if(OrdClient.INSCRIPTION_ID.matcher(id).matches()) {
                if(!inscriptions.contains(id)) {
                    inscriptions.add(id);
                }
            } else {
                unlisted = true;
            }
        }
        Matcher value = VALUE.matcher(page);
        long amount = value.find() ? Long.parseLong(value.group(1)) : 0;
        return new OrdOutput(outpoint, true, List.copyOf(inscriptions), Map.of(), amount, false, unlisted);
    }

    @Override
    public OrdInscription inscription(Transport t, String id) throws IOException {
        OrdInscription inscription = parseInscription(t.json(baseUrl + "/shibescription/" + id, null));
        if(!id.equals(inscription.id())) {
            throw new IOException("Inscriptions server answered for another inscription");
        }
        return inscription;
    }

    static OrdInscription parseInscription(String page) throws IOException {
        String id = field(page, "id");
        if(id == null || !OrdClient.INSCRIPTION_ID.matcher(id).matches()) {
            throw new IOException("Unexpected inscription from inscriptions server");
        }
        String satpoint = field(page, "location");
        if(satpoint == null || !SATPOINT.matcher(satpoint).matches()) {
            throw new IOException("Unexpected location from inscriptions server");
        }
        Matcher number = NUMBER.matcher(page);
        String length = field(page, "content length");
        Matcher lengthMatch = length == null ? null : LENGTH.matcher(length);
        String value = field(page, "output value");
        return new OrdInscription(id, number.find() ? Long.parseLong(number.group(1)) : 0, contentType(field(page, "content type")),
                lengthMatch != null && lengthMatch.matches() ? Long.parseLong(lengthMatch.group(1)) : -1, satpoint,
                value != null && value.matches("\\d{1,19}") ? Long.parseLong(value) : 0);
    }

    /** The text of the dd after a dt of this name, tags removed, or null. */
    private static String field(String page, String name) {
        Matcher matcher = Pattern.compile("<dt>" + Pattern.quote(name) + "</dt>\\s*<dd[^>]*>(.*?)</dd>", Pattern.DOTALL).matcher(page);
        return matcher.find() ? matcher.group(1).replaceAll("<[^>]*>", "").trim() : null;
    }

    /** Printable ASCII only, entities for &amp; &lt; &gt; read back; anything else is shown as unknown. */
    private static String contentType(String text) {
        if(text == null) {
            return null;
        }
        String type = text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
        if(type.isEmpty() || type.length() > MAX_CONTENT_TYPE || !type.chars().allMatch(c -> c >= 0x20 && c <= 0x7e)) {
            return null;
        }
        return type;
    }

    @Override
    public byte[] content(Transport t, String id) throws IOException {
        return t.bytes(baseUrl + "/content/" + id, "*/*", OrdClient.MAX_CONTENT_BYTES);
    }

    @Override
    public String explorerUrl(String baseUrl, String id) {
        return baseUrl + "/shibescription/" + id;
    }
}
