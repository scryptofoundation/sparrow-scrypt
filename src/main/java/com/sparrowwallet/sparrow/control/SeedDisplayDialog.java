package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.OsType;
import com.sparrowwallet.drongo.SecureString;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.wallet.DeterministicSeed;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.SeedQR;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.io.SeedBackup;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.scene.control.*;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;

public class SeedDisplayDialog extends Dialog<Void> {
    private static final Logger log = LoggerFactory.getLogger(SeedDisplayDialog.class);

    public SeedDisplayDialog(Keystore decryptedKeystore) {
        this(decryptedKeystore, null);
    }

    /**
     * @param wallet the wallet the keystore belongs to, or null. When given and it is a single signature seed
     *               wallet, the dialog offers an encrypted seed backup of it (via its master wallet, so a backup
     *               started from the MWEB Private tab describes the whole wallet).
     */
    public SeedDisplayDialog(Keystore decryptedKeystore, Wallet wallet) {
        final DialogPane dialogPane = getDialogPane();
        dialogPane.getStylesheets().add(AppServices.class.getResource("general.css").toExternalForm());
        AppServices.setStageIcon(dialogPane.getScene().getWindow());
        setTitle("Seed Words");

        int lines = Math.ceilDiv(decryptedKeystore.getSeed().getMnemonicCode().size(), 3);
        int height = lines * 40;

        StackPane stackPane = new StackPane();
        dialogPane.setContent(stackPane);

        AnchorPane anchorPane = new AnchorPane();
        ScrollPane scrollPane = new ScrollPane();
        scrollPane.getStyleClass().add("edge-to-edge");
        scrollPane.setPrefHeight(74 + height);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        anchorPane.getChildren().add(scrollPane);
        scrollPane.setFitToWidth(true);
        AnchorPane.setLeftAnchor(scrollPane, 0.0);
        AnchorPane.setRightAnchor(scrollPane, 0.0);

        Accordion keystoreAccordion = new Accordion();
        scrollPane.setContent(keystoreAccordion);

        MnemonicKeystoreDisplayPane keystorePane = new MnemonicKeystoreDisplayPane(decryptedKeystore);
        keystorePane.setAnimated(false);
        keystoreAccordion.getPanes().add(keystorePane);

        stackPane.getChildren().addAll(anchorPane);

        if(decryptedKeystore.getSeed().getType() == DeterministicSeed.Type.BIP39) {
            final ButtonType seedQRButtonType = new javafx.scene.control.ButtonType("Show SeedQR", ButtonBar.ButtonData.LEFT);
            dialogPane.getButtonTypes().add(seedQRButtonType);

            Button seedQRButton = (Button)dialogPane.lookupButton(seedQRButtonType);
            seedQRButton.addEventFilter(ActionEvent.ACTION, event -> {
                event.consume();
                showSeedQR(decryptedKeystore);
            });
        }

        Wallet masterWallet = wallet == null ? null : (wallet.isMasterWallet() ? wallet : wallet.getMasterWallet());
        DeterministicSeed.Type seedType = decryptedKeystore.getSeed().getType();
        if(masterWallet != null && masterWallet.getPolicyType() == PolicyType.SINGLE_HD && masterWallet.getKeystores().size() == 1
                && (seedType == DeterministicSeed.Type.BIP39 || seedType == DeterministicSeed.Type.ELECTRUM)) {
            final ButtonType backupButtonType = new javafx.scene.control.ButtonType("Save Encrypted Backup...", ButtonBar.ButtonData.LEFT);
            dialogPane.getButtonTypes().add(backupButtonType);

            Button backupButton = (Button)dialogPane.lookupButton(backupButtonType);
            backupButton.addEventFilter(ActionEvent.ACTION, event -> {
                event.consume();
                saveBackup(decryptedKeystore, masterWallet, backupButton);
            });
        }

        final ButtonType cancelButtonType = new javafx.scene.control.ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialogPane.getButtonTypes().add(cancelButtonType);

        dialogPane.setPrefWidth(500);
        dialogPane.setPrefHeight(150 + height);
        AppServices.moveToActiveWindowScreen(this);

        Platform.runLater(() -> keystoreAccordion.setExpandedPane(keystorePane));
    }

