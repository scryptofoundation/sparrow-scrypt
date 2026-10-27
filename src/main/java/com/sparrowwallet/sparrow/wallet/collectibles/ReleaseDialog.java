package com.sparrowwallet.sparrow.wallet.collectibles;

import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.Status;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.event.WalletUtxoStatusChangedEvent;
import com.sparrowwallet.sparrow.net.ord.OrdOutput;
import com.sparrowwallet.sparrow.net.ord.RuneBalance;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Lets the user deliberately spend a protected output as plain LTC, after naming everything it would lose. */
public final class ReleaseDialog {
    private ReleaseDialog() {
    }

    public static boolean confirm(OrdOutput carried) {
        List<String> lost = new ArrayList<>();
        if(carried != null) {
            for(String id : carried.inscriptions()) {
                lost.add("Inscription " + id);
            }
            for(Map.Entry<String, RuneBalance> rune : carried.runes().entrySet()) {
                lost.add(rune.getValue().format() + " " + rune.getKey());
            }
            if(carried.unlisted()) {
                lost.add("Other inscriptions or runes the server reported");
            }
        }
        if(lost.isEmpty()) {
            lost.add("The inscriptions or runes it carries");
        }
        ButtonType release = new ButtonType("Spend as plain LTC", ButtonBar.ButtonData.OK_DONE);
        //By ord's rules the inscription follows its sat into an output of the spending transaction: often your own
        //change, which is then protected again, or the recipient's output, where it is gone for good
        Optional<ButtonType> result = AppServices.showWarningDialog("Spend as plain LTC?", "Spending this output as plain LTC moves or loses:\n" + String.join("\n", lost) +
                "\n\nIf it lands in your own change it is protected again; if it lands in a payment, it belongs to the recipient. This cannot be undone.",
                ButtonType.CANCEL, release);
        return result.isPresent() && result.get() == release;
    }

    /** Asks, then marks the output RELEASED so coin selection may use it and later checks do not protect it again. */
    public static boolean release(Wallet wallet, BlockTransactionHashIndex utxo, OrdOutput carried) {
        if(utxo.getStatus() != Status.PROTECTED || !confirm(carried)) {
            return false;
        }
        utxo.setStatus(Status.RELEASED);
        EventManager.get().post(new WalletUtxoStatusChangedEvent(wallet, List.of(utxo)));
        return true;
    }
}
