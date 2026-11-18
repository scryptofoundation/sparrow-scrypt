package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.evm.*;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.wallet.collectibles.InscriptionPreview;
import javafx.animation.AnimationTimer;
import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.NodeOrientation;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.math.BigInteger;
import java.util.*;

import static com.sparrowwallet.sparrow.control.CollectiblesPresenter.*;

/**
 * The Collectibles section of an EVM network: the NFTs the account holds in the built-in collections and the ones the
 * user added, as tiles with their pictures, and the selected one's detail. Read only: nothing here signs or sends.
 *
 * Everything a collection says about itself (names, descriptions, attributes) is attacker-chosen text. It is shown only
 * as plain text, each value in its own left-to-right label with a maximum width, never joined with the app's own words
 * in one node, and in the detail only inside a box of its own below the app's rows. Every tile and the detail carry the
 * app's mark of where the collection comes from, and a warning when an added collection takes a built-in one's name.
 * The only link is the explorer page the app builds from the network and the contract address.
 *
 * The section reads only while it is shown: in a scene, every enclosing tab selected (its section's tab under Other
 * Networks, the LitVM sub-tab, the wallet's tab) and every ancestor visible. Pictures are laid out off the FX thread
 * with the listing, at most {@link CollectiblesPresenter#MAX_TILES} tiles are built, and their pictures are drawn
 * within {@link CollectiblesPresenter#FRAME_COMMANDS} path commands a frame. A result that arrives after the view left
 * its window is dropped quietly.
 *
 * What the pictures hold is bounded as a whole, not only one by one: a listing's drawings are laid out for the tile size
 * only and within one {@link NftPicture.Budget} (a picture past it is the "No picture" placeholder), a tile's plan is
 * released as soon as its picture is drawn (the rendered image stays on screen), and a hidden view releases the plans
 * not yet drawn. So a view keeps at most one listing's budget, and only for the frames it takes to draw it. What was
 * released and is needed again is planned again from the listing's own metadata, off the FX thread: the tiles a hidden
 * view had not drawn, when it is shown again (whether or not its next read succeeds), and the detail's picture, when it
 * opens.
 */
class LitVmCollectiblesView extends VBox {
    private static final double TEXT_WIDTH = 480;
    private static final String WARNING_STYLE = "-fx-text-fill: #c0392b; -fx-font-weight: bold;";

    private final LitVmContext context;
    private final LitVmNetwork network;
    private final TilePane tiles = new TilePane(10, 10);
    private final Label empty = new Label("Loading...");
    private final VBox notes = new VBox(4);
    private final VBox detail = new VBox(8);
    //The last name each collection gave, to name it when it later cannot be read
    private final Map<EvmAddress, String> names = new HashMap<>();
    private List<Shown> shown = new ArrayList<>();
    private Key selected;

    private final RefreshGate gate = new RefreshGate();
    private Task<Loaded> running;
    private boolean active = true;
    private boolean showing;
    //What decides whether the view is shown: its ancestors' visibility and every enclosing tab pane's selection
    private final InvalidationListener shownListener = obs -> updateShowing();
    private final List<Observable> shownBy = new ArrayList<>();

    //Tile pictures still to draw, each with its cost in path commands, a frame's budget at a time
    private record Pending(Key key, int cost, Runnable draw) {}

    //Tiles whose pictures were let go undrawn when the view was hidden: planned again when it is shown
    private final List<Key> undrawn = new ArrayList<>();
    //Counts the listings shown, so a picture planned for an earlier one is not put into a later one
    private int listing;

    private final ArrayDeque<Pending> pictures = new ArrayDeque<>();
    private final AnimationTimer painter = new AnimationTimer() {
        @Override
        public void handle(long now) {
            if(drawPictures(FRAME_COMMANDS) == 0) {
                stop();
            }
        }
    };

    private record Key(EvmAddress contract, BigInteger tokenId) {}

    record Shown(Nft nft, NftPicture.Plan picture, boolean known, String lookalike) {}

    record Loaded(List<LitVmContext.CollectionResult> results, List<Shown> shown, int hidden) {}

