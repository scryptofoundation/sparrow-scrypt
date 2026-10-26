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
                broadcast(steps, signed, onSent);
            }, () -> {
                busy = false;
                status.accept("Not sent.");
            });
        });
        task.setOnFailed(e -> {
            busy = false;
            status.accept("Could not prepare the transaction: " + message(task.getException()));
        });
        start(task);
    }

    /**
     * Broadcasts a signed transaction. A node error (a JSON-RPC error answer) means it was refused; any other
     * failure, such as a timeout, may come after the node accepted it, so it is reported with the transaction's own
     * hash instead of as "not sent", to keep the user from paying twice by retrying.
     */
    static String send(LitVmRpcClient client, Eip1559Transaction.Signed signed, String description) {
        try {
            return client.sendRawTransaction(signed);
        } catch(LitVmRpcClient.LitVmRpcException e) {
            throw new IllegalStateException(description + " was refused by the node: " + message(e));
        } catch(IOException e) {
            throw new IllegalStateException(description + " may have been broadcast as " + signed.hashHex() + " (" + message(e)
                    + "). Check it in the explorer before trying again.");
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
                        } catch(IOException e) {
                            throw new IllegalStateException(step.description() + " would fail (" + message(e) + "), so it was not sent");
                        }
                        if(gas.compareTo(step.tx().getGasLimit()) > 0) {
                            throw new IllegalStateException(step.description() + " needs " + gas + " gas, more than the " + step.tx().getGasLimit() + " it was signed with, so it was not sent");
                        }
                    }
                    String hash = send(step.client(), signed.get(i), step.description() + (i + 1 < steps.size() ? " (later steps were not sent)" : ""));
                    hashes.add(hash);
                    updateMessage("Sent " + step.description() + ": " + hash);
                    Optional<LitVmRpcClient.Receipt> receipt = Optional.empty();
                    for(int poll = 0; poll < RECEIPT_POLLS && receipt.isEmpty(); poll++) {
                        Thread.sleep(2000);
                        receipt = receipt(step.client(), hash);
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
            busy = false;
            onSent.accept(task.getValue());
            onBalancesChanged.run();
            refresh();
        });
        task.setOnFailed(e -> {
            busy = false;
            status.accept("Not completed: " + message(task.getException()));
            onBalancesChanged.run();
            refresh();
        });
        start(task);
    }

    protected boolean confirmSteps(String title, String header, String details, List<Step> steps, String warning) {
        StringBuilder text = new StringBuilder(details).append("\n\nTransactions:");
        for(int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            BigInteger maxFee = step.tx().getGasLimit().multiply(step.tx().getMaxFeePerGas());
            text.append("\n").append(i + 1).append(". ").append(step.description()).append(" on ").append(step.client().getNetwork().getDisplayName())
                    .append(" (contract ").append(step.tx().getTo()).append(", max fee ").append(Wei.format(maxFee)).append(" ").append(step.client().getNetwork().getSymbol()).append(")");
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
            throw new IllegalStateException("Not enough " + network.getSymbol() + " on " + network.getDisplayName() + ": need up to " + Wei.format(needed) + ", have " + Wei.format(balance));
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

    protected static String message(Throwable t) {
        return t == null ? "unknown error" : (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
    }

    protected static void start(Task<?> task) {
        Thread thread = new Thread(task, "LitVM");
        thread.setDaemon(true);
        thread.start();
    }
}
