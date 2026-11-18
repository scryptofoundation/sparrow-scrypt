package com.sparrowwallet.sparrow.net.token;

import java.math.BigDecimal;

/** A token balance at one address. Amounts are decimal strings. */
public record TokenBalance(String tick, String available, String transferable) {
    public String total() {
        return new BigDecimal(available).add(new BigDecimal(transferable)).toPlainString();
    }
}
