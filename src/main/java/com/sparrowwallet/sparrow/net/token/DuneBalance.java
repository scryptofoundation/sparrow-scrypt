package com.sparrowwallet.sparrow.net.token;

/** A dune amount on one output; id is "block:tx", or null when the server does not give it. */
public record DuneBalance(String spacedName, String id, String amount, int divisibility, String symbol, String outpoint) {
}
