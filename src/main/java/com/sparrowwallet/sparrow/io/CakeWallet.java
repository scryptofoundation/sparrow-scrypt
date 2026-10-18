package com.sparrowwallet.sparrow.io;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.crypto.ChildNumber;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a Cake Wallet Litecoin or Dogecoin wallet from its recovery phrase, using the seed type and
 * derivation path Cake Wallet itself uses so the user never has to know them:
 * <ul>
 *   <li>Litecoin, BIP39 seed (Cake's default): Native Segwit at m/84'/2'/0'</li>
 *   <li>Litecoin, Electrum seed (Cake's alternative): Native Segwit at m/0'</li>
 *   <li>Dogecoin, BIP39 seed: Legacy P2PKH at m/44'/3'/0'</li>
 * </ul>
 * Cake Wallet's MWEB keys are derived from the same seed bytes at m/1000' (scan = /0', spend = /1'), which is
 * exactly what {@link Wallet#addMwebChildWallet()} derives, so a Litecoin restore can bring the Private (MWEB)
 * balance across as the same-seed Private sub-wallet. Cake multi-account wallets (account 1 and up) are not
 * covered: Cake numbers them m/84'/2'/1' and so on, which the standard Add Account flow restores afterwards.
 */
public class CakeWallet {
    public static final List<Coin> COINS = List.of(Coin.LITECOIN, Coin.DOGECOIN);

    /**
     * Electrum is tested first: a BIP39 phrase passes Electrum's seed-version HMAC check about once in 4096,
     * while an Electrum phrase passes the BIP39 checksum about once in 16, so the reverse order would
     * misread real Electrum seeds as BIP39.
     */
    public static DeterministicSeed.Type detectSeedType(List<String> words) {
        try {
            ElectrumMnemonicCode.INSTANCE.check(words);
            return DeterministicSeed.Type.ELECTRUM;
        } catch(Exception e) {
            //not an Electrum seed
        }

        try {
            Bip39MnemonicCode.INSTANCE.check(words);
            return DeterministicSeed.Type.BIP39;
        } catch(Exception e) {
            return null;
        }
    }

    /**
     * Builds the wallet under an explicit scope for the chosen coin: default derivations, xpub headers and the
     * MWEB child all resolve the active coin, and on the FX thread that would otherwise be whichever wallet
     * tab happens to be focused.
     */
    public static Wallet createWallet(Coin coin, DeterministicSeed.Type seedType, List<String> words, String passphrase, boolean includeMweb) throws MnemonicException {
        Coin previousCoin = Coin.beginScope(coin);
        try {
            ScriptType scriptType;
            List<ChildNumber> derivation;
            if(coin == Coin.DOGECOIN) {
                scriptType = ScriptType.P2PKH;
                derivation = ScriptType.P2PKH.getDefaultDerivation();
            } else if(seedType == DeterministicSeed.Type.ELECTRUM) {
                scriptType = ScriptType.P2WPKH;
                derivation = List.of(new ChildNumber(0, true));
            } else {
                scriptType = ScriptType.P2WPKH;
                derivation = ScriptType.P2WPKH.getDefaultDerivation();
            }

            DeterministicSeed seed = new DeterministicSeed(new ArrayList<>(words), passphrase == null ? "" : passphrase, 0, seedType);
            Keystore keystore = Keystore.fromSeed(seed, derivation);
            keystore.setLabel("Cake Wallet");

            Wallet wallet = new Wallet("Cake Wallet " + coin.toDisplayString());
            wallet.setCoin(coin);
            wallet.setPolicyType(PolicyType.SINGLE);
            wallet.setScriptType(scriptType);
            wallet.getKeystores().add(keystore);
            wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE, scriptType, wallet.getKeystores(), 1));

            if(includeMweb && coin.isMwebSupported()) {
                wallet.addMwebChildWallet();
                //Matches Settings > Enable MWEB: the transparent master reads as the Public side
                wallet.setLabel("Public");
            }

            return wallet;
        } finally {
            Coin.endScope(previousCoin);
        }
    }
}
