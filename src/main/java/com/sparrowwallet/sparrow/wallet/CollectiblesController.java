package com.sparrowwallet.sparrow.wallet;

import com.google.common.eventbus.Subscribe;
import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.Status;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.event.CollectiblesUpdatedEvent;
import com.sparrowwallet.sparrow.event.WalletUtxoStatusChangedEvent;
import com.sparrowwallet.sparrow.net.ord.OrdClient;
import com.sparrowwallet.sparrow.net.ord.OrdInscription;
import com.sparrowwallet.sparrow.net.ord.OrdOutput;
import com.sparrowwallet.sparrow.net.ord.RuneBalance;
import com.sparrowwallet.sparrow.settings.SettingsDialog;
import com.sparrowwallet.sparrow.settings.SettingsGroup;
import com.sparrowwallet.sparrow.wallet.collectibles.CollectibleSendDialog;
import com.sparrowwallet.sparrow.wallet.collectibles.CollectiblesManager;
import com.sparrowwallet.sparrow.wallet.collectibles.InscriptionPreview;
import com.sparrowwallet.sparrow.wallet.collectibles.MoveInDialog;
import com.sparrowwallet.sparrow.wallet.collectibles.ReleaseDialog;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;

import java.net.URL;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The inscriptions and runes held by a Litecoin wallet and the other open wallets of the same seed (an MWEB view
 * shows its public side). What is shown comes from CollectiblesManager's last check; inscription details and
 * content are fetched when first shown and cached.
 */
