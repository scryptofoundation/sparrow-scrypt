package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.address.InvalidAddressException;
import com.sparrowwallet.drongo.evm.*;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.wallet.BlockTransaction;
import com.sparrowwallet.drongo.wallet.LitVmLink;
import com.sparrowwallet.drongo.wallet.Payment;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.event.SendActionEvent;
import com.sparrowwallet.sparrow.event.SpendUtxoEvent;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.wallet.WalletForm;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Consumer;

/**
 * The bridges section of the LitVM tab: the Junkcoin bridge (JKC on Junkcoin testnet to wtJKC on LiteForge) and
 * the native zkLTC bridge between Ethereum Sepolia and LiteForge. Every transaction is built and signed locally
 * with the LitVM account; bridge contracts are pinned in drongo and checked against the chain before use.
 */
public class LitVmBridgePane extends VBox {
    //Gas for a call signed before the approval it depends on is mined, so it cannot be estimated yet. Unused gas is
    //not charged. A first Sepolia depositERC20 measured 355926 gas (2026-10-25).
    private static final BigInteger JKC_WITHDRAW_GAS = BigInteger.valueOf(600_000);
    private static final BigInteger ZK_DEPOSIT_GAS = BigInteger.valueOf(600_000);
    private static final int RECEIPT_POLLS = 90;

    private static final String JKC_DEPOSIT = "jkc-deposit";
    private static final String JKC_WITHDRAW = "jkc-withdraw";
    private static final String ZK_DEPOSIT = "zk-deposit";
    private static final String ZK_WITHDRAW = "zk-withdraw";

    private final WalletForm walletForm;
    private final LitVmRpcClient l2;
    private final LitVmRpcClient l1 = new LitVmRpcClient(ZkLtcBridge.L1);
    private final Consumer<String> status;
    private final Runnable onBalancesChanged;

    private final VBox jkcRows = new VBox(6);
    private final VBox zkRows = new VBox(6);
    private final Label sepoliaBalance = new Label("Loading...");

    /** One transaction of a flow. A dependent one was signed with a fixed gas limit before the step it needs was mined. */
    private record Step(LitVmRpcClient client, Eip1559Transaction tx, String description, boolean dependent) {
        Step(LitVmRpcClient client, Eip1559Transaction tx, String description) {
            this(client, tx, description, false);
        }
    }

    private record Entry(String kind, String hash) {}

    public LitVmBridgePane(WalletForm walletForm, LitVmRpcClient l2, Consumer<String> status, Runnable onBalancesChanged) {
        super(10);
        this.walletForm = walletForm;
        this.l2 = l2;
        this.status = status;
        this.onBalancesChanged = onBalancesChanged;

        Label jkcTitle = new Label("Junkcoin bridge (JKC testnet to " + JunkcoinBridge.WJKC.symbol() + ")");
        jkcTitle.setStyle("-fx-font-weight: bold;");
        Label jkcWarning = new Label(JunkcoinBridge.TRUST_WARNING);
        jkcWarning.setWrapText(true);
        jkcWarning.setMaxWidth(560);
        Button depositJkc = new Button("Deposit JKC...");
        depositJkc.setOnAction(e -> depositJkc());
        Button withdrawJkc = new Button("Withdraw " + JunkcoinBridge.WJKC.symbol() + "...");
        withdrawJkc.setOnAction(e -> withdrawJkc());
        Button checkDeposit = new Button("Check Deposit...");
        checkDeposit.setOnAction(e -> checkDeposit());

        Label zkTitle = new Label("zkLTC bridge (Ethereum Sepolia to LiteForge)");
        zkTitle.setStyle("-fx-font-weight: bold;");
        Label zkNote = new Label(ZkLtcBridge.TRUST_WARNING + " There is no Litecoin to zkLTC bridge yet; this is the Ethereum leg of LitVM's planned route.");
        zkNote.setWrapText(true);
        zkNote.setMaxWidth(560);
        Button depositZk = new Button("Deposit from Sepolia...");
        depositZk.setOnAction(e -> depositZk());
        Button withdrawZk = new Button("Withdraw to Sepolia...");
        withdrawZk.setOnAction(e -> withdrawZk());
        Button trackWithdrawal = new Button("Track Withdrawal...");
        trackWithdrawal.setOnAction(e -> trackWithdrawal());

        getChildren().addAll(jkcTitle, jkcWarning, new HBox(8, depositJkc, withdrawJkc, checkDeposit), jkcRows,
                new Separator(), zkTitle, zkNote, new HBox(10, new Label("Sepolia account:"), sepoliaBalance), new HBox(8, depositZk, withdrawZk, trackWithdrawal), zkRows);
    }

