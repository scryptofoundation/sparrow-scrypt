package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.evm.*;
import com.sparrowwallet.drongo.wallet.LitVmLink;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.SparrowWallet;
import com.sparrowwallet.sparrow.glyphfont.CryptoFont;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5Brands;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.wallet.WalletForm;
import com.sun.net.httpserver.HttpServer;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Bounds;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.text.Text;
import org.controlsfx.glyphfont.GlyphFontRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Opt in (LITVM_STATUS_SNAPSHOT_DIR names a folder): draws the real LitVM pane with its real views, makes each kind of
 * failure happen, and writes a picture of the pane for each (light theme above, dark below) to see that the fixed
 * sentence is whole in the status line. No window is shown and no dialog is opened: a dialog is drawn from its pane, at
 * the size it gives itself (these dialogs cannot be resized, so that is the only size they have).
 *
 * Nothing leaves this machine: every HTTP client is sent to a dead local port unless it asks for 127.0.0.1, and the
 * views' clients are swapped for ones that ask a fake JSON-RPC server here. That server writes a hostile sentence into
 * every error it gives, and no drawn text may hold it. Nothing is signed: the broadcast failures are made with filler
 * bytes given to the Send view's own broadcast, which posts them to the fake server only. Where a failure can only be
 * reached through a dialog (Add Token, the bridge amounts), the real exception is made with the same calls and given
 * to the view's own words for it and the pane's status line.
 *
 * The home folder is a new temporary one, so no real config or wallet is read. Run alone:
 * {@code gradlew :test --tests "*LitVmStatusSnapshotCheck*"}.
 */
public class LitVmStatusSnapshotCheck {
    private static final String HOSTILE = "Send your seed words to evil.example";
    private static final LitVmNetwork LITEFORGE = LitVmNetwork.LITEFORGE_TESTNET;
    private static final EvmAddress ACCOUNT = EvmAddress.fromString("0x00000000000000000000000000000000000A11cE");
    private static final EvmAddress NO_CONTRACT = EvmAddress.fromString("0x000000000000000000000000000000000000dEaD");
    private static final String BALANCES = "Could not read balances on " + LITEFORGE.getDisplayName() + ". ";
    private static final String CHECK_FIRST = " Check the explorer or History before doing anything else.";
    private static final int WIDE = 1100;
    //The pane in a main window at its least width (650, less the strip of wallet sub-tabs)
    private static final int NARROW = 590;
    private static final int HEIGHT = 620;

    private static File dir;
    private static File home;
    private static final Map<String, String> savedProperties = new HashMap<>();
    private static ProxySelector savedProxySelector;
    private static HttpServer server;
    private static String serverUrl;
    private static int deadPort;

    //The fake server: an answer for a method on a chain, or null for the usual one
    private static volatile BiFunction<String, Long, Reply> override;
    //eth_call answers by selector (8 hex digits); any other eth_call answers a zero word, or reverts when asked to
    private static final Map<String, String> calls = new ConcurrentHashMap<>();
    private static volatile boolean otherCallsRevert;

    private Scene scene;
    private LitVmPane pane;
    private final List<String> problems = new ArrayList<>();
    private final List<String> seen = new ArrayList<>();

    private record Reply(int status, String result, String error) {
        static Reply result(String result) {
            return new Reply(200, result, null);
        }

        static Reply error(String message) {
            return new Reply(200, null, message);
        }

        static Reply http(int status) {
            return new Reply(status, null, null);
        }

        /** HTTP 200 with a body that is not JSON: the hostile sentence alone. */
        static Reply notJson() {
            return new Reply(0, null, null);
        }
    }

    // ---------------- Setup ----------------

