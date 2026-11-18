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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    //Sends whose fate is not known: a place of their own, which no status line and no menu click takes away
    private final VBox noticesBox = new VBox(8);
    private final LitVmNotices notices = LitVmNotices.get();
    private final LitVmNotices.Listener noticeListener = new LitVmNotices.Listener() {
        @Override
        public void changed() {
            javafx.application.Platform.runLater(LitVmPane.this::showNotices);
        }

        @Override
        public void confirmed(LitVmNotices.Notice notice, java.math.BigInteger block) {
            javafx.application.Platform.runLater(() -> {
                if(getScene() != null && notice.account().equals(context.address())) {
                    statusLabel.setText(LitVmNotices.confirmedLine(notice, block));
                    context.balancesChanged();
                }
            });
        }
    };

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
                item(CollectiblesPresenter.SECTION, new Glyph("Font Awesome 5 Free Solid", "GEM"), () -> new LitVmCollectiblesView(context, WALLET_NETWORK)),
                item("Swap", new Glyph("FontAwesome", "RANDOM"), () -> new LitVmSwapView(context)),
                item("Bridges", new Glyph("FontAwesome", "EXCHANGE"), this::bridgesView),
                item("Other Networks", new Glyph("FontAwesome", "GLOBE"), () -> new LitVmOtherNetworksView(context)),
                item("Settings", new Glyph("FontAwesome", "COG"), () -> new LitVmSettingsView(context)));
        setLeft(menuBox);

        Label warning = new Label(WALLET_NETWORK.getDisplayName() + " (chain " + WALLET_NETWORK.getChainId() + "). Test " + WALLET_NETWORK.getSymbol() + " and test tokens have no value.");
        warning.setStyle("-fx-font-weight: bold;");
        //Wrapped in full at the least window width, not cut short
        warning.setWrapText(true);
        warning.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        top.getChildren().add(warning);
        top.setPadding(new Insets(12, 25, 0, 25));
        top.managedProperty().bind(top.visibleProperty());
        noticesBox.setPadding(new Insets(10, 25, 0, 25));
        noticesBox.managedProperty().bind(noticesBox.visibleProperty());
        noticesBox.setVisible(false);
        content.setTop(new VBox(top, noticesBox));
        //The notices are followed only while the tab is in a window; what is kept from an earlier run is watched again
        sceneProperty().addListener((obs, old, scene) -> {
            notices.removeListener(noticeListener);
            if(context.link() == null) {
                return;
            }
            if(scene != null) {
                notices.addListener(noticeListener);
                showNotices();
                //What is kept, from this run or an earlier one, is watched while the tab is open: each for what is left
                //of its time in this run, so coming back to the tab starts no watch that has ended
                for(LitVmNotices.Notice notice : notices.of(context.address())) {
                    notices.watch(notice, receipts(notice));
                }
            } else {
                notices.stopWatching(context.address());
            }
        });
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

    /** Shows the account's notices as they are now. FX thread. */
    private void showNotices() {
        if(context.link() == null) {
            return;
        }
        List<Node> nodes = new ArrayList<>();
        for(LitVmNotices.Notice notice : notices.of(context.address())) {
            nodes.add(noticeNode(notice));
        }
        if(!nodes.isEmpty()) {
            for(String line : new String[] {notices.unsaved() ? LitVmNotices.UNSAVED : null, notices.crowded(context.address()) ? LitVmNotices.CROWDED : null}) {
                if(line != null) {
                    Label label = new Label(line);
                    label.setWrapText(true);
                    label.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
                    label.setStyle("-fx-font-weight: bold; -fx-text-fill: #c0392b;");
                    nodes.add(label);
                }
            }
        }
        noticesBox.getChildren().setAll(nodes);
        noticesBox.setVisible(!nodes.isEmpty());
    }

    private LitVmNotices.Receipts receipts(LitVmNotices.Notice notice) {
        com.sparrowwallet.drongo.evm.LitVmRpcClient client = context.client(notice.network());
        return (network, hash) -> client.getReceipt(hash);
    }

    /** The question before a notice with bookkeeping still to do is dismissed. Not shown: the caller shows it. */
    static Alert dismissAlert(javafx.stage.Window owner, LitVmNotices.Notice notice) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "", ButtonType.OK, ButtonType.CANCEL);
        if(owner != null) {
            alert.initOwner(owner);
        }
        alert.setTitle("Dismiss Notice");
        alert.setHeaderText("Dismiss this notice?");
        Label warning = new Label(LitVmNotices.dismissWarning(notice));
        warning.setWrapText(true);
        warning.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        alert.getDialogPane().setContent(warning);
        ((Button)alert.getDialogPane().lookupButton(ButtonType.OK)).setText("Dismiss");
        alert.getDialogPane().setPrefWidth(520);
        return alert;
    }

    /**
     * One notice: the app's sentence for its state, the transaction's own hash, whole at any width, to select or copy,
     * the explorer page the app builds from the network and that hash, another look at the chain, and a way to put the
     * notice away.
     */
    private Node noticeNode(LitVmNotices.Notice notice) {
        String later = LitVmNotices.laterSteps(notice);
        Label sentence = new Label(LitVmNotices.sentence(notice.state()) + (later == null ? "" : " " + later));
        sentence.setWrapText(true);
        sentence.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        sentence.setStyle("-fx-font-weight: bold;");
        //Wraps, so the whole hash shows at the least window width too
        TextArea hash = new TextArea(notice.hash());
        hash.setEditable(false);
        hash.setWrapText(true);
        hash.setPrefRowCount(2);
        hash.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        hash.setStyle("-fx-font-family: monospace;");
        HBox.setHgrow(hash, javafx.scene.layout.Priority.ALWAYS);
        Button copy = new Button("Copy");
        copy.setOnAction(e -> {
            javafx.scene.input.ClipboardContent clip = new javafx.scene.input.ClipboardContent();
            clip.putString(notice.hash());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(clip);
        });
        Hyperlink explorer = new Hyperlink("View in explorer");
        explorer.setOnAction(e -> context.showDocument(notice.network().explorerTxUrl(notice.hash())));
        Button check = new Button("Check Again");
        check.setOnAction(e -> {
            LitVmNotices.Receipts receipts = receipts(notice);
            javafx.concurrent.Task<Boolean> look = new javafx.concurrent.Task<>() {
                @Override
                protected Boolean call() throws Exception {
                    return notices.checkAgain(notice, receipts);
                }
            };
            look.setOnSucceeded(done -> {
                if(!look.getValue()) {
                    statusLabel.setText("No receipt yet for " + notice.hash() + ".");
                }
            });
            look.setOnFailed(done -> statusLabel.setText(LitVmWords.failure("Could not look for the receipt.", look.getException())));
            LitVmContext.start(look);
        });
        Button dismiss = new Button("Dismiss");
        dismiss.setOnAction(e -> {
            //Dismissing gives up bookkeeping the wallet would do on a later receipt: said first
            if(LitVmNotices.dismissWarning(notice) == null || dismissAlert(getScene().getWindow(), notice).showAndWait().filter(ButtonType.OK::equals).isPresent()) {
                notices.dismiss(notice);
            }
        });
        for(Button button : List.of(copy, check, dismiss)) {
            button.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        }
        explorer.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        HBox hashRow = new HBox(8, hash, copy);
        hashRow.setAlignment(Pos.CENTER_LEFT);
        javafx.scene.layout.FlowPane actions = new javafx.scene.layout.FlowPane(8, 6, explorer, check, dismiss);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(6, sentence, new Label(LitVmNotices.where(notice)), hashRow, actions);
        box.getStyleClass().add("litvm-notice");
        //Amber for a send that may still go through, red-brown for one a server reports failed; both read on either theme
        String colour = notice.state() == LitVmNotices.State.FAILED ? "192, 57, 43" : "230, 126, 34";
        box.setStyle("-fx-padding: 8 12 8 12; -fx-background-color: rgba(" + colour + ", 0.16); -fx-border-color: rgb(" + colour + "); -fx-border-width: 0 0 0 4;");
        return box;
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
        //A notice seen confirmed can add a bridge row: the rows are read again with the balances
        context.onBalancesChanged(bridgePane::refresh);
        //The warnings are shown whole (their labels do not shrink), so at a small window the section's scroll pane scrolls
        return box;
    }
}