    private Wallet getMasterWallet() {
        Wallet wallet = walletForm.getWallet();
        return wallet.isMasterWallet() ? wallet : wallet.getMasterWallet();
    }

    private EvmAddress address() {
        return getMasterWallet().getLitVmLink().getAddress();
    }

    // ---- history ----

    private List<Entry> history(String kind) {
        String suffix = "|" + address();
        List<Entry> entries = new ArrayList<>();
        for(String item : Config.get().getLitVmBridgeHistory()) {
            String[] parts = item.split("\\|");
            if(parts.length == 3 && parts[0].equals(kind) && item.endsWith(suffix)) {
                entries.add(new Entry(parts[0], parts[1]));
            }
        }
        return entries;
    }

    private void record(String kind, String hash) {
        List<String> history = Config.get().getLitVmBridgeHistory();
        String item = kind + "|" + hash.toLowerCase(Locale.ROOT) + "|" + address();
        if(!history.contains(item)) {
            history.add(item);
            Config.get().setLitVmBridgeHistory(history);
        }
    }

    // ---- refresh ----

    private record Row(String text, Runnable claim, String claimLabel) {}

    private record BridgeState(List<Row> jkc, List<Row> zk, String sepolia) {}

    public void refresh() {
        EvmAddress address = address();
        Set<String> jkcDeposits = new LinkedHashSet<>(scanJkcDeposits(address));
        history(JKC_DEPOSIT).forEach(entry -> jkcDeposits.add(entry.hash()));
        List<Entry> jkcWithdrawals = history(JKC_WITHDRAW);
        List<Entry> zkDeposits = history(ZK_DEPOSIT);
        List<Entry> zkWithdrawals = history(ZK_WITHDRAW);

        Task<BridgeState> task = new Task<>() {
            @Override
            protected BridgeState call() throws Exception {
                List<Row> jkc = new ArrayList<>();
                for(String txid : jkcDeposits) {
                    jkc.add(jkcDepositRow(address, txid));
                }
                for(Entry entry : jkcWithdrawals) {
                    jkc.add(new Row("Withdrawal " + shortHash(entry.hash()) + ": submitted; the bridge pays the JKC out to your Junkcoin address", null, null));
                }

                String sepolia;
                List<Row> zk = new ArrayList<>();
                try {
                    l1.verifyChain();
                    sepolia = Wei.format(l1.getBalance(address)) + " ETH, " + ZkLtcBridge.ZKLTC_L1.format(l1.getTokenBalance(ZkLtcBridge.ZKLTC_L1, address)) + " zkLTC";
                } catch(Exception e) {
                    sepolia = "Unavailable: " + message(e);
                }
                for(Entry entry : zkDeposits) {
                    zk.add(zkDepositRow(entry.hash()));
                }
                ZkLtcBridge.Confirmed confirmed = null;
                for(Entry entry : zkWithdrawals) {
                    try {
                        ZkLtcBridge.Withdrawal withdrawal = ZkLtcBridge.findWithdrawal(l2, entry.hash());
                        if(withdrawal == null) {
                            zk.add(new Row("Withdrawal " + shortHash(entry.hash()) + ": pending on LiteForge", null, null));
                            continue;
                        }
                        String amount = Wei.format(withdrawal.callValue()) + " zkLTC";
                        if(confirmed == null) {
                            confirmed = ZkLtcBridge.confirmedState(l1, l2);
                        }
                        if(withdrawal.position().compareTo(confirmed.sendCount()) >= 0) {
                            zk.add(new Row("Withdrawal of " + amount + " (" + shortHash(entry.hash()) + "): waiting for the rollup to confirm", null, null));
                        } else if(ZkLtcBridge.isClaimed(l1, withdrawal)) {
                            zk.add(new Row("Withdrawal of " + amount + " (" + shortHash(entry.hash()) + "): claimed on Sepolia", null, null));
                        } else {
                            ZkLtcBridge.Withdrawal ready = withdrawal;
                            BigInteger sendCount = confirmed.sendCount();
                            zk.add(new Row("Withdrawal of " + amount + " (" + shortHash(entry.hash()) + "): ready to claim", () -> claimZk(ready, sendCount), "Claim on Sepolia"));
                        }
                    } catch(Exception e) {
                        zk.add(new Row("Withdrawal " + shortHash(entry.hash()) + ": " + message(e), null, null));
                    }
                }
                return new BridgeState(jkc, zk, sepolia);
            }
        };
        task.setOnSucceeded(e -> show(task.getValue()));
        task.setOnFailed(e -> status.accept("Could not read bridge status: " + message(task.getException())));
        start(task);
    }

