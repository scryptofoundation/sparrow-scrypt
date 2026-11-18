package com.sparrowwallet.sparrow.net.token;

/** Deploy parameters of a token; any field but tick may be null when the server does not give it. */
public record TokenInfo(String tick, String max, String limit, Integer dec, String minted) {
}
