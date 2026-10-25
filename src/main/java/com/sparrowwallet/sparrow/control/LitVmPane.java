package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.evm.*;
import com.sparrowwallet.drongo.wallet.LitVmLink;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.wallet.WalletForm;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Content of the LitVM sub-tab under a Litecoin wallet: the linked account's address, its zkLTC and tracked ERC-20
 * token balances, and transfers of either on the LiteForge testnet. The address comes from the stored link, so
 * viewing needs no password; sending asks for the wallet password (when it has one) to unlock the key just long
 * enough to sign.
 */
public class LitVmPane extends BorderPane {
    private static final String FAUCET_URL = "https://liteforge.hub.caldera.xyz/";

    private final WalletForm walletForm;
    private final LitVmRpcClient client = new LitVmRpcClient(LitVmNetwork.LITEFORGE_TESTNET);
    private final LitVmNetwork network = client.getNetwork();

    private final Label balanceLabel = new Label("Loading...");
    private final VBox tokenBalances = new VBox(4);
    private final Label statusLabel = new Label();
    private final ComboBox<Asset> assetCombo = new ComboBox<>();
    private final TextField toField = new TextField();
    private final TextField amountField = new TextField();
    private final Button sendButton = new Button("Send");
    private final Button removeTokenButton = new Button("Remove Token");

    /** What is being sent: native zkLTC (token null) or an ERC-20 token. */
    private record Asset(Erc20Token token) {
        boolean isNative() {
            return token == null;
        }
    }

    public LitVmPane(WalletForm walletForm) {
        this.walletForm = walletForm;
        LitVmLink link = getMasterWallet().getLitVmLink();
        EvmAddress address = link.getAddress();

        Label title = new Label("LitVM");
        title.setStyle("-fx-font-size: 1.4em;");
        Label warning = new Label(network.getDisplayName() + " (chain " + network.getChainId() + "). Test " + network.getSymbol() + " and test tokens have no value.");
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

        LitVmBridgePane bridgePane = new LitVmBridgePane(walletForm, client, this::setStatus, () -> refreshBalances(address));
        Button refreshButton = new Button("Refresh");
        refreshButton.setOnAction(e -> {
            refreshBalances(address);
            bridgePane.refresh();
        });
        Button addTokenButton = new Button("Add Token...");
        addTokenButton.setOnAction(e -> addToken(address));

        Button unlinkButton = new Button("Unlink LitVM...");
        unlinkButton.setOnAction(e -> LitVmLinkDialog.unlink(getMasterWallet(), getScene().getWindow()));

        assetCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Asset asset) {
                return asset == null ? "" : (asset.isNative() ? network.getSymbol() : asset.token().symbol() + " (" + shortAddress(asset.token().contract()) + ")");
            }