    private Row jkcDepositRow(EvmAddress address, String txid) {
        try {
            JunkcoinBridge.DepositStatus deposit = JunkcoinBridge.depositStatus(l2, txid);
            return switch(deposit.state()) {
                case NOT_REGISTERED -> new Row("Deposit " + shortHash(txid) + ": waiting for " + JunkcoinBridge.DEPOSIT_CONFIRMATIONS + " confirmations and the bridge signers", null, null);
                case CLAIMED -> new Row("Deposit " + shortHash(txid) + ": claimed", null, null);
                case READY -> {
                    JunkcoinBridge.PendingClaim claim = deposit.claim();
                    if(claim.expiry().compareTo(BigInteger.valueOf(System.currentTimeMillis() / 1000)) <= 0) {
                        yield new Row("Deposit " + shortHash(txid) + ": claim expired", null, null);
                    }
                    String amount = JunkcoinBridge.WJKC.format(claim.netAmount()) + " " + JunkcoinBridge.WJKC.symbol();
                    if(!claim.to().equals(address)) {
                        yield new Row("Deposit " + shortHash(txid) + ": " + amount + " for another account (" + claim.to() + ")", null, null);
                    }
                    yield new Row("Deposit " + shortHash(txid) + ": " + amount + " ready to claim", () -> claimJkc(txid, claim), "Claim");
                }
            };
        } catch(Exception e) {
            return new Row("Deposit " + shortHash(txid) + ": " + message(e), null, null);
        }
    }

    private Row zkDepositRow(String hash) {
        try {
            Optional<LitVmRpcClient.Receipt> receipt = l1.getReceipt(hash);
            String text = receipt.isEmpty() ? "pending on Sepolia" : (receipt.get().success() ? "sent; credited on LiteForge within about 15 minutes" : "failed on Sepolia");
            return new Row("Deposit " + shortHash(hash) + ": " + text, null, null);
        } catch(Exception e) {
            return new Row("Deposit " + shortHash(hash) + ": " + message(e), null, null);
        }
    }

    private void show(BridgeState state) {
        sepoliaBalance.setText(state.sepolia());
        fill(jkcRows, state.jkc());
        fill(zkRows, state.zk());
    }

    private void fill(VBox box, List<Row> rows) {
        box.getChildren().clear();
        for(Row row : rows) {
            Label label = new Label(row.text());
            label.setWrapText(true);
            label.setMaxWidth(440);
            if(row.claim() == null) {
                box.getChildren().add(label);
            } else {
                Button claim = new Button(row.claimLabel());
                claim.setOnAction(e -> row.claim().run());
                box.getChildren().add(new HBox(8, label, claim));
            }
        }
    }

    // ---- Junkcoin bridge ----

    private static boolean onJunkcoinTestnet() {
        return Network.get() == Network.TESTNET;
    }

    private static List<Wallet> jkcWallets() {
        List<Wallet> wallets = new ArrayList<>();
        if(onJunkcoinTestnet()) {
            for(Wallet wallet : AppServices.get().getOpenWallets().keySet()) {
                if(wallet.getCoin() == Coin.JUNKCOIN && wallet.isValid()) {
                    wallets.add(wallet);
                }
            }
        }
        return wallets;
    }

    private static Address custodyAddress() throws InvalidAddressException {
        Coin previous = Coin.beginScope(Coin.JUNKCOIN);
        try {
            return Address.fromString(Network.TESTNET, JunkcoinBridge.CUSTODY_ADDRESS);
        } finally {
            Coin.endScope(previous);
        }
    }

    /** Deposits to this account found in open Junkcoin testnet wallets: a custody payment plus the OP_RETURN marker. */
    private static List<String> scanJkcDeposits(EvmAddress address) {
        List<String> txids = new ArrayList<>();
        byte[] custody;
        try {
            custody = custodyAddress().getOutputScript().getProgram();
        } catch(InvalidAddressException e) {
            return txids;
        }
        for(Wallet wallet : jkcWallets()) {
            for(Map.Entry<Sha256Hash, BlockTransaction> entry : wallet.getWalletTransactions().entrySet()) {
                boolean pays = false;
                boolean marked = false;
                for(TransactionOutput output : entry.getValue().getTransaction().getOutputs()) {
                    if(Arrays.equals(output.getScriptBytes(), custody)) {
                        pays = true;
                    }
                    List<ScriptChunk> chunks = output.getScript().getChunks();
                    if(chunks.size() == 2 && chunks.get(0).isOpCode() && chunks.get(0).getOpcode() == ScriptOpCodes.OP_RETURN
                            && address.equals(JunkcoinBridge.depositRecipient(chunks.get(1).getData()))) {
                        marked = true;
                    }
                }
                if(pays && marked && !txids.contains(entry.getKey().toString())) {
                    txids.add(entry.getKey().toString());
                }
            }
        }
        return txids;
    }

