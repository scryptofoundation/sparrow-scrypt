package com.sparrowwallet.sparrow.wallet.collectibles;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.ordinals.CollectibleTransactions;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletTransaction;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.event.ViewPSBTEvent;
import com.sparrowwallet.sparrow.net.ord.OrdClient;
import com.sparrowwallet.sparrow.net.ord.OrdRune;
import com.sparrowwallet.sparrow.net.ord.RuneBalance;
import com.sparrowwallet.sparrow.wallet.WalletForm;
import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.stage.Window;

import java.util.Optional;

/**
 * Mint Rune: one mint of a rune's open terms, paid from the open wallet, the minted amount landing in the seed's
 * taproot wallet. The mint is an ordinary fixed-order transaction, so it opens in a transaction tab and is signed
 * there like any send.
 */
public final class MintRuneDialog {
    private MintRuneDialog() {}

    public static void start(Window owner, WalletForm walletForm, String presetName) {
        Wallet funding = MintFlow.fundingWallet(walletForm.getWallet());
        if(MintFlow.needsPegOut(funding)) {
            TaprootWallets.ensure(walletForm, owner, taproot -> MintFlow.startFromMweb(owner, funding, taproot,
                    MintFlow.pegOutForRuneMint(taproot, AppServices.getDefaultFeeRate()), "minting a rune"));
            return;
        }
        String url = CollectiblesManager.serverUrl(funding);
        if(url == null) {
            AppServices.showErrorDialog("Cannot mint", "Inscription checks are off. Turn them on in Preferences > Server > Inscriptions.");
            return;
        }
        TaprootWallets.ensure(walletForm, owner, taproot -> {
            TextInputDialog nameDialog = new TextInputDialog(presetName == null ? "" : presetName);
            nameDialog.initOwner(owner);
            nameDialog.setTitle("Mint Rune");
            nameDialog.setHeaderText("Mint a rune with open mint terms into " + taproot.getFullDisplayName());
            nameDialog.setContentText("Rune name:");
            Optional<String> name = nameDialog.showAndWait().map(String::trim).map(text -> text.replace('.', '•'));
            if(name.isEmpty() || name.get().isEmpty()) {
                return;
            }
            Task<OrdRune> lookup = new Task<>() {
                @Override
                protected OrdRune call() throws Exception {
                    Coin previousCoin = Coin.beginScope(funding.getCoin());
                    try {
                        return OrdClient.forApp(url).rune(name.get());
                    } finally {
                        Coin.endScope(previousCoin);
                    }
                }
            };
            lookup.setOnSucceeded(event -> mint(owner, funding, taproot, lookup.getValue()));
            lookup.setOnFailed(event -> AppServices.showErrorDialog("Cannot mint", lookup.getException() instanceof OrdClient.HttpStatusException status && status.getStatus() == 404
                    ? "No rune " + name.get() + " is etched." : "Could not look up " + name.get() + ": " + lookup.getException().getMessage()));
            Thread thread = new Thread(lookup, "Rune lookup");
            thread.setDaemon(true);
            thread.start();
        });
    }

    private static void mint(Window owner, Wallet funding, Wallet taproot, OrdRune rune) {
        Optional<String> problem = RuneMinting.mintProblem(rune);
        if(problem.isPresent()) {
            AppServices.showErrorDialog("Cannot mint", problem.get());
            return;
        }
        try {
            Wallet master = funding.isNested() ? funding.getMasterWallet() : funding;
            CollectiblesManager.Snapshot snapshot = CollectiblesManager.get().getSnapshot(master);
            if(snapshot == null || !snapshot.enabled()) {
                throw new IllegalStateException("This wallet's outputs have not been checked for inscriptions yet, so no output can safely pay for the mint.");
            }
            Address destination = taproot.getFreshNode(KeyPurpose.RECEIVE).getAddress();
            double feeRate = AppServices.getDefaultFeeRate();
            WalletTransaction walletTransaction = CollectibleTransactions.mintRune(funding, rune.id(), destination,
                    CollectibleSend.feeCandidates(funding, snapshot), feeRate, AppServices.getMinimumRelayFeeRate());
            String amount = new RuneBalance(rune.terms().amount(), rune.divisibility(), rune.symbol()).format();
            String text = "Mint " + amount + " " + rune.spacedName() + " (" + rune.remainingMints() + " of " + rune.terms().cap() + " mints left).\n\n"
                    + "To: " + destination + " in " + taproot.getFullDisplayName() + ", with " + CollectibleTransactions.POSTAGE + " litoshis of postage.\n"
                    + "Fee: " + walletTransaction.getFee() + " litoshis at " + String.format("%.2f", feeRate) + " lit/vB, paid from outputs checked to carry nothing.\n\n"
                    + "If the last mints are taken before this one confirms, it mints nothing and only the fee and postage are spent.";
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.CANCEL, ButtonType.OK);
            alert.initOwner(owner);
            alert.setTitle("Confirm");
            alert.setHeaderText("Mint rune?");
            alert.getDialogPane().setMinWidth(560);
            Optional<ButtonType> answer = alert.showAndWait();
            if(answer.isPresent() && answer.get() == ButtonType.OK) {
                PSBT psbt = walletTransaction.createPSBT();
                EventManager.get().post(new ViewPSBTEvent(owner, "Mint " + amount + " " + rune.spacedName(), null, psbt));
            }
        } catch(Exception e) {
            AppServices.showErrorDialog("Cannot mint", e.getMessage());
        }
    }
}
