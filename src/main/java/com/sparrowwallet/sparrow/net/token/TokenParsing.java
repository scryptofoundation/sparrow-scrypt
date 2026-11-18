package com.sparrowwallet.sparrow.net.token;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrowwallet.sparrow.net.ord.OrdClient;
import com.sparrowwallet.sparrow.net.ord.Transport;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/** Validation and request helpers shared by the token index clients. Every server answer is untrusted. */
final class TokenParsing {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final int MAX_RETRIES = 3;
    private static final Pattern AMOUNT = Pattern.compile("\\d{1,40}(\\.\\d{1,18})?");
    private static final Pattern ADDRESS = Pattern.compile("[A-Za-z0-9]{14,100}");
    private static final Pattern SPACED_NAME = Pattern.compile("[A-Z]{1,28}(•[A-Z]{1,28})*");
    private static final String DEFAULT_SYMBOL = "¤";
    //u128 is at most 39 digits
    static final int MAX_INTEGER_DIGITS = 39;
    static final int MAX_NAME_LETTERS = 28;
    static final int MAX_DIVISIBILITY = 38;
    private static final Pattern OUTPOINT = Pattern.compile("[0-9a-f]{64}:\\d{1,10}");

    private TokenParsing() {
    }

    /** One GET, held to the rate limit; a 429 is retried after a growing pause, up to MAX_RETRIES times, then thrown. */
    static String get(Transport transport, RateLimiter limiter, String url) throws IOException {
        for(int attempt = 0; ; attempt++) {
            limiter.acquire();
            try {
                return transport.json(url, null);
            } catch(OrdClient.HttpStatusException e) {
                if(e.getStatus() != 429 || attempt >= MAX_RETRIES) {
                    throw e;
                }
                limiter.backoff(attempt);
            }
        }
    }

    static JsonNode parse(String body) throws IOException {
        try {
            return MAPPER.readTree(body);
        } catch(Exception e) {
            throw new IOException("Unreadable answer from the token server", e);
        }
    }

    static Iterable<JsonNode> rows(JsonNode list) throws IOException {
        if(list == null || !list.isArray()) {
            throw new IOException("Unexpected answer from the token server");
        }
        return list;
    }

    static String tick(JsonNode node) {
        if(!node.isTextual()) {
            return null;
        }
        return normalizeTick(node.asText());
    }

    /** A decimal string, or the number 0 some servers use for a zero amount; anything else is null. */
    static String amount(JsonNode node) {
        if(node.isIntegralNumber() && node.canConvertToInt() && node.asInt() == 0) {
            return "0";
        }
        if(node.isTextual() && AMOUNT.matcher(node.asText()).matches()) {
            return node.asText();
        }
        return null;
    }

    /** Lower-cased, exactly 4 UTF-8 bytes and no control characters; otherwise null. */
    static String normalizeTick(String tick) {
        if(tick == null) {
            return null;
        }
        String normalized = tick.toLowerCase(Locale.ROOT);
        if(normalized.getBytes(StandardCharsets.UTF_8).length != 4 || normalized.chars().anyMatch(Character::isISOControl)) {
            return null;
        }
        return normalized;
    }

    static String requireTick(String tick) {
        String normalized = normalizeTick(tick);
        if(normalized == null || normalized.chars().anyMatch(c -> c == '&' || c == '?' || c == '#' || c == '%' || c == '/')) {
            throw new IllegalArgumentException("Invalid token ticker " + tick);
        }
        return normalized;
    }

    static String requireAddress(String address) {
        if(address == null || !ADDRESS.matcher(address).matches()) {
            throw new IllegalArgumentException("Invalid address " + address);
        }
        return address;
    }

    static boolean isOutpoint(String outpoint) {
        return outpoint != null && OUTPOINT.matcher(outpoint).matches();
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String cleanMessage(String message) {
        String cleaned = message.replaceAll("\\p{Cntrl}", " ").trim();
        return cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
    }

    static String stripSlash(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    static boolean isSpacedName(String name) {
        return name != null && SPACED_NAME.matcher(name).matches() && unspaced(name).length() <= MAX_NAME_LETTERS;
    }

    static String unspaced(String name) {
        return name.replace("•", "");
    }

    /** A non-negative integer of any size up to u128 as a plain string; otherwise null. */
    static String rawAmount(JsonNode node) {
        if(node.isIntegralNumber() && node.bigIntegerValue().signum() >= 0 && node.bigIntegerValue().toString().length() <= MAX_INTEGER_DIGITS) {
            return node.bigIntegerValue().toString();
        }
        return null;
    }

    /** One visible code point, else the default sign ord uses. */
    static String duneSymbol(JsonNode node) {
        if(node.isTextual()) {
            String text = node.asText();
            if(text.codePointCount(0, text.length()) == 1 && !Character.isISOControl(text.codePointAt(0))) {
                return text;
            }
        }
        return DEFAULT_SYMBOL;
    }
}
