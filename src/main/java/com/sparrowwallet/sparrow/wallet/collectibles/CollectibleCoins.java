package com.sparrowwallet.sparrow.wallet.collectibles;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.Wallet;

/**
 * Which coins have a Collectibles tab and what it offers on each. Litecoin has inscriptions, runes and minting;
 * Dogecoin, Pepecoin, Bellscoin and Junkcoin show and send inscriptions only.
 */
public final class CollectibleCoins {
    private CollectibleCoins() {}

    public static boolean hasCollectibles(Coin coin) {
        return switch(coin) {
            case LITECOIN, DOGECOIN, PEPECOIN, BELLSCOIN, JUNKCOIN -> true;
            case LUCKYCOIN, DINGOCOIN -> false;
        };
    }

    public static boolean hasRunes(Coin coin) {
        return coin == Coin.LITECOIN;
    }

    public static boolean canMint(Coin coin) {
        return coin == Coin.LITECOIN;
    }

    /** Whether the wallet's outputs are shown in Collectibles: any Litecoin wallet but MWEB, single-sig wallets on the other coins. */
    public static boolean isHolder(Wallet wallet) {
        Coin coin = wallet.getCoin();
        if(coin == Coin.LITECOIN) {
            return wallet.getScriptType() != ScriptType.MWEB;
        }
        return hasCollectibles(coin) && wallet.getPolicyType() == PolicyType.SINGLE_HD;
    }
}
