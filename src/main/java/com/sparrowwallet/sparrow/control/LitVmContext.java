package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.evm.*;
import com.sparrowwallet.drongo.wallet.LitVmLink;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.wallet.WalletForm;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.stage.Window;

import java.math.BigInteger;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * What every view of the LitVM sub-tab shares: the linked account, one RPC client per network, the tab's status line,
 * the tracked tokens (kept per chain in the config), and a way to tell the other views that balances changed.
 */
final class LitVmContext {
    final WalletForm walletForm;
    private final Map<LitVmNetwork, LitVmRpcClient> clients = new EnumMap<>(LitVmNetwork.class);
    private final Consumer<String> status;
    private final Supplier<Window> window;
    private final List<Runnable> balanceListeners = new ArrayList<>();

    LitVmContext(WalletForm walletForm, Consumer<String> status, Supplier<Window> window) {
        this.walletForm = walletForm;
        this.status = status;
        this.window = window;
    }

    Wallet masterWallet() {
        Wallet wallet = walletForm.getWallet();
        return wallet.isMasterWallet() ? wallet : wallet.getMasterWallet();
    }

    LitVmLink link() {
        return masterWallet().getLitVmLink();
    }

    EvmAddress address() {
        return link().getAddress();
    }

    LitVmRpcClient client(LitVmNetwork network) {
        return clients.computeIfAbsent(network, LitVmRpcClient::new);
    }

    Window window() {
        return window.get();
    }

    void status(String text) {
        if(Platform.isFxApplicationThread()) {
            status.accept(text);
        } else {
            Platform.runLater(() -> status.accept(text));
        }
    }

    Consumer<String> statusConsumer() {
        return this::status;
    }

    void onBalancesChanged(Runnable listener) {
        balanceListeners.add(listener);
    }

    /** Called after any transaction completes, so every view re-reads what it shows. */
    void balancesChanged() {
        balanceListeners.forEach(Runnable::run);
    }

    void showDocument(String url) {
        AppServices.get().getApplication().getHostServices().showDocument(url);
    }

    static String shortAddress(EvmAddress address) {
        String text = address.toString();
        return text.substring(0, 6) + "..." + text.substring(text.length() - 4);
    }

    static String message(Throwable t) {
        return t == null ? "unknown error" : (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
    }

    static void start(Task<?> task) {
        Thread thread = new Thread(task, "LitVM");
        thread.setDaemon(true);
        thread.start();
    }

    // ---- tracked tokens, per chain ----

    /**
     * Tracked token contracts on a network, from the config: the user's own list (contracts are public, not wallet
     * secrets). Entries are "chainId:0x..."; a bare "0x..." entry predates other networks and means LiteForge.
     */
    static List<EvmAddress> trackedTokens(LitVmNetwork network) {
        List<EvmAddress> tokens = new ArrayList<>();
        for(String entry : Config.get().getLitVmTokens()) {
            int colon = entry.indexOf(':');
            long chainId = colon < 0 ? LitVmNetwork.LITEFORGE_TESTNET.getChainId() : parseChainId(entry.substring(0, colon));
            if(chainId != network.getChainId()) {
                continue;
            }
            try {
                EvmAddress address = EvmAddress.fromString(colon < 0 ? entry : entry.substring(colon + 1));
                if(!tokens.contains(address)) {
                    tokens.add(address);
                }
            } catch(IllegalArgumentException e) {
                //skip a malformed entry rather than failing the tab
            }
        }
        return tokens;
    }

    static void track(LitVmNetwork network, EvmAddress contract) {
        List<String> tokens = Config.get().getLitVmTokens();
        if(tokens.stream().noneMatch(existing -> sameEntry(existing, network, contract))) {
            tokens.add(network.getChainId() + ":" + contract);
            Config.get().setLitVmTokens(tokens);
        }
    }

    static void untrack(LitVmNetwork network, EvmAddress contract) {
        List<String> tokens = Config.get().getLitVmTokens();
        tokens.removeIf(existing -> sameEntry(existing, network, contract));
        Config.get().setLitVmTokens(tokens);
    }

    /** Tokens deployed from this app on a network, with the template each was made from. */
    static Map<EvmAddress, String> createdTokens(LitVmNetwork network) {
        Map<EvmAddress, String> created = new LinkedHashMap<>();
        for(String entry : Config.get().getLitVmCreatedTokens()) {
            String[] parts = entry.split(":");
            if(parts.length != 3 || parseChainId(parts[0]) != network.getChainId()) {
                continue;
            }
            try {
                Erc20Templates.Options.fromKey(parts[2]);
                created.put(EvmAddress.fromString(parts[1]), parts[2]);
            } catch(IllegalArgumentException e) {
                //skip a malformed entry
            }
        }
        return created;
    }

    static void recordCreated(LitVmNetwork network, EvmAddress contract, String key) {
        List<String> created = Config.get().getLitVmCreatedTokens();
        created.add(network.getChainId() + ":" + contract + ":" + key);
        Config.get().setLitVmCreatedTokens(created);
    }

    private static long parseChainId(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch(NumberFormatException e) {
            return -1;
        }
    }

    private static boolean sameEntry(String entry, LitVmNetwork network, EvmAddress contract) {
        int colon = entry.indexOf(':');
        long chainId = colon < 0 ? LitVmNetwork.LITEFORGE_TESTNET.getChainId() : parseChainId(entry.substring(0, colon));
        return chainId == network.getChainId() && (colon < 0 ? entry : entry.substring(colon + 1)).trim().equalsIgnoreCase(contract.toString());
    }

    /** The native balance and every tracked token with its balance on a network, read off the FX thread. */
    record Balances(LitVmNetwork network, BigInteger nativeBalance, List<Erc20Token> tokens, List<BigInteger> tokenBalances, List<String> errors) {}

    Balances readBalances(LitVmNetwork network) throws Exception {
        LitVmRpcClient client = client(network);
        client.verifyChain();
        BigInteger nativeBalance = client.getBalance(address());
        List<Erc20Token> tokens = new ArrayList<>();
        List<BigInteger> balances = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for(EvmAddress contract : trackedTokens(network)) {
            try {
                Erc20Token token = client.readToken(contract);
                tokens.add(token);
                balances.add(client.getTokenBalance(token, address()));
            } catch(Exception e) {
                errors.add(shortAddress(contract) + ": " + message(e));
            }
        }
        return new Balances(network, nativeBalance, tokens, balances, errors);
    }
}
