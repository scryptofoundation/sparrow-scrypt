package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.evm.*;
import com.sparrowwallet.sparrow.wallet.WalletForm;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The swap section of the LitVM tab: swaps on the selected network's pinned venue (Uniswap v3 on Base Sepolia, a
 * Uniswap v2 router on LiteForge), with a fresh quote, a minimum output from the slippage setting, an exact-amount
 * approval when a token is sold, and a deadline.
 */
public class LitVmSwapPane extends LitVmFlowPane {
    //Gas for a swap signed before its approval is mined, so it cannot be estimated yet; unused gas is not charged
    private static final BigInteger DEPENDENT_SWAP_GAS = BigInteger.valueOf(600_000);
    private static final long DEADLINE_SECONDS = 20 * 60;

    private final Label title = new Label();
    private final Label note = new Label();
    private final GridPane form = new GridPane();
    private final ComboBox<SwapAsset> fromCombo = new ComboBox<>();
    private final ComboBox<SwapAsset> toCombo = new ComboBox<>();
    private final TextField amountField = new TextField();
    private final TextField slippageField = new TextField("1.0");
    private final Label quoteLabel = new Label();
    private final Button swapButton = new Button("Swap");

    private LitVmRpcClient client;
    private SwapVenue venue;

    /** A swappable asset: the network's native coin (token null) or an ERC-20 token. */
    private record SwapAsset(Erc20Token token, String nativeSymbol) {
        @Override
        public String toString() {
            return token == null ? nativeSymbol : token.symbol() + " (" + shortHash(token.contract().toString()) + ")";
        }

        String symbol() {
            return token == null ? nativeSymbol : token.symbol();
        }

        BigInteger parse(String amount) {
            return token == null ? Wei.parse(amount) : token.parse(amount);
        }

        String format(BigInteger units) {
            return token == null ? Wei.format(units) : token.format(units);
        }
    }

    public LitVmSwapPane(WalletForm walletForm, Consumer<String> status, Runnable onBalancesChanged) {
        super(walletForm, status, onBalancesChanged);
        title.setStyle("-fx-font-weight: bold;");
        note.setWrapText(true);
        note.setMaxWidth(560);
        amountField.setPromptText("0.0");
        slippageField.setPrefColumnCount(4);
        quoteLabel.setWrapText(true);
        quoteLabel.setMaxWidth(560);

        Button quoteButton = new Button("Get Quote");
        quoteButton.setOnAction(e -> quote());
        swapButton.setOnAction(e -> swap());
        Button flip = new Button("Flip");
        flip.setOnAction(e -> {
            SwapAsset from = fromCombo.getValue();
            fromCombo.setValue(toCombo.getValue());
            toCombo.setValue(from);
            quoteLabel.setText("");
        });
        fromCombo.valueProperty().addListener((obs, old, value) -> quoteLabel.setText(""));
        toCombo.valueProperty().addListener((obs, old, value) -> quoteLabel.setText(""));

        form.setHgap(10);
        form.setVgap(10);
        form.add(new Label("From"), 0, 0);
        form.add(new HBox(8, fromCombo, flip), 1, 0);
        form.add(new Label("To"), 0, 1);
        form.add(toCombo, 1, 1);
        form.add(new Label("Amount"), 0, 2);
        form.add(amountField, 1, 2);
        form.add(new Label("Slippage %"), 0, 3);
        form.add(new HBox(8, slippageField, quoteButton, swapButton), 1, 3);
        getChildren().addAll(title, note, form, quoteLabel);
    }

