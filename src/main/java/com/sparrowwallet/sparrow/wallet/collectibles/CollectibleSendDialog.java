package com.sparrowwallet.sparrow.wallet.collectibles;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.address.InvalidAddressException;
import com.sparrowwallet.drongo.ordinals.CollectibleTransactions;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.wallet.InsufficientFundsException;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletTransaction;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.control.FeeRangeSlider;
import com.sparrowwallet.sparrow.event.ViewPSBTEvent;
import com.sparrowwallet.sparrow.net.ord.OrdClient;
import com.sparrowwallet.sparrow.net.ord.OrdOutput;
import com.sparrowwallet.sparrow.net.ord.OrdRune;
import com.sparrowwallet.sparrow.net.ord.RuneBalance;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sending an inscription or an amount of a rune: asks for the recipient (and amount), builds the fixed-order
 * transaction, shows everything that will move, and opens the PSBT in a transaction tab, where the wallet's own
 * signer (software or hardware) signs and broadcasts it as for any other send.
 */
public final class CollectibleSendDialog {
    private CollectibleSendDialog() {}

    private record Choice(Address recipient, BigInteger amount, double feeRate) {}

    /**
     * Sends the inscription at the satpoint; carried is the server's answer for that same output, fetched just before.
     * On the coins other than Litecoin an inscription alone on its output at a positive offset is split off (offset plus
     * postage goes, the rest returns as change); otherwise the whole output goes, with everything on it.
     */
    public static void sendInscription(Window owner, Wallet wallet, String inscriptionId, String satpoint, OrdOutput carried) {
        Coin coin = wallet.getCoin();
        Optional<Choice> choice = ask(owner, coin, "Send Inscription", "Send inscription " + shortId(inscriptionId), null, 0);
        if(choice.isEmpty()) {
            return;
        }
        try {
            CollectiblesManager.Snapshot snapshot = snapshot(wallet);
            CollectibleSend.Inscribed inscribed = CollectibleSend.inscribedInput(wallet, satpoint);
            WalletTransaction walletTransaction;
            try {
                walletTransaction = CollectibleTransactions.sendInscription(wallet, inscribed.pinned(), CollectibleSend.splitOffset(coin, inscribed),
                        CollectibleSend.inscriptionsOnOutput(carried), choice.get().recipient(), CollectibleSend.feeCandidates(wallet, snapshot),
                        choice.get().feeRate(), AppServices.getMinimumRelayFeeRate());
            } catch(InsufficientFundsException e) {
                if(coin == Coin.LITECOIN) {
                    throw e;
                }
                throw new InsufficientFundsException("Not enough checked " + coin.getTicker() + " to pay the fee for this transaction");
            }
            long whole = inscribed.pinned().utxo().getValue();
            long sent = walletTransaction.getTransaction().getOutputs().getFirst().getValue();
            List<String> moving = new ArrayList<>();
            if(sent < whole) {
                moving.add("Inscription " + inscriptionId);
            } else {
                for(String id : carried.inscriptions()) {
                    moving.add("Inscription " + id);
                }
                for(Map.Entry<String, RuneBalance> rune : carried.runes().entrySet()) {
                    moving.add(rune.getValue().format() + " " + rune.getKey());
                }
            }
            if(coin == Coin.LITECOIN) {
                moving.add(whole + " litoshis of LTC (the output's full value)");
            } else if(sent < whole) {
                moving.add(sent + " " + coin.getSubunit() + " of " + coin.getTicker() + ", split from an output of " + whole + " " + coin.getSubunit()
                        + " (the rest comes back to this wallet as change)");
            } else {
                moving.add(whole + " " + coin.getSubunit() + " of " + coin.getTicker() + " (the output's full value)");
            }
            String note = sent < coin.getDustLimit() && coin.getSoftDustSurcharge() > 0
                    ? "The fee includes " + coin.getCapitalizedName() + "'s " + coin.getSoftDustSurcharge() + " " + coin.getSubunit()
                      + " charge for an output under " + coin.getDustLimit() + " " + coin.getSubunit() + "." : null;
            if(confirm(owner, coin, "Send inscription?", choice.get().recipient(), moving, walletTransaction, note)) {
                open(owner, walletTransaction, "Send inscription " + shortId(inscriptionId));
            }
        } catch(Exception e) {
            AppServices.showErrorDialog("Cannot send inscription", e.getMessage());
        }
    }