    private void depositJkc() {
        if(!onJunkcoinTestnet()) {
            status.accept("The Junkcoin bridge uses Junkcoin testnet. Switch Sparrow-Scrypt to test mode first.");
            return;
        }
        List<Wallet> wallets = jkcWallets();
        if(wallets.isEmpty()) {
            status.accept("Open a Junkcoin testnet wallet first; the deposit is sent from it.");
            return;
        }
        Wallet wallet = wallets.getFirst();
        if(wallets.size() > 1) {
            Map<String, Wallet> byName = new LinkedHashMap<>();
            wallets.forEach(w -> byName.put(w.getFullDisplayName(), w));
            ChoiceDialog<String> choice = new ChoiceDialog<>(byName.keySet().iterator().next(), byName.keySet());
            choice.initOwner(getScene().getWindow());
            choice.setTitle("Deposit JKC");
            choice.setHeaderText("Send the deposit from which Junkcoin wallet?");
            Optional<String> picked = choice.showAndWait();
            if(picked.isEmpty()) {
                return;
            }
            wallet = byName.get(picked.get());
        }

        Optional<String> input = ask("Deposit JKC", "Amount of JKC to bridge (at least " + JunkcoinBridge.MIN_DEPOSIT_SATS / 100_000_000L + " JKC)", "JKC:");
        if(input.isEmpty()) {
            return;
        }
        long sats;
        try {
            sats = new BigDecimal(input.get().trim()).movePointRight(8).longValueExact();
        } catch(NumberFormatException | ArithmeticException e) {
            status.accept("Enter a JKC amount with at most 8 decimal places.");
            return;
        }
        if(sats < JunkcoinBridge.MIN_DEPOSIT_SATS) {
            status.accept("The bridge only accepts deposits of at least " + JunkcoinBridge.MIN_DEPOSIT_SATS / 100_000_000L + " JKC.");
            return;
        }

        Address custody;
        try {
            custody = custodyAddress();
        } catch(InvalidAddressException e) {
            status.accept("Could not read the custody address: " + e.getMessage());
            return;
        }
        EvmAddress address = address();
        BigInteger net = JunkcoinBridge.afterFee(BigInteger.valueOf(sats), JunkcoinBridge.MAX_FEE_BPS);
        String jkc = JunkcoinBridge.WJKC.format(BigInteger.valueOf(sats));
        if(!confirm("Bridge " + jkc + " JKC to LitVM?",
                "From wallet: " + wallet.getFullDisplayName() + "\nTo LitVM account: " + address + "\nCustody address: " + JunkcoinBridge.CUSTODY_ADDRESS
                + "\nYou receive about " + JunkcoinBridge.WJKC.format(net) + " " + JunkcoinBridge.WJKC.symbol() + " after the 1% bridge fee, claimable here after "
                + JunkcoinBridge.DEPOSIT_CONFIRMATIONS + " confirmations.\n\n" + JunkcoinBridge.TRUST_WARNING
                + "\n\nThe wallet's Send tab opens with the custody payment and an OP_RETURN output naming your LitVM account. Review and send it there; "
                + "do not change the OP_RETURN or the deposit is lost.")) {
            return;
        }

        Wallet sendingWallet = wallet;
        Payment payment = new Payment(custody, "Junkcoin bridge deposit to " + address, sats, false);
        EventManager.get().post(new SendActionEvent(sendingWallet, new ArrayList<>(sendingWallet.getSpendableUtxos().keySet())));
        Platform.runLater(() -> EventManager.get().post(new SpendUtxoEvent(sendingWallet, List.of(payment), List.of(JunkcoinBridge.depositData(address)), null)));
        status.accept("Send the deposit from " + sendingWallet.getFullDisplayName() + ". It shows up here once that wallet sees it; press Refresh.");
    }

    private void checkDeposit() {
        Optional<String> input = ask("Check Deposit", "Junkcoin transaction id of a bridge deposit to this account", "Txid:");
        if(input.isEmpty()) {
            return;
        }
        try {
            JunkcoinBridge.txidKey(input.get());
        } catch(IllegalArgumentException e) {
            status.accept(e.getMessage());
            return;
        }
        record(JKC_DEPOSIT, input.get().trim());
        refresh();
    }