    LitVmCollectiblesView(LitVmContext context, LitVmNetwork network) {
        super(12);
        this.context = context;
        this.network = network;
        getStylesheets().add(AppServices.class.getResource("wallet/collectibles.css").toExternalForm());
        setPadding(new Insets(20, 25, 20, 25));

        Label title = new Label(SECTION + " on " + network.getDisplayName());
        title.getStyleClass().add("collectibles-title");
        Button add = new Button(ADD_COLLECTION + "...");
        add.setOnAction(e -> addCollection());
        Button remove = new Button("Remove collection...");
        remove.setOnAction(e -> removeCollection());
        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> refresh());

        tiles.setPrefColumns(4);
        empty.setWrapText(true);
        empty.managedProperty().bind(empty.visibleProperty());
        notes.managedProperty().bind(notes.visibleProperty());
        detail.getStyleClass().add("inscription-detail");
        Label note = new Label("Anyone can send an NFT to any account, and a collection's name proves nothing. Only the collections built in "
                + "and the ones you add are listed, by contract address. Nothing here sends or signs.");
        note.setWrapText(true);
        //A wrapped label is never squeezed to one elided line when the section is short of room: it scrolls instead
        note.setMinHeight(Region.USE_PREF_SIZE);
        note.getStyleClass().add("collectible-muted");

        getChildren().addAll(title, new HBox(8, add, remove, refresh), empty, tiles, notes, new Separator(), detail, note);
        showDetail(null);