public class CollectiblesController extends WalletFormController implements Initializable {
    private static final double TILE_SIZE = 120;
    private static final double DETAIL_SIZE = 260;
    private static final int CACHE_SIZE = 64;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private static final ExecutorService fetcher = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Inscription content");
        thread.setDaemon(true);
        return thread;
    });
    //Inscription details and content never change; keyed by server URL and id
    private static final Map<String, Details> details = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Details> eldest) {
            return size() > CACHE_SIZE;
        }
    });

    @FXML
    private Label statusLabel;

    @FXML
    private Button preferencesButton;

    @FXML
    private Button refreshButton;

    @FXML
    private Button moveInButton;

    @FXML
    private TilePane inscriptionTiles;

    @FXML
    private VBox detailPane;

    @FXML
    private TableView<RuneRow> runesTable;

    @FXML
    private Label emptyLabel;

    @FXML
    private Button sendRunesButton;

    private String selectedId;

    private record Holding(Wallet wallet, BlockTransactionHashIndex utxo, OrdOutput output, String serverUrl) {
    }

    private record Details(OrdInscription inscription, byte[] content, String error) {
    }

    public record RuneRow(String name, String symbol, String balance, List<String> outpoints) {
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        EventManager.get().register(this);
    }

    @Override
    public void initializeView() {
        preferencesButton.managedProperty().bind(preferencesButton.visibleProperty());
        emptyLabel.managedProperty().bind(emptyLabel.visibleProperty());
        preferencesButton.setOnAction(event -> {
            SettingsDialog settingsDialog = new SettingsDialog(SettingsGroup.SERVER);
            settingsDialog.initOwner(preferencesButton.getScene().getWindow());
            settingsDialog.showAndWait();
        });
        refreshButton.setOnAction(event -> holdingWallets().forEach(CollectiblesManager.get()::refresh));
        moveInButton.setOnAction(event -> MoveInDialog.start(moveInButton.getScene().getWindow(), getWalletForm()));

        TableColumn<RuneRow, String> name = new TableColumn<>("Rune");
        name.setCellValueFactory(row -> new ReadOnlyStringWrapper(row.getValue().name()));
        TableColumn<RuneRow, String> symbol = new TableColumn<>("Symbol");
        symbol.setCellValueFactory(row -> new ReadOnlyStringWrapper(row.getValue().symbol()));
        TableColumn<RuneRow, String> balance = new TableColumn<>("Balance");
        balance.setCellValueFactory(row -> new ReadOnlyStringWrapper(row.getValue().balance()));
        TableColumn<RuneRow, String> outputs = new TableColumn<>("Outputs");
        outputs.setCellValueFactory(row -> new ReadOnlyStringWrapper(String.valueOf(row.getValue().outpoints().size())));
        outputs.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                RuneRow row = empty || getTableRow() == null ? null : getTableRow().getItem();
                setTooltip(row == null ? null : new Tooltip(String.join("\n", row.outpoints())));
            }
        });
        runesTable.getColumns().setAll(List.of(name, symbol, balance, outputs));
        runesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        runesTable.setPlaceholder(new Label("No runes found"));
        sendRunesButton.disableProperty().bind(runesTable.getSelectionModel().selectedItemProperty().isNull());
        sendRunesButton.setOnAction(event -> CollectibleSendDialog.sendRunes(sendRunesButton.getScene().getWindow(), holdingWallets(),
                runesTable.getSelectionModel().getSelectedItem().name()));

        showDetail(null);
        refresh();
    }

    /** The wallets whose holdings this view shows: see {@link #holdingWallets(Wallet, Collection, Wallet)}. */
    private List<Wallet> holdingWallets() {
        Wallet wallet = getWalletForm().getWallet();
        Wallet linkedPublic = wallet.getScriptType() == ScriptType.MWEB ? WalletLink.getPublicWallet(wallet) : null;
        return holdingWallets(wallet, AppServices.get().getOpenWallets().keySet(), linkedPublic);
    }

    /**
     * The open wallet itself (or, for an MWEB view, its linked public wallet, or the public wallet it is a child of),
     * plus every other open Litecoin wallet of the same seed, such as the taproot wallet minted inscriptions land in.
     */
    static List<Wallet> holdingWallets(Wallet open, Collection<Wallet> openWallets, Wallet linkedPublic) {
        Wallet base = open;
        if(open.getScriptType() == ScriptType.MWEB) {
            base = linkedPublic != null ? linkedPublic : (open.isMasterWallet() ? null : open.getMasterWallet());
        }
        String fingerprint = fingerprint(base != null ? base : open);
        List<Wallet> wallets = new ArrayList<>();
        if(base != null && isHolder(base)) {
            wallets.add(base);
        }
        for(Wallet wallet : openWallets) {
            if(isHolder(wallet) && !wallets.contains(wallet) && fingerprint != null && fingerprint.equals(fingerprint(wallet))) {
                wallets.add(wallet);
            }
        }
        return wallets;
    }

    private static boolean isHolder(Wallet wallet) {
        return wallet.getCoin() == Coin.LITECOIN && wallet.getScriptType() != ScriptType.MWEB;
    }

    private static String fingerprint(Wallet wallet) {
        if(wallet.getKeystores().isEmpty() || wallet.getKeystores().getFirst().getKeyDerivation() == null) {
            return null;
        }
        return wallet.getKeystores().getFirst().getKeyDerivation().getMasterFingerprint();
    }

    @Subscribe
    public void collectiblesUpdated(CollectiblesUpdatedEvent event) {
        if(holdingWallets().contains(event.getWallet())) {
            Platform.runLater(this::refresh);
        }
    }

    @Subscribe
    public void walletUtxoStatusChanged(WalletUtxoStatusChangedEvent event) {
        if(holdingWallets().contains(event.getWallet())) {
            Platform.runLater(this::refresh);
        }
    }

    private void refresh() {
        List<Wallet> wallets = holdingWallets();
        List<String> off = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        //One line per server, with its latest check
        Map<String, java.time.Instant> checked = new LinkedHashMap<>();
        boolean noRuneIndex = false;
        Map<String, Holding> inscriptions = new LinkedHashMap<>();
        Map<String, RuneBalance> runeTotals = new TreeMap<>();
        Map<String, List<String>> runeOutputs = new HashMap<>();

        for(Wallet wallet : wallets) {
            String url = CollectiblesManager.serverUrl(wallet);
            CollectiblesManager.Snapshot snapshot = CollectiblesManager.get().getSnapshot(wallet);
            if(url == null) {
                off.add(wallet.getFullDisplayName());
                continue;
            }
            if(snapshot == null || !snapshot.enabled()) {
                continue;
            }
            if(!snapshot.runesIndexed()) {
                noRuneIndex = true;
            }
            if(snapshot.error() != null) {
                errors.add("Could not reach " + snapshot.serverHost() + ": " + snapshot.error() + ". Outputs not yet checked count as unknown.");
            } else {
                checked.merge(snapshot.serverHost(), snapshot.checkedAt(), (a, b) -> a.isAfter(b) ? a : b);
            }
            for(BlockTransactionHashIndex utxo : wallet.getWalletUtxos().keySet()) {
                OrdOutput output = snapshot.answers().get(utxo.toString());
                if(output == null || !output.carries()) {
                    continue;
                }
                for(String id : output.inscriptions()) {
                    inscriptions.putIfAbsent(id, new Holding(wallet, utxo, output, url));
                }
                for(Map.Entry<String, RuneBalance> rune : output.runes().entrySet()) {
                    runeTotals.merge(rune.getKey(), rune.getValue(), RuneBalance::add);
                    runeOutputs.computeIfAbsent(rune.getKey(), k -> new ArrayList<>()).add(utxo.toString());
                }
            }
        }

        if(!off.isEmpty()) {
            statusLabel.setText("Inscription checks are off. Turn them on in Preferences > Server > Inscriptions. Until then, inscriptions and runes in "
                    + (off.size() == 1 ? "this wallet are" : "these wallets are") + " not protected.");
        } else if(!errors.isEmpty()) {
            statusLabel.setText(errors.getFirst());
        } else if(!checked.isEmpty()) {
            statusLabel.setText(checked.entrySet().stream().map(e -> "Checked with " + e.getKey() + " at " + TIME_FORMAT.format(e.getValue()) + ".")
                    .collect(java.util.stream.Collectors.joining(" "))
                    + (noRuneIndex ? " This server does not index runes, so outputs without inscriptions count as unchecked (start ord with --index-runes)." : ""));
        } else {
            statusLabel.setText("Checking outputs for inscriptions and runes...");
        }
        preferencesButton.setVisible(!off.isEmpty());

        inscriptionTiles.getChildren().clear();
        for(Map.Entry<String, Holding> entry : inscriptions.entrySet()) {
            inscriptionTiles.getChildren().add(tile(entry.getKey(), entry.getValue()));
        }
        List<RuneRow> rows = new ArrayList<>();
        for(Map.Entry<String, RuneBalance> rune : runeTotals.entrySet()) {
            rows.add(new RuneRow(rune.getKey(), rune.getValue().symbol(), rune.getValue().format(), runeOutputs.get(rune.getKey())));
        }
        runesTable.getItems().setAll(rows);

        boolean nothing = inscriptions.isEmpty() && rows.isEmpty() && off.isEmpty();
        emptyLabel.setVisible(nothing);
        emptyLabel.setText("Nothing found. If your inscriptions are in a Litescribe wallet, restore its seed here as two wallets: Taproot (P2TR) at m/86'/2'/0' " +
                "and Native Segwit (P2WPKH) at m/84'/2'/0'. Advanced: some Litescribe wallets use m/44'/2'/0' paths; set a custom derivation in the wallet Settings.");

        Holding selected = selectedId == null ? null : inscriptions.get(selectedId);
        showDetail(selected == null ? null : selectedId);
        if(selected == null) {
            selectedId = null;
        }
    }

    private Node tile(String id, Holding holding) {
        VBox tile = new VBox(4);
        tile.getStyleClass().add("inscription-tile");
        if(id.equals(selectedId)) {
            tile.getStyleClass().add("selected");
        }
        Label number = new Label(id.substring(0, 8) + "…");
        tile.getChildren().addAll(InscriptionPreview.placeholder("Loading", TILE_SIZE), number);
        tile.setUserData(holding);
        tile.setOnMouseClicked(event -> {
            selectedId = id;
            inscriptionTiles.getChildren().forEach(node -> node.getStyleClass().remove("selected"));
            tile.getStyleClass().add("selected");
            showDetail(id);
        });
        load(id, holding, loaded -> {
            tile.getChildren().set(0, preview(loaded, TILE_SIZE));
            if(loaded.inscription() != null) {
                number.setText("#" + loaded.inscription().number());
            }
        });
        return tile;
    }

    private void showDetail(String id) {
        detailPane.getChildren().clear();
        if(id == null) {
            detailPane.getChildren().add(new Label("Select an inscription to see its details."));
            return;
        }
        Holding holding = inscriptionTiles.getChildren().stream().map(Node::getUserData).filter(Holding.class::isInstance).map(Holding.class::cast)
                .filter(h -> h.output().inscriptions().contains(id)).findFirst().orElse(null);
        if(holding == null) {
            return;
        }
        load(id, holding, loaded -> {
            if(!id.equals(selectedId)) {
                return;
            }
            detailPane.getChildren().clear();
            detailPane.getChildren().add(preview(loaded, DETAIL_SIZE));
            OrdInscription inscription = loaded.inscription();
            addDetail("Inscription", id);
            if(inscription != null) {
                addDetail("Number", String.valueOf(inscription.number()));
                addDetail("Content type", inscription.contentType() == null ? "None" : inscription.contentType());
                addDetail("Size", inscription.contentLength() < 0 ? "Unknown" : inscription.contentLength() + " bytes");
                if(inscription.satpoint() != null) {
                    addDetail("Sat offset", inscription.satpoint().substring(inscription.satpoint().lastIndexOf(':') + 1));
                }
            }
            addDetail("Output", holding.utxo().toString());
            addDetail("Wallet", holding.wallet().getFullDisplayName());
            if(loaded.error() != null) {
                addDetail("Note", loaded.error());
            }

            Hyperlink explorer = new Hyperlink("Open in explorer");
            explorer.setOnAction(event -> AppServices.get().getApplication().getHostServices().showDocument(new OrdClient(holding.serverUrl()).explorerUrl(id)));
            detailPane.getChildren().add(explorer);
            if(inscription != null && inscription.satpoint() != null) {
                Button send = new Button("Send...");
                send.setOnAction(event -> CollectibleSendDialog.sendInscription(send.getScene().getWindow(), holding.wallet(), id, inscription.satpoint(), holding.output()));
                detailPane.getChildren().add(send);
            }
            if(holding.utxo().getStatus() == Status.PROTECTED) {
                Button release = new Button("Spend as plain LTC...");
                release.setOnAction(event -> ReleaseDialog.release(holding.wallet(), holding.utxo(), holding.output()));
                detailPane.getChildren().add(release);
            } else if(holding.utxo().getStatus() == Status.RELEASED) {
                detailPane.getChildren().add(new Label("Released: this output can be spent as plain LTC."));
            }
        });
    }

    private void addDetail(String name, String value) {
        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().add("detail-name");
        Label valueLabel = new Label(value);
        valueLabel.setWrapText(true);
        detailPane.getChildren().addAll(nameLabel, valueLabel);
    }

    private static Node preview(Details loaded, double size) {
        if(loaded.content() == null) {
            return InscriptionPreview.placeholder(loaded.error() != null ? loaded.error()
                    : (loaded.inscription() != null && loaded.inscription().contentType() != null ? loaded.inscription().contentType() : "No preview"), size);
        }
        String key = loaded.inscription() == null ? null : loaded.inscription().id();
        return InscriptionPreview.node(key, loaded.inscription() == null ? null : loaded.inscription().contentType(), loaded.content(), size);
    }

    /** Details and content for an inscription, from the cache or fetched in the background, handed back on the FX thread. */
    private static void load(String id, Holding holding, java.util.function.Consumer<Details> consumer) {
        String key = holding.serverUrl() + "|" + id;
        Details cached = details.get(key);
        if(cached != null) {
            consumer.accept(cached);
            return;
        }
        Coin coin = holding.wallet().getCoin();
        fetcher.submit(() -> {
            Details loaded;
            Coin previous = Coin.beginScope(coin);
            try {
                OrdClient client = OrdClient.forApp(holding.serverUrl());
                OrdInscription inscription = client.inscription(id);
                if(inscription.contentLength() > OrdClient.MAX_CONTENT_BYTES) {
                    loaded = new Details(inscription, null, "Too large to preview");
                } else {
                    byte[] content;
                    try {
                        content = client.content(id);
                    } catch(OrdClient.ContentTooLargeException e) {
                        content = null;
                    }
                    loaded = new Details(inscription, content, content == null ? "Too large to preview" : null);
                }
                details.put(key, loaded);
            } catch(Exception e) {
                loaded = new Details(null, null, "Could not load: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            } finally {
                Coin.endScope(previous);
            }
            Details result = loaded;
            Platform.runLater(() -> consumer.accept(result));
        });
    }
}
