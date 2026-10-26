package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.evm.LitVmNetwork;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.wallet.WalletForm;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.controlsfx.glyphfont.Glyph;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Content of the LitVM sub-tab under a Litecoin wallet, laid out like the wallet itself: a zkLTC wallet on LitVM
 * (Transactions, Send, Receive), then an EVM section for Tokens, Swap, Bridges, the other EVM networks the same
 * account works on, and Settings. The address comes from the stored link, so viewing needs no password; sending
 * asks for the wallet password (when it has one) to unlock the key just long enough to sign.
 */
public class LitVmPane extends BorderPane {
    static final LitVmNetwork WALLET_NETWORK = LitVmNetwork.LITEFORGE_TESTNET;

    private final LitVmContext context;
    private final Label statusLabel = new Label();
    private final BorderPane content = new BorderPane();
    private final Map<String, Node> views = new HashMap<>();
    private final ToggleGroup menu = new ToggleGroup();
    private final HBox top = new HBox();

    public LitVmPane(WalletForm walletForm) {
        getStylesheets().add(AppServices.class.getResource("wallet/wallet.css").toExternalForm());
        context = new LitVmContext(walletForm, statusLabel::setText, () -> getScene().getWindow());

        VBox menuBox = new VBox();
        menuBox.getStyleClass().add("list-menu");
        menuBox.getChildren().addAll(
                item("Transactions", new Glyph("FontAwesome", "HISTORY"), () -> new LitVmHistoryView(context, WALLET_NETWORK)),
                item("Send", new Glyph("FontAwesome", "SEND"), () -> new LitVmSendView(context, WALLET_NETWORK)),
                item("Receive", new Glyph("FontAwesome", "ARROW_DOWN"), () -> new LitVmReceiveView(context, WALLET_NETWORK)),
                sectionLabel("EVM"),
                item("Tokens", new Glyph("Font Awesome 5 Free Solid", "COINS"), () -> new LitVmTokensView(context, WALLET_NETWORK)),
                item("Swap", new Glyph("FontAwesome", "RANDOM"), () -> new LitVmSwapView(context)),
                item("Bridges", new Glyph("FontAwesome", "EXCHANGE"), this::bridgesView),
                item("Other Networks", new Glyph("FontAwesome", "GLOBE"), () -> new LitVmOtherNetworksView(context)),
                item("Settings", new Glyph("FontAwesome", "COG"), () -> new LitVmSettingsView(context)));
        setLeft(menuBox);

        Label warning = new Label(WALLET_NETWORK.getDisplayName() + " (chain " + WALLET_NETWORK.getChainId() + "). Test " + WALLET_NETWORK.getSymbol() + " and test tokens have no value.");
        warning.setStyle("-fx-font-weight: bold;");
        top.getChildren().add(warning);
        top.setPadding(new Insets(12, 25, 0, 25));
        top.managedProperty().bind(top.visibleProperty());
        content.setTop(top);
        statusLabel.setWrapText(true);
        statusLabel.setPadding(new Insets(6, 25, 10, 25));
        content.setBottom(statusLabel);
        setCenter(content);

        menu.selectedToggleProperty().addListener((obs, old, selected) -> {
            if(selected == null) {
                //Keep one view selected, as the wallet menu does
                menu.selectToggle(old);
            }
        });
        menu.getToggles().getFirst().setSelected(true);
    }

    private ToggleButton item(String name, Glyph glyph, Supplier<Node> view) {
        glyph.setFontSize(20);
        ToggleButton button = new ToggleButton(name, glyph);
        button.setContentDisplay(ContentDisplay.TOP);
        button.setWrapText(true);
        button.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        button.getStyleClass().add("list-item");
        button.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(button, javafx.scene.layout.Priority.ALWAYS);
        button.setToggleGroup(menu);
        //Views are built on first use so opening the tab does not query every network at once
        button.selectedProperty().addListener((obs, old, selected) -> {
            if(selected) {
                statusLabel.setText("");
                //Other Networks shows its own network and warning
                top.setVisible(!name.equals("Other Networks"));
                content.setCenter(views.computeIfAbsent(name, n -> scroll(view.get())));
            }
        });
        return button;
    }

    private static Label sectionLabel(String text) {
        Label label = new Label(text);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setAlignment(Pos.CENTER);
        label.setPadding(new Insets(6, 0, 6, 0));
        label.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-background-color: derive(#3da0e3, -25%);");
        return label;
    }

    private static Node scroll(Node view) {
        if(view instanceof BorderPane) {
            return view;
        }
        ScrollPane scroll = new ScrollPane(view);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        return scroll;
    }

    private Node bridgesView() {
        LitVmBridgePane bridgePane = new LitVmBridgePane(context.walletForm, context.client(WALLET_NETWORK), context.statusConsumer(), context::balancesChanged);
        Label title = new Label("Bridges");
        title.setStyle("-fx-font-size: 1.2em;");
        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> bridgePane.refresh());
        VBox box = new VBox(14, new HBox(12, title, refresh), bridgePane);
        box.setPadding(new Insets(20, 25, 20, 25));
        bridgePane.refresh();
        return box;
    }
}