    private void claimJkc(String txid, JunkcoinBridge.PendingClaim claim) {
        EvmAddress address = address();
        prepare(() -> {
            l2.verifyChain();
            JunkcoinBridge.verifyBridge(l2);
            Eip1559Transaction tx = l2.prepareCall(address, JunkcoinBridge.BRIDGE, BigInteger.ZERO, JunkcoinBridge.claimCall(txid));
            requireBalance(l2, address, tx.maxCost(), "zkLTC");
            return List.of(new Step(l2, tx, "claimDeposit on the Junkcoin bridge"));
        }, steps -> {
            String amount = JunkcoinBridge.WJKC.format(claim.netAmount()) + " " + JunkcoinBridge.WJKC.symbol();
            return confirmSteps("Claim " + amount + "?", "Mints " + amount + " for Junkcoin deposit " + txid + " to " + address
                    + "\nBridge contract: " + JunkcoinBridge.BRIDGE, steps, JunkcoinBridge.TRUST_WARNING);
        }, hashes -> status.accept("Claimed " + JunkcoinBridge.WJKC.symbol() + " in " + hashes.getLast()));
    }

    private void withdrawJkc() {
        if(!onJunkcoinTestnet()) {
            status.accept("The Junkcoin bridge pays out on Junkcoin testnet. Switch Sparrow-Scrypt to test mode first.");
            return;
        }
        ComboBox<String> destination = new ComboBox<>();
        destination.setEditable(true);
        destination.setPrefWidth(380);
        for(Wallet wallet : jkcWallets()) {
            Coin previous = Coin.beginScope(Coin.JUNKCOIN);
            try {
                destination.getItems().add(wallet.getFreshNode(KeyPurpose.RECEIVE).getAddress().getAddress(Network.TESTNET));
            } finally {
                Coin.endScope(previous);
            }
        }
        if(!destination.getItems().isEmpty()) {
            destination.getSelectionModel().selectFirst();
        }
        TextField amountField = new TextField();
        amountField.setPromptText("0.0");
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.add(new Label("Amount (" + JunkcoinBridge.WJKC.symbol() + ")"), 0, 0);
        grid.add(amountField, 1, 0);
        grid.add(new Label("To Junkcoin address"), 0, 1);
        grid.add(destination, 1, 1);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(getScene().getWindow());
        dialog.setTitle("Withdraw " + JunkcoinBridge.WJKC.symbol());
        dialog.setHeaderText("Burn " + JunkcoinBridge.WJKC.symbol() + " on LiteForge and receive JKC on Junkcoin testnet");
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        Optional<ButtonType> answer = dialog.showAndWait();
        if(answer.isEmpty() || answer.get() != ButtonType.OK) {
            return;
        }

        String jkcAddress = destination.getEditor().getText().trim();
        Coin previous = Coin.beginScope(Coin.JUNKCOIN);
        try {
            Address.fromString(Network.TESTNET, jkcAddress);
        } catch(InvalidAddressException e) {
            status.accept("Not a Junkcoin testnet address: " + jkcAddress);
            return;
        } finally {
            Coin.endScope(previous);
        }
        BigInteger amount;
        try {
            amount = JunkcoinBridge.WJKC.parse(amountField.getText());
        } catch(IllegalArgumentException e) {
            status.accept(e.getMessage());
            return;
        }
        if(amount.signum() == 0) {
            status.accept("Enter an amount greater than zero.");
            return;
        }

        EvmAddress address = address();
        prepare(() -> {
            l2.verifyChain();
            JunkcoinBridge.verifyBridge(l2);
            int feeBps = JunkcoinBridge.feeBps(l2);
            if(feeBps > JunkcoinBridge.MAX_FEE_BPS) {
                throw new IllegalStateException("The bridge fee is now " + feeBps / 100.0 + "%, above the 1% this wallet accepts");
            }
            BigInteger balance = l2.getTokenBalance(JunkcoinBridge.WJKC, address);
            if(balance.compareTo(amount) < 0) {
                throw new IllegalStateException("Only " + JunkcoinBridge.WJKC.format(balance) + " " + JunkcoinBridge.WJKC.symbol() + " available");
            }
            byte[] withdraw = JunkcoinBridge.withdrawCall(amount, jkcAddress, JunkcoinBridge.MAX_FEE_BPS);
            List<Step> steps = new ArrayList<>();
            if(JunkcoinBridge.allowance(l2, address).compareTo(amount) < 0) {
                Eip1559Transaction approve = l2.prepareCall(address, JunkcoinBridge.WJKC.contract(), BigInteger.ZERO, JunkcoinBridge.approveCall(amount));
                steps.add(new Step(l2, approve, "approve the bridge to take " + JunkcoinBridge.WJKC.format(amount) + " " + JunkcoinBridge.WJKC.symbol()));
                steps.add(new Step(l2, l2.prepareCall(address, JunkcoinBridge.BRIDGE, BigInteger.ZERO, withdraw, approve.getNonce().add(BigInteger.ONE), JKC_WITHDRAW_GAS),
                        "initiateWithdrawal to " + jkcAddress, true));
            } else {
                steps.add(new Step(l2, l2.prepareCall(address, JunkcoinBridge.BRIDGE, BigInteger.ZERO, withdraw), "initiateWithdrawal to " + jkcAddress));
            }
            requireBalance(l2, address, totalCost(steps), "zkLTC");
            return steps;
        }, steps -> confirmSteps("Withdraw " + JunkcoinBridge.WJKC.format(amount) + " " + JunkcoinBridge.WJKC.symbol() + "?",
                "To Junkcoin address: " + jkcAddress + "\nYou receive about " + JunkcoinBridge.WJKC.format(JunkcoinBridge.afterFee(amount, JunkcoinBridge.MAX_FEE_BPS))
                        + " JKC after the 1% bridge fee, less the Junkcoin network fee.\nBridge contract: " + JunkcoinBridge.BRIDGE, steps, JunkcoinBridge.TRUST_WARNING),
        hashes -> {
            record(JKC_WITHDRAW, hashes.getLast());
            status.accept("Withdrawal submitted in " + hashes.getLast() + ". The bridge pays the JKC out to " + jkcAddress + ".");
        });
    }

