package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.SecureString;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.crypto.EncryptionType;
import com.sparrowwallet.drongo.crypto.Key;
import com.sparrowwallet.drongo.evm.EvmAccount;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.*;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.event.WalletLitVmChangedEvent;
import com.sparrowwallet.sparrow.io.Storage;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Links a LitVM account to a Litecoin master wallet, MWEB-style: once linked the wallet shows a LitVM sub-tab.
 * The account is either the wallet's own seed at m/44'/60'/0'/0/0 (the MetaMask account for the same words) or
 * a separate account entered as seed words or a private key, whose secret is encrypted with the wallet password.
 */
public class LitVmLinkDialog extends Dialog<LitVmLinkDialog.Choice> {
    private static final Logger log = LoggerFactory.getLogger(LitVmLinkDialog.class);

    public record Choice(LitVmLink.Source source, String secret) {}

    public LitVmLinkDialog(Wallet masterWallet) {
        setTitle("Link LitVM");
        DialogPane pane = getDialogPane();
        AppServices.setStageIcon(pane.getScene().getWindow());
        pane.getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);

        Label header = new Label("Choose the LitVM account to show under " + masterWallet.getFullDisplayName() + ".\n"
                + "Only the LiteForge testnet (chain 4441) is available; test zkLTC has no value.");
        header.setWrapText(true);

        ToggleGroup group = new ToggleGroup();
        RadioButton walletSeed = new RadioButton(LitVmLink.Source.WALLET_SEED.getDisplayName() + " (" + EvmAccount.DERIVATION_PREFIX + "0, same address as MetaMask for these words)");
        RadioButton mnemonic = new RadioButton(LitVmLink.Source.MNEMONIC.getDisplayName() + ", e.g. a MetaMask recovery phrase");
        RadioButton privateKey = new RadioButton(LitVmLink.Source.PRIVATE_KEY.getDisplayName() + ", e.g. a MetaMask exported key");
        walletSeed.setToggleGroup(group);
        mnemonic.setToggleGroup(group);
        privateKey.setToggleGroup(group);

        boolean hasSeed = hasWalletSeed(masterWallet);
        walletSeed.setDisable(!hasSeed);
        group.selectToggle(hasSeed ? walletSeed : mnemonic);

        TextArea secretArea = new TextArea();
        secretArea.setWrapText(true);
        secretArea.setPrefRowCount(3);
        secretArea.setPrefColumnCount(48);
        Label secretNote = new Label("Stored in this wallet file, encrypted with the wallet password when it has one.");
        secretNote.setWrapText(true);
        secretNote.setStyle("-fx-opacity: 0.7;");

        Runnable update = () -> {
            boolean separate = group.getSelectedToggle() != walletSeed;
            secretArea.setVisible(separate);
            secretArea.setManaged(separate);
            secretNote.setVisible(separate);
            secretNote.setManaged(separate);
            secretArea.setPromptText(group.getSelectedToggle() == privateKey ? "0x... (64 hex characters)" : "12 or 24 seed words");
        };
        group.selectedToggleProperty().addListener((observable, oldValue, newValue) -> update.run());
        update.run();

        VBox content = new VBox(10, header, walletSeed, mnemonic, privateKey, secretArea, secretNote);
        content.setPadding(new Insets(16));
        content.setPrefWidth(560);
        pane.setContent(content);

