package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.evm.*;
import com.sparrowwallet.drongo.wallet.LitVmLink;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Send view of an EVM network, laid out like a wallet's Send tab: pay to, asset (the native coin or a tracked
 * token), amount, then a confirmation showing the most it can cost, the wallet password to sign, and broadcast.
 */
class LitVmSendView extends VBox {
    private final LitVmContext context;
    private final LitVmNetwork network;
    private final TextField toField = new TextField();
    private final ComboBox<Asset> assetCombo = new ComboBox<>();
    private final TextField amountField = new TextField();
    private final Label available = new Label();
    private final Button sendButton = new Button("Send");

    /** What is being sent: the network's native coin (token null) or an ERC-20 token. */
    private record Asset(Erc20Token token, BigInteger balance) {
        boolean isNative() {
            return token == null;
        }
    }

    LitVmSendView(LitVmContext context, LitVmNetwork network) {
        super(14);
        this.context = context;
        this.network = network;
        setPadding(new Insets(20, 25, 20, 25));

        Label title = new Label("Send");
        title.setStyle("-fx-font-size: 1.2em;");
        toField.setPromptText("0x...");
        toField.setPrefColumnCount(34);
        amountField.setPromptText("0.0");
        assetCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Asset asset) {
                return asset == null ? "" : asset.isNative() ? network.getSymbol() : asset.token().symbol() + " (" + LitVmContext.shortAddress(asset.token().contract()) + ")";
            }

