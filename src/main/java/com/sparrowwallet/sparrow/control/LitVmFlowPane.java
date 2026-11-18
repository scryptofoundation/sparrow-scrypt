package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.evm.*;
import com.sparrowwallet.drongo.wallet.LitVmLink;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.wallet.WalletForm;
import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Shared transaction flow for the LitVM tab's bridge and swap sections: prepare on a background thread, confirm with
 * the user, unlock the LitVM account once to sign every step, then broadcast the steps in order, each waiting for a
 * successful receipt before the next is sent.
 */
public abstract class LitVmFlowPane extends VBox {
    private static final int RECEIPT_POLLS = 90;

    protected final WalletForm walletForm;
    protected final Consumer<String> status;
    private final Runnable onBalancesChanged;
    //Shared by every LitVM flow (swaps, bridges, sends); touched only on the FX thread
    static boolean busy;

    /** One transaction of a flow. A dependent one was signed with a fixed gas limit before the step it needs was mined. */
    protected record Step(LitVmRpcClient client, Eip1559Transaction tx, String description, boolean dependent) {
        Step(LitVmRpcClient client, Eip1559Transaction tx, String description) {
            this(client, tx, description, false);
        }
    }

    protected interface Preparer {
        List<Step> prepare() throws Exception;
    }

    protected LitVmFlowPane(WalletForm walletForm, Consumer<String> status, Runnable onBalancesChanged) {
        super(10);
        this.walletForm = walletForm;
        this.status = status;
        this.onBalancesChanged = onBalancesChanged;
    }

    /** Re-reads this section's on-chain state; runs after every completed flow. */
    public abstract void refresh();

    protected Wallet getMasterWallet() {
        Wallet wallet = walletForm.getWallet();
        return wallet.isMasterWallet() ? wallet : wallet.getMasterWallet();
    }

    protected EvmAddress address() {
        return getMasterWallet().getLitVmLink().getAddress();
    }

    protected void prepare(Preparer preparer, Predicate<List<Step>> confirmation, Consumer<List<String>> onSent) {
        prepare(preparer, confirmation, onSent, null);
    }

    /**
     * As {@link #prepare(Preparer, Predicate, Consumer)}. tag, when given, names the bookkeeping onSent does for the
     * flow's last transaction (see {@link LitVmNotices}), so that it is still done when that transaction is only seen
     * confirmed later: after the wait for its receipt ran out, or after a broadcast whose answer was lost.
     */
    protected void prepare(Preparer preparer, Predicate<List<Step>> confirmation, Consumer<List<String>> onSent, java.util.function.Supplier<String> tag) {
        //One flow at a time per account: a second one could take the nonce a pre-signed dependent step was signed with,
        //or repeat a claim that is already on its way
        if(busy) {
            status.accept("Another LitVM transaction is still in progress; wait for it to finish.");
            return;
        }
        busy = true;
        status.accept("Preparing transaction...");
        Task<List<Step>> task = new Task<>() {
            @Override
            protected List<Step> call() throws Exception {
                return preparer.prepare();
            }
        };
        task.setOnSucceeded(e -> {
            List<Step> steps = task.getValue();
            //An earlier send on one of these networks whose fate is not known: ask before taking the next nonce
            if(!mayProceed(LitVmNotices.get(), address(), steps.stream().map(step -> step.client().getNetwork()).toList(),
                    earlier -> confirmAgain(getScene().getWindow(), earlier, false))) {
                busy = false;
                status.accept("Not sent.");
                return;
            }
            if(!confirmation.test(steps)) {
                busy = false;
                status.accept("Cancelled.");
                return;
            }
            LitVmLink link = getMasterWallet().getLitVmLink();
            LitVmLinkDialog.withAccount(getMasterWallet(), walletForm.getStorage(), getScene().getWindow(), account -> {
                if(!account.getAddress().equals(link.getAddress())) {
                    busy = false;
                    status.accept("The unlocked account does not match this LitVM link; nothing was sent.");
                    return;
                }
                List<Eip1559Transaction.Signed> signed = new ArrayList<>();
                for(Step step : steps) {
                    signed.add(account.sign(step.tx()));
                }
                broadcast(steps, signed, onSent, tag);
            }, () -> {
                busy = false;
                status.accept("Not sent.");
            });
        });
        task.setOnFailed(e -> {
            busy = false;
            status.accept(prepareFailed(task.getException()));
        });
        start(task);
    }

    /**
     * Broadcasts a signed transaction. A node error (a JSON-RPC error answer) means the node did not accept it
     * here, which is not proof that it is not out; any other failure at all, such as a timeout or an answer that is
     * not JSON, may come after the node accepted it, so it is reported with the transaction's own hash instead of as
     * "not sent", to keep the user from paying twice by retrying. Either way the failure is told in
     * the app's own words: name is the app's name for the transaction, and the node's message goes to the log only.
     */
    static String send(LitVmRpcClient client, Eip1559Transaction.Signed signed, String name) {
        return send(client, signed, name, false);
    }