    @BeforeAll
    static void setUp() throws Exception {
        String path = System.getenv("LITVM_STATUS_SNAPSHOT_DIR");
        Assumptions.assumeTrue(path != null && !path.isBlank(), "LITVM_STATUS_SNAPSHOT_DIR not set");
        dir = new File(path);
        Assumptions.assumeTrue(dir.isDirectory(), "LITVM_STATUS_SNAPSHOT_DIR is not a folder");

        //A new home before any view reads the config, as AppCollectiblesCheck does
        home = Files.createTempDirectory("litvm-status-home").toFile().getCanonicalFile();
        for(String property : List.of(SparrowWallet.APP_HOME_PROPERTY, "sparrow-ltc.home", "sparrow-scrypt.home")) {
            savedProperties.put(property, System.getProperty(property));
            System.setProperty(property, home.getAbsolutePath());
        }
        clearConfig();
        for(File resolved : List.of(Storage.getConfigHome(), Storage.getDataHome(), Storage.getCacheHome(), Storage.getStateHome(),
                Storage.getConfigDir(), Storage.getDataDir(), Storage.getWalletsDir())) {
            Assertions.assertTrue(resolved.getCanonicalFile().toPath().startsWith(home.toPath()), resolved + " is not inside the temporary home " + home);
        }

        //A port nothing listens on, for everything that is not the fake server
        try(ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            deadPort = socket.getLocalPort();
        }
        savedProxySelector = ProxySelector.getDefault();
        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return "127.0.0.1".equals(uri.getHost()) ? List.of(Proxy.NO_PROXY)
                        : List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(InetAddress.getLoopbackAddress(), deadPort)));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException e) {
                //expected: nothing listens there
            }
        });

        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            long chain = Long.parseLong(exchange.getRequestURI().getPath().substring(1));
            Reply reply = answer(group(body, "\"method\":\"([A-Za-z_]+)\""), chain, body);
            String id = group(body, "\"id\":(\\d+)");
            String text = reply.status() != 200 ? HOSTILE : reply.error() != null
                    ? "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":" + (reply.error().startsWith("execution reverted") ? 3 : -32000) + ",\"message\":\"" + reply.error() + "\"}}"
                    : "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":\"" + reply.result() + "\"}";
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status() == 0 ? 200 : reply.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        serverUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";

        try {
            Platform.startup(() -> {});
        } catch(IllegalStateException e) {
            //already started
        }
        Platform.setImplicitExit(false);
        //The fonts the app registers at start, for the menu's icons
        GlyphFontRegistry.register(new CryptoFont());
        GlyphFontRegistry.register(new FontAwesome5());
        GlyphFontRegistry.register(new FontAwesome5Brands());
    }

    @AfterAll
    static void tearDown() throws Exception {
        if(server != null) {
            server.stop(0);
        }
        if(savedProxySelector != null) {
            ProxySelector.setDefault(savedProxySelector);
        }
        if(home == null) {
            return;
        }
        for(Map.Entry<String, String> property : savedProperties.entrySet()) {
            if(property.getValue() == null) {
                System.clearProperty(property.getKey());
            } else {
                System.setProperty(property.getKey(), property.getValue());
            }
        }
        clearConfig();
        //The log file in the home stays open until logging stops (by reflection, as AppCollectiblesCheck does)
        org.slf4j.ILoggerFactory loggers = org.slf4j.LoggerFactory.getILoggerFactory();
        try {
            loggers.getClass().getMethod("stop").invoke(loggers);
        } catch(ReflectiveOperationException e) {
            //no such backend: nothing to stop
        }
        try(Stream<Path> walk = Files.walk(home.toPath())) {
            for(Path file : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        } catch(IOException e) {
            //a file still held open stays in the temporary folder; it holds no secret
        }
    }

    private static void clearConfig() throws ReflectiveOperationException {
        Field instance = Config.class.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    private static String group(String text, String pattern) {
        Matcher matcher = Pattern.compile(pattern).matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static Reply answer(String method, long chain, String body) {
        BiFunction<String, Long, Reply> special = override;
        Reply reply = special == null ? null : special.apply(method, chain);
        if(reply != null) {
            return reply;
        }
        return switch(method) {
            case "eth_chainId" -> Reply.result("0x" + Long.toHexString(chain));
            case "eth_getCode" -> Reply.result("0x");
            case "eth_call" -> {
                String scripted = calls.get(group(body, "\"data\":\"0x([0-9a-fA-F]{8})").toLowerCase(Locale.ROOT));
                yield scripted != null ? Reply.result(scripted) : otherCallsRevert ? Reply.error("execution reverted: " + HOSTILE) : Reply.result(word(0));
            }
            default -> Reply.result("0x0");
        };
    }

    private static String word(long value) {
        return "0x" + "0".repeat(48) + String.format("%016x", value);
    }

    private static String word(EvmAddress address) {
        return "0x" + "0".repeat(24) + address.toString().substring(2).toLowerCase(Locale.ROOT);
    }

    private static void script(String getter, String answer) {
        calls.put(Utils.bytesToHex(Abi.selector(getter)).toLowerCase(Locale.ROOT), answer);
    }

    private static LitVmRpcClient fake(LitVmNetwork network) {
        return new LitVmRpcClient(network, serverUrl + network.getChainId(), com.sparrowwallet.drongo.evm.EvmTransport.direct());
    }

    // ---------------- The check ----------------

    @Test
    public void theFailureLinesAreDrawnWhole() throws Exception {
        Wallet wallet = new Wallet("litvm-status-check");
        //An address only: there is no key, so nothing can be signed
        wallet.setLitVmLink(LitVmLink.restore(LitVmLink.Source.WALLET_SEED, ACCOUNT, 0, null, null, 0));
        onFx(() -> {
            pane = new LitVmPane(new WalletForm(null, wallet));
            show(WIDE);
            return null;
        });
        Label status = onFx(() -> (Label)((BorderPane)pane.getCenter()).getBottom());
        LitVmContext context = (LitVmContext)field(LitVmPane.class, "context").get(pane);
        @SuppressWarnings("unchecked")
        Map<LitVmNetwork, LitVmRpcClient> clients = (Map<LitVmNetwork, LitVmRpcClient>)field(LitVmContext.class, "clients").get(context);

        //The pane starts on Transactions, which reads at once through the real endpoints: the dead port stops both
        LitVmHistoryView history = waitFor("the Transactions section", 30, () -> find(pane, LitVmHistoryView.class));
        TableView<?> table = onFx(() -> find(history, TableView.class));
        waitFor("the first reads to fail at the dead port", 90, () -> status.getText().contains("could not be reached")
                && labelText(table.getPlaceholder()).startsWith("History unavailable.") ? true : null);
        seen.add("history placeholder with the explorer behind the dead port: " + onFx(() -> labelText(table.getPlaceholder())));
        onFx(() -> {
            for(LitVmNetwork network : LitVmNetwork.values()) {
                clients.put(network, fake(network));
            }
            return null;
        });

        //The server cannot be reached: Transactions, Refresh
        Node firstPlaceholder = onFx(table::getPlaceholder);
        onFx(() -> {
            clients.put(LITEFORGE, new LitVmRpcClient(LITEFORGE, "http://127.0.0.1:" + deadPort + "/", com.sparrowwallet.drongo.evm.EvmTransport.direct()));
            status.setText("");
            button(history, "Refresh").fire();
            return null;
        });
        await(status, BALANCES + "The server could not be reached.");
        waitFor("the history read to end", 60, () -> table.getPlaceholder() != firstPlaceholder && !labels(history).contains("Loading...") ? true : null);
        snap("01-server-unreachable", status, (Labeled)onFx(table::getPlaceholder));

        //The server is limiting requests (HTTP 429): opening Send reads the balances
        onFx(() -> clients.put(LITEFORGE, fake(LITEFORGE)));
        override = (method, chain) -> Reply.http(429);
        onFx(() -> select("Send"));
        await(status, BALANCES + "The server is limiting requests. Try again later.");
        snap("02-rate-limited", status);

        //The server took too long: a client whose time is already spent, as a call that ran out of time is
        override = null;
        onFx(() -> clients.put(LITEFORGE, fake(LITEFORGE).withDeadline(Duration.ZERO)));
        onFx(() -> select("Tokens"));
        await(status, BALANCES + "The server took too long.");
        snap("03-server-too-slow", status);
        onFx(() -> clients.put(LITEFORGE, fake(LITEFORGE)));
        LitVmTokensView tokens = onFx(() -> find(pane, LitVmTokensView.class));

        //The server answers every request with an error of its own words: Tokens, Refresh
        override = (method, chain) -> Reply.error(HOSTILE);
        refresh(status, tokens);
        await(status, BALANCES + "The server refused the request.");
        snap("04-server-refused-request", status);

        //An answer that is no number is the server's fault, not the wallet's: Tokens, Refresh
        override = (method, chain) -> method.equals("eth_getBalance") ? Reply.result(HOSTILE) : null;
        refresh(status, tokens);
        await(status, BALANCES + "The server could not be reached, or its answer could not be used.");
        snap("05-answer-not-a-number", status);
        //And so is an answer that is not JSON at all
        override = (method, chain) -> method.equals("eth_getBalance") ? Reply.notJson() : null;
        refresh(status, tokens);
        await(status, BALANCES + "The server could not be reached, or its answer could not be used.");
        override = null;

        //Add Token at an address with no contract: the read Add Token makes, then its own words for the failure
        Throwable noContract = failure(() -> {
            LitVmContext.verifyChain(clients.get(LITEFORGE));
            clients.get(LITEFORGE).readToken(NO_CONTRACT);
        });
        say(context, LitVmTokensView.addTokenFailed(noContract));
        await(status, "Could not read a token at that address. There is no contract at that address.");
        snap("06-add-token-no-contract", status);

        //The longest line a flow can give: a step that may have gone out while the server was limiting requests
        String stepHash = "0x" + Utils.bytesToHex(Keccak.keccak256("step".getBytes(StandardCharsets.UTF_8))).toLowerCase(Locale.ROOT);
        say(context, LitVmFlowPane.broadcastFailed(LitVmWords.maybeSent(LITEFORGE, "Step 1 of 2 (later steps were not sent)", stepHash, new LitVmRpcClient.RateLimitedException(HOSTILE))));
        await(status, "Not confirmed. Step 1 of 2 (later steps were not sent) may have been broadcast as " + stepHash
                + ". The server was limiting requests, so its answer is not known." + CHECK_FIRST);
        snap("07-flow-step-may-have-been-broadcast", status);
        narrow("07-flow-step-may-have-been-broadcast-narrow", status);

        //Send: the form with its balance, then the view's own broadcast with filler bytes (nothing is signed)
        onFx(() -> select("Send"));
        LitVmSendView send = onFx(() -> find(pane, LitVmSendView.class));
        onFx(() -> {
            send.refresh();
            return null;
        });
        waitFor("the Send view's balance", 30, () -> all(send).stream().anyMatch(n -> n instanceof ComboBox<?> c && c.getValue() != null) ? true : null);
        onFx(() -> {
            textField(send, "0x...").setText(NO_CONTRACT.toString());
            textField(send, "0.0").setText("0.0001");
            return null;
        });
        byte[] filler = ("not a transaction: " + HOSTILE).getBytes(StandardCharsets.UTF_8);
        Eip1559Transaction.Signed unsigned = new Eip1559Transaction.Signed(filler, Keccak.keccak256(filler), ACCOUNT);
        Method broadcast = LitVmSendView.class.getDeclaredMethod("broadcast", LitVmRpcClient.class, Eip1559Transaction.Signed.class);
        broadcast.setAccessible(true);

        override = (method, chain) -> method.equals("eth_sendRawTransaction") ? Reply.error(HOSTILE) : null;
        broadcast(broadcast, send, clients.get(LITEFORGE), unsigned);
        await(status, "The transfer was not accepted by the server (" + unsigned.hashHex() + "). It may already be known to the network. "
                + "Check History or the explorer before sending it again.");
        snap("08-send-not-accepted-by-server", status);
        narrow("08-send-not-accepted-by-server-narrow", status);

        //The server answers with its own words where the hash belongs
        override = (method, chain) -> method.equals("eth_sendRawTransaction") ? Reply.result(HOSTILE) : null;
        broadcast(broadcast, send, clients.get(LITEFORGE), unsigned);
        await(status, "Not confirmed. The transfer may have been broadcast as " + unsigned.hashHex() + ". The server's answer was lost or could not be used." + CHECK_FIRST);
        snap("09-send-may-have-been-broadcast", status);
        narrow("09-send-may-have-been-broadcast-narrow", status);

        override = (method, chain) -> method.equals("eth_sendRawTransaction") ? Reply.http(429) : null;
        broadcast(broadcast, send, clients.get(LITEFORGE), unsigned);
        await(status, "Not confirmed. The transfer may have been broadcast as " + unsigned.hashHex() + ". The server was limiting requests, so its answer is not known." + CHECK_FIRST);
        snap("10-send-may-have-been-broadcast-rate-limited", status);

        //The send that may have gone out left a notice of its own, with the hash and the app's words
        List<LitVmNotices.Notice> kept = onFx(() -> LitVmNotices.get().of(ACCOUNT));
        Assertions.assertEquals(List.of(new LitVmNotices.Notice(LITEFORGE, ACCOUNT, unsigned.hashHex(), LitVmNotices.State.MAYBE_SENT, null)), kept);
        Assertions.assertEquals(kept, onFx(() -> LitVmNotices.get().outstanding(ACCOUNT, List.of(LITEFORGE))));
        Assertions.assertEquals(List.of(LitVmNotices.format(kept.getFirst())), Config.get().getLitVmNotices());
        Label notice = waitFor("the notice", 30, () -> noticeLine(LitVmNotices.sentence(LitVmNotices.State.MAYBE_SENT)));
        Assertions.assertEquals(unsigned.hashHex(), onFx(() -> all(pane).stream().filter(n -> n instanceof TextArea t && !t.isEditable()).map(n -> ((TextArea)n).getText())
                .findFirst().orElse(null)));
        //The amount is not left for a second click
        Assertions.assertEquals("", onFx(() -> textField(send, "0.0").getText()));

        //A balance read that fails afterwards takes the status line, and the notice stays
        override = (method, chain) -> method.equals("eth_getBalance") ? Reply.error(HOSTILE) : null;
        onFx(() -> {
            send.refresh();
            return null;
        });
        await(status, BALANCES + "The server refused the request.");
        Assertions.assertSame(notice, onFx(() -> noticeLine(LitVmNotices.sentence(LitVmNotices.State.MAYBE_SENT))));
        snap("22-notice-after-refresh-failure", status, notice);
        narrowNotice("22-notice-after-refresh-failure-narrow", LitVmNotices.State.MAYBE_SENT, status);
        override = null;

        //A click in the menu clears the status line, and the notice stays: here on Transactions, where the line sends the user
        onFx(() -> select("Transactions"));
        Assertions.assertEquals("", onFx(status::getText));
        Label afterClick = onFx(() -> noticeLine(LitVmNotices.sentence(LitVmNotices.State.MAYBE_SENT)));
        Assertions.assertNotNull(afterClick, "the notice went with the menu click");
        waitFor("the history read to end", 60, () -> labels(history).contains("Loading...") ? null : true);
        snap("23-notice-after-menu-click", afterClick);
        Assertions.assertEquals("", onFx(status::getText));
        narrowNotice("23-notice-after-menu-click-narrow", LitVmNotices.State.MAYBE_SENT);

        //A second one stacks; one the server did not accept is as uncertain
        LitVmNotices.Notice second = new LitVmNotices.Notice(LITEFORGE, ACCOUNT, stepHash, LitVmNotices.State.NOT_ACCEPTED, null);
        onFx(() -> {
            LitVmNotices.get().add(second);
            return null;
        });
        Label stacked = waitFor("the second notice", 30, () -> noticeLine(LitVmNotices.sentence(LitVmNotices.State.NOT_ACCEPTED)));
        //The area is drawn anew when the notices change: the first one's line is found again
        snap("23-notices-stacked", onFx(() -> noticeLine(LitVmNotices.sentence(LitVmNotices.State.MAYBE_SENT))), stacked);

        //A server's report that it failed on chain: the notice is reworded and stays, still in the way of a second send
        onFx(() -> LitVmNotices.get().resolve(second, Optional.of(new LitVmRpcClient.Receipt(false, BigInteger.ONE, BigInteger.ONE))));
        Label reported = waitFor("the reported failure", 30, () -> noticeLine(LitVmNotices.sentence(LitVmNotices.State.FAILED)));
        snap("23-notice-reported-failed", reported);
        narrowNotice("23-notice-reported-failed-narrow", LitVmNotices.State.FAILED);

        //Dismissing a notice whose bookkeeping still waits for the receipt says what will not be done
        snapDialog("25-dismiss-confirmation", onFx(() -> LitVmPane.dismissAlert(null, new LitVmNotices.Notice(LITEFORGE, ACCOUNT, stepHash, LitVmNotices.State.PENDING,
                LitVmNotices.tokenTag(NO_CONTRACT, "mintable_bp"))).getDialogPane()));

        //Sending again asks first, naming both
        List<LitVmNotices.Notice> outstanding = onFx(() -> LitVmNotices.get().outstanding(ACCOUNT, List.of(LITEFORGE)));
        Assertions.assertEquals(2, outstanding.size());
        snapDialog("24-send-again-confirmation", onFx(() -> LitVmFlowPane.againAlert(null, outstanding, true).getDialogPane()));
        snapDialog("24-flow-again-confirmation", onFx(() -> LitVmFlowPane.againAlert(null, outstanding, false).getDialogPane()));

        //Dismissed, it is gone from the tab and from the config, and no longer stands in the way
        onFx(() -> {
            LitVmNotices.get().dismiss(second);
            return null;
        });
        waitFor("the second notice to go", 30, () -> noticeLine(LitVmNotices.sentence(LitVmNotices.State.FAILED)) == null ? true : null);
        Assertions.assertEquals(1, Config.get().getLitVmNotices().size());
        onFx(() -> select("Send"));

        //HTTP 200 with a body that is not JSON, after the bytes were handed over: it may be out, and the hash is given
        override = (method, chain) -> method.equals("eth_sendRawTransaction") ? Reply.notJson() : null;
        onFx(() -> {
            status.setText("");
            return null;
        });
        broadcast(broadcast, send, clients.get(LITEFORGE), unsigned);
        await(status, "Not confirmed. The transfer may have been broadcast as " + unsigned.hashHex() + ". The server's answer was lost or could not be used." + CHECK_FIRST);
        snap("19-send-answer-not-json", status);
        override = null;

        //Sent, and no receipt after the wait: told as sent, by the flow and by Send alike, never as not completed
        say(context, LitVmFlowPane.broadcastFailed(LitVmWords.pending(LITEFORGE, "Step 1 of 2", stepHash, true)));
        await(status, "Step 1 of 2 was sent and is still pending (" + stepHash + "). The next step was not sent." + CHECK_FIRST);
        snap("20-flow-step-still-pending", status);
        narrow("20-flow-step-still-pending-narrow", status);
        say(context, LitVmWords.stillPending("The transfer", unsigned.hashHex(), false));
        await(status, "The transfer was sent and is still pending (" + unsigned.hashHex() + ")." + CHECK_FIRST);
        snap("21-send-still-pending", status);

        //A fault of the wallet's own before anything was sent
        say(context, LitVmSendView.prepareFailed(new IllegalStateException(HOSTILE)));
        await(status, "Could not prepare the transaction. Something went wrong in the wallet. Nothing was sent.");
        snap("11-wallet-fault-before-sending", status);

        //Swap, Get Quote: the router is the pinned one, and every pool call reverts
        script("factory()", word((EvmAddress)field(UniswapV2Venue.class, "FACTORY").get(null)));
        script("WETH()", word((EvmAddress)field(UniswapV2Venue.class, "WZKLTC").get(null)));
        otherCallsRevert = true;
        onFx(() -> select("Swap"));
        LitVmSwapPane swap = waitFor("the Swap section", 30, () -> find(pane, LitVmSwapPane.class));
        Label quote = (Label)field(LitVmSwapPane.class, "quoteLabel").get(swap);
        waitFor("the swap form", 30, () -> labels(swap).stream().anyMatch(text -> text.startsWith("Swap on ")) ? true : null);
        onFx(() -> {
            textField(swap, "0.0").setText("1");
            button(swap, "Get Quote").fire();
            return null;
        });
        await(quote, "No quote. No pool can fill this trade.");
        snap("12-swap-no-pool", quote);
        narrow("12-swap-no-pool-narrow", quote);

        //The router answers for other contracts than the pinned ones
        calls.clear();
        otherCallsRevert = false;
        onFx(() -> {
            button(swap, "Get Quote").fire();
            return null;
        });
        await(quote, "No quote. The swap contracts do not match what the wallet expects.");
        snap("13-swap-venue-mismatch", quote);

        //Bridges: its own Sepolia client is the real one, so its first read ends at the dead port
        onFx(() -> select("Bridges"));
        LitVmBridgePane bridge = waitFor("the Bridges section", 30, () -> find(pane, LitVmBridgePane.class));
        Label sepolia = (Label)field(LitVmBridgePane.class, "sepoliaBalance").get(bridge);
        waitFor("the Sepolia balance line", 90, () -> sepolia.getText().equals("Loading...") ? null : true);
        Assertions.assertTrue(onFx(sepolia::getText).startsWith("Unavailable. The server could not be reached"), onFx(sepolia::getText));
        snap("14-bridge-sepolia-unavailable", sepolia);
        narrow("14-bridge-sepolia-unavailable-narrow", sepolia);

        //The checks a bridge transfer makes first, against the fake Sepolia; then the flow's own words for the failure
        LitVmRpcClient l1 = fake(BaseBridge.L1);
        script("systemConfig()", word((EvmAddress)field(BaseBridge.class, "SYSTEM_CONFIG").get(null)));
        script("l2ChainId()", word(BaseBridge.L2.getChainId()));
        script("paused()", word(1));
        Throwable paused = failure(() -> {
            LitVmContext.verifyChain(l1);
            BaseBridge.verify(l1);
        });
        calls.clear();
        say(context, LitVmFlowPane.prepareFailed(paused));
        await(status, "Could not prepare the transaction. The Base portal is paused. Nothing was sent.");
        snap("15-base-portal-paused", status);

        Throwable mismatch = failure(() -> {
            LitVmContext.verifyChain(l1);
            ZkLtcBridge.verifyBridge(l1);
        });
        say(context, LitVmFlowPane.prepareFailed(mismatch));
        await(status, "Could not prepare the transaction. The bridge contracts do not match what the wallet expects. Nothing was sent.");
        snap("16-bridge-mismatch", status);

        say(context, LitVmBridgePane.verifyFailed(mismatch));
        await(status, "The Junkcoin bridge could not be verified, so no deposit was started. The bridge contracts do not match what the wallet expects.");
        snap("17-junkcoin-bridge-mismatch", status);
        narrow("17-junkcoin-bridge-mismatch-narrow", status);

        //Transactions: the table's line when the explorer's answer could not be used
        onFx(() -> select("Transactions"));
        Label unavailable = onFx(() -> {
            Label label = LitVmHistoryView.placeholder(table, LitVmHistoryView.historyFailed(new IOException(HOSTILE)));
            table.setPlaceholder(label);
            return label;
        });
        Assertions.assertEquals("History unavailable. The explorer could not be reached, or its answer could not be used.", onFx(unavailable::getText));
        snap("18-history-unavailable", unavailable);
        narrow("18-history-unavailable-narrow", unavailable);

        //Other Networks at the least width: its warning is whole
        onFx(() -> select("Other Networks"));
        Label testNetworks = waitFor("the Other Networks warning", 30, () -> all(pane).stream().filter(n -> n instanceof Label l && l.getText() != null
                && l.getText().startsWith("Test networks only") && l.getScene() != null).map(n -> (Label)n).findFirst().orElse(null));
        snap("27-other-networks", testNetworks);
        narrow("27-other-networks-narrow", testNetworks);

        //The token dialogs, at the only size they have
        snapDialog("28-add-token-dialog", onFx(() -> LitVmTokensView.addTokenDialog(null, LITEFORGE).getDialogPane()));
        snapDialog("28-create-token-dialog", onFx(() -> new LitVmCreateTokenDialog(null, LITEFORGE, ACCOUNT).getDialogPane()));

        collectibles(status, clients);

        Files.write(new File(dir, "sentences.txt").toPath(), seen, StandardCharsets.UTF_8);
        Assertions.assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    // ---------------- Collectibles ----------------

    private static final EvmAddress HOSTILE_COLLECTION = EvmAddress.fromString("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266");

    /** A picture close to the most one may cost: two paths of the most segments, filled and stroked, over a coloured square. */
    private static String costly(int n) {
        StringBuilder svg = new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 64 64\"><rect width=\"64\" height=\"64\" fill=\"#")
                .append(String.format("%02x%02x%02x", 40 + n * 2, 200 - n, 120 + n)).append("\"/>");
        for(int path = 0; path < 2; path++) {
            svg.append("<path fill=\"none\" stroke=\"white\" d=\"M").append(path * 32).append(" 0");
            for(int i = 0; i < SvgSubset.MAX_PATH_SEGMENTS - 2; i++) {
                svg.append(i % 2 == 0 ? "l8 8" : "l-8 8");
            }
            svg.append("\"/>");
        }
        return svg.append("</svg>").toString();
    }

    /**
     * A listing over the picture budget in the pane's own Collectibles section: placeholders past the budget, hosted
     * pictures named by their host, then the section hidden with tiles still to draw and shown again while its read
     * fails, and a detail opened after its tile's plan was released.
     */
    private void collectibles(Label status, Map<LitVmNetwork, LitVmRpcClient> clients) throws Exception {
        onFx(() -> select(CollectiblesPresenter.SECTION));
        LitVmCollectiblesView view = waitFor("the Collectibles section", 30, () -> find(pane, LitVmCollectiblesView.class));
        try {
            waitFor("its first read to end", 90, () -> labels(view).contains("Loading...") ? null : true);
        } catch(AssertionError e) {
            throw new AssertionError(e.getMessage() + ": status [" + onFx(status::getText) + "] labels " + onFx(() -> labels(view)) + " in scene " + onFx(() -> view.getScene() != null));
        }

        Erc721Collection collection = new Erc721Collection(HOSTILE_COLLECTION, "Costly pictures", "COST", false, false);
        List<Nft> tokens = new ArrayList<>();
        for(int i = 0; i < CollectiblesPresenter.MAX_TILES; i++) {
            NftMetadata.Image image = i % 12 == 5 ? new NftMetadata.Image.Hosted("pictures.example.org") : new NftMetadata.Image.Svg(costly(i));
            tokens.add(new Nft(collection, BigInteger.valueOf(i + 1), Optional.of(new NftMetadata("Picture " + (i + 1), "", List.of(), image))));
        }
        LitVmContext.CollectionResult result = new LitVmContext.CollectionResult(HOSTILE_COLLECTION, false, collection, LitVmContext.CollectionStatus.LISTED, tokens, 0);
        LitVmCollectiblesView.Loaded loaded = LitVmCollectiblesView.load(LITEFORGE, List.of(result), 1);
        long drawn = loaded.shown().stream().filter(s -> s.picture().kind() == NftPicture.Kind.SVG).count();
        long hosted = loaded.shown().stream().filter(s -> s.picture().kind() == NftPicture.Kind.HOSTED).count();
        long past = loaded.shown().stream().filter(s -> s.picture().kind() == NftPicture.Kind.NONE).count();
        Assertions.assertEquals(8, hosted);
        Assertions.assertTrue(drawn > 0 && past > 0 && drawn + past + hosted == CollectiblesPresenter.MAX_TILES, drawn + " drawn, " + past + " past the budget");
        Assertions.assertTrue(loaded.shown().stream().mapToLong(s -> s.picture().weight()).sum() <= NftPicture.LISTING_UNITS);
        seen.add("collectibles: " + drawn + " pictures drawn, " + past + " past the budget (No picture), " + hosted + " hosted");

        //Shown and drawn: the pictures within the budget, "No picture" past it, the hosted ones named by their host
        onFx(() -> {
            view.show(loaded);
            view.drawPictures(Integer.MAX_VALUE);
            return null;
        });
        Assertions.assertEquals(0, (int)onFx(view::waitingPictures));
        Assertions.assertEquals(0, (int)onFx(view::heldUnits), "the plans are released once the tiles are drawn");
        Assertions.assertEquals(drawn, (long)onFx(() -> images(view)));
        Assertions.assertEquals(past, (long)onFx(() -> labels(view).stream().filter(NftPicture.NO_PICTURE::equals).count()));
        Assertions.assertEquals(hosted, (long)onFx(() -> labels(view).stream().filter("Picture not shown: hosted at pictures.example.org"::equals).count()));
        snap("29-collectibles-over-budget");
        //The end of the list, where the pictures past the budget are
        ScrollPane around = onFx(() -> all(pane).stream().filter(n -> n instanceof ScrollPane sp && sp.getContent() == view).map(n -> (ScrollPane)n).findFirst().orElseThrow());
        onFx(() -> {
            around.setVvalue(around.getVmax());
            return null;
        });
        snap("29-collectibles-over-budget-end");
        onFx(() -> {
            around.setVvalue(around.getVmin());
            return null;
        });

        //Hidden with every tile still to draw, then shown again while the server cannot be reached: the read fails, and
        //the tiles are drawn all the same, from the listing
        onFx(() -> {
            view.show(loaded);
            select("Tokens");
            return null;
        });
        Assertions.assertEquals(0, (int)onFx(view::heldUnits), "a hidden view holds no plans");
        Assertions.assertEquals((long)CollectiblesPresenter.MAX_TILES, (long)onFx(view::waitingPictures));
        Assertions.assertEquals(0L, (long)onFx(() -> images(view)));
        onFx(() -> {
            clients.put(LITEFORGE, new LitVmRpcClient(LITEFORGE, "http://127.0.0.1:" + deadPort + "/", com.sparrowwallet.drongo.evm.EvmTransport.direct()));
            select(CollectiblesPresenter.SECTION);
            status.setText("");
            view.refresh();
            return null;
        });
        await(status, CollectiblesPresenter.UNREACHABLE);
        waitFor("the tiles to be drawn again", 120, () -> view.drawPictures(Integer.MAX_VALUE) == 0 && view.waitingPictures() == 0 ? true : null);
        Assertions.assertEquals(drawn, (long)onFx(() -> images(view)));
        Assertions.assertEquals(0, (int)onFx(view::heldUnits));
        snap("30-collectibles-hidden-and-shown-again", status);

        //A detail opened after its tile's plan was released: planned again off the FX thread, then drawn
        onFx(() -> {
            view.selectTile(0);
            return null;
        });
        waitFor("the detail's picture", 60, () -> view.detailPending() ? null : true);
        Assertions.assertEquals(drawn + 1, (long)onFx(() -> images(view)), "the detail's picture was not drawn");
        //The detail is below the tiles
        onFx(() -> {
            pane.applyCss();
            pane.layout();
            around.setVvalue(around.getVmax());
            return null;
        });
        snap("31-collectibles-detail-after-release");
        onFx(() -> clients.put(LITEFORGE, fake(LITEFORGE)));
    }

    private static long images(Node root) {
        return all(root).stream().filter(n -> n instanceof javafx.scene.image.ImageView).count();
    }

    /** The label of a notice with this sentence, or null. */
    private Label noticeLine(String sentence) {
        return all(pane).stream().filter(n -> n instanceof Label l && sentence.equals(l.getText()) && l.getScene() != null).map(n -> (Label)n).findFirst().orElse(null);
    }

    /**
     * Writes a dialog's pane, light above and dark below, at the size it gives itself, and notes any label in it that is
     * cut. The dialog is never shown.
     */
    private void snapDialog(String name, DialogPane dialogPane) throws Exception {
        String dark = AppServices.class.getResource("darktheme.css").toExternalForm();
        WritableImage both = onFx(() -> {
            Scene dialogScene = dialogPane.getScene() != null ? dialogPane.getScene() : new Scene(dialogPane);
            dialogScene.getStylesheets().addAll(AppServices.class.getResource("app.css").toExternalForm(), AppServices.class.getResource("general.css").toExternalForm());
            WritableImage image = null;
            for(int theme = 0; theme < 2; theme++) {
                if(theme == 1) {
                    dialogScene.getStylesheets().add(dark);
                }
                dialogPane.applyCss();
                //The size a shown dialog gives it: its own width, and the height its wrapped text needs at that width
                double paneWidth = Math.ceil(dialogPane.prefWidth(-1));
                dialogPane.resize(paneWidth, Math.ceil(dialogPane.prefHeight(paneWidth)));
                dialogPane.layout();
                WritableImage drawn = dialogPane.snapshot(null, null);
                if(image == null) {
                    image = new WritableImage((int)drawn.getWidth(), 2 * (int)drawn.getHeight());
                }
                int width = (int)Math.min(image.getWidth(), drawn.getWidth());
                int height = (int)Math.min(image.getHeight() / 2, drawn.getHeight());
                image.getPixelWriter().setPixels(0, theme * (int)(image.getHeight() / 2), width, height, drawn.getPixelReader(), 0, 0);
                for(Node node : all(dialogPane)) {
                    if(node instanceof Labeled labeled && labeled.getText() != null && !labeled.getText().isEmpty() && labeled.isVisible()) {
                        Node text = labeled.lookup(".text");
                        if(text instanceof Text t && !labeled.getText().equals(t.getText())) {
                            problems.add(name + (theme == 1 ? " (dark)" : " (light)") + ": a label is cut. Wanted: " + labeled.getText() + " Drawn: " + t.getText());
                        }
                        if(labeled.getText().contains("evil.example")) {
                            problems.add(name + ": hostile text is drawn: " + labeled.getText());
                        }
                    }
                }
            }
            seen.add(name + ".png: " + (int)dialogPane.getWidth() + " x " + (int)dialogPane.getHeight() + " " + dialogPane.getHeaderText());
            return image;
        });
        Assertions.assertTrue(ImageIO.write(SwingFXUtils.fromFXImage(both, null), "png", new File(dir, name + ".png")), name);
    }

    // ---------------- Driving ----------------

    private interface Failing {
        void run() throws Exception;
    }

    /** What these calls throw: the real failure, made off the FX thread as the views make it. */
    private static Throwable failure(Failing calls) {
        try {
            calls.run();
        } catch(Exception e) {
            return e;
        }
        throw new AssertionError("the calls did not fail");
    }

    /** A line given to the pane's status line through the context, as every view gives its own. */
    private static void say(LitVmContext context, String line) throws Exception {
        onFx(() -> {
            context.status(line);
            return null;
        });
    }

    private static void refresh(Label status, Node view) throws Exception {
        onFx(() -> {
            status.setText("");
            button(view, "Refresh").fire();
            return null;
        });
    }

    private static void broadcast(Method broadcast, LitVmSendView send, LitVmRpcClient client, Eip1559Transaction.Signed filler) throws Exception {
        onFx(() -> {
            try {
                broadcast.invoke(send, client, filler);
            } catch(ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
    }

    private Object select(String section) {
        ToggleButton button = all(pane).stream().filter(n -> n instanceof ToggleButton t && section.equals(t.getText())).map(n -> (ToggleButton)n)
                .findFirst().orElseThrow(() -> new AssertionError("no section " + section));
        if(!button.isSelected()) {
            button.fire();
        }
        //No window gives a pulse here: the section's nodes join the scene when the pane is laid out, as a frame would do
        pane.applyCss();
        pane.layout();
        return null;
    }

    /** Waits for the label to hold exactly these words; the lines a view shows on the way (Broadcasting...) pass by. */
    private static void await(Label label, String expected) throws Exception {
        long end = System.nanoTime() + 60_000_000_000L;
        String text = null;
        while(System.nanoTime() < end) {
            text = onFx(label::getText);
            if(expected.equals(text)) {
                return;
            }
            Thread.sleep(200);
        }
        Assertions.assertEquals(expected, text);
    }

    // ---------------- Pictures ----------------

    /** Puts the pane in a scene of this width with the main window's stylesheets. */
    private void show(int width) {
        if(scene != null) {
            scene.setRoot(new Group());
        }
        scene = new Scene(pane, width, HEIGHT);
        scene.getStylesheets().add(AppServices.class.getResource("app.css").toExternalForm());
        scene.getStylesheets().add(AppServices.class.getResource("general.css").toExternalForm());
    }

    /** As narrow, for a notice: the pane draws its notices anew when it moves to another scene, so the line is found there. */
    private void narrowNotice(String name, LitVmNotices.State state, Labeled... lines) throws Exception {
        onFx(() -> {
            show(NARROW);
            return null;
        });
        Label line = waitFor("the notice at the least width", 30, () -> noticeLine(LitVmNotices.sentence(state)));
        List<Labeled> all = new ArrayList<>(List.of(lines));
        all.add(line);
        snap(name, all.toArray(new Labeled[0]));
        onFx(() -> {
            show(WIDE);
            return null;
        });
        waitFor("the notice at the usual width", 30, () -> noticeLine(LitVmNotices.sentence(state)));
    }

    private void narrow(String name, Labeled... lines) throws Exception {
        onFx(() -> {
            show(NARROW);
            return null;
        });
        snap(name, lines);
        onFx(() -> {
            show(WIDE);
            return null;
        });
    }

    /**
     * Writes the pane as it is, in the light theme above and the dark theme below, and notes anything wrong: a line that
     * is not drawn whole or not inside the picture, and any text holding the hostile sentence.
     */
    private void snap(String name, Labeled... lines) throws Exception {
        String dark = AppServices.class.getResource("darktheme.css").toExternalForm();
        WritableImage both = onFx(() -> {
            int width = (int)scene.getWidth();
            int height = (int)scene.getHeight();
            WritableImage image = new WritableImage(width, 2 * height);
            for(int theme = 0; theme < 2; theme++) {
                if(theme == 1) {
                    scene.getStylesheets().add(dark);
                }
                pane.applyCss();
                pane.layout();
                WritableImage drawn = scene.snapshot(null);
                image.getPixelWriter().setPixels(0, theme * height, width, height, drawn.getPixelReader(), 0, 0);
                for(Labeled line : lines) {
                    check(name + (theme == 1 ? " (dark)" : " (light)"), line);
                }
                for(Node node : all(pane)) {
                    String text = node instanceof Labeled labeled ? labeled.getText() : node instanceof TextInputControl input ? input.getText() : node instanceof Text t ? t.getText() : null;
                    if(text != null && text.contains("evil.example") && !(node instanceof TextInputControl)) {
                        problems.add(name + ": hostile text is drawn: " + text);
                    }
                }
            }
            scene.getStylesheets().remove(dark);
            return image;
        });
        Assertions.assertTrue(ImageIO.write(SwingFXUtils.fromFXImage(both, null), "png", new File(dir, name + ".png")), name);
        for(Labeled line : lines) {
            seen.add(name + ".png: " + onFx(line::getText));
        }
    }

    /** The line is drawn with all its words (the skin cuts what does not fit and ends it with an ellipsis) inside the picture. */
    private void check(String name, Labeled line) {
        Node text = line.lookup(".text");
        String drawn = text instanceof Text t ? t.getText() : null;
        if(!line.getText().equals(drawn)) {
            problems.add(name + ": the line is cut. Wanted: " + line.getText() + " Drawn: " + drawn);
        }
        Bounds bounds = line.localToScene(line.getBoundsInLocal());
        if(line.getScene() != scene || bounds.getMinX() < 0 || bounds.getMinY() < 0 || bounds.getMaxX() > scene.getWidth() + 0.5 || bounds.getMaxY() > scene.getHeight() + 0.5) {
            problems.add(name + ": the line is not inside the picture: " + bounds);
        }
    }

    // ---------------- Helpers ----------------

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static String labelText(Node node) {
        return node instanceof Labeled labeled && labeled.getText() != null ? labeled.getText() : "";
    }

    private static Button button(Node root, String text) {
        return all(root).stream().filter(n -> n instanceof Button b && text.equals(b.getText())).map(n -> (Button)n).findFirst()
                .orElseThrow(() -> new AssertionError("no button " + text));
    }

    private static TextField textField(Node root, String prompt) {
        return all(root).stream().filter(n -> n instanceof TextField t && prompt.equals(t.getPromptText())).map(n -> (TextField)n).findFirst()
                .orElseThrow(() -> new AssertionError("no field " + prompt));
    }

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

    private static <T> T waitFor(String what, int seconds, Supplier<T> answer) throws Exception {
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        while(System.nanoTime() < end) {
            T value = onFx(answer);
            if(value != null) {
                return value;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    private static <T> T find(Node root, Class<T> type) {
        return all(root).stream().filter(type::isInstance).map(type::cast).findFirst().orElse(null);
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
        if(n instanceof ScrollPane scroll && scroll.getContent() != null) {
            collect(scroll.getContent(), nodes);
        } else if(n instanceof Parent parent) {
            for(Node child : parent.getChildrenUnmodifiable()) {
                collect(child, nodes);
            }
        }
    }
}
