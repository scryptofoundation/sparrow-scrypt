package com.sparrowwallet.sparrow.wallet.collectibles;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.ordinals.Inscription;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.DeterministicSeed;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.Wallet;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

public class MintInscriptionDialogTest {
    private static final List<String> WORDS = List.of("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" "));

    private static Wallet wallet(Coin coin, ScriptType scriptType) throws Exception {
        Coin previous = Coin.beginScope(coin);
        try {
            DeterministicSeed seed = new DeterministicSeed(new ArrayList<>(WORDS), "", 0, DeterministicSeed.Type.BIP39);
            Wallet wallet = new Wallet(coin.getName());
            wallet.setCoin(coin);
            wallet.setPolicyType(PolicyType.SINGLE_HD);
            wallet.setScriptType(scriptType);
            wallet.getKeystores().add(Keystore.fromSeed(seed, PolicyType.SINGLE_HD, scriptType.getDefaultDerivation()));
            wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE_HD, scriptType, wallet.getKeystores(), 1));
            return wallet;
        } finally {
            Coin.endScope(previous);
        }
    }

    private static String address(Wallet wallet) {
        Coin previous = Coin.beginScope(wallet.getCoin());
        try {
            return wallet.getFreshNode(KeyPurpose.RECEIVE).getAddress().toString();
        } finally {
            Coin.endScope(previous);
        }
    }

    @Test
    public void doginalsCapIsTwoMegabytes() {
        for(Coin coin : List.of(Coin.DOGECOIN, Coin.PEPECOIN, Coin.JUNKCOIN)) {
            Assertions.assertEquals(2_000_000, MintInscriptionDialog.maxBody(coin, "image/png"), coin.toString());
            Assertions.assertEquals(2_000_000, MintInscriptionDialog.maxBody(coin, Inscription.TEXT), coin.toString());
        }
    }

    @Test
    public void bellscoinCapFollowsRevealWeight() {
        Assertions.assertEquals(Inscription.maxBodyBytes("image/png"), MintInscriptionDialog.maxBody(Coin.BELLSCOIN, "image/png"));
        Assertions.assertEquals(Inscription.maxBodyBytes("image/png"), MintInscriptionDialog.maxBody(Coin.LITECOIN, "image/png"));
    }

    @Test
    public void feeUnitPerCoin() {
        Assertions.assertEquals("sats/vB", MintInscriptionDialog.feeUnit(Coin.DOGECOIN));
        Assertions.assertEquals("sats/vB", MintInscriptionDialog.feeUnit(Coin.BELLSCOIN));
        Assertions.assertEquals("lits/vB", MintInscriptionDialog.feeUnit(Coin.LITECOIN));
    }

    @Test
    public void destinationOfAnotherCoinRefused() throws Exception {
        Wallet doge = wallet(Coin.DOGECOIN, ScriptType.P2PKH);
        Wallet ltc = wallet(Coin.LITECOIN, ScriptType.P2WPKH);
        String dogeAddress = address(doge);
        //An address prints with the prefix of the coin in scope, so compare under Dogecoin's
        Coin previous = Coin.beginScope(Coin.DOGECOIN);
        try {
            Assertions.assertEquals(dogeAddress, MintInscriptionDialog.destination(doge, " " + dogeAddress + " ").toString());
        } finally {
            Coin.endScope(previous);
        }

        IllegalArgumentException e = Assertions.assertThrows(IllegalArgumentException.class, () -> MintInscriptionDialog.destination(doge, address(ltc)));
        Assertions.assertTrue(e.getMessage().contains("Dogecoin"), e.getMessage());
        Assertions.assertThrows(IllegalArgumentException.class, () -> MintInscriptionDialog.destination(doge, ""));
        Assertions.assertThrows(IllegalArgumentException.class, () -> MintInscriptionDialog.destination(ltc, dogeAddress));
    }
}