    /**
     * As {@link #send(LitVmRpcClient, Eip1559Transaction.Signed, String)} for a step of a flow with steps after it,
     * which are not sent when this one fails. Only a failure that is certain to have left nothing of the transaction
     * outside this process (the client and its transport say so by type) is "was not sent".
     */
    static String send(LitVmRpcClient client, Eip1559Transaction.Signed signed, String plainName, boolean laterSteps) {
        String name = plainName + (laterSteps ? " (later steps were not sent)" : "");
        try {
            return client.sendRawTransaction(signed);
        } catch(EvmTransport.NotSentException e) {
            throw LitVmWords.notSent(plainName, laterSteps, e);
        } catch(LitVmRpcClient.LitVmRpcException e) {
            throw LitVmWords.refused(client.getNetwork(), name, signed.hashHex(), e);
        } catch(IOException | RuntimeException e) {
            //Whatever else went wrong once the bytes were handed over, the transaction may be out
            throw LitVmWords.maybeSent(client.getNetwork(), name, signed.hashHex(), e);
        }
    }

    /** A receipt, or empty while pending; an unreadable answer counts as still pending rather than as a failure. */
    static Optional<LitVmRpcClient.Receipt> receipt(LitVmRpcClient client, String hash) {
        try {
            return client.getReceipt(hash);
        } catch(IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The question before a new send while earlier ones are not yet seen confirmed. Not shown: the caller shows it. */
    static Alert againAlert(javafx.stage.Window owner, List<LitVmNotices.Notice> earlier, boolean transfer) {
        Alert again = new Alert(Alert.AlertType.WARNING, "", ButtonType.OK, ButtonType.CANCEL);
        if(owner != null) {
            again.initOwner(owner);
        }
        again.setTitle(LitVmNotices.AGAIN_TITLE);
        again.setHeaderText(LitVmNotices.againHeader(transfer));
        //Each line a label of its own, wrapped and never cut: the warning and every hash are read whole
        VBox lines = new VBox(4);
        for(String line : LitVmNotices.againText(earlier).split("\n")) {
            Label label = new Label(line);
            label.setWrapText(true);
            label.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
            if(line.startsWith("0x")) {
                label.setStyle("-fx-font-family: monospace;");
            }
            lines.getChildren().add(label);
        }
        again.getDialogPane().setContent(lines);
        //Not sending is what Enter does
        Button anyway = (Button)again.getDialogPane().lookupButton(ButtonType.OK);
        anyway.setText(LitVmNotices.AGAIN_BUTTON);
        anyway.setDefaultButton(false);
        ((Button)again.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
        again.getDialogPane().setPrefWidth(620);
        return again;
    }

    /** Asks whether to send although earlier sends are not yet seen confirmed; true to go on. */
    static boolean confirmAgain(javafx.stage.Window owner, List<LitVmNotices.Notice> earlier, boolean transfer) {
        Optional<ButtonType> answer = againAlert(owner, earlier, transfer).showAndWait();
        return answer.isPresent() && answer.get() == ButtonType.OK;
    }

    /**
     * The guard before a new send: when earlier sends from this account on these networks are not yet seen confirmed
     * (a reported failure too), ask is put the question with them, and its answer decides; with none it is not asked.
     */
    static boolean mayProceed(LitVmNotices notices, EvmAddress account, java.util.Collection<LitVmNetwork> networks, Predicate<List<LitVmNotices.Notice>> ask) {
        List<LitVmNotices.Notice> earlier = notices.outstanding(account, networks);
        return earlier.isEmpty() || ask.test(earlier);
    }

    /** The wait between two looks for a receipt. */
    interface Wait {
        void sleep(long millis) throws InterruptedException;
    }

    /**
     * Hands one signed transaction to the network and waits for its receipt, keeping its notice true all the way:
     * written before the request is made (it may have been broadcast, whatever happens to the app from here on), then
     * sent and pending, then gone when the receipt succeeded. Every other end leaves the notice standing, in the state
     * that end is: the request failed (it may be out), the server did not accept it, no receipt in time, or a receipt
     * that reports a failure. The account is the one that signed, as the signed transaction itself says.
     *
     * tag names the bookkeeping to do should this transaction only be seen confirmed later (see {@link LitVmNotices});
     * handedOver, when given, hears the hash once the server has taken the transaction.
     */
    static LitVmRpcClient.Receipt transact(LitVmNotices notices, LitVmRpcClient client, Eip1559Transaction.Signed signed, String name, boolean moreSteps,
                                           String tag, int polls, Wait wait, Consumer<String> handedOver) throws InterruptedException {
        LitVmNotices.Notice mine = new LitVmNotices.Notice(client.getNetwork(), signed.from(), signed.hashHex(), LitVmNotices.State.MAYBE_SENT, tag);
        //The same transaction may have a notice already: an earlier try signed the same bytes (the same nonce gives the
        //same hash) and its fate is not known. That notice guards already, and is not touched before this try is over
        LitVmNotices.Notice earlier = notices.standing(mine);
        boolean stoodBefore = earlier != null;
        //What this try says of the transaction from here on keeps the earlier notice's bookkeeping when it names none
        LitVmNotices.Notice notice = stoodBefore && tag == null ? new LitVmNotices.Notice(mine.network(), mine.account(), mine.hash(), mine.state(), earlier.tag()) : mine;
        //A notice that could not be saved does not stop the send (a wallet whose config cannot be written must still
        //work): it stands for this run, and the tab says that it will not survive a restart
        if(!stoodBefore && !notices.addWritten(notice)) {
            LitVmWords.logOnly("the notice of " + signed.hashHex() + " was not saved before its transaction was sent", null);
        }
        String hash;
        try {
            hash = send(client, signed, name, moreSteps);
        } catch(LitVmWords.NotAccepted e) {
            notices.add(notice.as(LitVmNotices.State.NOT_ACCEPTED));
            throw e;
        } catch(LitVmWords.MaybeSent e) {
            if(stoodBefore) {
                //This try did hand the bytes over: the earlier notice now says what is latest known
                notices.add(notice);
            }
            throw e;
        } catch(LitVmWords.NeverSent e) {
            if(stoodBefore) {
                //Only this try sent nothing: the earlier one's notice stays exactly as it was
                throw LitVmWords.notSentThisTime(name, moreSteps, signed.hashHex(), e.getCause());
            }
            //Nothing left this process, and the notice was this try's own: there is nothing to keep it for
            notices.dismiss(notice);
            throw e;
        }
        LitVmNotices.Notice pending = notice.as(LitVmNotices.State.PENDING);
        notices.add(pending);
        if(handedOver != null) {
            handedOver.accept(hash);
        }
        Optional<LitVmRpcClient.Receipt> receipt = Optional.empty();
        for(int poll = 0; poll < polls && receipt.isEmpty(); poll++) {
            wait.sleep(2000);
            receipt = receipt(client, hash);
        }
        if(receipt.isEmpty()) {
            throw LitVmWords.pending(client.getNetwork(), name, hash, moreSteps);
        }
        notices.settle(pending, receipt.get());
        if(!receipt.get().success()) {
            throw new LitVmWords.Said(name + " failed on chain (" + hash + ")" + (moreSteps ? "; the next step was not sent." : "."));
        }
        return receipt.get();
    }

    /**
     * After a send ended without a receipt that succeeded: its notice (kept by {@link #transact}) is watched from here
     * on. Never throws: whatever goes wrong here, the caller still has its status line to set.
     */
    static void watchAfter(LitVmNotices notices, Throwable failure, EvmAddress signer, LitVmRpcClient client) {
        try {
            LitVmWords.Uncertain uncertain = LitVmWords.uncertain(failure);
            if(uncertain != null) {
                notices.watch(new LitVmNotices.Notice(uncertain.network(), signer, uncertain.hash(), uncertain.state(), null), (network, hash) -> client.getReceipt(hash));
            }
        } catch(RuntimeException e) {
            LitVmWords.logOnly("could not watch a notice", e);
        }
    }

    private void broadcast(List<Step> steps, List<Eip1559Transaction.Signed> signed, Consumer<List<String>> onSent, java.util.function.Supplier<String> tag) {
        //Which step is being sent, for the failure's handler
        List<String> hashes = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicInteger at = new java.util.concurrent.atomic.AtomicInteger();
        LitVmNotices notices = LitVmNotices.get();
        Task<List<String>> task = new Task<>() {
            @Override
            protected List<String> call() throws Exception {
                for(int i = 0; i < steps.size(); i++) {
                    at.set(i);
                    Step step = steps.get(i);
                    //A failure names the step by its place: its description can hold a token's own symbol
                    String name = LitVmWords.stepName(i, steps.size());
                    if(step.dependent()) {
                        //Now that the step before is mined the call can be simulated: refuse to send it if it would revert
                        //or need more gas than it was signed with, rather than spending the fee on a failure
                        BigInteger gas;
                        try {
                            gas = step.client().estimateGas(signed.get(i).from(), step.tx().getTo(), step.tx().getValue(), step.tx().getData());
                        } catch(IOException e) {
                            throw LitVmWords.wouldFail(name, e);
                        }
                        if(gas.compareTo(step.tx().getGasLimit()) > 0) {
                            throw new LitVmWords.Said(name + " needs " + gas + " gas, more than the " + step.tx().getGasLimit() + " it was signed with, so it was not sent.");
                        }
                    }
                    boolean more = i + 1 < steps.size();
                    //Should this transaction only be seen confirmed later: the last one's bookkeeping, or that later steps were held back
                    String noticeTag = more ? LitVmNotices.NOT_LAST_TAG : tag == null ? null : tag.get();
                    transact(notices, step.client(), signed.get(i), name, more, noticeTag, RECEIPT_POLLS, Thread::sleep, hash -> {
                        hashes.add(hash);
                        updateMessage("Sent " + step.description() + ": " + hash);
                    });
                }
                return List.copyOf(hashes);
            }
        };
        task.messageProperty().addListener((obs, old, text) -> status.accept(text));
        task.setOnSucceeded(e -> {
            busy = false;
            onSent.accept(task.getValue());
            onBalancesChanged.run();
            refresh();
        });
        task.setOnFailed(e -> {
            busy = false;
            int index = Math.min(at.get(), steps.size() - 1);
            //The notice is already kept; here it is only watched on, and nothing here can cost the status line
            watchAfter(notices, task.getException(), signed.get(index).from(), steps.get(index).client());
            status.accept(broadcastFailed(task.getException()));
            try {
                onBalancesChanged.run();
                refresh();
            } catch(RuntimeException failure) {
                LitVmWords.logOnly("could not refresh after a failed flow", failure);
            }
        });
        start(task);
    }

    protected boolean confirmSteps(String title, String header, String details, List<Step> steps, String warning) {
        StringBuilder text = new StringBuilder(details).append("\n\nTransactions:");
        for(int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            BigInteger maxFee = step.tx().getGasLimit().multiply(step.tx().getMaxFeePerGas());
            text.append("\n").append(i + 1).append(". ").append(step.description()).append(" on ").append(step.client().getNetwork().getDisplayName())
                    .append(step.tx().isCreation() ? " (creates a new contract" : " (contract " + step.tx().getTo()).append(", max fee ").append(Wei.format(maxFee)).append(" ").append(step.client().getNetwork().getSymbol()).append(")");
        }
        if(warning != null) {
            text.append("\n\n").append(warning);
        }
        return confirm(title, header, text.toString());
    }

    protected boolean confirm(String title, String header, String text) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.initOwner(getScene().getWindow());
        confirm.setTitle(title);
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

    protected Optional<String> ask(String title, String header, String label) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(getScene().getWindow());
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.setContentText(label);
        Optional<String> input = dialog.showAndWait();
        return input.filter(text -> !text.isBlank());
    }

    /** The most the steps can cost in the native coin: value and gas, plus the L1 data fee on OP Stack chains. */
    protected static BigInteger totalCost(List<Step> steps) throws IOException {
        BigInteger total = BigInteger.ZERO;
        for(Step step : steps) {
            total = total.add(step.tx().maxCost()).add(step.client().l1DataFee(step.tx()));
        }
        return total;
    }

    protected static void requireBalance(LitVmRpcClient client, EvmAddress address, BigInteger needed) throws IOException {
        BigInteger balance = client.getBalance(address);
        if(balance.compareTo(needed) < 0) {
            LitVmNetwork network = client.getNetwork();
            throw new LitVmWords.Said("Not enough " + network.getSymbol() + " on " + network.getDisplayName() + ": need up to " + Wei.format(needed) + ", have " + Wei.format(balance) + ".");
        }
    }

    protected static BigInteger allowance(LitVmRpcClient client, Erc20Token token, EvmAddress owner, EvmAddress spender) throws IOException {
        try {
            return Abi.decodeUint(client.ethCall(token.contract(), Abi.encodeCall("allowance(address,address)", owner, spender)), 0);
        } catch(IllegalArgumentException e) {
            throw new IOException("Invalid allowance response from " + token.contract());
        }
    }

    protected static String shortHash(String hash) {
        String text = hash.startsWith("0x") ? hash.substring(2) : hash;
        return text.length() <= 12 ? text : text.substring(0, 8) + "..." + text.substring(text.length() - 4);
    }

    /** The status line when a flow could not be prepared: nothing was signed or sent yet. */
    static String prepareFailed(Throwable failure) {
        return LitVmWords.prepareFailed(failure);
    }

    /** The status line when a flow stopped while its steps were being sent. */
    static String broadcastFailed(Throwable failure) {
        return LitVmWords.broadcastFailed(failure);
    }

    /** The chain check every flow makes first; another chain is told apart so the status line can say so. */
    protected static void verifyChain(LitVmRpcClient client) throws IOException {
        LitVmContext.verifyChain(client);
    }

    protected static void start(Task<?> task) {
        Thread thread = new Thread(task, "LitVM");
        thread.setDaemon(true);
        thread.start();
    }
}
