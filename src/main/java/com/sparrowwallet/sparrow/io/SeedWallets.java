package com.sparrowwallet.sparrow.io;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.crypto.ChildNumber;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.KeyDerivation;
import com.sparrowwallet.drongo.wallet.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Recovery-phrase imports for the coins' own wallet apps, using the derivation and any built-in BIP39 passphrase
 * each app applies, so a phrase from one of them restores the same addresses here without the user knowing either.
 * Checked against each app's source (2026-10-28): none of them holds more than one of our coins, and none uses a
 * change chain (change goes back to the sending address).
 */
public class SeedWallets {
    /**
     * One way an app derives a coin's addresses. An app with several (Nintondo's address types) lists its default first;
     * the label tells them apart in the import pane.
     */
    public record Account(Coin coin, ScriptType scriptType, String derivationPath, String hiddenPassphrase, String label) {
        public boolean hasHiddenPassphrase() {
            return !hiddenPassphrase.isEmpty();
        }
    }

    public record SeedWallet(String name, String help, List<Account> accounts) {
        public List<Coin> coins() {
            return accounts.stream().map(Account::coin).distinct().toList();
        }

        public List<Account> accountsFor(Coin coin) {
            return accounts.stream().filter(account -> account.coin() == coin).toList();
        }
    }

    public static final List<SeedWallet> ALL = List.of(
            //dingocoin/dingobrowserwallet dingocoin.js
            new SeedWallet("Dingo browser wallet", "Restores the Dingo browser wallet's Legacy (D) addresses at m/44'/3'/0'.",
                    List.of(new Account(Coin.DINGOCOIN, ScriptType.P2PKH, "m/44'/3'/0'", "", "Legacy"))),
            //nodecattel/junkiewally: junkcoinhdw falls back to "junk" when no passphrase is given, on create and restore alike
            new SeedWallet("JunkieWally", "Adds JunkieWally's built-in passphrase \"junk\" and restores Legacy (7) addresses at m/44'/0'/0'.",
                    List.of(new Account(Coin.JUNKCOIN, ScriptType.P2PKH, "m/44'/0'/0'", "junk", "Legacy"))),
            //harnodgraves/luckycoinwallet: luckycoinhdw falls back to "lky"
            new SeedWallet("luckycoinwallet", "Adds luckycoinwallet's built-in passphrase \"lky\" and restores Legacy (L) addresses at m/44'/0'/0'.",
                    List.of(new Account(Coin.LUCKYCOIN, ScriptType.P2PKH, "m/44'/0'/0'", "lky", "Legacy"))),
            //mydoge-com/mydogemask scripts/helpers/wallet.js
            new SeedWallet("MyDoge", "Restores MyDoge's Legacy (D) addresses at m/44'/3'/0'.",
                    List.of(new Account(Coin.DOGECOIN, ScriptType.P2PKH, "m/44'/3'/0'", "", "Legacy"))),
            //Nintondo/extension: bellhdw falls back to "bells", and every address type comes from the same m/44'/0'/0'/0/i
            //keys (the m/84' and m/86' paths it shows are labels only). Native Segwit is its default. Its Taproot type
            //needs Bellscoin Taproot, which is not enabled here, and its "Ordinals" restore option is not covered.
            new SeedWallet("Nintondo", "Adds Nintondo's built-in passphrase \"bells\" and restores m/44'/0'/0' with the address type the Nintondo wallet uses:\n" +
                    "Native Segwit (bel1) is Nintondo's default, Legacy (B) the other choice there. Its Taproot type is not supported yet.",
                    List.of(new Account(Coin.BELLSCOIN, ScriptType.P2WPKH, "m/44'/0'/0'", "bells", "Native Segwit (default)"),
                            new Account(Coin.BELLSCOIN, ScriptType.P2PKH, "m/44'/0'/0'", "bells", "Legacy"))),
            //mvdnbrk/peppool-wallet src/utils/crypto.ts: each Peppool account is m/44'/3434'/N'/0/0
            new SeedWallet("Peppool", "Restores the first Peppool account's Legacy (P) address at m/44'/3434'/0'.\n" +
                    "Peppool puts each further account at the next account number: add them afterwards from the wallet's Settings > Add Account.",
                    List.of(new Account(Coin.PEPECOIN, ScriptType.P2PKH, "m/44'/3434'/0'", "", "Legacy")))
    );

    /**
     * All six apps make BIP39 phrases. The import pane's base class leaves checksum checking to the subclass.
     */
    public static boolean isValidPhrase(List<String> words) {
        try {
            Bip39MnemonicCode.INSTANCE.check(words);
            return true;
        } catch(Exception e) {
            return false;
        }
    }

    /**
     * Builds the wallet under an explicit scope for the account's coin, as for {@link CakeWallet}: xpub headers and
     * address encoding resolve the active coin, which on the FX thread is whichever wallet tab happens to be focused.
     */
    public static Wallet createWallet(SeedWallet seedWallet, Account account, List<String> words, String userPassphrase) throws MnemonicException {
        String passphrase = userPassphrase == null ? "" : userPassphrase;
        if(account.hasHiddenPassphrase() && !passphrase.isEmpty()) {
            throw new IllegalArgumentException("This wallet adds its own passphrase and does not take another one.");
        }

        Coin previousCoin = Coin.beginScope(account.coin());
        try {
            List<ChildNumber> derivation = KeyDerivation.parsePath(account.derivationPath());
            DeterministicSeed seed = new DeterministicSeed(new ArrayList<>(words), account.hasHiddenPassphrase() ? account.hiddenPassphrase() : passphrase, 0, DeterministicSeed.Type.BIP39);
            Keystore keystore = Keystore.fromSeed(seed, derivation);
            keystore.setLabel(seedWallet.name());

            Wallet wallet = new Wallet(seedWallet.name() + " " + account.coin().toDisplayString());
            wallet.setCoin(account.coin());
            wallet.setPolicyType(PolicyType.SINGLE);
            wallet.setScriptType(account.scriptType());
            wallet.getKeystores().add(keystore);
            wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE, account.scriptType(), wallet.getKeystores(), 1));
            return wallet;
        } finally {
            Coin.endScope(previousCoin);
        }
    }
}