    // ---- zkLTC bridge ----

    private void depositZk() {
        Optional<String> input = ask("Deposit from Sepolia", "Amount of zkLTC to move from Ethereum Sepolia to LiteForge", "zkLTC:");
        if(input.isEmpty()) {
            return;
        }
        BigInteger amount;
        try {
            amount = ZkLtcBridge.ZKLTC_L1.parse(input.get());
        } catch(IllegalArgumentException e) {
            status.accept(e.getMessage());
            return;
        }
        if(amount.signum() == 0) {
            status.accept("Enter an amount greater than zero.");
            return;
        }

        EvmAddress address = address();
        prepare(() -> {
            l1.verifyChain();
            ZkLtcBridge.verifyBridge(l1);
            BigInteger balance = l1.getTokenBalance(ZkLtcBridge.ZKLTC_L1, address);
            if(balance.compareTo(amount) < 0) {
                throw new IllegalStateException("Only " + ZkLtcBridge.ZKLTC_L1.format(balance) + " zkLTC on Sepolia");
            }
            List<Step> steps = new ArrayList<>();
            if(ZkLtcBridge.depositAllowance(l1, address).compareTo(amount) < 0) {
                Eip1559Transaction approve = l1.prepareCall(address, ZkLtcBridge.ZKLTC_L1.contract(), BigInteger.ZERO, ZkLtcBridge.approveCall(amount));
                steps.add(new Step(l1, approve, "approve the inbox to take " + ZkLtcBridge.ZKLTC_L1.format(amount) + " zkLTC"));
                steps.add(new Step(l1, l1.prepareCall(address, ZkLtcBridge.INBOX, BigInteger.ZERO, ZkLtcBridge.depositCall(amount), approve.getNonce().add(BigInteger.ONE), ZK_DEPOSIT_GAS),
                        "depositERC20 on the Sepolia inbox", true));
            } else {
                steps.add(new Step(l1, l1.prepareCall(address, ZkLtcBridge.INBOX, BigInteger.ZERO, ZkLtcBridge.depositCall(amount)), "depositERC20 on the Sepolia inbox"));
            }
            requireBalance(l1, address, totalCost(steps), "Sepolia ETH");
            return steps;
        }, steps -> confirmSteps("Deposit " + ZkLtcBridge.ZKLTC_L1.format(amount) + " zkLTC to LiteForge?",
                "From and to: " + address + "\nInbox: " + ZkLtcBridge.INBOX + "\nToken: " + ZkLtcBridge.ZKLTC_L1.contract(), steps, ZkLtcBridge.TRUST_WARNING),
        hashes -> {
            record(ZK_DEPOSIT, hashes.getLast());
            status.accept("Deposit sent in " + hashes.getLast() + ". It is credited on LiteForge within about 15 minutes.");
        });
    }

