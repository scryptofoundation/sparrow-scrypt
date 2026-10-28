package com.sparrowwallet.sparrow.io;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.Wallet;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

public class SeedWalletsTest {
    private static final List<String> WORDS = Arrays.asList("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" "));

    private static SeedWallets.SeedWallet seedWallet(String name) {
        return SeedWallets.ALL.stream().filter(w -> w.name().equals(name)).findFirst().orElseThrow();
    }

    //First receive addresses computed outside drongo (plain Python BIP39 + BIP32 + P2PKH), so these check the real
    //derivation rather than restating it. The same script reproduces the well-known Bitcoin and Dogecoin addresses.
    private static String firstAddress(String walletName, Coin coin, String userPassphrase) throws Exception {
        SeedWallets.SeedWallet seedWallet = seedWallet(walletName);
        //Built while a Litecoin context is active: the importer must still derive on the wallet's own coin
        Coin previous = Coin.beginScope(Coin.LITECOIN);
        try {
            Wallet wallet = SeedWallets.createWallet(seedWallet, coin, WORDS, userPassphrase);
            Assertions.assertEquals(coin, wallet.coin());
            Assertions.assertEquals(ScriptType.P2PKH, wallet.getScriptType());
            Assertions.assertEquals(seedWallet.accountFor(coin).derivationPath(), wallet.getKeystores().getFirst().getKeyDerivation().getDerivationPath());

            Coin inner = Coin.beginScope(coin);
            try {
                return wallet.getAddress(wallet.getFreshNode(KeyPurpose.RECEIVE)).toString();
            } finally {
                Coin.endScope(inner);
            }
        } finally {
            Coin.endScope(previous);
        }
    }

    @Test
    public void acceptsOnlyValidBip39Phrases() {
        Assertions.assertTrue(SeedWallets.isValidPhrase(WORDS));
        Assertions.assertFalse(SeedWallets.isValidPhrase(Arrays.asList("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon".split(" "))));
    }

    @Test
    public void nintondoAddsBellsPassphraseAtBitcoinPath() throws Exception {
        Assertions.assertEquals("m/44'/0'/0'", seedWallet("Nintondo").accountFor(Coin.BELLSCOIN).derivationPath());
        Assertions.assertEquals("BMb5oFUiW5cYqA4zfEtyQiZgW884vg1vKT", firstAddress("Nintondo", Coin.BELLSCOIN, ""));
    }

    @Test
    public void myDogeUsesDogecoinPath() throws Exception {
        Assertions.assertEquals("DBus3bamQjgJULBJtYXpEzDWQRwF5iwxgC", firstAddress("MyDoge", Coin.DOGECOIN, ""));
    }

    @Test
    public void peppoolUsesPepecoinPath() throws Exception {
        Assertions.assertEquals("PsGyoVVSD9VTYoDZGp5FmRB2BSoE69f9ZV", firstAddress("Peppool", Coin.PEPECOIN, ""));
    }

    @Test
    public void dingoBrowserWalletUsesDogecoinPath() throws Exception {
        Assertions.assertEquals("DBus3bamQjgJULBJtYXpEzDWQRwF5iwxgC", firstAddress("Dingo browser wallet", Coin.DINGOCOIN, ""));
    }

    @Test
    public void junkieWallyAddsJunkPassphrase() throws Exception {
        Assertions.assertEquals("7p8H8sXPri7bdWG1N9YVuLjnXJ8og7tWmr", firstAddress("JunkieWally", Coin.JUNKCOIN, ""));
    }

    @Test
    public void luckycoinWalletAddsLkyPassphrase() throws Exception {
        Assertions.assertEquals("LACv4L1XCLC6yw56YMFaZA8fVH7iw66bH6", firstAddress("luckycoinwallet", Coin.LUCKYCOIN, ""));
    }

    @Test
    public void hiddenPassphraseIsKeptInTheKeystore() throws Exception {
        Wallet wallet = SeedWallets.createWallet(seedWallet("Nintondo"), Coin.BELLSCOIN, WORDS, "");
        Assertions.assertEquals("bells", wallet.getKeystores().getFirst().getSeed().getPassphrase().asString());
        Assertions.assertEquals("Nintondo Bellscoin", wallet.getName());
    }

    @Test
    public void refusesOwnPassphraseWhenWalletAddsOne() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> SeedWallets.createWallet(seedWallet("luckycoinwallet"), Coin.LUCKYCOIN, WORDS, "mine"));
    }

    @Test
    public void ownPassphraseIsUsedWhenWalletAddsNone() throws Exception {
        Assertions.assertNotEquals("DBus3bamQjgJULBJtYXpEzDWQRwF5iwxgC", firstAddress("MyDoge", Coin.DOGECOIN, "mine"));
    }
}
