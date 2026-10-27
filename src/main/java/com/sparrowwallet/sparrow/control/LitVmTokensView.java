package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.evm.*;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.math.BigInteger;
import java.util.*;

/**
 * The Tokens view of an EVM network: tracked ERC-20 tokens with balances, adding or removing them, creating a token
 * from an OpenZeppelin template, and managing a token (minting, burning, pausing, ownership, publishing its source).
 * Each row is marked official, created here, or a lookalike, since a token's name proves nothing.
 */
class LitVmTokensView extends LitVmFlowPane {
    //LiteForge's explorer has taken 15 minutes to index a new contract
    private static final int VERIFY_SUBMIT_TRIES = 120;
    private static final int VERIFY_POLLS = 30;
    private static final int SOURCIFY_TRIES = 6;

    private final LitVmContext context;
    private final LitVmNetwork network;
    private final TableView<Row> table = new TableView<>();
    //The deployment awaiting broadcast; set when the user confirms it
    private Pending pending;

    private record Row(Erc20Token token, String balance, String mark, String reason) {}

    private record Pending(EvmAddress contract, Erc20Templates.Options options, String symbol, boolean publish) {}

    LitVmTokensView(LitVmContext context, LitVmNetwork network) {
        super(context.walletForm, context.statusConsumer(), context::balancesChanged);
        setSpacing(12);
        this.context = context;
        this.network = network;
        setPadding(new Insets(20, 25, 20, 25));

        Label title = new Label("Tokens on " + network.getDisplayName());
        title.setStyle("-fx-font-size: 1.2em;");
        TableColumn<Row, String> symbol = new TableColumn<>("Token");
        symbol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().token().symbol()));
        symbol.setPrefWidth(110);
        TableColumn<Row, String> name = new TableColumn<>("Name");
        name.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().token().name()));
        name.setPrefWidth(180);
        TableColumn<Row, String> balance = new TableColumn<>("Balance");
        balance.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().balance()));
        balance.setPrefWidth(160);
        TableColumn<Row, String> mark = new TableColumn<>("Mark");
        mark.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().mark()));
        mark.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                Row row = empty ? null : getTableRow().getItem();
                setText(empty ? null : item);
                setTooltip(row == null || row.reason() == null ? null : new Tooltip(row.reason()));
                setStyle(row != null && "Lookalike".equals(item) ? "-fx-text-fill: #c0392b; -fx-font-weight: bold;" : "");
            }
        });
        mark.setPrefWidth(100);
        TableColumn<Row, String> contract = new TableColumn<>("Contract");
        contract.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().token().contract().toString()));
        contract.setPrefWidth(330);
        table.getColumns().addAll(List.of(symbol, name, balance, mark, contract));
        table.setPlaceholder(new Label("No tokens tracked"));
        VBox.setVgrow(table, Priority.ALWAYS);

        Button create = new Button("Create Token...");
        create.setOnAction(e -> createToken());
        Button manage = new Button("Manage...");
        manage.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        manage.setOnAction(e -> manageToken(table.getSelectionModel().getSelectedItem().token()));
        Button add = new Button("Add Token...");
        add.setOnAction(e -> addToken());
        Button remove = new Button("Remove Token");
        remove.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(e -> {
            Row row = table.getSelectionModel().getSelectedItem();
            LitVmContext.untrack(network, row.token().contract());
            context.status("Stopped tracking " + row.token().symbol() + ".");
            context.balancesChanged();
        });
        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> refresh());
        Label note = new Label("Anyone can deploy a token with any name, so tokens are tracked by contract address, and a name proves nothing. "
                + "Only add contracts from a source you trust.");
        note.setWrapText(true);

        getChildren().addAll(title, table, new HBox(8, create, manage, add, remove, refresh), note);
        context.onBalancesChanged(this::refresh);
        refresh();
    }

    @Override
    public void refresh() {
        Task<LitVmContext.Balances> task = new Task<>() {
            @Override
            protected LitVmContext.Balances call() throws Exception {
                return context.readBalances(network);
            }
        };
        task.setOnSucceeded(e -> {
            LitVmContext.Balances b = task.getValue();
            Map<EvmAddress, String> created = LitVmContext.createdTokens(network);
            List<Row> rows = new ArrayList<>();
            for(int i = 0; i < b.tokens().size(); i++) {
                Erc20Token token = b.tokens().get(i);
                Optional<String> lookalike = KnownTokens.lookalike(network, token);
                String mark = KnownTokens.official(network, token.contract()).isPresent() ? "Official"
                        : lookalike.isPresent() ? "Lookalike" : created.containsKey(token.contract()) ? "Created here" : "";
                rows.add(new Row(token, token.format(b.tokenBalances().get(i)), mark, lookalike.orElse(null)));
            }
            table.setItems(FXCollections.observableArrayList(rows));
            if(!b.errors().isEmpty()) {
                context.status("Could not read token(s): " + String.join("; ", b.errors()));
            }
        });
        task.setOnFailed(e -> context.status("Could not reach " + network.getDisplayName() + ": " + LitVmContext.message(task.getException())));
        LitVmContext.start(task);
    }

    private void addToken() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(context.window());
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
            context.status(e.getMessage());
            return;
        }
        context.status("Reading token " + contract + "...");
        LitVmRpcClient client = context.client(network);
        Task<Erc20Token> read = new Task<>() {
            @Override
            protected Erc20Token call() throws Exception {
                client.verifyChain();
                return client.readToken(contract);
            }
        };
        read.setOnSucceeded(e -> {
            Erc20Token token = read.getValue();
            Optional<KnownTokens.Known> official = KnownTokens.official(network, token.contract());
            Optional<String> lookalike = KnownTokens.lookalike(network, token);
            Alert confirm = new Alert(lookalike.isPresent() ? Alert.AlertType.WARNING : Alert.AlertType.CONFIRMATION, "", ButtonType.OK, ButtonType.CANCEL);
            confirm.initOwner(context.window());
            confirm.setTitle("Add Token");
            confirm.setHeaderText(lookalike.isPresent() ? "This looks like a token it is not" : "Track " + token.symbol() + " on " + network.getDisplayName() + "?");
            String details = "Name: " + token.name() + "\nSymbol: " + token.symbol() + "\nDecimals: " + token.decimals() + "\nContract: " + token.contract();
            confirm.setContentText(details + "\n\n" + (official.isPresent() ? "This is the official contract."
                    : lookalike.orElse("Anyone can deploy a token with any name. Only track contracts from a source you trust.")));
            ((Button)confirm.getDialogPane().lookupButton(ButtonType.OK)).setText(lookalike.isPresent() ? "Track Anyway" : "Track");
            if(lookalike.isPresent()) {
                ((Button)confirm.getDialogPane().lookupButton(ButtonType.OK)).setDefaultButton(false);
                ((Button)confirm.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
            }
            confirm.getDialogPane().setPrefWidth(520);
            Optional<ButtonType> answer = confirm.showAndWait();
            if(answer.isPresent() && answer.get() == ButtonType.OK) {
                LitVmContext.track(network, token.contract());
                context.status("Tracking " + token.symbol() + " on " + network.getDisplayName() + ".");
                context.balancesChanged();
            } else {
                context.status("Not added.");
            }
        });
        read.setOnFailed(e -> context.status("Not a readable token: " + LitVmContext.message(read.getException())));
        LitVmContext.start(read);
    }

    private void createToken() {
        EvmAddress me = address();
        Optional<LitVmCreateTokenDialog.Request> chosen = new LitVmCreateTokenDialog(context.window(), network, me).showAndWait();
        if(chosen.isEmpty()) {
            return;
        }
        LitVmCreateTokenDialog.Request request = chosen.get();
        Erc20Templates.Params params = request.params();
        LitVmRpcClient client = context.client(network);
        prepare(() -> {
            client.verifyChain();
            byte[] code = Erc20Templates.get().creationCode(request.options(), params);
            Eip1559Transaction tx = client.prepareCall(me, null, BigInteger.ZERO, code);
            List<Step> steps = List.of(new Step(client, tx, "Create " + params.symbol()));
            requireBalance(client, me, totalCost(steps));
            return steps;
        }, steps -> {
            Erc20Token shown = new Erc20Token(me, params.symbol(), params.name(), LitVmCreateTokenDialog.DECIMALS);
            String details = "Name: " + params.name() + "\nSymbol: " + params.symbol() + "\nDecimals: " + LitVmCreateTokenDialog.DECIMALS
                    + "\nInitial supply: " + shown.format(params.initialSupply()) + " " + params.symbol() + ", to you"
                    + (params.cap() != null ? "\nCap: " + shown.format(params.cap()) + " " + params.symbol() : "")
                    + "\nKind: " + LitVmManageTokenDialog.describe(request.options().key())
                    + (request.options().ownable() ? "\nOwner: you (" + me + ")" : "\nOwner: none")
                    + "\nContract address once created: " + steps.get(0).tx().createdContract(me);
            boolean ok = confirmSteps("Create Token", "Create " + params.symbol() + " on " + network.getDisplayName() + "?", details, steps,
                    "The token's name, symbol and rules are fixed once it is created.");
            if(ok) {
                pending = new Pending(steps.get(0).tx().createdContract(me), request.options(), params.symbol(), request.publishSource());
            }
            return ok;
        }, hashes -> {
            Pending created = pending;
            pending = null;
            LitVmContext.track(network, created.contract());
            LitVmContext.recordCreated(network, created.contract(), created.options().key());
            context.status("Created " + created.symbol() + " at " + created.contract() + ".");
            if(created.publish()) {
                publishSource(created.contract(), created.options(), created.symbol());
            }
        });
    }

    /**
     * Publishes the source: to Sourcify where it supports the network (it confirms an exact match within seconds), and
     * to the network's explorer, retrying while the explorer catches up with the new contract, then waiting for it.
     */
    private void publishSource(EvmAddress contract, Erc20Templates.Options options, String symbol) {
        Erc20Templates templates = Erc20Templates.get();
        String input = templates.verificationInput(options);
        BlockscoutClient explorer = new BlockscoutClient(network);
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                boolean onSourcify = false;
                String sourcifyProblem = null;
                if(SourcifyClient.supports(network)) {
                    SourcifyClient sourcify = new SourcifyClient(network);
                    for(int i = 0; i < SOURCIFY_TRIES && !onSourcify; i++) {
                        try {
                            onSourcify = sourcify.isVerified(contract);
                            if(onSourcify) {
                                break;
                            }
                            updateMessage("Publishing the source of " + symbol + " on Sourcify...");
                            String id = sourcify.submit(contract, input, templates.compilerVersion(), templates.qualifiedContractName());
                            SourcifyClient.Status status = SourcifyClient.Status.PENDING;
                            for(int poll = 0; poll < 30 && status == SourcifyClient.Status.PENDING; poll++) {
                                Thread.sleep(4000);
                                status = sourcify.job(id);
                            }
                            onSourcify = status == SourcifyClient.Status.EXACT;
                            sourcifyProblem = onSourcify ? null : "Sourcify reported " + status.name().toLowerCase(Locale.ROOT);
                        } catch(IOException e) {
                            //Sourcify reads the chain through its own node, which may not have the new contract yet
                            sourcifyProblem = e.getMessage();
                        }
                        if(!onSourcify) {
                            Thread.sleep(15000);
                        }
                    }
                }

                boolean onExplorer = false;
                String explorerProblem = null;
                boolean submitted = false;
                for(int i = 0; i < VERIFY_SUBMIT_TRIES && !submitted && !onExplorer; i++) {
                    try {
                        onExplorer = explorer.isVerified(contract);
                        if(!onExplorer) {
                            updateMessage("Publishing the source of " + symbol + " on the explorer...");
                            explorer.verifyStandardInput(contract, templates.compilerVersion(), templates.qualifiedContractName(), input, "mit");
                            submitted = true;
                        }
                    } catch(IOException e) {
                        //Most often the explorer has not indexed the new contract yet, which can take many minutes
                        explorerProblem = e.getMessage();
                        updateMessage("Waiting for the explorer to see " + symbol + " before publishing its source...");
                        Thread.sleep(15000);
                    }
                }
                for(int i = 0; i < VERIFY_POLLS && submitted && !onExplorer; i++) {
                    updateMessage("Waiting for the explorer to verify the source of " + symbol + "...");
                    Thread.sleep(10000);
                    try {
                        onExplorer = explorer.isVerified(contract);
                    } catch(IOException e) {
                        //keep waiting
                    }
                }
                if(submitted && !onExplorer) {
                    explorerProblem = "The explorer accepted the source but has not verified it";
                }

                String retry = " Manage > Publish Source tries again.";
                if(onExplorer) {
                    return "The source of " + symbol + " is verified on the explorer" + (onSourcify ? " and on Sourcify" : "") + ": " + network.explorerAddressUrl(contract);
                }
                if(onSourcify) {
                    return "The source of " + symbol + " is verified on Sourcify (exact match): " + new SourcifyClient(network).repositoryUrl(contract)
                            + ". The explorer has not verified it (" + explorerProblem + ")." + retry;
                }
                return "The source of " + symbol + " is not verified yet (" + explorerProblem
                        + (sourcifyProblem != null ? "; " + sourcifyProblem : "") + ")." + retry;
            }
        };
        task.messageProperty().addListener((o, a, text) -> context.status(text));
        task.setOnSucceeded(e -> context.status(task.getValue()));
        task.setOnFailed(e -> context.status("Could not publish the source of " + symbol + ": " + LitVmContext.message(task.getException())
                + ". Use Manage > Publish Source to try again."));
        LitVmContext.start(task);
    }

    private void manageToken(Erc20Token token) {
        EvmAddress me = address();
        LitVmRpcClient client = context.client(network);
        String createdKey = LitVmContext.createdTokens(network).get(token.contract());
        context.status("Reading " + token.symbol() + "...");
        Task<LitVmManageTokenDialog.Info> read = new Task<>() {
            @Override
            protected LitVmManageTokenDialog.Info call() throws Exception {
                client.verifyChain();
                BigInteger totalSupply = Abi.decodeUint(client.ethCall(token.contract(), Abi.selector("totalSupply()")), 0);
                BigInteger balance = client.getTokenBalance(token, me);
                Erc20Admin.Capabilities caps = Erc20Admin.read(client, token.contract(), me);
                boolean verified;
                try {
                    verified = new BlockscoutClient(network).isVerified(token.contract())
                            || (SourcifyClient.supports(network) && new SourcifyClient(network).isVerified(token.contract()));
                } catch(IOException e) {
                    verified = false;
                }
                return new LitVmManageTokenDialog.Info(network, token, totalSupply, balance, caps, createdKey, verified,
                        KnownTokens.official(network, token.contract()), KnownTokens.lookalike(network, token));
            }
        };
        read.setOnSucceeded(e -> {
            context.status("");
            LitVmManageTokenDialog.Info info = read.getValue();
            new LitVmManageTokenDialog(context.window(), info, me).showAndWait().ifPresent(action -> act(info, action));
        });
        read.setOnFailed(e -> context.status("Could not read " + token.symbol() + ": " + LitVmContext.message(read.getException())));
        LitVmContext.start(read);
    }

    private void act(LitVmManageTokenDialog.Info info, LitVmManageTokenDialog.Action action) {
        Erc20Token token = info.token();
        EvmAddress me = address();
        switch(action) {
            case OPEN_EXPLORER -> context.showDocument(network.explorerAddressUrl(token.contract()));
            case PUBLISH_SOURCE -> publishSource(token.contract(), Erc20Templates.Options.fromKey(info.createdKey()), token.symbol());
            case MINT -> amount("Mint " + token.symbol(), "Amount to mint:", token).ifPresent(amount -> {
                Optional<String> to = ask("Mint " + token.symbol(), "Who receives the new tokens? Leave as is to mint to yourself.", "Recipient:", me.toString());
                if(to.isEmpty()) {
                    return;
                }
                EvmAddress recipient;
                try {
                    recipient = EvmAddress.fromString(to.get());
                } catch(IllegalArgumentException e) {
                    context.status(e.getMessage());
                    return;
                }
                if(info.capabilities().cap().isPresent() && info.totalSupply().add(amount).compareTo(info.capabilities().cap().get()) > 0) {
                    context.status("That would take the supply past its cap of " + token.format(info.capabilities().cap().get()) + " " + token.symbol() + ".");
                    return;
                }
                run(token, Erc20Admin.mint(recipient, amount), "Mint " + token.format(amount) + " " + token.symbol(),
                        "Mint " + token.format(amount) + " " + token.symbol() + " to " + recipient + (recipient.equals(me) ? " (you)" : "") + ".", null);
            });
            case BURN -> amount("Burn " + token.symbol(), "Amount to burn (you hold " + token.format(info.balance()) + "):", token).ifPresent(amount -> {
                if(amount.compareTo(info.balance()) > 0) {
                    context.status("You hold only " + token.format(info.balance()) + " " + token.symbol() + ".");
                    return;
                }
                run(token, Erc20Admin.burn(amount), "Burn " + token.format(amount) + " " + token.symbol(),
                        "Destroy " + token.format(amount) + " " + token.symbol() + " from your balance.", "Burnt tokens are gone for good.");
            });
            case PAUSE -> run(token, Erc20Admin.pause(), "Pause " + token.symbol(), "Stop all transfers of " + token.symbol() + " until you unpause it.",
                    "While paused nobody can transfer, mint or burn " + token.symbol() + ", you included.");
            case UNPAUSE -> run(token, Erc20Admin.unpause(), "Unpause " + token.symbol(), "Let transfers of " + token.symbol() + " resume.", null);
            case TRANSFER_OWNERSHIP -> ask("Transfer Ownership", "Hand ownership of " + token.symbol() + " to another account.", "New owner:", "").ifPresent(text -> {
                EvmAddress newOwner;
                try {
                    newOwner = EvmAddress.fromString(text);
                } catch(IllegalArgumentException e) {
                    context.status(e.getMessage());
                    return;
                }
                run(token, Erc20Admin.transferOwnership(newOwner), "Transfer ownership of " + token.symbol(),
                        "Make " + newOwner + " the owner of " + token.symbol() + ".",
                        "The new owner takes over minting and pausing at once, and you lose them. Check the address: a mistyped one can never be corrected.");
            });
            case RENOUNCE_OWNERSHIP -> run(token, Erc20Admin.renounceOwnership(), "Renounce ownership of " + token.symbol(),
                    "Give up ownership of " + token.symbol() + " so nobody owns it.",
                    "This cannot be undone. Nobody will ever be able to mint more " + token.symbol() + (info.capabilities().paused().isPresent()
                            ? ", or pause or unpause it (it stays " + (info.capabilities().paused().get() ? "paused" : "running") + " for good)" : "") + ".");
        }
    }

    private Optional<BigInteger> amount(String title, String label, Erc20Token token) {
        Optional<String> text = ask(title, title + " (up to " + token.decimals() + " decimal places)", label, "");
        if(text.isEmpty()) {
            return Optional.empty();
        }
        String t = text.get().trim().replace(",", "");
        if(!t.matches("\\d{1,60}(\\.\\d{1," + token.decimals() + "})?")) {
            context.status("Not an amount: " + t);
            return Optional.empty();
        }
        BigInteger units = token.parse(t);
        if(units.signum() == 0) {
            context.status("Enter an amount above zero.");
            return Optional.empty();
        }
        return Optional.of(units);
    }

    private Optional<String> ask(String title, String header, String label, String initial) {
        TextInputDialog dialog = new TextInputDialog(initial);
        dialog.initOwner(context.window());
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.setContentText(label);
        dialog.getEditor().setPrefColumnCount(34);
        return dialog.showAndWait().filter(text -> !text.isBlank());
    }

    /** One owner or holder call on the token, simulated before it is signed so a call that would revert is never sent. */
    private void run(Erc20Token token, byte[] data, String description, String details, String warning) {
        EvmAddress me = address();
        LitVmRpcClient client = context.client(network);
        prepare(() -> {
            client.verifyChain();
            client.simulate(me, token.contract(), BigInteger.ZERO, data);
            Eip1559Transaction tx = client.prepareCall(me, token.contract(), BigInteger.ZERO, data);
            List<Step> steps = List.of(new Step(client, tx, description));
            requireBalance(client, me, totalCost(steps));
            return steps;
        }, steps -> confirmSteps(description, description + "?", details, steps, warning),
                hashes -> context.status(description + " done: " + hashes.get(0)));
    }
}
