package com.sparrowwallet.sparrow.transaction;

import com.sparrowwallet.drongo.Coin;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A transaction tab shows addresses in its coin's format. A Litecoin PSBT opened from text while a Bellscoin wallet's
 * tab was in front showed its outputs as bcrt1q... because the tab used the focused coin.
 */
public class TransactionCoinTest {
    private record W(String name, Coin coin, boolean ownsAnInput) {}

    @Test
    public void thePsbtsCoinMarkDecides() {
        List<W> wallets = List.of(new W("bells", Coin.BELLSCOIN, true));
        assertEquals(Coin.LITECOIN, TransactionCoin.of(Coin.LITECOIN, wallets, W::ownsAnInput, W::coin, Coin.BELLSCOIN));
    }

    @Test
    public void withoutAMarkTheWalletOwningAnInputDecides() {
        List<W> wallets = List.of(new W("bells", Coin.BELLSCOIN, false), new W("ltc", Coin.LITECOIN, true));
        assertEquals(Coin.LITECOIN, TransactionCoin.of(null, wallets, W::ownsAnInput, W::coin, Coin.BELLSCOIN));
    }

    @Test
    public void otherwiseTheFocusedCoin() {
        List<W> wallets = List.of(new W("ltc", Coin.LITECOIN, false));
        assertEquals(Coin.BELLSCOIN, TransactionCoin.of(null, wallets, W::ownsAnInput, W::coin, Coin.BELLSCOIN));
    }

    @Test
    public void ownersOnDifferentCoinsFallBackToTheFocusedCoin() {
        //One seed's wallets on two coins can both claim the same outpoint (a wallet whose coin was changed keeps its history)
        List<W> wallets = List.of(new W("bells", Coin.BELLSCOIN, true), new W("ltc", Coin.LITECOIN, true));
        assertEquals(Coin.BELLSCOIN, TransactionCoin.of(null, wallets, W::ownsAnInput, W::coin, Coin.BELLSCOIN));
        assertEquals(Coin.LITECOIN, TransactionCoin.of(null, wallets, W::ownsAnInput, W::coin, Coin.LITECOIN));
    }
}
