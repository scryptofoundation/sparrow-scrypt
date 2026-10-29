package com.sparrowwallet.sparrow.wallet;

import com.sparrowwallet.drongo.SecureString;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.crypto.EncryptionType;
import com.sparrowwallet.drongo.crypto.InvalidPasswordException;
import com.sparrowwallet.drongo.crypto.Key;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.control.WalletPasswordDialog;
import com.sparrowwallet.sparrow.event.ChildWalletsAddedEvent;
import com.sparrowwallet.sparrow.event.NewChildWalletSavedEvent;
import com.sparrowwallet.sparrow.event.StorageEvent;
import com.sparrowwallet.sparrow.event.TimedEvent;
import com.sparrowwallet.sparrow.io.Storage;
import javafx.application.Platform;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Adds a child wallet derived from the master wallet's own seed (the MWEB Private account, the Taproot account): asks
 * for the password when the wallet is encrypted, derives the child while decrypted, shows it as a new tab, saves it,
 * and encrypts everything again.
 */
public final class SameSeedChildWallets {
    private static final Logger log = LoggerFactory.getLogger(SameSeedChildWallets.class);

    private SameSeedChildWallets() {}

    /**
     * @param adder derives and attaches the child to the (decrypted) master, returning it
     * @param onAdded runs on the FX thread once the child is saved and the master is encrypted again
     * @param retry runs when the user wants to try again after a wrong password
     */
    public static void add(WalletForm walletForm, Wallet masterWallet, Window owner, Function<Wallet, Wallet> adder, Consumer<Wallet> onAdded, Runnable retry) {
        if(!masterWallet.isEncrypted()) {
            Wallet childWallet = derive(masterWallet, adder);
            if(childWallet == null) {
                return;
            }
            EventManager.get().post(new ChildWalletsAddedEvent(walletForm.getStorage(), masterWallet, childWallet));
            saveChildWallets(masterWallet);
            onAdded.accept(childWallet);
            return;
        }

        String walletId = walletForm.getWalletId();
        WalletPasswordDialog dlg = new WalletPasswordDialog(masterWallet.getName(), WalletPasswordDialog.PasswordRequirement.LOAD);
        dlg.initOwner(owner);
        Optional<SecureString> password = dlg.showAndWait();
        if(password.isEmpty()) {
            return;
        }
        Storage.KeyDerivationService keyDerivationService = new Storage.KeyDerivationService(walletForm.getStorage(), password.get(), true);
        keyDerivationService.setOnSucceeded(workerStateEvent -> {
            EventManager.get().post(new StorageEvent(walletId, TimedEvent.Action.END, "Done"));
            ECKey encryptionFullKey = keyDerivationService.getValue();
            Key key = new Key(encryptionFullKey.getPrivKeyBytes(), walletForm.getStorage().getKeyDeriver().getSalt(), EncryptionType.Deriver.ARGON2);
            masterWallet.decrypt(key);
            Wallet childWallet;
            try {
                childWallet = derive(masterWallet, adder);
                if(childWallet == null) {
                    return;
                }
                EventManager.get().post(new ChildWalletsAddedEvent(walletForm.getStorage(), masterWallet, childWallet));
                childWallet.encrypt(key);
                saveChildWallets(masterWallet);
            } finally {
                masterWallet.encrypt(key);
                key.clear();
            }
            onAdded.accept(childWallet);
        });
        keyDerivationService.setOnFailed(workerStateEvent -> {
            EventManager.get().post(new StorageEvent(walletId, TimedEvent.Action.END, "Failed"));
            if(keyDerivationService.getException() instanceof InvalidPasswordException) {
                Optional<ButtonType> optResponse = AppServices.showErrorDialog("Invalid Password", "The wallet password was invalid. Try again?", ButtonType.CANCEL, ButtonType.OK);
                if(optResponse.isPresent() && optResponse.get().equals(ButtonType.OK)) {
                    Platform.runLater(retry);
                }
            } else {
                log.error("Error deriving wallet key", keyDerivationService.getException());
            }
        });
        EventManager.get().post(new StorageEvent(walletId, TimedEvent.Action.START, "Decrypting wallet..."));
        keyDerivationService.start();
    }

    /** The new child, or null after telling the user why it could not be derived. */
    private static Wallet derive(Wallet masterWallet, Function<Wallet, Wallet> adder) {
        try {
            return adder.apply(masterWallet);
        } catch(RuntimeException e) {
            log.error("Error adding child wallet", e);
            AppServices.showErrorDialog("Cannot add wallet", e.getMessage());
            return null;
        }
    }

    public static void saveChildWallets(Wallet masterWallet) {
        for(Wallet childWallet : masterWallet.getChildWallets()) {
            if(!childWallet.isNested()) {
                Storage storage = AppServices.get().getOpenWallets().get(childWallet);
                if(!storage.isPersisted(childWallet)) {
                    try {
                        storage.saveWallet(childWallet);
                        EventManager.get().post(new NewChildWalletSavedEvent(storage, masterWallet, childWallet));
                    } catch(Exception e) {
                        log.error("Error saving wallet", e);
                        AppServices.showErrorDialog("Error saving wallet " + childWallet.getName(), e.getMessage());
                    }
                }
            }
        }
    }
}
