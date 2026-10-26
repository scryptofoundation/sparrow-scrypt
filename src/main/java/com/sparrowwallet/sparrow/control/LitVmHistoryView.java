package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.evm.*;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.math.BigInteger;
import java.net.URI;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The Transactions view of an EVM network: the native balance, the tracked tokens' balances, and the address's
 * history from the network's explorer (newest first; double-click a row to open it in the explorer).
 */
class LitVmHistoryView extends VBox {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final LitVmContext context;
    private final LitVmNetwork network;
    private final Label balance = new Label("Loading...");
    private final Label tokens = new Label();
    private final TableView<Row> table = new TableView<>();
    private final Label source = new Label();

    private record Row(String time, String description, String amount, String fee, String status, String hash) {}

    LitVmHistoryView(LitVmContext context, LitVmNetwork network) {
        super(12);
        this.context = context;
        this.network = network;
        setPadding(new Insets(20, 25, 20, 25));

        Label title = new Label("Transactions");
        title.setStyle("-fx-font-size: 1.2em;");
        balance.setStyle("-fx-font-size: 1.4em;");
        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> refresh());

        table.getColumns().add(column("Date", Row::time, 130));
        table.getColumns().add(column("Description", Row::description, 360));
        table.getColumns().add(column("Value (" + network.getSymbol() + ")", Row::amount, 170));
        table.getColumns().add(column("Fee", Row::fee, 120));
        table.getColumns().add(column("Status", Row::status, 110));
        table.setPlaceholder(new Label("No transactions"));
        table.setRowFactory(view -> {
            TableRow<Row> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if(event.getClickCount() == 2 && !row.isEmpty()) {
                    context.showDocument(network.explorerTxUrl(row.getItem().hash()));
                }
            });
            return row;
        });
        VBox.setVgrow(table, Priority.ALWAYS);
        source.setStyle("-fx-text-fill: -fx-mid-text-color;");
        source.setText("History from the " + URI.create(network.getExplorerUrl()).getHost() + " explorer (display only; balances come from the network). "
                + "Double-click a row to open it there. Value moved inside contract calls is not listed.");
        source.setWrapText(true);

        getChildren().addAll(title, new HBox(12, new Label("Balance"), balance, refresh), tokens, table, source);
        context.onBalancesChanged(this::refresh);
        refresh();
    }

    private static TableColumn<Row, String> column(String name, java.util.function.Function<Row, String> value, double width) {
        TableColumn<Row, String> column = new TableColumn<>(name);
        column.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(value.apply(data.getValue())));
        column.setPrefWidth(width);
        return column;
    }

    void refresh() {
        balance.setText("Loading...");
        EvmAddress me = context.address();
        Task<LitVmContext.Balances> balances = new Task<>() {
            @Override
            protected LitVmContext.Balances call() throws Exception {
                return context.readBalances(network);
            }
        };
        balances.setOnSucceeded(e -> {
            LitVmContext.Balances b = balances.getValue();
            balance.setText(Wei.format(b.nativeBalance()) + " " + network.getSymbol());
            List<String> parts = new ArrayList<>();
            for(int i = 0; i < b.tokens().size(); i++) {
                parts.add(b.tokens().get(i).format(b.tokenBalances().get(i)) + " " + b.tokens().get(i).symbol());
            }
            tokens.setText(parts.isEmpty() ? "" : "Tokens: " + String.join(",  ", parts));
        });
        balances.setOnFailed(e -> {
            balance.setText("Unavailable");
            context.status("Could not reach " + network.getDisplayName() + ": " + LitVmContext.message(balances.getException()));
        });
        LitVmContext.start(balances);

        Task<List<BlockscoutClient.Entry>> history = new Task<>() {
            @Override
            protected List<BlockscoutClient.Entry> call() throws Exception {
                return new BlockscoutClient(network).history(me);
            }
        };
        history.setOnSucceeded(e -> table.setItems(FXCollections.observableArrayList(history.getValue().stream().map(entry -> row(entry, me)).toList())));
        history.setOnFailed(e -> table.setPlaceholder(new Label("History unavailable: " + LitVmContext.message(history.getException()))));
        LitVmContext.start(history);
    }

    private Row row(BlockscoutClient.Entry entry, EvmAddress me) {
        List<String> parts = new ArrayList<>();
        for(BlockscoutClient.TokenTransfer transfer : entry.tokenTransfers()) {
            boolean in = me.equals(transfer.to());
            boolean out = me.equals(transfer.from());
            parts.add((in && !out ? "Received " : out && !in ? "Sent " : "Moved ") + transfer.format() + " " + transfer.symbol());
        }
        boolean sender = me.equals(entry.from());
        if(entry.value().signum() > 0) {
            parts.add(0, sender ? "Sent to " + (entry.to() == null ? "new contract" : LitVmContext.shortAddress(entry.to()))
                    : "Received from " + LitVmContext.shortAddress(entry.from()));
        } else if(parts.isEmpty() || entry.method() != null && entry.tokenTransfers().isEmpty()) {
            parts.add(0, "Contract call" + (entry.method() == null ? "" : ": " + entry.method()));
        }
        BigInteger change = entry.nativeChange(me);
        String amount = change.signum() == 0 ? "" : (change.signum() > 0 ? "+" : "") + Wei.format(change);
        String fee = sender && entry.fee().signum() > 0 ? Wei.format(entry.fee()) : "";
        String status = entry.failed() ? "Failed" : entry.block() == null ? "Pending" : "Block " + entry.block();
        return new Row(entry.time() == null ? "" : TIME.format(entry.time()), String.join("; ", parts), amount, fee, status, entry.hash());
    }
}