            @Override
            public Asset fromString(String string) {
                return null;
            }
        });
        assetCombo.valueProperty().addListener((obs, old, asset) -> available.setText(asset == null || asset.balance() == null ? ""
                : "Available: " + (asset.isNative() ? Wei.format(asset.balance()) + " " + network.getSymbol() : asset.token().format(asset.balance()) + " " + asset.token().symbol())));
        sendButton.setOnAction(e -> send());

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.add(new Label("Pay to"), 0, 0);
        grid.add(toField, 1, 0);
        grid.add(new Label("Asset"), 0, 1);
        grid.add(new HBox(10, assetCombo, available), 1, 1);
        grid.add(new Label("Amount"), 0, 2);
        grid.add(new HBox(8, amountField, sendButton), 1, 2);
        Label note = new Label("Network: " + network.getDisplayName() + " (chain " + network.getChainId() + "). The fee is paid in " + network.getSymbol()
                + "; the confirmation shows the most it can cost.");
        note.setWrapText(true);
        note.setMaxWidth(620);

        getChildren().addAll(title, grid, note);
        context.onBalancesChanged(this::refresh);
        refresh();
    }

    void refresh() {
        Task<LitVmContext.Balances> task = new Task<>() {
            @Override
            protected LitVmContext.Balances call() throws Exception {
                return context.readBalances(network);
            }
        };
        task.setOnSucceeded(e -> {
            LitVmContext.Balances b = task.getValue();
            Asset selected = assetCombo.getValue();
            List<Asset> assets = new ArrayList<>();
            assets.add(new Asset(null, b.nativeBalance()));
            for(int i = 0; i < b.tokens().size(); i++) {
                assets.add(new Asset(b.tokens().get(i), b.tokenBalances().get(i)));
            }
            assetCombo.setItems(FXCollections.observableArrayList(assets));
            assetCombo.setValue(assets.stream().filter(a -> selected != null && (a.isNative() ? selected.isNative()
                    : !selected.isNative() && a.token().contract().equals(selected.token().contract()))).findFirst().orElse(assets.getFirst()));
        });
        task.setOnFailed(e -> context.status("Could not reach " + network.getDisplayName() + ": " + LitVmContext.message(task.getException())));
        LitVmContext.start(task);
    }

    private void send() {
        LitVmLink link = context.link();
        Asset asset = assetCombo.getValue();
        if(link == null || asset == null) {
            return;
        }
        LitVmRpcClient client = context.client(network);
        EvmAddress to;
        BigInteger amount;
        try {
            to = EvmAddress.fromString(toField.getText());
        } catch(IllegalArgumentException e) {
            context.status(e.getMessage());
            return;
        }
        try {
            amount = asset.isNative() ? Wei.parse(amountField.getText()) : asset.token().parse(amountField.getText());
        } catch(IllegalArgumentException e) {
            context.status(e.getMessage());
            return;
        }
        if(amount.signum() == 0) {
            context.status("Enter an amount greater than zero.");
            return;
        }
        if(!asset.isNative() && to.equals(asset.token().contract())) {
            context.status("That is the token's own contract address; tokens sent there are usually lost.");
            return;
        }
        if(LitVmFlowPane.busy) {
            context.status("Another LitVM transaction is still in progress; wait for it to finish.");
            return;
        }
        LitVmFlowPane.busy = true;
        sendButton.setDisable(true);
        context.status("Preparing transaction...");
        BigInteger[] dataFee = new BigInteger[1];
        Task<Eip1559Transaction> prepare = new Task<>() {
            @Override
            protected Eip1559Transaction call() throws Exception {
                client.verifyChain();
                Eip1559Transaction tx;
                if(asset.isNative()) {
                    tx = client.prepareTransfer(link.getAddress(), to, amount);
                } else {
                    BigInteger tokenBalance = client.getTokenBalance(asset.token(), link.getAddress());
                    if(tokenBalance.compareTo(amount) < 0) {
                        throw new IllegalStateException("Only " + asset.token().format(tokenBalance) + " " + asset.token().symbol() + " available");
                    }
                    tx = client.prepareTokenTransfer(link.getAddress(), asset.token(), to, amount);
                }
                dataFee[0] = client.l1DataFee(tx);
                BigInteger needed = tx.maxCost().add(dataFee[0]);
                if(client.getBalance(link.getAddress()).compareTo(needed) < 0) {
                    throw new IllegalStateException("Not enough " + network.getSymbol() + " for this transfer and its network fee (up to " + Wei.format(needed) + ")");
                }
                return tx;
            }
        };
        prepare.setOnSucceeded(e -> confirmAndSend(client, link, asset, to, amount, prepare.getValue(), dataFee[0]));
        prepare.setOnFailed(e -> {
            release();
            context.status("Could not prepare the transaction: " + LitVmContext.message(prepare.getException()));
        });
        LitVmContext.start(prepare);
    }

    private void confirmAndSend(LitVmRpcClient client, LitVmLink link, Asset asset, EvmAddress to, BigInteger amount, Eip1559Transaction tx, BigInteger dataFee) {
        BigInteger maxFee = tx.getGasLimit().multiply(tx.getMaxFeePerGas()).add(dataFee);
        String amountText = asset.isNative() ? Wei.format(amount) + " " + network.getSymbol() : asset.token().format(amount) + " " + asset.token().symbol();
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.initOwner(context.window());
        confirm.setTitle("Confirm Transfer");
        confirm.setHeaderText("Send " + amountText + " on " + network.getDisplayName() + "?");
        confirm.setContentText("From: " + link.getAddress() + "\nTo: " + to + "\nAmount: " + amountText
                + (asset.isNative() ? "" : "\nToken contract: " + asset.token().contract())
                + "\nMaximum network fee: " + Wei.format(maxFee) + " " + network.getSymbol()
                + (asset.isNative() ? "\nMaximum total: " + Wei.format(tx.maxCost().add(dataFee)) + " " + network.getSymbol() : ""));
        Optional<ButtonType> answer = confirm.showAndWait();
        if(answer.isEmpty() || answer.get() != ButtonType.OK) {
            release();
            context.status("Cancelled.");
            return;
        }
        LitVmLinkDialog.withAccount(context.masterWallet(), context.walletForm.getStorage(), context.window(), account -> broadcast(client, account.sign(tx)), () -> {
            release();
            context.status("Not sent.");
        });
    }

    private void broadcast(LitVmRpcClient client, Eip1559Transaction.Signed signed) {
        context.status("Broadcasting " + signed.hashHex() + "...");
        Task<String> broadcast = new Task<>() {
            @Override
            protected String call() throws Exception {
                String hash = LitVmFlowPane.send(client, signed, "The transfer");
                for(int i = 0; i < 30; i++) {
                    Optional<LitVmRpcClient.Receipt> receipt = LitVmFlowPane.receipt(client, hash);
                    if(receipt.isPresent()) {
                        return receipt.get().success() ? "Confirmed in block " + receipt.get().blockNumber() + ": " + hash : "Failed on chain: " + hash;
                    }
                    Thread.sleep(2000);
                }
                return "Sent, still pending: " + hash;
            }
        };
        broadcast.setOnSucceeded(e -> {
            release();
            context.status(broadcast.getValue());
            amountField.clear();
            context.balancesChanged();
        });
        broadcast.setOnFailed(e -> {
            release();
            context.status("Not completed: " + LitVmContext.message(broadcast.getException()));
        });
        LitVmContext.start(broadcast);
    }

    private void release() {
        sendButton.setDisable(false);
        LitVmFlowPane.busy = false;
    }
}
