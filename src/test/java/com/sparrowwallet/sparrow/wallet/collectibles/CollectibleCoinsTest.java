package com.sparrowwallet.sparrow.wallet.collectibles;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.Wallet;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class CollectibleCoinsTest {
    private static Wallet wallet(Coin coin, PolicyType policyType, ScriptType scriptType) {
        Wallet wallet = new Wallet(coin.getName());
        wallet.setCoin(coin);
        wallet.setPolicyType(policyType);
        wallet.setScriptType(scriptType);
        return wallet;
    }

    @Test
    public void holders() {
        Assertions.assertTrue(CollectibleCoins.isHolder(wallet(Coin.DOGECOIN, PolicyType.SINGLE_HD, ScriptType.P2PKH)));
        Assertions.assertTrue(CollectibleCoins.isHolder(wallet(Coin.PEPECOIN, PolicyType.SINGLE_HD, ScriptType.P2PKH)));
        Assertions.assertTrue(CollectibleCoins.isHolder(wallet(Coin.JUNKCOIN, PolicyType.SINGLE_HD, ScriptType.P2PKH)));
        Assertions.assertTrue(CollectibleCoins.isHolder(wallet(Coin.BELLSCOIN, PolicyType.SINGLE_HD, ScriptType.P2TR)));
        Assertions.assertTrue(CollectibleCoins.isHolder(wallet(Coin.BELLSCOIN, PolicyType.SINGLE_HD, ScriptType.P2WPKH)));
        Assertions.assertFalse(CollectibleCoins.isHolder(wallet(Coin.DOGECOIN, PolicyType.MULTI_HD, ScriptType.P2SH)), "multisig");
        Assertions.assertFalse(CollectibleCoins.isHolder(wallet(Coin.LUCKYCOIN, PolicyType.SINGLE_HD, ScriptType.P2PKH)));
        Assertions.assertFalse(CollectibleCoins.isHolder(wallet(Coin.DINGOCOIN, PolicyType.SINGLE_HD, ScriptType.P2PKH)));
        Assertions.assertFalse(CollectibleCoins.isHolder(wallet(Coin.LITECOIN, PolicyType.SINGLE_HD, ScriptType.MWEB)));
        Assertions.assertTrue(CollectibleCoins.isHolder(wallet(Coin.LITECOIN, PolicyType.SINGLE_HD, ScriptType.P2WPKH)));
    }

    @Test
    public void featuresPerCoin() {
        for(Coin coin : Coin.values()) {
            Assertions.assertEquals(coin == Coin.LITECOIN, CollectibleCoins.hasRunes(coin), coin.getName());
            Assertions.assertEquals(coin == Coin.LITECOIN, CollectibleCoins.canMint(coin), coin.getName());
        }
        Assertions.assertTrue(CollectibleCoins.hasCollectibles(Coin.DOGECOIN));
        Assertions.assertTrue(CollectibleCoins.hasCollectibles(Coin.PEPECOIN));
        Assertions.assertTrue(CollectibleCoins.hasCollectibles(Coin.BELLSCOIN));
        Assertions.assertTrue(CollectibleCoins.hasCollectibles(Coin.JUNKCOIN));
        Assertions.assertTrue(CollectibleCoins.hasCollectibles(Coin.LITECOIN));
        Assertions.assertFalse(CollectibleCoins.hasCollectibles(Coin.LUCKYCOIN));
        Assertions.assertFalse(CollectibleCoins.hasCollectibles(Coin.DINGOCOIN));
    }
}
