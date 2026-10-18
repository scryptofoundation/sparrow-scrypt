package com.sparrowwallet.sparrow.io;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.DeterministicSeed;
import com.sparrowwallet.drongo.wallet.Wallet;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

public class CakeWalletTest {
    private static final List<String> BIP39_WORDS = Arrays.asList("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" "));

    //Segwit seed from Electrum's own seed-version test vectors
    private static final List<String> ELECTRUM_WORDS = Arrays.asList("wild father tree among universe such mobile favorite target dynamic credit identify".split(" "));

    @Test
    public void detectsSeedType() {
        Assertions.assertEquals(DeterministicSeed.Type.BIP39, CakeWallet.detectSeedType(BIP39_WORDS));
        Assertions.assertEquals(DeterministicSeed.Type.ELECTRUM, CakeWallet.detectSeedType(ELECTRUM_WORDS));
        Assertions.assertNull(CakeWallet.detectSeedType(Arrays.asList("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon".split(" "))));
    }

    @Test
    public void litecoinBip39UsesCakeSegwitPathWithMwebChild() throws Exception {
        Wallet wallet = CakeWallet.createWallet(Coin.LITECOIN, DeterministicSeed.Type.BIP39, BIP39_WORDS, "", true);
        Assertions.assertEquals(Coin.LITECOIN, wallet.coin());
        Assertions.assertEquals(ScriptType.P2WPKH, wallet.getScriptType());
        Assertions.assertEquals("m/84'/2'/0'", wallet.getKeystores().getFirst().getKeyDerivation().getDerivationPath());

        Wallet mweb = wallet.getChildWallet(ScriptType.MWEB);
        Assertions.assertNotNull(mweb);
        Assertions.assertEquals("m/1000'", mweb.getKeystores().getFirst().getKeyDerivation().getDerivationPath());
        Assertions.assertEquals("Public", wallet.getLabel());

        Coin previous = Coin.beginScope(Coin.LITECOIN);
        try {
            Assertions.assertTrue(wallet.getAddress(wallet.getFreshNode(KeyPurpose.RECEIVE)).toString().startsWith("ltc1"));
            Assertions.assertTrue(mweb.getAddress(mweb.getFreshNode(KeyPurpose.RECEIVE)).toString().startsWith("ltcmweb1"));
        } finally {
            Coin.endScope(previous);
        }
    }

    @Test
    public void litecoinElectrumUsesCakeElectrumPath() throws Exception {
        Wallet wallet = CakeWallet.createWallet(Coin.LITECOIN, DeterministicSeed.Type.ELECTRUM, ELECTRUM_WORDS, "", false);
        Assertions.assertEquals(ScriptType.P2WPKH, wallet.getScriptType());
        Assertions.assertEquals("m/0'", wallet.getKeystores().getFirst().getKeyDerivation().getDerivationPath());
        Assertions.assertNull(wallet.getChildWallet(ScriptType.MWEB));
    }

    @Test
    public void dogecoinUsesCakeLegacyPathRegardlessOfFocusedCoin() throws Exception {
        //Built while a Litecoin context is active: the importer must still derive on Dogecoin's path
        Coin previous = Coin.beginScope(Coin.LITECOIN);
        try {
            Wallet wallet = CakeWallet.createWallet(Coin.DOGECOIN, DeterministicSeed.Type.BIP39, BIP39_WORDS, "", true);
            Assertions.assertEquals(Coin.DOGECOIN, wallet.coin());
            Assertions.assertEquals(ScriptType.P2PKH, wallet.getScriptType());
            Assertions.assertEquals("m/44'/3'/0'", wallet.getKeystores().getFirst().getKeyDerivation().getDerivationPath());
            Assertions.assertNull(wallet.getChildWallet(ScriptType.MWEB), "Dogecoin has no MWEB");

            Coin inner = Coin.beginScope(Coin.DOGECOIN);
            try {
                Assertions.assertTrue(wallet.getAddress(wallet.getFreshNode(KeyPurpose.RECEIVE)).toString().startsWith("D"));
            } finally {
                Coin.endScope(inner);
            }
        } finally {
            Coin.endScope(previous);
        }
    }
}
