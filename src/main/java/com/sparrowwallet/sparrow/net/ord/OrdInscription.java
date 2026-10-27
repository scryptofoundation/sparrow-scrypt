package com.sparrowwallet.sparrow.net.ord;

public record OrdInscription(String id, long number, String contentType, long contentLength, String satpoint, long value) {
}