    private void saveBackup(Keystore decryptedKeystore, Wallet masterWallet, Button backupButton) {
        WalletPasswordDialog passwordDialog = new WalletPasswordDialog(null, WalletPasswordDialog.PasswordRequirement.BACKUP_NEW);
        passwordDialog.initOwner(getDialogPane().getScene().getWindow());
        Optional<SecureString> optPassword = passwordDialog.showAndWait();
        if(optPassword.isEmpty()) {
            return;
        }
        SecureString password = optPassword.get();

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save Encrypted Seed Backup");
        fileChooser.setInitialFileName(masterWallet.getName() + "." + SeedBackup.FILE_EXTENSION);
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Encrypted Seed Backup", "*." + SeedBackup.FILE_EXTENSION));
        File file = fileChooser.showSaveDialog(getDialogPane().getScene().getWindow());
        if(file == null) {
            password.clear();
            return;
        }

        //Argon2 at wallet strength takes a few seconds (twice: encrypt, then verify), so work off the FX thread, on
        //a copy of the keystore so the worker never shares state with the dialog's. The coin is read here because
        //the worker has no coin scope of its own.
        Keystore keystoreCopy = decryptedKeystore.copy();
        Coin coin = masterWallet.getCoin();
        backupButton.setDisable(true);
        Task<byte[]> backupTask = new Task<>() {
            @Override
            protected byte[] call() throws Exception {
                Coin previousCoin = Coin.beginScope(coin);
                try {
                    byte[] backup = SeedBackup.createBackup(masterWallet, keystoreCopy, password);
                    SeedBackup.verifyBackup(backup, password);
                    return backup;
                } finally {
                    Coin.endScope(previousCoin);
                    password.clear();
                    keystoreCopy.clearPrivate();
                }
            }
        };
        backupTask.setOnSucceeded(event -> {
            backupButton.setDisable(false);
            try {
                writeOwnerOnly(file, backupTask.getValue());
                AppServices.showSuccessDialog("Backup Saved", "The encrypted seed backup was saved and checked: it opens with this password and restores this wallet's keys.\n\n" +
                        file.getAbsolutePath() + "\n\n" +
                        "Keep the backup password somewhere safe and separate from the file. Without it the backup cannot be opened, " +
                        "and anyone who has both can take the funds.\n\n" +
                        "It covers this wallet's seed, including its Private (MWEB) side if enabled. Extra accounts are re-added from Settings after restoring, " +
                        "and a separately seeded linked Private wallet needs its own backup.\n\nTo restore, use File > Import Wallet > Encrypted Seed Backup.");
            } catch(IOException e) {
                log.error("Error writing seed backup", e);
                AppServices.showErrorDialog("Backup Failed", "Could not write " + file.getAbsolutePath() + ": " + e.getMessage());
            }
        });
        backupTask.setOnFailed(event -> {
            backupButton.setDisable(false);
            log.error("Error creating seed backup", backupTask.getException());
            AppServices.showErrorDialog("Backup Failed", backupTask.getException().getMessage());
        });
        Thread thread = new Thread(backupTask, "SeedBackupThread");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Writes to a temporary file beside the target and moves it into place, so an existing backup is never left
     * truncated by a failed write. On POSIX the file is created owner-only from the start; on Windows it inherits
     * the folder's permissions.
     */
    private static void writeOwnerOnly(File file, byte[] contents) throws IOException {
        java.nio.file.Path target = file.toPath().toAbsolutePath();
        java.nio.file.Path temp;
        if(OsType.getCurrent() != OsType.WINDOWS && target.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            temp = Files.createTempFile(target.getParent(), ".seedbackup", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            temp = Files.createTempFile(target.getParent(), ".seedbackup", ".tmp");
        }

        try {
            try(java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(contents);
                while(buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch(java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void showSeedQR(Keystore decryptedKeystore) {
        Optional<ButtonType> optButtonType = AppServices.showWarningDialog("Sensitive QR", "The following QR contains these seed words. " +
                "Be careful before displaying or digitally recording it.\n\nAre you sure you want to continue?", ButtonType.YES, ButtonType.NO);
        if(optButtonType.isPresent() && optButtonType.get() == ButtonType.YES) {
            String seedQR = SeedQR.getSeedQR(decryptedKeystore.getSeed());
            QRDisplayDialog qrDisplayDialog = new QRDisplayDialog(seedQR);
            qrDisplayDialog.initOwner(getDialogPane().getScene().getWindow());
            qrDisplayDialog.showAndWait();
        }
    }
}
