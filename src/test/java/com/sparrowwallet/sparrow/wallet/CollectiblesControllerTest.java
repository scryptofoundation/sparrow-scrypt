package com.sparrowwallet.sparrow.wallet;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.KeyDerivation;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.Wallet;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class CollectiblesControllerTest {
    private static Wallet wallet(String name, Coin coin, ScriptType scriptType, String fingerprint) {
        Wallet wallet = new Wallet(name);
        wallet.setCoin(coin);
        wallet.setScriptType(scriptType);
        Keystore keystore = new Keystore();
        keystore.setKeyDerivation(new KeyDerivation(fingerprint, "m/84'/2'/0'"));
        wallet.getKeystores().add(keystore);
        return wallet;
    }

    @Test
    public void sameSeedTaprootWalletIsIncluded() {
        Wallet segwit = wallet("segwit", Coin.LITECOIN, ScriptType.P2WPKH, "aabbccdd");
        Wallet taproot = wallet("taproot", Coin.LITECOIN, ScriptType.P2TR, "aabbccdd");
        Wallet other = wallet("other", Coin.LITECOIN, ScriptType.P2TR, "11223344");
        Wallet doge = wallet("doge", Coin.DOGECOIN, ScriptType.P2PKH, "aabbccdd");
        Assertions.assertEquals(List.of(segwit, taproot), CollectiblesController.holdingWallets(segwit, List.of(segwit, taproot, other, doge), null));
    }

    @Test
    public void mwebWalletShowsItsLinkedPublicWallet() {
        Wallet segwit = wallet("segwit", Coin.LITECOIN, ScriptType.P2WPKH, "aabbccdd");
        Wallet taproot = wallet("taproot", Coin.LITECOIN, ScriptType.P2TR, "aabbccdd");
        Wallet mweb = wallet("private", Coin.LITECOIN, ScriptType.MWEB, "55667788");
        Assertions.assertEquals(List.of(segwit, taproot), CollectiblesController.holdingWallets(mweb, List.of(mweb, segwit, taproot), segwit));
    }

    @Test
    public void unlinkedMwebWalletUsesItsOwnSeed() {
        Wallet mweb = wallet("private", Coin.LITECOIN, ScriptType.MWEB, "aabbccdd");
        Wallet taproot = wallet("taproot", Coin.LITECOIN, ScriptType.P2TR, "aabbccdd");
        Assertions.assertEquals(List.of(taproot), CollectiblesController.holdingWallets(mweb, List.of(mweb, taproot), null));
    }
}
