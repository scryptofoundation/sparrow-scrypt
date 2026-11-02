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

    private static SeedWallets.Account account(String walletName, Coin coin, int index) {
        return seedWallet(walletName).accountsFor(coin).get(index);
    }

    //First receive addresses computed outside drongo (plain Python BIP39, BIP32, P2PKH and BIP173), so these check the
    //real derivation rather than restating it. The same script reproduces the well-known Bitcoin and Dogecoin addresses.
    private static String firstAddress(String walletName, Coin coin, int index, String userPassphrase) throws Exception {
        SeedWallets.Account account = account(walletName, coin, index);
        //Built while a Litecoin context is active: the importer must still derive on the wallet's own coin
        Coin previous = Coin.beginScope(Coin.LITECOIN);
        try {
            Wallet wallet = SeedWallets.createWallet(seedWallet(walletName), account, WORDS, userPassphrase);
            Assertions.assertEquals(coin, wallet.coin());
            Assertions.assertEquals(account.scriptType(), wallet.getScriptType());
            Assertions.assertEquals(account.derivationPath(), wallet.getKeystores().getFirst().getKeyDerivation().getDerivationPath());

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

    private static String firstAddress(String walletName, Coin coin, String userPassphrase) throws Exception {
        return firstAddress(walletName, coin, 0, userPassphrase);
    }

    @Test
    public void acceptsOnlyValidBip39Phrases() {
        Assertions.assertTrue(SeedWallets.isValidPhrase(WORDS));
        Assertions.assertFalse(SeedWallets.isValidPhrase(Arrays.asList("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon".split(" "))));
    }

    @Test
    public void nintondoDefaultsToNativeSegwitOverItsLegacyKeys() throws Exception {
        List<SeedWallets.Account> accounts = seedWallet("Nintondo").accountsFor(Coin.BELLSCOIN);
        Assertions.assertEquals(3, accounts.size());
        Assertions.assertEquals(ScriptType.P2WPKH, accounts.get(0).scriptType());
        Assertions.assertEquals(ScriptType.P2PKH, accounts.get(1).scriptType());
        Assertions.assertEquals(ScriptType.P2TR, accounts.get(2).scriptType());
        //Nintondo derives every address type from the same m/44' keys
        for(SeedWallets.Account account : accounts) {
            Assertions.assertEquals("m/44'/0'/0'", account.derivationPath());
        }
        Assertions.assertEquals("bel1qh07eu8daq4xvgefnq567dfrv8dssxm0ruw2q2a", firstAddress("Nintondo", Coin.BELLSCOIN, 0, ""));
        Assertions.assertEquals("BMb5oFUiW5cYqA4zfEtyQiZgW884vg1vKT", firstAddress("Nintondo", Coin.BELLSCOIN, 1, ""));
        //bellhdw's p2tr({internalPubkey: toXOnly(key)}) is BIP86 key-only Taproot; computed outside drongo
        Assertions.assertEquals("bel1paacjc7szg778dqsx3amwy7t02p8j5hsd9em3gxlq9jl9rl88jf3s53f3ea", firstAddress("Nintondo", Coin.BELLSCOIN, 2, ""));
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
        Wallet wallet = SeedWallets.createWallet(seedWallet("Nintondo"), account("Nintondo", Coin.BELLSCOIN, 0), WORDS, "");
        Assertions.assertEquals("bells", wallet.getKeystores().getFirst().getSeed().getPassphrase().asString());
        Assertions.assertEquals("Nintondo Bellscoin", wallet.getName());
    }

    @Test
    public void refusesOwnPassphraseWhenWalletAddsOne() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> SeedWallets.createWallet(seedWallet("luckycoinwallet"), account("luckycoinwallet", Coin.LUCKYCOIN, 0), WORDS, "mine"));
    }

    @Test
    public void ownPassphraseIsUsedWhenWalletAddsNone() throws Exception {
        Assertions.assertNotEquals("DBus3bamQjgJULBJtYXpEzDWQRwF5iwxgC", firstAddress("MyDoge", Coin.DOGECOIN, "mine"));
    }
}
