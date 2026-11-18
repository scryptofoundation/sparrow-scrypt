package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.evm.EvmAddress;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.DeterministicSeed;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.LitVmLink;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.Mode;
import com.sparrowwallet.sparrow.SparrowWallet;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Storage;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.Event;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Opt in (SPARROW_APP_CHECK_DIR names an output folder): starts the real desktop app in this process, on testnet, with
 * a brand-new home inside that folder and a throwaway wallet made from the Litecoin testnet end-to-end seed (the file
 * named by LITVM_E2E_SEED), drives it from inside without OS input to the wallet's LitVM sub-tab and its Collectibles,
 * Transactions and Tokens sections, writes pictures of the whole window there, and checks the Vault Badge from the
 * scene graph. The app runs offline (no wallet server); only the LitVM views' own read-only calls reach the network.
 *
 * Keeping away from the owner's wallets: before any app class reads a folder, the home folder property the app's own
 * --dir option sets is set here to the new home, Config's cached instance is cleared, and every folder the app
 * resolves (config, data, cache, state, wallets) is checked to be inside the output folder and not in the usual
 * Sparrow homes; the app is then started with -d pointing at the same folder, -c LITECOIN (a flavored start, which skips
 * the unified build's copy of the legacy config) and -n TESTNET. SPARROW_NO_LOCK_FILE_LINK=true is required, so the
 * single-instance lock never writes its pointer into the default home or finds the owner's running instance.
 *
 * Step 7 (sending to the badge contract to mint) runs only with SPARROW_APP_CHECK_MINT=1, after the owner approves the
 * send. SPARROW_APP_CHECK_SEND_PREVIEW=1 alone goes through the Send view to its confirmation and cancels there.
 * SPARROW_APP_CHECK_ADD_TOKEN=1 first asks Tokens, Add Token for an address with no contract (a read only) and writes
 * the window with the failure's status line.
 *
 * It changes JVM-wide state (the network, the config, the home folder, the JavaFX toolkit), so it is tagged "app",
 * which the test task leaves out unless Gradle is given -PappCheck, and then the task runs this class alone:
 * {@code gradlew :test -PappCheck}. The output folder must lie outside the repository and any build folder, since the
 * throwaway wallet holds the test seed unencrypted; the throwaway home is deleted at the end unless
 * SPARROW_APP_CHECK_KEEP_HOME=1.
 */
@Tag("app")
public class AppCollectiblesCheck {
    private static final EvmAddress HOLDER = EvmAddress.fromString("0x840e41D4484041A43f9307a90389edeB3d111fb3");
    private static final String BADGE = "0x3eEd9a4c9AD8a1Ac42BDb9eEE6079c75C8fd51B3";
    private static final String WORLD_1 = "Super Litecoin Vault Badge: World 1";
    private static final String WORLD_2 = "Super Litecoin Vault Badge: World 2";
    private static final String WALLET_NAME = "litvm-app-check";

    private File out;
    private Thread app;

    @Test
    public void theRealAppShowsTheBadge() throws Throwable {
        String outPath = System.getenv("SPARROW_APP_CHECK_DIR");
        Assumptions.assumeTrue(outPath != null && !outPath.isBlank(), "SPARROW_APP_CHECK_DIR not set");
        out = new File(outPath).getCanonicalFile();
        Assertions.assertTrue(out.isDirectory(), "SPARROW_APP_CHECK_DIR is not a folder: " + out);
        assertOutsideRepository(out);
        Assertions.assertEquals("true", System.getenv("SPARROW_NO_LOCK_FILE_LINK"),
                "set SPARROW_NO_LOCK_FILE_LINK=true so the app's lock never writes into or searches the default home");
        String seedPath = System.getenv("LITVM_E2E_SEED");
        Assertions.assertNotNull(seedPath, "LITVM_E2E_SEED must name the throwaway testnet seed file");

        File home = prepareHome();
        Throwable failure = null;
        try {
            run(home, Path.of(seedPath));
        } catch(Throwable t) {
            failure = t;
            throw t;
        } finally {
            if(!"1".equals(System.getenv("SPARROW_APP_CHECK_KEEP_HOME"))) {
                try {
                    deleteHome(home);
                } catch(Throwable t) {
                    if(failure == null) {
                        throw t;
                    }
                    failure.addSuppressed(t);
                }
            }
        }
    }

    private void run(File home, Path seedPath) throws Throwable {
        File walletFile = createWallet(seedPath);
        configure(walletFile);

        startApp(home);
        Throwable failure = null;
        try {
            Stage stage = waitFor("the main window with the wallet's LitVM sub-tab", 120, () -> mainStage().filter(s -> litVmPane(s) != null).orElse(null));
            onFx(() -> {
                place(stage);
                return null;
            });
            if("1".equals(System.getenv("SPARROW_APP_CHECK_STRICT_SCOPING"))) {
                //As the unified build runs: a thread with no coin scope may not resolve a coin. This start is a
                //single-coin one (which keeps the fallback), so the rule is turned on here, before the LitVM tab is
                //opened; the app's own start has already named its foreground thread
                com.sparrowwallet.drongo.Coin.setStrictScoping(true);
            }
            LitVmPane pane = onFx(() -> {
                LitVmPane litVm = litVmPane(stage);
                selectLitVmSubTab(stage, litVm);
                return litVm;
            });

            if("1".equals(System.getenv("SPARROW_APP_CHECK_ADD_TOKEN"))) {
                addTokenWhereNoContractIs(stage, pane);
            }

            //The Collectibles section, after its read and its pictures are done
            onFx(() -> select(pane, CollectiblesPresenter.SECTION));
            LitVmCollectiblesView view = waitFor("the Collectibles section", 30, () -> find(pane, LitVmCollectiblesView.class));
            waitFor("the Collectibles read and pictures", 90, () -> settled(view) ? true : null);
            snap(stage, "app-collectibles.png");

            VBox tile = onFx(() -> tile(view, WORLD_1));
            Assertions.assertNotNull(tile, "a tile labelled " + WORLD_1);
            Assertions.assertTrue(onFx(() -> labels(tile).contains(CollectiblesPresenter.BUILT_IN)), "the tile is marked Built in");
            Image picture = onFx(() -> picture(tile));
            Assertions.assertNotNull(picture, "the tile's picture is a drawn image");
            Assertions.assertTrue(onFx(() -> colours(picture)) > 3, "the tile's picture is not blank");

            //Select the tile as a click does, then show the detail
            onFx(() -> {
                Event.fireEvent(tile, new MouseEvent(MouseEvent.MOUSE_CLICKED, 1, 1, 1, 1, MouseButton.PRIMARY, 1,
                        false, false, false, false, true, false, false, true, false, false, null));
                return null;
            });
            waitFor("the badge's detail", 10, () -> labels(view).contains(CollectiblesPresenter.SOULBOUND) ? true : null);
            onFx(() -> {
                ScrollPane scroll = ancestor(view, ScrollPane.class);
                if(scroll != null) {
                    scroll.setVvalue(scroll.getVmax());
                }
                return null;
            });
            snap(stage, "app-collectibles-detail.png");
            Assertions.assertTrue(onFx(() -> labels(view).contains(CollectiblesPresenter.FROM_COLLECTION)), "the collection's box is shown");

            //Hidden behind the wallet's own sub-tab, a refresh (as balancesChanged asks) starts no read; shown again, it reads
            onFx(() -> {
                selectWalletSubTab(stage, pane);
                view.refresh();
                return null;
            });
            Assertions.assertNull(onFx(() -> reading(view)), "no read while the LitVM sub-tab is hidden");
            onFx(() -> {
                selectLitVmSubTab(stage, pane);
                return null;
            });
            waitFor("the read on showing again", 10, () -> reading(view) != null ? true : null);
            waitFor("the Collectibles read and pictures again", 90, () -> reading(view) == null && settled(view) ? true : null);
            Assertions.assertNotNull(onFx(() -> tile(view, WORLD_1)), "the World 1 tile after showing again");

            //The other sections still work
            onFx(() -> select(pane, "Transactions"));
            LitVmHistoryView history = waitFor("the Transactions section", 30, () -> find(pane, LitVmHistoryView.class));
            waitFor("the balance", 60, () -> labels(history).contains("Loading...") ? null : true);
            snap(stage, "app-litvm-transactions.png");
            onFx(() -> select(pane, "Tokens"));
            LitVmTokensView tokens = waitFor("the Tokens section", 30, () -> find(pane, LitVmTokensView.class));
            Thread.sleep(8000);
            snap(stage, "app-litvm-tokens.png");
            Assertions.assertNotNull(tokens);

            boolean mint = "1".equals(System.getenv("SPARROW_APP_CHECK_MINT"));
            if(mint || "1".equals(System.getenv("SPARROW_APP_CHECK_SEND_PREVIEW"))) {
                sendToTheBadge(stage, pane, mint);
            }
        } catch(Throwable t) {
            failure = t;
            throw t;
        } finally {
            //A failure to stop is reported, but never hides the failure that came first
            try {
                stopApp();
            } catch(Throwable t) {
                if(failure == null) {
                    throw t;
                }
                failure.addSuppressed(t);
            }
        }
    }

    // ---------------- Safety and setup ----------------

    /** Not inside the working tree or any repository above it, nor in a build folder. */
    private static void assertOutsideRepository(File out) throws IOException {
        for(File dir = new File("").getCanonicalFile(); dir != null; dir = dir.getParentFile()) {
            if(new File(dir, ".git").exists()) {
                Assertions.assertFalse(out.toPath().startsWith(dir.toPath()), "SPARROW_APP_CHECK_DIR must be outside the repository " + dir);
            }
        }
        for(Path part : out.toPath()) {
            Assertions.assertNotEquals("build", part.toString().toLowerCase(Locale.ROOT), "SPARROW_APP_CHECK_DIR must not be in a build folder");
        }
    }

    /** The throwaway home, and only inside the output folder; retried a few times while the app lets go of its files. */
    private void deleteHome(File home) throws Exception {
        //The app's log file in the home stays open for the life of the JVM: stop logging first (by reflection, so this
        //needs no compile dependency on the logging backend)
        org.slf4j.ILoggerFactory loggers = org.slf4j.LoggerFactory.getILoggerFactory();
        try {
            loggers.getClass().getMethod("stop").invoke(loggers);
        } catch(ReflectiveOperationException e) {
            //no such backend: nothing to stop
        }
        for(int attempt = 1; home.exists(); attempt++) {
            try(Stream<Path> walk = Files.walk(home.toPath())) {
                for(Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Assertions.assertTrue(path.toFile().getCanonicalFile().toPath().startsWith(out.toPath()));
                    Files.deleteIfExists(path);
                }
            } catch(IOException e) {
                if(attempt == 10) {
                    throw new AssertionError("Could not delete the throwaway home " + home + ": " + e.getMessage());
                }
                Thread.sleep(1000);
            }
        }
    }

    /** A brand-new home inside the output folder, which every folder the app resolves must be in. */
    private File prepareHome() throws IOException, ReflectiveOperationException {
        File home = new File(out, "home").getCanonicalFile();
        Assertions.assertTrue(home.toPath().startsWith(out.toPath()));
        if(home.exists()) {
            //Only the previous run's throwaway home, inside the output folder
            try(Stream<Path> walk = Files.walk(home.toPath())) {
                for(Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Assertions.assertTrue(path.toFile().getCanonicalFile().toPath().startsWith(out.toPath()));
                    Files.delete(path);
                }
            }
        }
        Assertions.assertTrue(home.mkdirs());

        //What the app's -d option sets, for this flavor and the unified build, before any app class reads a folder
        System.setProperty(SparrowWallet.APP_HOME_PROPERTY, home.getAbsolutePath());
        System.setProperty("sparrow-ltc.home", home.getAbsolutePath());
        System.setProperty("sparrow-scrypt.home", home.getAbsolutePath());
        Network.set(Network.TESTNET);
        Field instance = Config.class.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        instance.set(null, null);

        List<File> resolved = List.of(Storage.getConfigHome(), Storage.getDataHome(), Storage.getCacheHome(), Storage.getStateHome(),
                Storage.getConfigDir(), Storage.getDataDir(), Storage.getWalletsDir());
        for(File dir : resolved) {
            assertSafe(dir.getCanonicalFile(), home);
        }
        return home;
    }

    private void assertSafe(File dir, File home) throws IOException {
        Assertions.assertTrue(dir.toPath().startsWith(home.toPath()), dir + " is not inside the throwaway home " + home);
        String appData = System.getenv("APPDATA");
        if(appData != null) {
            Assertions.assertFalse(dir.toPath().startsWith(new File(appData).getCanonicalFile().toPath()), dir + " is under APPDATA");
        }
        List<File> sparrowHomes = new ArrayList<>();
        for(String base : new String[] {System.getenv("APPDATA"), System.getenv("LOCALAPPDATA"), System.getProperty("user.home")}) {
            if(base == null) {
                continue;
            }
            File[] children = new File(base).listFiles((parent, name) -> name.toLowerCase(Locale.ROOT).startsWith("sparrow")
                    || name.toLowerCase(Locale.ROOT).startsWith(".sparrow"));
            if(children != null) {
                sparrowHomes.addAll(Arrays.asList(children));
            }
        }
        for(File sparrowHome : sparrowHomes) {
            Assertions.assertFalse(dir.toPath().startsWith(sparrowHome.getCanonicalFile().toPath()), dir + " is in the Sparrow home " + sparrowHome);
        }
    }

    /** LitVmTestWalletCreator's wallet: Litecoin testnet P2WPKH from the seed, its same-seed LitVM link, no password. */
    private File createWallet(Path seedPath) throws Exception {
        File walletFile = new File(Storage.getWalletsDir(), WALLET_NAME + ".mv.db").getCanonicalFile();
        assertSafe(walletFile, new File(out, "home").getCanonicalFile());
        Coin previous = Coin.beginScope(Coin.LITECOIN);
        try {
            //The words are read here and go only into the wallet file in the throwaway home; they are never printed, and a
            //failure says nothing of them
            DeterministicSeed seed;
            LitVmLink link;
            try {
                seed = new DeterministicSeed(new ArrayList<>(Arrays.asList(Files.readString(seedPath, StandardCharsets.UTF_8).trim().split("\\s+"))),
                        "", 0, DeterministicSeed.Type.BIP39);
                link = LitVmLink.fromWalletSeed(seed, 0);
            } catch(Exception e) {
                throw new IllegalStateException("The seed file could not be read.");
            }
            Assertions.assertEquals(HOLDER, link.getAddress(), "the seed's LitVM account is the test account");
            Keystore keystore = Keystore.fromSeed(seed, PolicyType.SINGLE_HD, ScriptType.P2WPKH.getDefaultDerivation());
            Wallet wallet = new Wallet(WALLET_NAME);
            wallet.setCoin(Coin.LITECOIN);
            wallet.setNetwork(Network.TESTNET);
            wallet.setPolicyType(PolicyType.SINGLE_HD);
            wallet.setScriptType(ScriptType.P2WPKH);
            wallet.getKeystores().add(keystore);
            wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE_HD, ScriptType.P2WPKH, wallet.getKeystores(), 1));
            wallet.setLitVmLink(link);
            Assertions.assertTrue(wallet.isValid());
            Files.createDirectories(walletFile.getParentFile().toPath());
            Storage storage = new Storage(walletFile);
            storage.setEncryptionPubKey(Storage.NO_PASSWORD_KEY);
            storage.saveWallet(wallet);
            storage.restorePublicKeysFromSeed(wallet, null);
            //Release the database file, or the app cannot open it
            storage.closeAndWait();
        } finally {
            Coin.endScope(previous);
        }
        Assertions.assertTrue(walletFile.exists());
        return walletFile;
    }

    /** The throwaway config: offline (no wallet server, no update check), opening the throwaway wallet at start. */
    private void configure(File walletFile) throws IOException {
        Config config = Config.get();
        config.setMode(Mode.OFFLINE);
        config.setCheckNewVersions(false);
        config.setRecentWalletFiles(new ArrayList<>(List.of(walletFile)));
        File configFile = new File(Storage.getConfigDir(), Config.CONFIG_FILENAME).getCanonicalFile();
        Assertions.assertTrue(configFile.exists(), "the config was written to the throwaway home");
        assertSafe(configFile, new File(out, "home").getCanonicalFile());
    }

    private void startApp(File home) {
        String[] argv = {"-c", "LITECOIN", "-n", "TESTNET", "-d", home.getAbsolutePath()};
        app = new Thread(() -> SparrowWallet.main(argv), "sparrow-app-check");
        app.setDaemon(true);
        app.start();
    }

    private void stopApp() throws InterruptedException {
        try {
            Platform.exit();
        } catch(IllegalStateException e) {
            //the toolkit never started
        }
        if(app != null) {
            app.join(60_000);
            Assertions.assertFalse(app.isAlive(), "the app stopped");
        }
    }

    // ---------------- Driving ----------------

    private static Optional<Stage> mainStage() {
        return Window.getWindows().stream().filter(w -> w instanceof Stage && w.isShowing() && w.getScene() != null).map(w -> (Stage)w)
                .filter(s -> all(s.getScene().getRoot()).stream().anyMatch(n -> n instanceof TabPane)).findFirst();
    }

    private static LitVmPane litVmPane(Stage stage) {
        return all(stage.getScene().getRoot()).stream().filter(n -> n instanceof LitVmPane).map(n -> (LitVmPane)n).findFirst().orElse(null);
    }

    /** On the top-left monitor (smallest x, then smallest y), about 1200 x 900. */
    private static void place(Stage stage) {
        Screen screen = Screen.getScreens().stream().min(Comparator.<Screen>comparingDouble(s -> s.getBounds().getMinX())
                .thenComparingDouble(s -> s.getBounds().getMinY())).orElse(Screen.getPrimary());
        Rectangle2D bounds = screen.getVisualBounds();
        stage.setX(bounds.getMinX() + 20);
        stage.setY(bounds.getMinY() + 20);
        stage.setWidth(Math.min(1200, bounds.getWidth() - 40));
        stage.setHeight(Math.min(900, bounds.getHeight() - 40));
    }

    /** Selects the wallet's tab in the main tab pane and the LitVM sub-tab in the wallet's own tab pane. */
    private static void selectLitVmSubTab(Stage stage, LitVmPane litVm) {
        for(Node node : all(stage.getScene().getRoot())) {
            if(node instanceof TabPane tabs) {
                for(Tab tab : tabs.getTabs()) {
                    if(tab.getContent() == litVm) {
                        tabs.getSelectionModel().select(tab);
                    } else if(tab.getContent() instanceof TabPane subTabs && subTabs.getTabs().stream().anyMatch(t -> t.getContent() == litVm)) {
                        tabs.getSelectionModel().select(tab);
                    }
                }
            }
        }
    }

    /** Selects the wallet's own sub-tab (the first that is not the LitVM one), hiding the LitVM sub-tab. */
    private static void selectWalletSubTab(Stage stage, LitVmPane litVm) {
        for(Node node : all(stage.getScene().getRoot())) {
            if(node instanceof TabPane subTabs && subTabs.getTabs().stream().anyMatch(t -> t.getContent() == litVm)) {
                subTabs.getTabs().stream().filter(t -> t.getContent() != litVm).findFirst().ifPresent(t -> subTabs.getSelectionModel().select(t));
            }
        }
    }

    /** The view's read in progress, or null: read from its private field, so the app needs no hook for the check. */
    private static Object reading(LitVmCollectiblesView view) {
        try {
            Field running = LitVmCollectiblesView.class.getDeclaredField("running");
            running.setAccessible(true);
            return running.get(view);
        } catch(ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Selects a section of the LitVM pane by its menu button, as a click does. */
    private static Object select(LitVmPane pane, String section) {
        ToggleButton button = all(pane).stream().filter(n -> n instanceof ToggleButton t && section.equals(t.getText())).map(n -> (ToggleButton)n)
                .findFirst().orElseThrow(() -> new AssertionError("no section " + section));
        if(!button.isSelected()) {
            button.fire();
        }
        return null;
    }

    /** The read has ended (no "Loading..." left) and every tile's picture is drawn (no placeholder frame without an image). */
    private static boolean settled(LitVmCollectiblesView view) {
        if(labels(view).contains("Loading...")) {
            return false;
        }
        for(Node node : all(view)) {
            if(node instanceof VBox tile && tile.getStyleClass().contains("inscription-tile")) {
                Node first = tile.getChildren().getFirst();
                boolean placeholder = all(first).stream().anyMatch(n -> n instanceof Label l && l.getText().isEmpty());
                if(placeholder) {
                    return false;
                }
            }
        }
        return true;
    }

    private static VBox tile(LitVmCollectiblesView view, String name) {
        return all(view).stream().filter(n -> n instanceof VBox v && v.getStyleClass().contains("inscription-tile") && labels(v).contains(name))
                .map(n -> (VBox)n).findFirst().orElse(null);
    }

    private static Image picture(VBox tile) {
        return all(tile.getChildren().getFirst()).stream().filter(n -> n instanceof ImageView).map(n -> ((ImageView)n).getImage()).findFirst().orElse(null);
    }

    private static int colours(Image image) {
        PixelReader pixels = image.getPixelReader();
        Set<Integer> colours = new HashSet<>();
        for(int x = 0; x < (int)image.getWidth(); x++) {
            for(int y = 0; y < (int)image.getHeight(); y++) {
                colours.add(pixels.getArgb(x, y));
            }
        }
        return colours.size();
    }

    // ---------------- A read that fails: Add Token where no contract is (gated) ----------------

    /**
     * Through the real Tokens view and its Add Token dialog: asks LiteForge, read only, for a token at an address that
     * holds no contract, and writes the window with the status line that follows, in both themes. Nothing is signed.
     */
    private void addTokenWhereNoContractIs(Stage stage, LitVmPane pane) throws Exception {
        String lead = "Could not read a token at that address.";
        onFx(() -> select(pane, "Tokens"));
        LitVmTokensView tokens = waitFor("the Tokens section", 30, () -> find(pane, LitVmTokensView.class));
        //The view's own first read, so a line of its own cannot land over the one looked for
        Thread.sleep(8000);
        //Not through onFx: the dialog waits for its answer inside the click
        Platform.runLater(() -> all(tokens).stream().filter(n -> n instanceof Button b && "Add Token...".equals(b.getText())).map(n -> (Button)n)
                .findFirst().orElseThrow().fire());
        Stage dialog = waitFor("the Add Token dialog", 30, () -> Window.getWindows().stream()
                .filter(w -> w instanceof Stage s && w.isShowing() && "Add Token".equals(s.getTitle())).map(w -> (Stage)w).findFirst().orElse(null));
        onFx(() -> {
            DialogPane dialogPane = (DialogPane)dialog.getScene().getRoot();
            all(dialogPane).stream().filter(n -> n instanceof TextField).map(n -> (TextField)n).findFirst().orElseThrow()
                    .setText("0x000000000000000000000000000000000000dEaD");
            ((Button)dialogPane.lookupButton(ButtonType.OK)).fire();
            return null;
        });
        String line = waitFor("the Add Token failure", 90, () -> labels(pane).stream().filter(text -> text.startsWith(lead)).findFirst().orElse(null));
        String darkCss = com.sparrowwallet.sparrow.AppServices.class.getResource("darktheme.css").toExternalForm();
        boolean dark = onFx(() -> stage.getScene().getStylesheets().contains(darkCss));
        snap(stage, "app-add-token-no-contract-" + (dark ? "dark" : "light") + ".png");
        onFx(() -> dark ? stage.getScene().getStylesheets().remove(darkCss) : stage.getScene().getStylesheets().add(darkCss));
        snap(stage, "app-add-token-no-contract-" + (dark ? "light" : "dark") + ".png");
        onFx(() -> dark ? stage.getScene().getStylesheets().add(darkCss) : stage.getScene().getStylesheets().remove(darkCss));
        Assertions.assertEquals(lead + " There is no contract at that address.", line);
    }

    // ---------------- Step 7: send to the badge contract (gated) ----------------

    /**
     * Through the real Send view: pays 0.0001 zkLTC to the badge contract, which mints the next world's badge. Without
     * mint it stops at the confirmation and cancels there, so nothing is signed or sent.
     */
    private void sendToTheBadge(Stage stage, LitVmPane pane, boolean mint) throws Exception {
        onFx(() -> select(pane, "Send"));
        LitVmSendView send = waitFor("the Send section", 30, () -> find(pane, LitVmSendView.class));
        waitFor("the Send view's balances", 60, () -> all(send).stream().anyMatch(n -> n instanceof ComboBox<?> c && c.getValue() != null) ? true : null);
        onFx(() -> {
            TextField to = all(send).stream().filter(n -> n instanceof TextField t && "0x...".equals(t.getPromptText())).map(n -> (TextField)n).findFirst().orElseThrow();
            TextField amount = all(send).stream().filter(n -> n instanceof TextField t && "0.0".equals(t.getPromptText())).map(n -> (TextField)n).findFirst().orElseThrow();
            to.setText(BADGE);
            amount.setText("0.0001");
            Button button = all(send).stream().filter(n -> n instanceof Button b && !(n instanceof ToggleButton) && "Send".equals(b.getText()))
                    .map(n -> (Button)n).findFirst().orElseThrow();
            button.fire();
            return null;
        });
        Stage confirm = waitFor("the transfer confirmation", 90, () -> Window.getWindows().stream()
                .filter(w -> w instanceof Stage s && w.isShowing() && "Confirm Transfer".equals(s.getTitle())).map(w -> (Stage)w).findFirst().orElse(null));
        snap(confirm, "app-send-confirm.png");
        DialogPane dialog = onFx(() -> (DialogPane)confirm.getScene().getRoot());
        Assertions.assertTrue(onFx(dialog::getHeaderText).startsWith("Send 0.0001 zkLTC"), "the confirmation names the amount");
        Assertions.assertTrue(onFx(dialog::getContentText).contains("To: " + BADGE), "the confirmation names the badge contract");
        if(!mint) {
            onFx(() -> {
                ((Button)dialog.lookupButton(ButtonType.CANCEL)).fire();
                return null;
            });
            waitFor("the cancel", 10, () -> confirm.isShowing() ? null : true);
            return;
        }

        onFx(() -> {
            ((Button)dialog.lookupButton(ButtonType.OK)).fire();
            return null;
        });
        String result = waitFor("the transfer's receipt", 120, () -> labels(pane).stream()
                .filter(t -> t.startsWith("Confirmed in block") || t.startsWith("Failed on chain") || t.startsWith("Not completed") || t.startsWith("Sent, still pending"))
                .findFirst().orElse(null));
        Assertions.assertTrue(result.startsWith("Confirmed in block"), result);

        //Without a restart: the section refreshes when balancesChanged fires, and reads again when shown
        onFx(() -> select(pane, CollectiblesPresenter.SECTION));
        LitVmCollectiblesView view = waitFor("the Collectibles section", 30, () -> find(pane, LitVmCollectiblesView.class));
        waitFor("the World 2 badge", 120, () -> tile(view, WORLD_2) != null && settled(view) ? true : null);
        snap(stage, "app-collectibles-after-mint.png");
        Assertions.assertNotNull(onFx(() -> tile(view, WORLD_2)), "a World 2 tile");
    }

    // ---------------- Helpers ----------------

    private static <T> T onFx(Supplier<T> work) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.get());
            } catch(Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result.get(60, TimeUnit.SECONDS);
    }

    /** Polls on the FX thread until the answer is not null, for at most this many seconds. */
    private static <T> T waitFor(String what, int seconds, Supplier<T> answer) throws Exception {
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        while(System.nanoTime() < end) {
            try {
                T value = onFx(answer);
                if(value != null) {
                    return value;
                }
            } catch(IllegalStateException e) {
                //the toolkit is not up yet
            }
            Thread.sleep(500);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    private void snap(Stage stage, String name) throws Exception {
        WritableImage image = onFx(() -> stage.getScene().snapshot(null));
        Assertions.assertTrue(ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", new File(out, name)), name);
    }

    private static <T> T find(Node root, Class<T> type) {
        return all(root).stream().filter(type::isInstance).map(type::cast).findFirst().orElse(null);
    }

    private static <T> T ancestor(Node node, Class<T> type) {
        for(Node n = node.getParent(); n != null; n = n.getParent()) {
            if(type.isInstance(n)) {
                return type.cast(n);
            }
        }
        return null;
    }

    private static List<String> labels(Node root) {
        return all(root).stream().filter(n -> n instanceof Labeled).map(n -> ((Labeled)n).getText()).filter(Objects::nonNull).toList();
    }

    private static List<Node> all(Node root) {
        List<Node> nodes = new ArrayList<>();
        collect(root, nodes);
        return nodes;
    }

    private static void collect(Node n, List<Node> nodes) {
        nodes.add(n);
        if(n instanceof TabPane pane) {
            for(Tab t : pane.getTabs()) {
                if(t.getContent() != null) {
                    collect(t.getContent(), nodes);
                }
            }
        }
        if(n instanceof ScrollPane sp && sp.getContent() != null) {
            collect(sp.getContent(), nodes);
        }
        if(n instanceof Parent p) {
            for(Node c : p.getChildrenUnmodifiable()) {
                if(!(n instanceof ScrollPane) && !(n instanceof TabPane)) {
                    collect(c, nodes);
                }
            }
        }
    }
}