        context.onBalancesChanged(this::refresh);
        sceneProperty().addListener((obs, old, scene) -> {
            watchAncestors();
            updateShowing();
        });
    }

    /** Listens to what decides whether the view is shown, from its parent up, replacing what it listened to before. */
    private void watchAncestors() {
        shownBy.forEach(observable -> observable.removeListener(shownListener));
        shownBy.clear();
        if(getScene() == null) {
            return;
        }
        for(Node node = this; node != null; node = node.getParent()) {
            shownBy.add(node.visibleProperty());
            if(node instanceof TabPane pane) {
                shownBy.add(pane.getSelectionModel().selectedItemProperty());
            }
        }
        shownBy.forEach(observable -> observable.addListener(shownListener));
    }

    /** In a scene, every ancestor visible, and in every enclosing tab pane the selected tab is the one holding the view. */
    private boolean shownInScene() {
        if(getScene() == null) {
            return false;
        }
        for(Node node = this; node != null; node = node.getParent()) {
            if(!node.isVisible()) {
                return false;
            }
            if(node instanceof TabPane pane) {
                Tab selected = pane.getSelectionModel().getSelectedItem();
                if(selected == null || !holds(selected.getContent())) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean holds(Node content) {
        for(Node node = this; node != null; node = node.getParent()) {
            if(node == content) {
                return true;
            }
        }
        return false;
    }

    /** The view's window, or null once it has left it (its wallet closed, or another section shown). */
    private Window owner() {
        return getScene() == null ? null : getScene().getWindow();
    }

    /**
     * Whether the view's place is selected (its tab under Other Networks); the view reads only while active and in a
     * scene, since a tab pane keeps every tab's content in the scene.
     */
    void setActive(boolean active) {
        this.active = active;
        updateShowing();
    }

    private void updateShowing() {
        boolean now = active && shownInScene();
        if(now == showing) {
            return;
        }
        showing = now;
        if(now) {
            start(gate.show());
            if(!pictures.isEmpty()) {
                painter.start();
            }
            planUndrawn();
        } else {
            //Leaving the section or closing the wallet abandons a read in progress; its result is dropped
            if(gate.hide() && running != null) {
                running.cancel(true);
            }
            running = null;
            painter.stop();
            //A hidden view holds no drawings: the tiles not yet drawn are planned again when it is shown
            releasePlans();
        }
    }

    /** Lets go of every plan's drawing and of the pictures still waiting to be drawn, remembering which tiles those were. */
    private void releasePlans() {
        for(Pending pending : pictures) {
            if(!undrawn.contains(pending.key())) {
                undrawn.add(pending.key());
            }
        }
        pictures.clear();
        shown.replaceAll(item -> item.picture().isReleased() ? item : new Shown(item.nft(), item.picture().released(), item.known(), item.lookalike()));
    }

    /**
     * Plans again, off the FX thread and within one listing's budget, the pictures of the tiles a hidden view let go
     * undrawn, and queues them to be drawn. It needs nothing but the listing the view still holds, so the tiles are not
     * left blank when the read that follows fails.
     */
    private void planUndrawn() {
        if(undrawn.isEmpty()) {
            return;
        }
        int forListing = listing;
        double scale = NftPicture.screenScale();
        List<Shown> again = shown.stream().filter(item -> undrawn.contains(key(item.nft()))).toList();
        Task<List<NftPicture.Plan>> task = new Task<>() {
            @Override
            protected List<NftPicture.Plan> call() {
                NftPicture.Budget budget = new NftPicture.Budget(NftPicture.LISTING_UNITS);
                List<NftPicture.Plan> plans = new ArrayList<>();
                for(Shown item : again) {
                    plans.add(NftPicture.plan(item.nft().metadata(), scale, budget));
                }
                return plans;
            }
        };
        task.setOnSucceeded(e -> {
            //A later listing has its own plans; a view hidden again keeps its list for the next time
            if(forListing != listing || !showing) {
                return;
            }
            for(int i = 0; i < again.size(); i++) {
                Key key = key(again.get(i).nft());
                for(int index = 0; index < shown.size() && index < tiles.getChildren().size(); index++) {
                    if(key(shown.get(index).nft()).equals(key) && undrawn.contains(key) && tiles.getChildren().get(index) instanceof VBox tile) {
                        Shown planned = new Shown(shown.get(index).nft(), task.getValue().get(i), shown.get(index).known(), shown.get(index).lookalike());
                        shown.set(index, planned);
                        queue(tile, planned);
                    }
                }
            }
            undrawn.clear();
            if(!pictures.isEmpty()) {
                painter.start();
            }
        });
        LitVmContext.start(task);
    }

    /** The tiles whose pictures are still to be drawn or planned again (0 once the listing is drawn). */
    int waitingPictures() {
        return pictures.size() + undrawn.size();
    }

    /** Lets go of one tile's plan once its picture is drawn: the image stays, the drawing does not. */
    private void release(Shown drawn) {
        for(int i = 0; i < shown.size(); i++) {
            if(shown.get(i) == drawn) {
                shown.set(i, new Shown(drawn.nft(), drawn.picture().released(), drawn.known(), drawn.lookalike()));
                return;
            }
        }
    }

    /** What the view's plans hold now, in a budget's units (0 once every picture is drawn or the view is hidden). */
    int heldUnits() {
        return shown.stream().mapToInt(item -> item.picture().weight()).sum();
    }

    void refresh() {
        start(gate.request());
    }

    private void start(int ticket) {
        if(ticket < 0) {
            return;
        }
        double scale = NftPicture.screenScale();
        Task<Loaded> task = new Task<>() {
            @Override
            protected Loaded call() throws Exception {
                return load(network, context.readCollectibles(network), scale);
            }
        };
        task.setOnSucceeded(e -> {
            if(!gate.finish(ticket)) {
                return;
            }
            running = null;
            show(task.getValue());
            start(gate.next());
        });
        task.setOnFailed(e -> {
            if(!gate.finish(ticket)) {
                return;
            }
            running = null;
            //What was shown stays shown
            //Only the app's own words: never the server's message
            context.status(failureSentence(task.getException()));
            if(shown.isEmpty()) {
                empty.setText("Collectibles unavailable.");
            }
            start(gate.next());
        });
        running = task;
        LitVmContext.start(task);
    }

    /**
     * What a read shows: the tiles in order, capped, each with its picture planned. Pictures are parsed and laid out
     * here, off the FX thread, once per token for the tile, all within one listing's budget.
     */
    static Loaded load(LitVmNetwork network, List<LitVmContext.CollectionResult> results, double scale) {
        List<Nft> tokens = new ArrayList<>();
        Map<EvmAddress, LitVmContext.CollectionResult> byContract = new HashMap<>();
        for(LitVmContext.CollectionResult result : results) {
            tokens.addAll(result.tokens());
            byContract.put(result.contract(), result);
        }
        TileCap cap = capTiles(CollectiblesPresenter.order(network, tokens));
        List<Shown> shown = new ArrayList<>();
        NftPicture.Budget budget = new NftPicture.Budget(NftPicture.LISTING_UNITS);
        for(Nft nft : cap.shown()) {
            LitVmContext.CollectionResult result = byContract.get(nft.collection().contract());
            boolean known = result != null && result.known();
            String lookalike = result == null ? null : result.lookalike().orElse(null);
            shown.add(new Shown(nft, NftPicture.plan(nft.metadata(), scale, budget), known, lookalike));
        }
        return new Loaded(results, shown, cap.hidden());
    }

    /** Draws the tile pictures still waiting that fit this many path commands (at least one); the number still waiting after. FX thread. */
    int drawPictures(int budget) {
        List<Integer> costs = new ArrayList<>();
        for(Pending pending : pictures) {
            costs.add(pending.cost());
            if(costs.size() > MAX_TILES) {
                break;
            }
        }
        int count = picturesThisFrame(costs, budget);
        for(int i = 0; i < count; i++) {
            pictures.poll().draw().run();
        }
        return pictures.size();
    }

    /** Selects the tile at this index, as a click on it does. FX thread. */
    void selectTile(int index) {
        if(index >= 0 && index < tiles.getChildren().size()) {
            choose(tiles.getChildren().get(index), shown.get(index));
        }
    }

    private void choose(Node tile, Shown item) {
        selected = key(item.nft());
        tiles.getChildren().forEach(node -> node.getStyleClass().remove("selected"));
        tile.getStyleClass().add("selected");
        showDetail(item);
    }

    /** Shows what a read gave: tiles, the collections' lines, the empty state, and the selected tile's detail. FX thread. */
    void show(Loaded loaded) {
        shown = new ArrayList<>(loaded.shown());
        listing++;
        undrawn.clear();
        for(LitVmContext.CollectionResult result : loaded.results()) {
            if(result.collection() != null) {
                names.put(result.contract(), collectionLabel(result.collection()));
            }
        }
        pictures.clear();
        List<Node> nodes = new ArrayList<>();
        for(Shown item : shown) {
            nodes.add(tile(item));
        }
        tiles.getChildren().setAll(nodes);
        if(!pictures.isEmpty() && showing) {
            painter.start();
        }

        notes.getChildren().clear();
        for(LitVmContext.CollectionResult result : loaded.results()) {
            String name = result.collection() != null ? collectionLabel(result.collection())
                    : names.getOrDefault(result.contract(), LitVmContext.shortAddress(result.contract()));
            for(String status : statusLines(result)) {
                Label statusLabel = new Label(status);
                statusLabel.setWrapText(true);
                notes.getChildren().add(new HBox(8, plain(name, 260, false), statusLabel));
            }
        }
        String hidden = moreLine(loaded.hidden());
        if(hidden != null) {
            Label hiddenLabel = new Label(hidden);
            hiddenLabel.setWrapText(true);
            notes.getChildren().add(hiddenLabel);
        }
        notes.setVisible(!notes.getChildren().isEmpty());
        empty.setText(EMPTY);
        empty.setVisible(CollectiblesPresenter.isEmpty(loaded.results()));
        String stopped = stoppedLine(loaded.results());
        if(stopped != null) {
            context.status(stopped);
        }

        Shown reselected = selected == null ? null : shown.stream().filter(s -> key(s.nft()).equals(selected)).findFirst().orElse(null);
        if(reselected == null) {
            selected = null;
        }
        showDetail(reselected);
    }

    private static Key key(Nft nft) {
        return new Key(nft.collection().contract(), nft.tokenId());
    }

    private String cacheKey(Nft nft) {
        return network.getChainId() + ":" + nft.collection().contract() + ":" + nft.tokenId();
    }

    private Node tile(Shown item) {
        VBox tile = new VBox(4);
        tile.getStyleClass().add("inscription-tile");
        Key key = key(item.nft());
        if(key.equals(selected)) {
            tile.getStyleClass().add("selected");
        }
        Label name = plain(tileLabel(item.nft()), NftPicture.TILE_SIZE, true);
        Label collection = plain(collectionLabel(item.nft().collection()), NftPicture.TILE_SIZE, true);
        collection.getStyleClass().add("collectible-muted");
        tile.getChildren().addAll(InscriptionPreview.placeholder("", NftPicture.TILE_SIZE), name, collection, markerLabel(item.known()));
        if(item.lookalike() != null) {
            Label warning = new Label(lookalikeWarning(item.lookalike()));
            warning.setStyle(WARNING_STYLE);
            warning.setWrapText(true);
            warning.setMaxWidth(NftPicture.TILE_SIZE);
            tile.getChildren().add(warning);
        }
        queue(tile, item);
        //The tile outlives its plan: a click finds the token as the view holds it now
        tile.setOnMouseClicked(event -> shown.stream().filter(s -> key(s.nft()).equals(key)).findFirst().ifPresent(now -> choose(tile, now)));
        return tile;
    }

    /** Queues a tile's picture: drawn a frame's budget at a time, so a long or costly list does not hold up the window. */
    private void queue(VBox tile, Shown item) {
        int cost = item.picture().cost(NftPicture.pixels(NftPicture.TILE_SIZE, item.picture().scale()));
        pictures.add(new Pending(key(item.nft()), cost, () -> {
            tile.getChildren().set(0, NftPicture.node(item.picture(), NftPicture.TILE_SIZE, cacheKey(item.nft())));
            release(item);
        }));
    }

    //The detail picture being planned off the FX thread, or null
    private Task<NftPicture.Plan> detailLayout;

    /** Whether the detail's picture is still being planned (it then shows an empty frame). */
    boolean detailPending() {
        return detailLayout != null;
    }

    /**
     * The detail's picture. A plan that still holds its drawing, or whose picture is already rendered, is shown at once.
     * A released one is planned again from the metadata off the FX thread, for the detail's size only; until then the
     * frame is empty, and the picture is put in only if the same token is still selected.
     */
    private Node detailPicture(Shown item) {
        NftPicture.Plan plan = item.picture();
        String cacheKey = cacheKey(item.nft());
        if(detailLayout != null) {
            detailLayout.cancel();
            detailLayout = null;
        }
        if(!plan.isReleased() || NftPicture.isRendered(plan, NftPicture.DETAIL_SIZE, cacheKey)) {
            return NftPicture.node(plan, NftPicture.DETAIL_SIZE, cacheKey);
        }
        Node waiting = InscriptionPreview.placeholder("", NftPicture.DETAIL_SIZE);
        Key key = key(item.nft());
        Task<NftPicture.Plan> layout = new Task<>() {
            @Override
            protected NftPicture.Plan call() {
                return NftPicture.planFor(item.nft().metadata(), plan.scale(), NftPicture.DETAIL_SIZE);
            }
        };
        Runnable done = () -> {
            if(detailLayout != layout) {
                return;
            }
            detailLayout = null;
            int at = detail.getChildren().indexOf(waiting);
            if(at >= 0 && key.equals(selected)) {
                NftPicture.Plan planned = layout.getState() == javafx.concurrent.Worker.State.SUCCEEDED ? layout.getValue() : null;
                detail.getChildren().set(at, planned != null && planned.kind() == NftPicture.Kind.SVG ? NftPicture.node(planned, NftPicture.DETAIL_SIZE, cacheKey)
                        : InscriptionPreview.placeholder(NftPicture.NO_PICTURE, NftPicture.DETAIL_SIZE));
            }
        };
        layout.setOnSucceeded(e -> done.run());
        layout.setOnFailed(e -> done.run());
        detailLayout = layout;
        LitVmContext.start(layout);
        return waiting;
    }

    private static Label markerLabel(boolean known) {
        Label marker = new Label(marker(known));
        if(known) {
            marker.setStyle("-fx-font-weight: bold;");
        } else {
            marker.getStyleClass().add("collectible-muted");
        }
        return marker;
    }

    private void showDetail(Shown item) {
        detail.getChildren().clear();
        if(item == null) {
            detail.getChildren().add(new Label("Select a collectible to see its details."));
            return;
        }
        Detail d = CollectiblesPresenter.detail(item.nft(), item.known(), item.lookalike());

        //The app's own rows first, from the presenter's rows (built from the token id, the contract and the wallet's knowledge only)
        VBox app = new VBox(6);
        for(AppRow row : d.appRows()) {
            app.getChildren().add(switch(row.kind()) {
                case TOKEN_ID -> new HBox(6, new Label(row.label()), plain(row.value(), TEXT_WIDTH, false));
                case CONTRACT -> {
                    Hyperlink explorer = new Hyperlink("View on explorer");
                    EvmAddress contract = d.contract();
                    explorer.setOnAction(e -> context.showDocument(network.explorerAddressUrl(contract)));
                    yield new HBox(6, new Label(row.label()), plain(row.value(), TEXT_WIDTH, false), explorer);
                }
                case MARKER -> markerLabel(d.known());
                case WARNING -> {
                    Label warning = new Label(row.value());
                    warning.setStyle(WARNING_STYLE);
                    warning.setWrapText(true);
                    warning.setMaxWidth(TEXT_WIDTH);
                    yield warning;
                }
                case SOULBOUND -> {
                    Label soulbound = new Label(row.value());
                    soulbound.setStyle("-fx-font-weight: bold;");
                    yield soulbound;
                }
            });
        }

        //Then what the collection says, in a box of its own, indented and lighter, with attributes as two columns
        VBox box = new VBox(6);
        box.getStyleClass().add("collectible-box");
        VBox.setMargin(box, new Insets(0, 0, 0, 16));
        Label boxTitle = new Label(FROM_COLLECTION);
        boxTitle.getStyleClass().add("collectible-muted");
        boxTitle.setStyle("-fx-font-style: italic;");
        Label title = plain(d.title(), TEXT_WIDTH, false);
        title.getStyleClass().add("detail-name");
        box.getChildren().addAll(boxTitle, title, plain(d.collection(), TEXT_WIDTH, false));
        if(!d.description().isBlank()) {
            box.getChildren().add(plain(d.description(), TEXT_WIDTH, false));
        }
        if(!d.attributes().isEmpty()) {
            GridPane grid = new GridPane();
            grid.setHgap(16);
            grid.setVgap(4);
            for(int i = 0; i < d.attributes().size(); i++) {
                NftMetadata.Attribute attribute = d.attributes().get(i);
                Label name = plain(attribute.name(), 200, false);
                name.getStyleClass().add("collectible-muted");
                grid.add(name, 0, i);
                grid.add(plain(attribute.value(), 260, false), 1, i);
            }
            box.getChildren().add(grid);
        }

        detail.getChildren().addAll(detailPicture(item), app, box);
    }

    /** Untrusted text: a plain label of its own, left to right, wrapped within a width, never markup or a link. */
    private static Label plain(String text, double maxWidth, boolean twoLines) {
        Label label = new Label(text);
        label.setMnemonicParsing(false);
        label.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        label.setWrapText(true);
        label.setMaxWidth(maxWidth);
        label.setMinWidth(0);
        label.setTextOverrun(OverrunStyle.ELLIPSIS);
        if(twoLines) {
            label.setPrefWidth(maxWidth);
            label.setMaxHeight(36);
        } else {
            //Wrapped in full, not squeezed to one elided line when the section is short of room
            label.setMinHeight(Region.USE_PREF_SIZE);
        }
        return label;
    }

    private void addCollection() {
        Window owner = owner();
        if(owner == null) {
            return;
        }
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(owner);
        dialog.setTitle(ADD_COLLECTION);
        dialog.setHeaderText("Watch an NFT collection on " + network.getDisplayName());
        dialog.setContentText("Collection contract:");
        dialog.getEditor().setPrefColumnCount(34);
        Optional<String> input = dialog.showAndWait();
        if(input.isEmpty() || input.get().isBlank()) {
            return;
        }
        EvmAddress contract;
        try {
            contract = EvmAddress.fromString(input.get().trim());
        } catch(IllegalArgumentException e) {
            context.status(cleanMessage(e.getMessage()));
            return;
        }
        if(KnownCollections.isKnown(network, contract)) {
            context.status("That collection is built in and always listed.");
            return;
        }
        if(LitVmContext.watchedCollections(network).contains(contract)) {
            context.status("Already watching this collection.");
            return;
        }
        context.status("Reading collection " + contract + "...");
        LitVmRpcClient client = context.client(network);
        //Whether it can be listed is decided from the collection alone: no listing before the user agrees
        Task<Optional<Erc721Collection>> read = new Task<>() {
            @Override
            protected Optional<Erc721Collection> call() throws Exception {
                return LitVmContext.readToAdd(client, contract);
            }
        };
        read.setOnSucceeded(e -> {
            //The wallet may have closed, or another section been shown, while it was read
            if(owner() == null) {
                return;
            }
            Optional<Erc721Collection> collection = read.getValue();
            if(collection.isEmpty()) {
                context.status(NOT_A_COLLECTION);
            } else if(!NftLister.canList(network, collection.get())) {
                context.status(NEEDS_EXPLORER);
            } else {
                confirmAndWatch(collection.get());
            }
        });
        read.setOnFailed(e -> {
            if(owner() != null) {
                context.status(failureSentence(read.getException()));
            }
        });
        LitVmContext.start(read);
    }

    /** The token dialog's confirmation, and its lookalike warning (Cancel by default) for a collection taking a built-in name. */
    private void confirmAndWatch(Erc721Collection collection) {
        Window owner = owner();
        if(owner == null) {
            return;
        }
        Optional<String> imitates = KnownCollections.lookalikeOf(network, collection);
        boolean lookalike = imitates.isPresent();
        Alert confirm = new Alert(lookalike ? Alert.AlertType.WARNING : Alert.AlertType.CONFIRMATION, "", ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(owner);
        confirm.setTitle(ADD_COLLECTION);
        confirm.setHeaderText(lookalike ? "This looks like a collection it is not" : "Watch this collection on " + network.getDisplayName() + "?");
        Label explanation = new Label(lookalike
                ? lookalikeWarning(imitates.get()) + ". Anyone can deploy a collection with any name. Only watch contracts from a source you trust."
                : "Anyone can deploy a collection with any name. Only watch contracts from a source you trust.");
        explanation.setWrapText(true);
        explanation.setMaxWidth(TEXT_WIDTH);
        if(lookalike) {
            explanation.setStyle(WARNING_STYLE);
        }
        VBox content = new VBox(6, row("Name:", collection.name()), row("Symbol:", collection.symbol()), row("Contract:", collection.contract().toString()));
        if(collection.soulbound()) {
            content.getChildren().add(new Label(SOULBOUND));
        }
        content.getChildren().add(explanation);
        confirm.getDialogPane().setContent(content);
        Button ok = (Button)confirm.getDialogPane().lookupButton(ButtonType.OK);
        ok.setText(lookalike ? "Watch Anyway" : "Watch");
        if(lookalike) {
            ok.setDefaultButton(false);
            ((Button)confirm.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
        }
        confirm.getDialogPane().setPrefWidth(540);
        Optional<ButtonType> answer = confirm.showAndWait();
        if(answer.isPresent() && answer.get() == ButtonType.OK) {
            if(LitVmContext.watchCollection(network, collection.contract())) {
                context.status("Watching the collection at " + collection.contract() + ".");
                refresh();
            } else {
                context.status("Already watching this collection.");
            }
        } else {
            context.status("Not added.");
        }
    }

    private static HBox row(String name, String value) {
        return new HBox(6, new Label(name), plain(value, TEXT_WIDTH - 80, false));
    }

    private void removeCollection() {
        List<EvmAddress> watched = LitVmContext.watchedCollections(network);
        if(watched.isEmpty()) {
            context.status("No collections have been added on " + network.getDisplayName() + ". Built-in collections are always listed.");
            return;
        }
        Window owner = owner();
        if(owner == null) {
            return;
        }
        ChoiceDialog<EvmAddress> dialog = new ChoiceDialog<>(watched.getFirst(), watched);
        dialog.initOwner(owner);
        dialog.setTitle("Remove collection");
        dialog.setHeaderText("Stop watching a collection on " + network.getDisplayName());
        dialog.setContentText("Collection contract:");
        dialog.showAndWait().ifPresent(contract -> {
            if(LitVmContext.unwatchCollection(network, contract)) {
                context.status("Stopped watching the collection at " + contract + ".");
                refresh();
            }
        });
    }
}