    private void withdrawZk() {
        Optional<String> input = ask("Withdraw to Sepolia", "Amount of zkLTC to move from LiteForge to Ethereum Sepolia", "zkLTC:");
        if(input.isEmpty()) {
            return;
        }
        BigInteger amount;
        try {
            amount = Wei.parse(input.get());
        } catch(IllegalArgumentException e) {
            status.accept(e.getMessage());
            return;
        }
        if(amount.signum() == 0) {
            status.accept("Enter an amount greater than zero.");
            return;
        }

        EvmAddress address = address();
        prepare(() -> {
            l2.verifyChain();
            Eip1559Transaction tx = l2.prepareCall(address, ZkLtcBridge.ARB_SYS, amount, ZkLtcBridge.withdrawCall(address));
            requireBalance(l2, address, tx.maxCost(), "zkLTC");
            return List.of(new Step(l2, tx, "withdrawEth to " + address + " on Sepolia"));
        }, steps -> confirmSteps("Withdraw " + Wei.format(amount) + " zkLTC to Sepolia?",
                "From and to: " + address + "\nAfter the rollup confirms it (typically 1 to 8 hours) a Claim button appears here; claiming needs Sepolia ETH for gas.",
                steps, ZkLtcBridge.TRUST_WARNING),
        hashes -> {
            record(ZK_WITHDRAW, hashes.getLast());
            status.accept("Withdrawal sent in " + hashes.getLast() + ". Claim it on Sepolia once the rollup confirms it.");
        });
    }

    private void trackWithdrawal() {
        Optional<String> input = ask("Track Withdrawal", "LiteForge transaction hash of a withdrawal to Sepolia made from this account", "Tx hash:");
        if(input.isEmpty()) {
            return;
        }
        String hash = input.get().trim();
        if(!hash.matches("0x[0-9a-fA-F]{64}")) {
            status.accept("Not a LiteForge transaction hash: " + hash);
            return;
        }
        EvmAddress address = address();
        Task<ZkLtcBridge.Withdrawal> task = new Task<>() {
            @Override
            protected ZkLtcBridge.Withdrawal call() throws Exception {
                l2.verifyChain();
                return ZkLtcBridge.findWithdrawal(l2, hash);
            }
        };
        task.setOnSucceeded(e -> {
            ZkLtcBridge.Withdrawal withdrawal = task.getValue();
            if(withdrawal == null) {
                status.accept("That transaction made no withdrawal to Sepolia, or is still pending.");
            } else if(!withdrawal.caller().equals(address)) {
                status.accept("That withdrawal was made by " + withdrawal.caller() + ", not this account.");
            } else {
                record(ZK_WITHDRAW, hash);
                refresh();
            }
        });
        task.setOnFailed(e -> status.accept("Could not read the withdrawal: " + message(task.getException())));
        start(task);
    }

    private void claimZk(ZkLtcBridge.Withdrawal withdrawal, BigInteger sendCount) {
        EvmAddress address = address();
        prepare(() -> {
            l1.verifyChain();
            l2.verifyChain();
            byte[] data = ZkLtcBridge.claimCall(l2, withdrawal, sendCount);
            Eip1559Transaction tx = l1.prepareCall(address, ZkLtcBridge.OUTBOX, BigInteger.ZERO, data);
            requireBalance(l1, address, tx.maxCost(), "Sepolia ETH");
            return List.of(new Step(l1, tx, "executeTransaction on the Sepolia outbox"));
        }, steps -> confirmSteps("Claim " + Wei.format(withdrawal.callValue()) + " zkLTC on Sepolia?",
                "Pays " + withdrawal.destination() + "\nOutbox: " + ZkLtcBridge.OUTBOX, steps, ZkLtcBridge.TRUST_WARNING),
        hashes -> status.accept("Claimed on Sepolia in " + hashes.getLast()));
    }

    // ---- shared flow: prepare, confirm, sign once, broadcast in order ----

    private interface Preparer {
        List<Step> prepare() throws Exception;
    }

    private void prepare(Preparer preparer, java.util.function.Predicate<List<Step>> confirmation, Consumer<List<String>> onSent) {
        status.accept("Preparing transaction...");
        Task<List<Step>> task = new Task<>() {
            @Override
            protected List<Step> call() throws Exception {
                return preparer.prepare();
            }
        };
        task.setOnSucceeded(e -> {
            List<Step> steps = task.getValue();
            if(!confirmation.test(steps)) {
                status.accept("Cancelled.");
                return;
            }
            LitVmLink link = getMasterWallet().getLitVmLink();
            LitVmLinkDialog.withAccount(getMasterWallet(), walletForm.getStorage(), getScene().getWindow(), account -> {
                if(!account.getAddress().equals(link.getAddress())) {
                    status.accept("The unlocked account does not match this LitVM link; nothing was sent.");
                    return;
                }
                List<Eip1559Transaction.Signed> signed = new ArrayList<>();
                for(Step step : steps) {
                    signed.add(account.sign(step.tx()));
                }
                broadcast(steps, signed, onSent);
            }, () -> status.accept("Not sent."));
        });
        task.setOnFailed(e -> status.accept("Could not prepare the transaction: " + message(task.getException())));
        start(task);
    }