    /** Points the section at a network and the tokens tracked there; hides it where no venue exists. */
    public void setNetwork(LitVmRpcClient client, List<Erc20Token> trackedTokens) {
        //A balance refresh calls this again for the same network: keep what the user picked and typed
        boolean sameNetwork = this.client != null && this.client.getNetwork() == client.getNetwork();
        SwapAsset previousFrom = sameNetwork ? fromCombo.getValue() : null;
        SwapAsset previousTo = sameNetwork ? toCombo.getValue() : null;
        this.client = client;
        List<SwapVenue> venues = SwapVenue.forNetwork(client.getNetwork());
        venue = venues.isEmpty() ? null : venues.getFirst();
        if(!sameNetwork) {
            amountField.clear();
            quoteLabel.setText("");
        }
        if(venue == null) {
            title.setText("Swaps");
            note.setText("No swap venue is set up on " + client.getNetwork().getDisplayName() + ".");
            form.setVisible(false);
            form.setManaged(false);
            return;
        }
        form.setVisible(true);
        form.setManaged(true);
        title.setText("Swap on " + client.getNetwork().getDisplayName() + " (" + venue.name() + ")");
        note.setText("Testnet pools are thin and not arbitraged, so prices can be far from real markets."
                + (venue instanceof UniswapV2Venue ? " This is a community deployment on LiteForge, not an official Uniswap one." : "")
                + " Router " + venue.spender() + ".");

        String nativeSymbol = client.getNetwork().getSymbol();
        List<SwapAsset> assets = new ArrayList<>();
        assets.add(new SwapAsset(null, nativeSymbol));
        for(Erc20Token token : venue.knownTokens()) {
            assets.add(new SwapAsset(token, nativeSymbol));
        }
        for(Erc20Token token : trackedTokens) {
            if(assets.stream().noneMatch(asset -> asset.token() != null && asset.token().contract().equals(token.contract()))) {
                assets.add(new SwapAsset(token, nativeSymbol));
            }
        }
        String quote = quoteLabel.getText();
        fromCombo.setItems(FXCollections.observableArrayList(assets));
        toCombo.setItems(FXCollections.observableArrayList(assets));
        fromCombo.setValue(find(assets, previousFrom).orElse(assets.get(0)));
        toCombo.setValue(find(assets, previousTo).orElse(assets.size() > 1 ? assets.get(1) : null));
        if(sameNetwork) {
            quoteLabel.setText(quote);
        }
    }

    private static java.util.Optional<SwapAsset> find(List<SwapAsset> assets, SwapAsset wanted) {
        if(wanted == null) {
            return java.util.Optional.empty();
        }
        return assets.stream().filter(asset -> wanted.token() == null ? asset.token() == null
                : asset.token() != null && asset.token().contract().equals(wanted.token().contract())).findFirst();
    }

    @Override
    public void refresh() {
        //Balances live in the LitVM pane; nothing here is on-chain state
    }

    private record Inputs(SwapAsset from, SwapAsset to, BigInteger amount, int slippageBps) {}

    private Inputs inputs() {
        SwapAsset from = fromCombo.getValue();
        SwapAsset to = toCombo.getValue();
        if(venue == null || from == null || to == null) {
            throw new IllegalArgumentException("Choose what to swap from and to.");
        }
        if(from.token() == null ? to.token() == null : (to.token() != null && from.token().contract().equals(to.token().contract()))) {
            throw new IllegalArgumentException("Choose two different assets.");
        }
        BigInteger amount = from.parse(amountField.getText());
        if(amount.signum() == 0) {
            throw new IllegalArgumentException("Enter an amount greater than zero.");
        }
        int slippageBps;
        try {
            slippageBps = new BigDecimal(slippageField.getText().trim()).movePointRight(2).intValueExact();
        } catch(NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("Enter slippage as a percentage, such as 1.0");
        }
        SwapVenue.minimumOut(BigInteger.ONE, slippageBps);
        return new Inputs(from, to, amount, slippageBps);
    }

    private String describe(Inputs inputs, SwapVenue.Quote quote) {
        BigInteger minOut = SwapVenue.minimumOut(quote.amountOut(), inputs.slippageBps());
        return inputs.from().format(inputs.amount()) + " " + inputs.from().symbol() + " -> about " + inputs.to().format(quote.amountOut()) + " " + inputs.to().symbol()
                + " (" + quote.route() + "); at least " + inputs.to().format(minOut) + " " + inputs.to().symbol() + " at " + inputs.slippageBps() / 100.0 + "% slippage";
    }

