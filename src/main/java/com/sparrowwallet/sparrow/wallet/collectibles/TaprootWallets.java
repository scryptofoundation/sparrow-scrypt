package com.sparrowwallet.sparrow.wallet.collectibles;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.KeystoreSource;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.wallet.SameSeedChildWallets;
import com.sparrowwallet.sparrow.wallet.WalletForm;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The seed's taproot wallet, where inscriptions and runes land (spec: "Following through to the taproot wallet"). It
 * is the open wallet when that is taproot, else the master's Taproot child, else another open taproot wallet of the
 * same seed; when there is none, it is created as a Taproot child of the master after the user agrees.
 */
public final class TaprootWallets {
    private TaprootWallets() {}

    public static Optional<Wallet> find(Wallet open, Collection<Wallet> openWallets) {
        if(isTaproot(open)) {
            return Optional.of(open);
        }
        Wallet master = open.isMasterWallet() ? open : open.getMasterWallet();
        Wallet child = master.getTaprootChildWallet();
        if(child != null) {
            return Optional.of(child);
        }
        String fingerprint = fingerprint(master);
        return openWallets.stream().filter(TaprootWallets::isTaproot)
                .filter(wallet -> fingerprint != null && fingerprint.equals(fingerprint(wallet))).findFirst();
    }

    /** Finds the taproot wallet, or offers to create it, then hands it to then (on the FX thread). */
    public static void ensure(WalletForm walletForm, Window owner, Consumer<Wallet> then) {
        Wallet open = walletForm.getWallet();
        Optional<Wallet> found = find(open, AppServices.get().getOpenWallets().keySet());
        if(found.isPresent()) {
            then.accept(found.get());
            return;
        }
        Wallet master = open.isMasterWallet() ? open : open.getMasterWallet();
        boolean seedWallet = master.getPolicyType() == PolicyType.SINGLE && master.getKeystores().stream().allMatch(keystore -> keystore.getSource() == KeystoreSource.SW_SEED);
        if(!seedWallet || master.getScriptType() == ScriptType.MWEB) {
            AppServices.showAlertDialog("No taproot wallet", "Inscriptions and runes are kept in a taproot wallet of this seed. This wallet cannot create one "
                    + "(it needs a single-signature wallet with its seed in this app). Open or create a taproot wallet of the same seed and try again.", Alert.AlertType.WARNING, ButtonType.OK);
            return;
        }
        Optional<ButtonType> answer = AppServices.showAlertDialog("Create Taproot wallet?", "Inscriptions and runes are kept in this seed's taproot wallet (m/86'/2'/0'). "
                + "Create it now as a Taproot view of " + master.getFullDisplayName() + "?", Alert.AlertType.CONFIRMATION, ButtonType.CANCEL, ButtonType.OK);
        if(answer.isEmpty() || answer.get() != ButtonType.OK) {
            return;
        }
        SameSeedChildWallets.add(walletForm, master, owner, Wallet::addTaprootChildWallet, then, () -> ensure(walletForm, owner, then));
    }

    private static boolean isTaproot(Wallet wallet) {
        return wallet != null && wallet.getScriptType() == ScriptType.P2TR && wallet.getPolicyType() == PolicyType.SINGLE && wallet.getCoin() == Coin.LITECOIN;
    }

    private static String fingerprint(Wallet wallet) {
        return wallet.getKeystores().stream().map(Keystore::getKeyDerivation).filter(Objects::nonNull).map(derivation -> derivation.getMasterFingerprint())
                .findFirst().orElse(null);
    }
}
