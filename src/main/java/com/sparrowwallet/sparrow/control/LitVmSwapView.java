package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.evm.LitVmNetwork;
import com.sparrowwallet.drongo.evm.SwapVenue;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.Arrays;
import java.util.List;

/** The Swap view: a choice of the networks that have a swap venue (LiteForge first), around the swap form. */
class LitVmSwapView extends VBox {
    private final LitVmContext context;
    private final ComboBox<LitVmNetwork> networkCombo = new ComboBox<>();
    private final LitVmSwapPane swapPane;

    LitVmSwapView(LitVmContext context) {
        super(14);
        this.context = context;
        setPadding(new Insets(20, 25, 20, 25));

        Label title = new Label("Swap");
        title.setStyle("-fx-font-size: 1.2em;");
        List<LitVmNetwork> networks = Arrays.stream(LitVmNetwork.values()).filter(network -> !SwapVenue.forNetwork(network).isEmpty()).toList();
        networkCombo.setItems(FXCollections.observableArrayList(networks));
        networkCombo.setConverter(LitVmOtherNetworksView.networkConverter());
        networkCombo.setValue(networks.contains(LitVmNetwork.LITEFORGE_TESTNET) ? LitVmNetwork.LITEFORGE_TESTNET : networks.getFirst());
        swapPane = new LitVmSwapPane(context.walletForm, context.statusConsumer(), context::balancesChanged);
        networkCombo.valueProperty().addListener((obs, old, network) -> refresh());

        getChildren().addAll(title, new HBox(10, new Label("Network"), networkCombo), swapPane);
        context.onBalancesChanged(this::refresh);
        refresh();
    }

    /** Re-reads the tokens tracked on the chosen network so they can be swapped too. */
    void refresh() {
        LitVmNetwork network = networkCombo.getValue();
        Task<LitVmContext.Balances> task = new Task<>() {
            @Override
            protected LitVmContext.Balances call() throws Exception {
                return context.readBalances(network);
            }
        };
        task.setOnSucceeded(e -> {
            if(network == networkCombo.getValue()) {
                swapPane.setNetwork(context.client(network), task.getValue().tokens());
            }
        });
        task.setOnFailed(e -> {
            if(network == networkCombo.getValue()) {
                swapPane.setNetwork(context.client(network), List.of());
                context.status("Could not reach " + network.getDisplayName() + ": " + LitVmContext.message(task.getException()));
            }
        });
        LitVmContext.start(task);
    }
}
