package com.sparrowwallet.sparrow.event;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.sparrow.CurrencyRate;

import java.util.Currency;

public class ExchangeRatesUpdatedEvent {
    private final Currency currency;
    private final Double btcRate;
    private final Coin coin;

    public ExchangeRatesUpdatedEvent(Currency currency, Double btcRate, Coin coin) {
        this.currency = currency;
        this.btcRate = btcRate;
        this.coin = coin;
    }

    public Currency getCurrency() {
        return currency;
    }

    public Double getBtcRate() {
        return btcRate;
    }

    /** The coin this rate prices - one app holds wallets of several coins, and a rate must never be
     * applied to another coin's balance. */
    public Coin getCoin() {
        return coin;
    }

    public CurrencyRate getCurrencyRate() {
        return new CurrencyRate(currency, btcRate);
    }
}