        setResultConverter(buttonType -> {
            if(buttonType != ButtonType.OK) {
                return null;
            }
            LitVmLink.Source source = group.getSelectedToggle() == walletSeed ? LitVmLink.Source.WALLET_SEED
                    : (group.getSelectedToggle() == mnemonic ? LitVmLink.Source.MNEMONIC : LitVmLink.Source.PRIVATE_KEY);
            String secret = secretArea.getText();
            secretArea.clear();
            return new Choice(source, secret);
        });
    }

    /** Litecoin master wallets other than MWEB ones can carry a LitVM link. */
    public static boolean canLink(Wallet wallet) {
        Wallet masterWallet = wallet.isMasterWallet() ? wallet : wallet.getMasterWallet();
        return masterWallet.isValid() && masterWallet.getCoin() == com.sparrowwallet.drongo.Coin.LITECOIN
                && masterWallet.getScriptType() != ScriptType.MWEB;
    }

    private static boolean hasWalletSeed(Wallet masterWallet) {
        return masterWallet.getKeystores().size() == 1 && masterWallet.getKeystores().get(0).getSource() == KeystoreSource.SW_SEED
                && masterWallet.getKeystores().get(0).hasSeed();
    }

    /** Shows the dialog and links the chosen account to the master wallet, asking for the wallet password when needed. */
    public static void link(Wallet masterWallet, Storage storage, Window owner) {
        LitVmLinkDialog dialog = new LitVmLinkDialog(masterWallet);
        dialog.initOwner(owner);
        Optional<Choice> optChoice = dialog.showAndWait();
        if(optChoice.isEmpty()) {
            return;
        }
        Choice choice = optChoice.get();

        withWalletKey(masterWallet, storage, owner, () -> {}, key -> {
            LitVmLink link;
            try {
                link = createLink(masterWallet, choice, key);
            } catch(Exception e) {
                AppServices.showErrorDialog("Could Not Link LitVM", e.getMessage());
                return;
            }
            masterWallet.setLitVmLink(link);
            EventManager.get().post(new WalletLitVmChangedEvent(masterWallet));
        });
    }

    private static LitVmLink createLink(Wallet masterWallet, Choice choice, Key key) throws MnemonicException {
        if(choice.source() == LitVmLink.Source.WALLET_SEED) {
            DeterministicSeed seed = masterWallet.getKeystores().get(0).getSeed();
            DeterministicSeed decrypted = seed.isEncrypted() ? seed.decrypt(key) : seed;
            try {
                return LitVmLink.fromWalletSeed(decrypted, 0);
            } finally {
                if(decrypted != seed) {
                    decrypted.clear();
                }
            }
        }

        LitVmLink link = choice.source() == LitVmLink.Source.MNEMONIC ? LitVmLink.fromMnemonic(choice.secret(), 0) : LitVmLink.fromPrivateKey(choice.secret());
        if(key == null) {
            return link;
        }
        LitVmLink encrypted = link.encrypt(key);
        link.clear();
        return encrypted;
    }

    public static void unlink(Wallet masterWallet, Window owner) {
        LitVmLink link = masterWallet.getLitVmLink();
        if(link == null) {
            return;
        }

        String detail = link.isSeparateAccount() ? "The separate account's " + (link.getSource() == LitVmLink.Source.MNEMONIC ? "seed words are" : "private key is")
                + " removed from this wallet file. Make sure you have them elsewhere (e.g. MetaMask) before unlinking." : "The account can be linked again at any time from this wallet's seed.";
        Optional<ButtonType> answer = AppServices.showWarningDialog("Unlink LitVM?", "Remove the LitVM sub-tab for " + link.getAddress() + "?\n\n" + detail, ButtonType.CANCEL, ButtonType.OK);
        if(answer.isPresent() && answer.get() == ButtonType.OK) {
            link.clear();
            masterWallet.setLitVmLink(null);
            EventManager.get().post(new WalletLitVmChangedEvent(masterWallet));
        }
    }

    /**
     * Supplies the signing account for the wallet's LitVM link, asking for the wallet password when the seed or the
     * separate secret is encrypted. The account holds the private key, so the consumer should sign and drop it.
     * onAbort runs instead when the password is cancelled or wrong, or the account cannot be unlocked.
     */
    public static void withAccount(Wallet masterWallet, Storage storage, Window owner, Consumer<EvmAccount> consumer, Runnable onAbort) {
        LitVmLink link = masterWallet.getLitVmLink();
        if(link == null) {
            onAbort.run();
            return;
        }

        withWalletKey(masterWallet, storage, owner, onAbort, key -> {
            DeterministicSeed decryptedSeed = null;
            LitVmLink decryptedLink = null;
            EvmAccount account;
            try {
                if(link.getSource() == LitVmLink.Source.WALLET_SEED) {
                    DeterministicSeed seed = masterWallet.getKeystores().get(0).getSeed();
                    decryptedSeed = seed.isEncrypted() ? seed.decrypt(key) : seed.copy();
                    account = link.getAccount(decryptedSeed);
                } else {
                    decryptedLink = link.isEncrypted() ? link.decrypt(key) : link.copy();
                    account = decryptedLink.getAccount(null);
                }
            } catch(Exception e) {
                log.error("Could not unlock the LitVM account", e);
                AppServices.showErrorDialog("Could Not Unlock LitVM Account", e.getMessage());
                onAbort.run();
                return;
            } finally {
                if(decryptedSeed != null) {
                    decryptedSeed.clear();
                }
                if(decryptedLink != null) {
                    decryptedLink.clear();
                }
            }
            consumer.accept(account);
        });
    }

    /**
     * Runs the action with the wallet's encryption key when the wallet has a password, or with null when it does not.
     * The key is cleared afterwards. onAbort runs instead if the password is cancelled or wrong.
     */
    private static void withWalletKey(Wallet masterWallet, Storage storage, Window owner, Runnable onAbort, Consumer<Key> action) {
        boolean encrypted;
        try {
            encrypted = masterWallet.isEncrypted() || storage.isEncrypted();
        } catch(IOException e) {
            AppServices.showErrorDialog("Could Not Read Wallet", e.getMessage());
            onAbort.run();
            return;
        }

        if(!encrypted) {
            action.accept(null);
            return;
        }

        WalletPasswordDialog dlg = new WalletPasswordDialog(masterWallet.getMasterName(), WalletPasswordDialog.PasswordRequirement.LOAD);
        dlg.initOwner(owner);
        Optional<SecureString> password = dlg.showAndWait();
        if(password.isEmpty()) {
            onAbort.run();
            return;
        }

        Storage.KeyDerivationService keyDerivationService = new Storage.KeyDerivationService(storage, password.get(), true);
        keyDerivationService.setOnSucceeded(workerStateEvent -> {
            ECKey encryptionFullKey = keyDerivationService.getValue();
            Key key = new Key(encryptionFullKey.getPrivKeyBytes(), storage.getKeyDeriver().getSalt(), EncryptionType.Deriver.ARGON2);
            encryptionFullKey.clear();
            try {
                action.accept(key);
            } finally {
                key.clear();
            }
        });
        keyDerivationService.setOnFailed(workerStateEvent -> {
            AppServices.showErrorDialog("Incorrect Password", "The wallet password was incorrect.");
            onAbort.run();
        });
        keyDerivationService.start();
    }
}