            @Override
            public Asset fromString(String string) {
                return null;
            }
        });
        assetCombo.setItems(FXCollections.observableArrayList(new Asset(null)));
        assetCombo.getSelectionModel().selectFirst();
        assetCombo.valueProperty().addListener((obs, old, asset) -> removeTokenButton.setVisible(asset != null && !asset.isNative()));
        removeTokenButton.setVisible(false);
        removeTokenButton.managedProperty().bind(removeTokenButton.visibleProperty());
        removeTokenButton.setOnAction(e -> removeToken(address));

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
        grid.add(new Label("Tokens"), 0, 3);
        grid.add(new VBox(6, tokenBalances, addTokenButton), 1, 3);
        grid.add(new HBox(16, explorerLink, faucetLink), 1, 4);
        grid.add(new Separator(), 0, 5, 2, 1);
        grid.add(new Label("Asset"), 0, 6);
        grid.add(new HBox(8, assetCombo, removeTokenButton), 1, 6);
        grid.add(new Label("Send to"), 0, 7);
        grid.add(toField, 1, 7);
        grid.add(new Label("Amount"), 0, 8);
        grid.add(new HBox(8, amountField, sendButton), 1, 8);

        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(560);
        Label bridgesTitle = new Label("Bridges");
        bridgesTitle.setStyle("-fx-font-size: 1.2em;");
        VBox content = new VBox(14, title, warning, grid, statusLabel, new Separator(), bridgesTitle, bridgePane, new Separator(), unlinkButton);
        content.setPadding(new Insets(20, 25, 20, 25));
        setCenter(new ScrollPane(content));
        ((ScrollPane)getCenter()).setFitToWidth(true);

        refreshBalances(address);
        bridgePane.refresh();
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

    private static String shortAddress(EvmAddress address) {
        String text = address.toString();
        return text.substring(0, 6) + "..." + text.substring(text.length() - 4);
    }

    /** Tracked token contracts, from the config: the user's own list (contracts are public, not wallet secrets). */
    private static List<EvmAddress> trackedTokens() {
        List<EvmAddress> tokens = new ArrayList<>();
        for(String contract : Config.get().getLitVmTokens()) {
            try {
                EvmAddress address = EvmAddress.fromString(contract);
                if(!tokens.contains(address)) {
                    tokens.add(address);
                }
            } catch(IllegalArgumentException e) {
                //skip a malformed entry rather than failing the pane
            }
        }
        return tokens;
    }

    private record Balances(BigInteger nativeBalance, List<Erc20Token> tokens, List<BigInteger> tokenBalances, List<String> errors) {}

    private void refreshBalances(EvmAddress address) {
        balanceLabel.setText("Loading...");
        Task<Balances> task = new Task<>() {
            @Override
            protected Balances call() throws Exception {
                client.verifyChain();
                BigInteger nativeBalance = client.getBalance(address);
                List<Erc20Token> tokens = new ArrayList<>();
                List<BigInteger> balances = new ArrayList<>();
                List<String> errors = new ArrayList<>();
                for(EvmAddress contract : trackedTokens()) {
                    try {
                        Erc20Token token = client.readToken(contract);
                        tokens.add(token);
                        balances.add(client.getTokenBalance(token, address));
                    } catch(Exception e) {
                        errors.add(shortAddress(contract) + ": " + message(e));
                    }
                }
                return new Balances(nativeBalance, tokens, balances, errors);
            }
        };
        task.setOnSucceeded(e -> showBalances(task.getValue()));
        task.setOnFailed(e -> {
            balanceLabel.setText("Unavailable");
            setStatus("Could not reach " + network.getDisplayName() + ": " + message(task.getException()));
        });
        start(task);
    }

    private void showBalances(Balances balances) {
        balanceLabel.setText(Wei.format(balances.nativeBalance()) + " " + network.getSymbol());

        tokenBalances.getChildren().clear();
        for(int i = 0; i < balances.tokens().size(); i++) {
            Erc20Token token = balances.tokens().get(i);
            Label line = new Label(token.format(balances.tokenBalances().get(i)) + " " + token.symbol() + "   " + token.name());
            line.setTooltip(new Tooltip("Contract " + token.contract()));
            tokenBalances.getChildren().add(line);
        }
        if(balances.tokens().isEmpty()) {
            tokenBalances.getChildren().add(new Label("None tracked"));
        }

        Asset selected = assetCombo.getValue();
        List<Asset> assets = new ArrayList<>();
        assets.add(new Asset(null));
        balances.tokens().forEach(token -> assets.add(new Asset(token)));
        assetCombo.setItems(FXCollections.observableArrayList(assets));
        assetCombo.setValue(assets.stream().filter(asset -> selected != null && sameAsset(asset, selected)).findFirst().orElse(assets.getFirst()));

        if(!balances.errors().isEmpty()) {
            setStatus("Could not read token(s): " + String.join("; ", balances.errors()));
        }
    }

    private static boolean sameAsset(Asset a, Asset b) {
        return a.isNative() ? b.isNative() : (!b.isNative() && a.token().contract().equals(b.token().contract()));
    }

    private void addToken(EvmAddress owner) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(getScene().getWindow());
        dialog.setTitle("Add Token");
        dialog.setHeaderText("Track an ERC-20 token on " + network.getDisplayName());
        dialog.setContentText("Token contract:");
        Optional<String> input = dialog.showAndWait();
        if(input.isEmpty() || input.get().isBlank()) {
            return;
        }

        EvmAddress contract;
        try {
            contract = EvmAddress.fromString(input.get());
        } catch(IllegalArgumentException e) {
            setStatus(e.getMessage());
            return;
        }

        setStatus("Reading token " + contract + "...");
        Task<Erc20Token> read = new Task<>() {
            @Override
            protected Erc20Token call() throws Exception {
                client.verifyChain();
                return client.readToken(contract);
            }
        };
        read.setOnSucceeded(e -> {
            Erc20Token token = read.getValue();
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.initOwner(getScene().getWindow());
            confirm.setTitle("Add Token");
            confirm.setHeaderText("Track " + token.symbol() + "?");
            confirm.setContentText("Name: " + token.name() + "\nSymbol: " + token.symbol() + "\nDecimals: " + token.decimals() + "\nContract: " + token.contract()
                    + "\n\nAnyone can deploy a token with any name. Only track contracts from a source you trust.");
            Optional<ButtonType> answer = confirm.showAndWait();
            if(answer.isPresent() && answer.get() == ButtonType.OK) {
                List<String> tokens = Config.get().getLitVmTokens();
                if(tokens.stream().noneMatch(existing -> existing.equalsIgnoreCase(token.contract().toString()))) {
                    tokens.add(token.contract().toString());
                    Config.get().setLitVmTokens(tokens);
                }
                setStatus("Tracking " + token.symbol() + ".");
                refreshBalances(owner);
            } else {
                setStatus("Not added.");
            }
        });
        read.setOnFailed(e -> setStatus("Not a readable token: " + message(read.getException())));
        start(read);
    }

    private void removeToken(EvmAddress owner) {
        Asset asset = assetCombo.getValue();
        if(asset == null || asset.isNative()) {
            return;
        }
        String contract = asset.token().contract().toString();
        List<String> tokens = Config.get().getLitVmTokens();
        tokens.removeIf(existing -> existing.equalsIgnoreCase(contract));
        Config.get().setLitVmTokens(tokens);
        setStatus("Stopped tracking " + asset.token().symbol() + ".");
        refreshBalances(owner);
    }

    private void send() {
        LitVmLink link = getMasterWallet().getLitVmLink();
        Asset asset = assetCombo.getValue();
        if(link == null || asset == null) {
            return;
        }

        EvmAddress to;
        BigInteger amount;
        try {
            to = EvmAddress.fromString(toField.getText());
        } catch(IllegalArgumentException e) {
            setStatus(e.getMessage());
            return;
        }
        try {
            amount = asset.isNative() ? Wei.parse(amountField.getText()) : asset.token().parse(amountField.getText());
        } catch(IllegalArgumentException e) {
            setStatus(e.getMessage());
            return;
        }
        if(amount.signum() == 0) {
            setStatus("Enter an amount greater than zero.");
            return;
        }
        if(!asset.isNative() && to.equals(asset.token().contract())) {
            setStatus("That is the token's own contract address; tokens sent there are usually lost.");
            return;
        }

        sendButton.setDisable(true);
        setStatus("Preparing transaction...");
        Task<Eip1559Transaction> prepare = new Task<>() {
            @Override
            protected Eip1559Transaction call() throws Exception {
                client.verifyChain();
                if(asset.isNative()) {
                    return client.prepareTransfer(link.getAddress(), to, amount);
                }
                BigInteger tokenBalance = client.getTokenBalance(asset.token(), link.getAddress());
                if(tokenBalance.compareTo(amount) < 0) {
                    throw new IllegalStateException("Only " + asset.token().format(tokenBalance) + " " + asset.token().symbol() + " available");
                }
                Eip1559Transaction tx = client.prepareTokenTransfer(link.getAddress(), asset.token(), to, amount);
                BigInteger gasBalance = client.getBalance(link.getAddress());
                if(gasBalance.compareTo(tx.maxCost()) < 0) {
                    throw new IllegalStateException("Not enough " + network.getSymbol() + " for the network fee (up to " + Wei.format(tx.maxCost()) + ")");
                }
                return tx;
            }
        };
        prepare.setOnSucceeded(e -> confirmAndSend(link, asset, to, amount, prepare.getValue()));
        prepare.setOnFailed(e -> {
            sendButton.setDisable(false);
            setStatus("Could not prepare the transaction: " + message(prepare.getException()));
        });
        start(prepare);
    }

    private void confirmAndSend(LitVmLink link, Asset asset, EvmAddress to, BigInteger amount, Eip1559Transaction tx) {
        BigInteger maxFee = tx.getGasLimit().multiply(tx.getMaxFeePerGas());
        String amountText = asset.isNative() ? Wei.format(amount) + " " + network.getSymbol() : asset.token().format(amount) + " " + asset.token().symbol();
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.initOwner(getScene().getWindow());
        confirm.setTitle("Confirm LitVM Transfer");
        confirm.setHeaderText("Send " + amountText + " on " + network.getDisplayName() + "?");
        confirm.setContentText("From: " + link.getAddress() + "\nTo: " + to + "\nAmount: " + amountText
                + (asset.isNative() ? "" : "\nToken contract: " + asset.token().contract())
                + "\nMaximum network fee: " + Wei.format(maxFee) + " " + network.getSymbol()
                + (asset.isNative() ? "\nMaximum total: " + Wei.format(tx.maxCost()) + " " + network.getSymbol() : ""));
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
            refreshBalances(link.getAddress());
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
