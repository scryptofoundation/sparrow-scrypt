package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.evm.*;
import com.sparrowwallet.drongo.wallet.LitVmLink;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.wallet.WalletForm;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Content of the LitVM sub-tab under a Litecoin wallet: the linked account's address, zkLTC balance and plain
 * zkLTC transfers on the LiteForge testnet. The address comes from the stored link, so viewing needs no password;
 * sending asks for the wallet password (when it has one) to unlock the key just long enough to sign.
 */
public class LitVmPane extends BorderPane {
    private static final String FAUCET_URL = "https://testnet.litvm.com/";

    private final WalletForm walletForm;
    private final LitVmRpcClient client = new LitVmRpcClient(LitVmNetwork.LITEFORGE_TESTNET);
    private final LitVmNetwork network = client.getNetwork();

    private final Label balanceLabel = new Label("Loading...");
    private final Label statusLabel = new Label();
    private final TextField toField = new TextField();
    private final TextField amountField = new TextField();
    private final Button sendButton = new Button("Send");

    public LitVmPane(WalletForm walletForm) {
        this.walletForm = walletForm;
        LitVmLink link = getMasterWallet().getLitVmLink();
        EvmAddress address = link.getAddress();

        Label title = new Label("LitVM");
        title.setStyle("-fx-font-size: 1.4em;");
        Label warning = new Label(network.getDisplayName() + " (chain " + network.getChainId() + "). Test " + network.getSymbol() + " has no value.");
        warning.setStyle("-fx-font-weight: bold;");

        TextField addressField = new TextField(address.toString());
        addressField.setEditable(false);
        addressField.setPrefColumnCount(34);
        Button copyButton = new Button("Copy");
        copyButton.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(address.toString());
            Clipboard.getSystemClipboard().setContent(content);
        });
        Hyperlink explorerLink = link("View in explorer", network.explorerAddressUrl(address));
        Hyperlink faucetLink = link("Get free test " + network.getSymbol(), FAUCET_URL);

        String accountText = link.getSource().getDisplayName() + (link.getDerivationPath() == null ? "" : " at " + link.getDerivationPath());

        Button refreshButton = new Button("Refresh");
        refreshButton.setOnAction(e -> refreshBalance(address));

        Button unlinkButton = new Button("Unlink LitVM...");
        unlinkButton.setOnAction(e -> LitVmLinkDialog.unlink(getMasterWallet(), getScene().getWindow()));

        toField.setPromptText("0x...");
        amountField.setPromptText("0.0");
        sendButton.setOnAction(e -> send());

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.add(new Label("Address"), 0, 0);
        grid.add(new HBox(8, addressField, copyButton), 1, 0);
        grid.add(new Label("Account"), 0, 1);
        grid.add(new Label(accountText), 1, 1);
        grid.add(new Label("Balance"), 0, 2);
        grid.add(new HBox(10, balanceLabel, refreshButton), 1, 2);
        grid.add(new HBox(16, explorerLink, faucetLink), 1, 3);
        grid.add(new Separator(), 0, 4, 2, 1);
        grid.add(new Label("Send to"), 0, 5);
        grid.add(toField, 1, 5);
        grid.add(new Label("Amount"), 0, 6);
        grid.add(new HBox(8, amountField, new Label(network.getSymbol()), sendButton), 1, 6);

        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(560);
        VBox content = new VBox(14, title, warning, grid, statusLabel, unlinkButton);
        content.setPadding(new Insets(20, 25, 20, 25));
        setCenter(new ScrollPane(content));
        ((ScrollPane)getCenter()).setFitToWidth(true);

        refreshBalance(address);
    }

    private Wallet getMasterWallet() {
        Wallet wallet = walletForm.getWallet();
        return wallet.isMasterWallet() ? wallet : wallet.getMasterWallet();
    }

    private Hyperlink link(String text, String url) {
        Hyperlink link = new Hyperlink(text);
        link.setOnAction(e -> AppServices.get().getApplication().getHostServices().showDocument(url));
        return link;
    }

    private void refreshBalance(EvmAddress address) {
        balanceLabel.setText("Loading...");
        Task<BigInteger> task = new Task<>() {
            @Override
            protected BigInteger call() throws Exception {
                client.verifyChain();
                return client.getBalance(address);
            }
        };
        task.setOnSucceeded(e -> balanceLabel.setText(Wei.format(task.getValue()) + " " + network.getSymbol()));
        task.setOnFailed(e -> {
            balanceLabel.setText("Unavailable");
            setStatus("Could not reach " + network.getDisplayName() + ": " + message(task.getException()));
        });
        start(task);
    }

    private void send() {
        LitVmLink link = getMasterWallet().getLitVmLink();
        if(link == null) {
            return;
        }

        EvmAddress to;
        BigInteger value;
        try {
            to = EvmAddress.fromString(toField.getText());
        } catch(IllegalArgumentException e) {
            setStatus(e.getMessage());
            return;
        }
        try {
            value = Wei.parse(amountField.getText());
        } catch(IllegalArgumentException e) {
            setStatus(e.getMessage());
            return;
        }
        if(value.signum() == 0) {
            setStatus("Enter an amount greater than zero.");
            return;
        }

        sendButton.setDisable(true);
        setStatus("Preparing transaction...");
        Task<Eip1559Transaction> prepare = new Task<>() {
            @Override
            protected Eip1559Transaction call() throws Exception {
                client.verifyChain();
                return client.prepareTransfer(link.getAddress(), to, value);
            }
        };
        prepare.setOnSucceeded(e -> confirmAndSend(link, prepare.getValue()));
        prepare.setOnFailed(e -> {
            sendButton.setDisable(false);
            setStatus("Could not prepare the transaction: " + message(prepare.getException()));
        });
        start(prepare);
    }

    private void confirmAndSend(LitVmLink link, Eip1559Transaction tx) {
        BigInteger maxFee = tx.getGasLimit().multiply(tx.getMaxFeePerGas());
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.initOwner(getScene().getWindow());
        confirm.setTitle("Confirm LitVM Transfer");
        confirm.setHeaderText("Send " + Wei.format(tx.getValue()) + " " + network.getSymbol() + " on " + network.getDisplayName() + "?");
        confirm.setContentText("From: " + link.getAddress() + "\nTo: " + tx.getTo() + "\nAmount: " + Wei.format(tx.getValue()) + " " + network.getSymbol()
                + "\nMaximum network fee: " + Wei.format(maxFee) + " " + network.getSymbol()
                + "\nMaximum total: " + Wei.format(tx.maxCost()) + " " + network.getSymbol());
        Optional<ButtonType> answer = confirm.showAndWait();
        if(answer.isEmpty() || answer.get() != ButtonType.OK) {
            sendButton.setDisable(false);
            setStatus("Cancelled.");
            return;
        }

        LitVmLinkDialog.withAccount(getMasterWallet(), walletForm.getStorage(), getScene().getWindow(), account -> broadcast(account.sign(tx), link), () -> {
            sendButton.setDisable(false);
            setStatus("Not sent.");
        });
    }

    private void broadcast(Eip1559Transaction.Signed signed, LitVmLink link) {
        setStatus("Broadcasting " + signed.hashHex() + "...");
        Task<String> broadcast = new Task<>() {
            @Override
            protected String call() throws Exception {
                String hash = client.sendRawTransaction(signed);
                for(int i = 0; i < 30; i++) {
                    Optional<LitVmRpcClient.Receipt> receipt = client.getReceipt(hash);
                    if(receipt.isPresent()) {
                        return receipt.get().success() ? "Confirmed in block " + receipt.get().blockNumber() + ": " + hash : "Failed on chain: " + hash;
                    }
                    Thread.sleep(2000);
                }
                return "Sent, still pending: " + hash;
            }
        };
        broadcast.setOnSucceeded(e -> {
            sendButton.setDisable(false);
            setStatus(broadcast.getValue());
            amountField.clear();
            refreshBalance(link.getAddress());
        });
        broadcast.setOnFailed(e -> {
            sendButton.setDisable(false);
            setStatus("Not sent: " + message(broadcast.getException()));
        });
        start(broadcast);
    }

    private void setStatus(String text) {
        if(Platform.isFxApplicationThread()) {
            statusLabel.setText(text);
        } else {
            Platform.runLater(() -> statusLabel.setText(text));
        }
    }

    private static String message(Throwable t) {
        return t == null ? "unknown error" : (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
    }

    private static void start(Task<?> task) {
        Thread thread = new Thread(task, "LitVM");
        thread.setDaemon(true);
        thread.start();
    }
}
