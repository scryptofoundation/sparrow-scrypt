package com.sparrowwallet.sparrow.net.ord;

import java.math.BigDecimal;
import java.math.BigInteger;

/** A rune balance on one output, as the ord server reports it. */
public record RuneBalance(BigInteger amount, int divisibility, String symbol) {
    public String format() {
        return new BigDecimal(amount, divisibility).toPlainString();
    }

    public RuneBalance add(RuneBalance other) {
        return new RuneBalance(amount.add(other.amount), divisibility, symbol);
    }
}