    /** Looks up the rune on the ord server first (its id and divisibility), off the FX thread. */
    public static void sendRunes(Window owner, List<Wallet> holdingWallets, String runeName) {
        if(holdingWallets.isEmpty()) {
            return;
        }
        String url = CollectiblesManager.serverUrl(holdingWallets.getFirst());
        if(url == null) {
            AppServices.showErrorDialog("Cannot send runes", "Inscription checks are off.");
            return;
        }
        Task<OrdRune> lookup = new Task<>() {
            @Override
            protected OrdRune call() throws Exception {
                //The app's settings resolve per coin, and the unified app refuses a thread with no coin scope
                com.sparrowwallet.drongo.Coin previousCoin = com.sparrowwallet.drongo.Coin.beginScope(holdingWallets.getFirst().getCoin());
                try {
                    return OrdClient.forApp(url).rune(runeName);
                } finally {
                    com.sparrowwallet.drongo.Coin.endScope(previousCoin);
                }
            }
        };
        lookup.setOnSucceeded(event -> sendRunes(owner, holdingWallets, lookup.getValue()));
        lookup.setOnFailed(event -> AppServices.showErrorDialog("Cannot send runes", "Could not look up " + runeName + ": " + lookup.getException().getMessage()));
        Thread thread = new Thread(lookup, "Rune lookup");
        thread.setDaemon(true);
        thread.start();
    }

    private static void sendRunes(Window owner, List<Wallet> holdingWallets, OrdRune rune) {
        Optional<Choice> choice = ask(owner, holdingWallets.getFirst().getCoin(), "Send Runes", "Send " + rune.spacedName(), rune.spacedName(), rune.divisibility());
        if(choice.isEmpty()) {
            return;
        }
        //Build from the first holding wallet that can; a wallet that holds the runes but lacks fee coins explains more than one that lacks the runes
        String problem = null;
        String holdsLess = null;
        for(Wallet wallet : holdingWallets) {
            CollectiblesManager.Snapshot snapshot;
            List<CollectibleTransactions.Pinned> inputs;
            WalletTransaction walletTransaction;
            try {
                snapshot = snapshot(wallet);
                inputs = CollectibleSend.runeInputs(wallet, snapshot, rune.spacedName(), choice.get().amount());
            } catch(IllegalArgumentException e) {
                holdsLess = holdsLess == null ? e.getMessage() : holdsLess;
                continue;
            } catch(Exception e) {
                problem = problem == null ? e.getMessage() : problem;
                continue;
            }
            try {
                walletTransaction = CollectibleTransactions.sendRunes(wallet, inputs, choice.get().recipient(), rune.id(), choice.get().amount(),
                        CollectibleSend.feeCandidates(wallet, snapshot), choice.get().feeRate(), AppServices.getMinimumRelayFeeRate());
            } catch(Exception e) {
                problem = problem == null ? wallet.getFullDisplayName() + " holds the runes, but: " + e.getMessage() : problem;
                continue;
            }

            RuneBalance sent = new RuneBalance(choice.get().amount(), rune.divisibility(), rune.symbol());
            List<String> moving = new ArrayList<>();
            moving.add(sent.format() + " " + rune.spacedName() + " to the recipient");
            moving.add("Everything else on the spent outputs stays in " + wallet.getFullDisplayName() + " (its own change output)");
            for(CollectibleTransactions.Pinned input : inputs) {
                OrdOutput answer = snapshot.answers().get(input.utxo().toString());
                if(answer != null) {
                    for(Map.Entry<String, RuneBalance> other : answer.runes().entrySet()) {
                        if(!other.getKey().equals(rune.spacedName())) {
                            moving.add("Also on the inputs (kept): " + other.getValue().format() + " " + other.getKey());
                        }
                    }
                }
            }
            //One confirmation only, whatever the answer
            if(confirm(owner, wallet.getCoin(), "Send runes?", choice.get().recipient(), moving, walletTransaction, null)) {
                try {
                    open(owner, walletTransaction, "Send " + sent.format() + " " + rune.spacedName());
                } catch(Exception e) {
                    AppServices.showErrorDialog("Cannot send runes", e.getMessage());
                }
            }
            return;
        }
        AppServices.showErrorDialog("Cannot send runes", problem != null ? problem : holdsLess);
    }

    private static CollectiblesManager.Snapshot snapshot(Wallet wallet) {
        Wallet master = wallet.isNested() ? wallet.getMasterWallet() : wallet;
        CollectiblesManager.Snapshot snapshot = CollectiblesManager.get().getSnapshot(master);
        if(snapshot == null || !snapshot.enabled()) {
            throw new IllegalStateException("This wallet's outputs have not been checked for inscriptions yet, so no output can safely pay the fee.");
        }
        return snapshot;
    }

