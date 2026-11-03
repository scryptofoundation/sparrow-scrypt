package com.sparrowwallet.sparrow.net.ord;

import java.io.IOException;
import java.util.List;

/**
 * One family of inscription indexer API. Ids and outpoints are validated by OrdClient before they reach a dialect, and
 * everything a server answers is untrusted: dialects check what they keep and never pass it on unchecked.
 */
interface OrdDialect {
    /** A short name for messages. */
    String name();

    OrdStatus status(Transport t) throws IOException;

    /** The server's view of each outpoint, in request order; one the server does not know is unknown, never clean. */
    List<OrdOutput> outputs(Transport t, List<String> outpoints) throws IOException;

    default OrdOutput output(Transport t, String outpoint) throws IOException {
        return outputs(t, List.of(outpoint)).getFirst();
    }

    OrdInscription inscription(Transport t, String id) throws IOException;

    byte[] content(Transport t, String id) throws IOException;

    /** A page a person can open in a browser to see the inscription. */
    String explorerUrl(String baseUrl, String id);
}
