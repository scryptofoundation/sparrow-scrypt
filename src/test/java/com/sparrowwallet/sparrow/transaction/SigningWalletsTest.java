package com.sparrowwallet.sparrow.transaction;

import com.sparrowwallet.drongo.Coin;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

public class SigningWalletsTest {
    private record W(String name, Coin coin) {}

    private static List<W> choose(List<W> wallets, Set<String> owners, Coin activeCoin) {
        return SigningWallets.forTransaction(wallets, w -> owners.contains(w.name()), W::coin, activeCoin);
    }

    /**
     * Two wallets with the same xpub on different coins can both sign. The one whose coins the transaction spends is
     * the right one, whatever tab has the focus (picking by the focused tab's coin chose wrongly once on 2026-10-26).
     */
    @Test
    public void prefersTheWalletThatOwnsTheInputsOverTheFocusedCoin() {
        W ltc = new W("ltc", Coin.LITECOIN);
        W doge = new W("doge", Coin.DOGECOIN);
        Assertions.assertEquals(List.of(doge), choose(List.of(ltc, doge), Set.of("doge"), Coin.LITECOIN));
    }

    @Test
    public void fallsBackToTheFocusedCoinThenToAll() {
        W ltc = new W("ltc", Coin.LITECOIN);
        W doge = new W("doge", Coin.DOGECOIN);
        //A PSBT whose inputs no open wallet has synced yet
        Assertions.assertEquals(List.of(ltc), choose(List.of(ltc, doge), Set.of(), Coin.LITECOIN));
        Assertions.assertEquals(List.of(ltc, doge), choose(List.of(ltc, doge), Set.of(), Coin.PEPECOIN));
    }
}
