package com.sparrowwallet.sparrow.transaction;

import com.sparrowwallet.drongo.Coin;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Narrows the wallets that can sign a transaction to the ones on its coin. Wallets on different coins can hold the same
 * keys (one seed, the same derivation path), so several "can sign". Each chain has its own coins though, so a wallet
 * that owns one of the inputs is on the transaction's coin. Without one (a PSBT whose inputs no open wallet has synced),
 * the focused tab's coin decides, and failing that all are offered.
 */
public class SigningWallets {
    public static <W> List<W> forTransaction(List<W> wallets, Predicate<W> ownsAnInput, Function<W, Coin> coinOf, Coin activeCoin) {
        List<W> owners = wallets.stream().filter(ownsAnInput).toList();
        if(!owners.isEmpty()) {
            return owners;
        }
        List<W> sameCoin = wallets.stream().filter(wallet -> coinOf.apply(wallet) == activeCoin).toList();
        return sameCoin.isEmpty() ? wallets : sameCoin;
    }
}