    private void quote() {
        Inputs inputs;
        try {
            inputs = inputs();
        } catch(IllegalArgumentException e) {
            status.accept(e.getMessage());
            return;
        }
        LitVmRpcClient quoteClient = client;
        SwapVenue quoteVenue = venue;
        quoteLabel.setText("Quoting...");
        Task<SwapVenue.Quote> task = new Task<>() {
            @Override
            protected SwapVenue.Quote call() throws Exception {
                quoteClient.verifyChain();
                quoteVenue.verify(quoteClient);
                return quoteVenue.quote(quoteClient, inputs.from().token(), inputs.to().token(), inputs.amount());
            }
        };
        task.setOnSucceeded(e -> quoteLabel.setText(describe(inputs, task.getValue())));
        task.setOnFailed(e -> quoteLabel.setText("No quote: " + message(task.getException())));
        start(task);
    }

    private void swap() {
        Inputs inputs;
        try {
            inputs = inputs();
        } catch(IllegalArgumentException e) {
            status.accept(e.getMessage());
            return;
        }
        LitVmRpcClient swapClient = client;
        SwapVenue swapVenue = venue;
        EvmAddress address = address();
        SwapVenue.Quote[] quoted = new SwapVenue.Quote[1];
        prepare(() -> {
            swapClient.verifyChain();
            swapVenue.verify(swapClient);
            SwapVenue.Quote quote = swapVenue.quote(swapClient, inputs.from().token(), inputs.to().token(), inputs.amount());
            quoted[0] = quote;
            BigInteger minOut = SwapVenue.minimumOut(quote.amountOut(), inputs.slippageBps());
            long deadline = System.currentTimeMillis() / 1000 + DEADLINE_SECONDS;
            SwapVenue.Call call = swapVenue.swapCall(quote, address, minOut, deadline);
            String description = "swap on " + swapVenue.name();

            List<Step> steps = new ArrayList<>();
            Erc20Token sold = inputs.from().token();
            if(sold != null) {
                BigInteger balance = swapClient.getTokenBalance(sold, address);
                if(balance.compareTo(inputs.amount()) < 0) {
                    throw new IllegalStateException("Only " + sold.format(balance) + " " + sold.symbol() + " available");
                }
            }
            if(sold != null && allowance(swapClient, sold, address, swapVenue.spender()).compareTo(inputs.amount()) < 0) {
                Eip1559Transaction approve = swapClient.prepareCall(address, sold.contract(), BigInteger.ZERO,
                        Abi.encodeCall("approve(address,uint256)", swapVenue.spender(), inputs.amount()));
                steps.add(new Step(swapClient, approve, "approve the router to take " + sold.format(inputs.amount()) + " " + sold.symbol()));
                steps.add(new Step(swapClient, swapClient.prepareCall(address, call.to(), call.value(), call.data(), approve.getNonce().add(BigInteger.ONE), DEPENDENT_SWAP_GAS),
                        description, true));
            } else {
                steps.add(new Step(swapClient, swapClient.prepareCall(address, call.to(), call.value(), call.data()), description));
            }
            requireBalance(swapClient, address, totalCost(steps));
            return steps;
        }, steps -> {
            SwapVenue.Quote quote = quoted[0];
            BigInteger minOut = SwapVenue.minimumOut(quote.amountOut(), inputs.slippageBps());
            quoteLabel.setText(describe(inputs, quote));
            String details = "Venue: " + swapVenue.name() + " on " + swapClient.getNetwork().getDisplayName()
                    + "\nRoute: " + quote.route() + " " + quote.path()
                    + "\nYou pay: " + inputs.from().format(inputs.amount()) + " " + inputs.from().symbol()
                    + "\nQuoted: " + inputs.to().format(quote.amountOut()) + " " + inputs.to().symbol()
                    + "\nMinimum received (" + inputs.slippageBps() / 100.0 + "% slippage): " + inputs.to().format(minOut) + " " + inputs.to().symbol()
                    + "\nTo: " + address
                    + "\nRouter: " + swapVenue.spender() + " (expires in " + DEADLINE_SECONDS / 60 + " minutes)";
            return confirmSteps("Confirm Swap", "Swap " + inputs.from().symbol() + " for " + inputs.to().symbol() + "?", details, steps,
                    "Testnet pools are thin; the minimum received is the most this swap can lose to price movement.");
        }, hashes -> {
            amountField.clear();
            status.accept("Swapped in " + hashes.getLast());
        });
    }
}
