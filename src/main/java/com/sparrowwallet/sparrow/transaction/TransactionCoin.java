package com.sparrowwallet.sparrow.transaction;

import com.sparrowwallet.drongo.Coin;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The coin a transaction tab belongs to, so its addresses show in that chain's format and selecting the tab makes it
 * the active coin. A PSBT made by this app names its coin; otherwise an open wallet that owns one of the inputs is on
 * the transaction's chain. When neither settles it (no owner, or owners on different coins, which one seed's wallets
 * can be), the focused coin stays.
 */
public class TransactionCoin {
    public static <W> Coin of(Coin psbtMark, List<W> wallets, Predicate<W> ownsAnInput, Function<W, Coin> coinOf, Coin focused) {
        if(psbtMark != null) {
            return psbtMark;
        }
        List<Coin> ownerCoins = wallets.stream().filter(ownsAnInput).map(coinOf).distinct().toList();
        return ownerCoins.size() == 1 ? ownerCoins.getFirst() : focused;
    }
}
