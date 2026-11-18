package com.sparrowwallet.sparrow.net.ord;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * ord-dogecoin (apezord, ord 0.5) on the sandboxed Dogecoin test chain, pages recorded 2026-11-05: it serves HTML
 * only, calls inscriptions shibescriptions, answers an output it does not hold with HTTP 500.
 */
public class OrdDogecoinHtmlDialectTest {
    private static final String BASE = "http://127.0.0.1:8091";
    private static final String ID = "706044f1dba203bef72c456ef2eddd862d3b9668b504fe6ced615076cf2a5dd1i0";
    private static final String INSCRIBED = "d11e26b1aaaad75e524616f609b6fe1dda0f72e800fe05a81080d81fa496ab30:0";
    private static final String CLEAN = "ae7d57e7a946965fd74afc7be803789ad0d404969b635c9d77752d87cdaa57eb:0";
    private static final String MISSING = "0000000000000000000000000000000000000000000000000000000000000001:0";

    static String page(String name) throws IOException {
        try(InputStream in = OrdDogecoinHtmlDialectTest.class.getResourceAsStream("ord-dogecoin/" + name)) {
            Assertions.assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static class PageTransport implements Transport {
        final Map<String, String> pages;

        PageTransport(Map<String, String> pages) {
            this.pages = pages;
        }

        @Override
        public String json(String url, String postBody) throws IOException {
            String page = pages.get(url);
            if(page == null) {
                throw new OrdClient.HttpStatusException(500);
            }
            return page;
        }

        @Override
        public byte[] bytes(String url, String accept, int maxBytes) throws IOException {
            return json(url, null).getBytes(StandardCharsets.UTF_8);
        }
    }

    private static PageTransport transport() throws IOException {
        return new PageTransport(Map.of(
                BASE + "/status", page("status.txt"),
                BASE + "/block-count", "71",
                BASE + "/output/" + INSCRIBED, page("output-inscribed.html"),
                BASE + "/output/" + CLEAN, page("output-clean.html"),
                BASE + "/shibescription/" + ID, page("shibescription.html")));
    }

    @Test
    public void outputs() throws IOException {
        List<OrdOutput> outputs = new OrdDogecoinHtmlDialect(BASE).outputs(transport(), List.of(INSCRIBED, CLEAN, MISSING));
        Assertions.assertEquals(new OrdOutput(INSCRIBED, true, List.of(ID), Map.of(), 100000, false, false), outputs.get(0));
        Assertions.assertTrue(outputs.get(1).indexed());
        Assertions.assertFalse(outputs.get(1).carries());
        Assertions.assertEquals(OrdOutput.unknown(MISSING), outputs.get(2));
    }

    @Test
    public void pageForAnotherOutputIsUnknown() throws IOException {
        //A page that is not the indexer's page for the asked outpoint proves nothing
        PageTransport wrong = new PageTransport(Map.of(BASE + "/output/" + CLEAN, page("output-inscribed.html")));
        Assertions.assertEquals(OrdOutput.unknown(CLEAN), new OrdDogecoinHtmlDialect(BASE).outputs(wrong, List.of(CLEAN)).getFirst());
    }

    @Test
    public void inscription() throws IOException {
        OrdInscription inscription = new OrdDogecoinHtmlDialect(BASE).inscription(transport(), ID);
        Assertions.assertEquals(ID, inscription.id());
        Assertions.assertEquals(2, inscription.number());
        Assertions.assertEquals("text/plain;charset=utf-8", inscription.contentType());
        Assertions.assertEquals(4000, inscription.contentLength());
        Assertions.assertEquals(INSCRIBED + ":0", inscription.satpoint());
        Assertions.assertEquals(100000, inscription.value());
    }

    @Test
    public void status() throws IOException {
        OrdStatus status = new OrdDogecoinHtmlDialect(BASE).status(transport());
        Assertions.assertEquals(70, status.height());
    }

    @Test
    public void detection() throws IOException {
        Assertions.assertInstanceOf(OrdDogecoinHtmlDialect.class, OrdClient.detect(BASE, "OK", page("home.html")));
        Assertions.assertInstanceOf(LegacyOrdDialect.class, OrdClient.detect(BASE, "OK", ""));
        Assertions.assertInstanceOf(LegacyOrdDialect.class, OrdClient.detect(BASE, "OK", null));
        Assertions.assertEquals("ord-dogecoin", new OrdDogecoinHtmlDialect(BASE).name());
        Assertions.assertEquals(BASE + "/shibescription/" + ID, new OrdDogecoinHtmlDialect(BASE).explorerUrl(BASE, ID));
    }
}
