package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.evm.LitVmNetwork;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.util.StringConverter;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

/**
 * Other EVM networks where the same account works (Ethereum Sepolia, Base Sepolia): pick one, then its own
 * Transactions, Send, Receive, Tokens and Collectibles, the same views the LitVM wallet uses.
 */
class LitVmOtherNetworksView extends BorderPane {
    private final LitVmContext context;
    private final ComboBox<LitVmNetwork> networkCombo = new ComboBox<>();
    private final Map<LitVmNetwork, TabPane> views = new EnumMap<>(LitVmNetwork.class);

    LitVmOtherNetworksView(LitVmContext context) {
        this.context = context;
        networkCombo.setItems(FXCollections.observableArrayList(Arrays.stream(LitVmNetwork.values()).filter(network -> network != LitVmNetwork.LITEFORGE_TESTNET).toList()));
        networkCombo.setConverter(networkConverter());
        networkCombo.valueProperty().addListener((obs, old, network) -> show(network));
        Label warning = new Label("Test networks only. Test coins and tokens on them have no value.");
        warning.setStyle("-fx-font-weight: bold;");
        //On a line of its own and wrapped: beside the network it was the first thing cut at the least window width
        warning.setWrapText(true);
        warning.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        HBox choice = new HBox(12, LitVmContext.formLabel("Network"), networkCombo);
        choice.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        javafx.scene.layout.VBox top = new javafx.scene.layout.VBox(8, choice, warning);
        top.setPadding(new Insets(20, 25, 0, 25));
        setTop(top);
        networkCombo.getSelectionModel().selectFirst();
    }

    static StringConverter<LitVmNetwork> networkConverter() {
        return new StringConverter<>() {
            @Override
            public String toString(LitVmNetwork network) {
                return network == null ? "" : network.getDisplayName() + " (" + network.getSymbol() + ")";
            }

            @Override
            public LitVmNetwork fromString(String string) {
                return null;
            }
        };
    }

    /** Builds a network's views the first time it is picked and keeps them, so each registers for refreshes once. */
    private void show(LitVmNetwork network) {
        if(network == null) {
            return;
        }
        setCenter(views.computeIfAbsent(network, n -> {
            TabPane tabs = new TabPane();
            tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
            //A tab pane keeps every tab's content in the scene, so the collectibles read only while their tab is selected
            LitVmCollectiblesView collectibles = new LitVmCollectiblesView(context, n);
            Tab collectiblesTab = tab(CollectiblesPresenter.SECTION, collectibles);
            collectibles.setActive(false);
            collectiblesTab.selectedProperty().addListener((obs, old, selected) -> collectibles.setActive(selected));
            tabs.getTabs().addAll(tab("Transactions", new LitVmHistoryView(context, n)), tab("Send", new LitVmSendView(context, n)),
                    tab("Receive", new LitVmReceiveView(context, n)), tab("Tokens", new LitVmTokensView(context, n)), collectiblesTab);
            return tabs;
        }));
    }

    private static Tab tab(String name, javafx.scene.Node content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        return new Tab(name, scroll);
    }
}
