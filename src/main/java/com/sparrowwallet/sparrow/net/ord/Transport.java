package com.sparrowwallet.sparrow.net.ord;

import java.io.IOException;

/**
 * How a dialect reaches an inscriptions server. It takes absolute URLs because Nintondo's indexer answers on three
 * hosts; the client behind it applies the app's proxy rules and the size caps to every one of them.
 */
interface Transport {
    /** A JSON answer as text, at most OrdClient.MAX_JSON_CHARS; a non-2xx status is an OrdClient.HttpStatusException. */
    String json(String url, String postBody) throws IOException;

    /** The raw answer, at most maxBytes. */
    byte[] bytes(String url, String accept, int maxBytes) throws IOException;
}