    private static Optional<Choice> ask(Window owner, Coin coin, String title, String header, String runeName, int divisibility) {
        Dialog<Choice> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        TextField recipient = new TextField();
        recipient.setPromptText(coin == Coin.LITECOIN ? "ltc1p..." : coin.getCapitalizedName() + " address");
        recipient.setPrefColumnCount(42);
        TextField amount = new TextField();
        FeeRangeSlider feeRange = new FeeRangeSlider();
        feeRange.setFeeRate(AppServices.getDefaultFeeRate());
        Label feeLabel = new Label();
        String rateUnit = coin == Coin.LITECOIN ? "lit/vB" : "sat/vB";
        feeRange.valueProperty().addListener((o, a, b) -> feeLabel.setText(String.format("%.2f " + rateUnit, feeRange.getFeeRate())));
        feeLabel.setText(String.format("%.2f " + rateUnit, feeRange.getFeeRate()));
        Label problem = new Label();
        problem.setWrapText(true);
        problem.setMaxWidth(520);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.addRow(0, new Label("Recipient:"), recipient);
        if(runeName != null) {
            amount.setPromptText("up to " + divisibility + " decimal places");
            grid.addRow(1, new Label("Amount:"), amount);
        }
        grid.addRow(2, new Label("Fee rate:"), new VBox(4, feeRange, feeLabel));
        VBox content = new VBox(10, grid, problem);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        Button ok = (Button)dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.setText("Continue");

        Runnable check = () -> {
            Optional<String> recipientProblem = CollectibleSend.recipientProblem(recipient.getText(), coin);
            String text = recipientProblem.orElse(null);
            if(text == null && runeName != null) {
                try {
                    if(CollectibleSend.toBaseUnits(amount.getText(), divisibility).signum() <= 0) {
                        text = "Enter an amount above zero";
                    }
                } catch(IllegalArgumentException e) {
                    text = e.getMessage();
                }
            }
            if(text == null) {
                try {
                    text = CollectibleSend.recipientWarning(CollectibleSend.parseRecipient(recipient.getText(), coin), coin).orElse(null);
                    problem.setStyle("");
                    ok.setDisable(false);
                } catch(InvalidAddressException e) {
                    ok.setDisable(true);
                }
            } else {
                problem.setStyle("-fx-text-fill: -fx-accent;");
                ok.setDisable(true);
            }
            problem.setText(text == null ? "" : text);
        };
        recipient.textProperty().addListener((o, a, b) -> check.run());
        amount.textProperty().addListener((o, a, b) -> check.run());
        check.run();

        dialog.setResultConverter(button -> {
            if(button != ButtonType.OK) {
                return null;
            }
            try {
                BigInteger units = runeName == null ? null : CollectibleSend.toBaseUnits(amount.getText(), divisibility);
                return new Choice(CollectibleSend.parseRecipient(recipient.getText(), coin), units, feeRange.getFeeRate());
            } catch(InvalidAddressException e) {
                return null;
            }
        });
        return dialog.showAndWait();
    }

    private static boolean confirm(Window owner, Coin coin, String header, Address recipient, List<String> moving, WalletTransaction walletTransaction, String note) {
        StringBuilder text = new StringBuilder("To: ").append(recipient).append("\n\nThis moves:\n");
        for(String item : moving) {
            text.append("  ").append(item).append("\n");
        }
        text.append("\nFee: ").append(walletTransaction.getFee()).append(coin == Coin.LITECOIN ? " litoshis" : " " + coin.getSubunit())
                .append(", paid from outputs checked to carry nothing.");
        if(note != null) {
            text.append(" ").append(note);
        }
        CollectibleSend.recipientWarning(recipient, coin).ifPresent(warning -> text.append("\n\n").append(warning));
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "", ButtonType.CANCEL, ButtonType.OK);
        alert.initOwner(owner);
        alert.setTitle("Confirm");
        alert.setHeaderText(header);
        TextArea area = new TextArea(text.toString());
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefRowCount(10);
        alert.getDialogPane().setContent(area);
        Optional<ButtonType> answer = alert.showAndWait();
        return answer.isPresent() && answer.get() == ButtonType.OK;
    }

    private static void open(Window owner, WalletTransaction walletTransaction, String label) {
        PSBT psbt = walletTransaction.createPSBT();
        EventManager.get().post(new ViewPSBTEvent(owner, label, null, psbt));
    }

    private static String shortId(String id) {
        return id.length() > 12 ? id.substring(0, 8) + "…" + id.substring(id.length() - 3) : id;
    }
}