    private void broadcast(List<Step> steps, List<Eip1559Transaction.Signed> signed, Consumer<List<String>> onSent) {
        Task<List<String>> task = new Task<>() {
            @Override
            protected List<String> call() throws Exception {
                List<String> hashes = new ArrayList<>();
                for(int i = 0; i < steps.size(); i++) {
                    Step step = steps.get(i);
                    if(step.dependent()) {
                        //Now that the step before is mined the call can be simulated: refuse to send it if it would revert
                        //or need more gas than it was signed with, rather than spending the fee on a failure
                        BigInteger gas;
                        try {
                            gas = step.client().estimateGas(signed.get(i).from(), step.tx().getTo(), step.tx().getValue(), step.tx().getData());
                        } catch(java.io.IOException e) {
                            throw new IllegalStateException(step.description() + " would fail (" + message(e) + "), so it was not sent");
                        }
                        if(gas.compareTo(step.tx().getGasLimit()) > 0) {
                            throw new IllegalStateException(step.description() + " needs " + gas + " gas, more than the " + step.tx().getGasLimit() + " it was signed with, so it was not sent");
                        }
                    }
                    String hash = step.client().sendRawTransaction(signed.get(i));
                    hashes.add(hash);
                    updateMessage("Sent " + step.description() + ": " + hash);
                    Optional<LitVmRpcClient.Receipt> receipt = Optional.empty();
                    for(int poll = 0; poll < RECEIPT_POLLS && receipt.isEmpty(); poll++) {
                        Thread.sleep(2000);
                        receipt = step.client().getReceipt(hash);
                    }
                    if(receipt.isEmpty()) {
                        throw new IllegalStateException(step.description() + " is still pending (" + hash + ")" + (i + 1 < steps.size() ? "; the next step was not sent" : ""));
                    }
                    if(!receipt.get().success()) {
                        throw new IllegalStateException(step.description() + " failed on chain (" + hash + ")" + (i + 1 < steps.size() ? "; the next step was not sent" : ""));
                    }
                }
                return hashes;
            }
        };
        task.messageProperty().addListener((obs, old, text) -> status.accept(text));
        task.setOnSucceeded(e -> {
            onSent.accept(task.getValue());
            onBalancesChanged.run();
            refresh();
        });
        task.setOnFailed(e -> status.accept("Not completed: " + message(task.getException())));
        start(task);
    }

    private boolean confirmSteps(String header, String details, List<Step> steps, String warning) {
        StringBuilder text = new StringBuilder(details).append("\n\nTransactions:");
        for(int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            BigInteger maxFee = step.tx().getGasLimit().multiply(step.tx().getMaxFeePerGas());
            text.append("\n").append(i + 1).append(". ").append(step.description()).append(" on ").append(step.client().getNetwork().getDisplayName())
                    .append(" (contract ").append(step.tx().getTo()).append(", max fee ").append(Wei.format(maxFee)).append(" ").append(step.client().getNetwork().getSymbol()).append(")");
        }
        text.append("\n\n").append(warning);
        return confirm(header, text.toString());
    }

    private boolean confirm(String header, String text) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.initOwner(getScene().getWindow());
        confirm.setTitle("Confirm Bridge Transfer");
        confirm.setHeaderText(header);
        TextArea area = new TextArea(text);
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefRowCount(14);
        area.setPrefColumnCount(60);
        confirm.getDialogPane().setContent(area);
        Optional<ButtonType> answer = confirm.showAndWait();
        return answer.isPresent() && answer.get() == ButtonType.OK;
    }

    private Optional<String> ask(String title, String header, String label) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(getScene().getWindow());
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.setContentText(label);
        Optional<String> input = dialog.showAndWait();
        return input.filter(text -> !text.isBlank());
    }

    private static BigInteger totalCost(List<Step> steps) {
        return steps.stream().map(step -> step.tx().maxCost()).reduce(BigInteger.ZERO, BigInteger::add);
    }

    private static void requireBalance(LitVmRpcClient client, EvmAddress address, BigInteger needed, String unit) throws Exception {
        BigInteger balance = client.getBalance(address);
        if(balance.compareTo(needed) < 0) {
            throw new IllegalStateException("Not enough " + unit + " on " + client.getNetwork().getDisplayName() + ": need up to " + Wei.format(needed) + ", have " + Wei.format(balance));
        }
    }

    private static String shortHash(String hash) {
        String text = hash.startsWith("0x") ? hash.substring(2) : hash;
        return text.length() <= 12 ? text : text.substring(0, 8) + "..." + text.substring(text.length() - 4);
    }

    private static String message(Throwable t) {
        return t == null ? "unknown error" : (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
    }

    private static void start(Task<?> task) {
        Thread thread = new Thread(task, "LitVM bridge");
        thread.setDaemon(true);
        thread.start();
    }
}
