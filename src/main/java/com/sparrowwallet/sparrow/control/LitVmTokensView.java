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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The Tokens view of an EVM network: tracked ERC-20 tokens with balances, and adding or removing them. */
class LitVmTokensView extends VBox {
    private final LitVmContext context;
    private final LitVmNetwork network;
    private final TableView<Row> table = new TableView<>();

    private record Row(Erc20Token token, String balance) {}

    LitVmTokensView(LitVmContext context, LitVmNetwork network) {
        super(12);
        this.context = context;
        this.network = network;
        setPadding(new Insets(20, 25, 20, 25));

        Label title = new Label("Tokens on " + network.getDisplayName());
        title.setStyle("-fx-font-size: 1.2em;");
        TableColumn<Row, String> symbol = new TableColumn<>("Token");
        symbol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().token().symbol()));
        symbol.setPrefWidth(120);
        TableColumn<Row, String> name = new TableColumn<>("Name");
        name.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().token().name()));
        name.setPrefWidth(200);
        TableColumn<Row, String> balance = new TableColumn<>("Balance");
        balance.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().balance()));
        balance.setPrefWidth(180);
        TableColumn<Row, String> contract = new TableColumn<>("Contract");
        contract.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().token().contract().toString()));
        contract.setPrefWidth(330);
        table.getColumns().addAll(List.of(symbol, name, balance, contract));
        table.setPlaceholder(new Label("No tokens tracked"));
        VBox.setVgrow(table, Priority.ALWAYS);

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
        Label note = new Label("Anyone can deploy a token with any name, so tokens are tracked by contract address. Only add contracts from a source you trust.");
        note.setWrapText(true);

        getChildren().addAll(title, table, new HBox(8, add, remove, refresh), note);
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
            List<Row> rows = new ArrayList<>();
            for(int i = 0; i < b.tokens().size(); i++) {
                rows.add(new Row(b.tokens().get(i), b.tokens().get(i).format(b.tokenBalances().get(i))));
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
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.initOwner(context.window());
            confirm.setTitle("Add Token");
            confirm.setHeaderText("Track " + token.symbol() + " on " + network.getDisplayName() + "?");
            confirm.setContentText("Name: " + token.name() + "\nSymbol: " + token.symbol() + "\nDecimals: " + token.decimals() + "\nContract: " + token.contract()
                    + "\n\nAnyone can deploy a token with any name. Only track contracts from a source you trust.");
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
}
