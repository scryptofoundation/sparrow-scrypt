package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.wallet.Status;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.event.WalletUtxoStatusChangedEvent;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5;
import com.sparrowwallet.sparrow.wallet.Entry;
import com.sparrowwallet.sparrow.wallet.NodeEntry;
import com.sparrowwallet.sparrow.wallet.StatusTransitions;
import com.sparrowwallet.sparrow.wallet.UtxoEntry;
import com.sparrowwallet.sparrow.wallet.collectibles.CollectiblesManager;
import com.sparrowwallet.sparrow.wallet.collectibles.HeldOutputs;
import javafx.geometry.Pos;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeTableCell;
import javafx.util.Duration;
import org.controlsfx.glyphfont.Glyph;

import java.util.Collections;

public class AddressCell extends TreeTableCell<Entry, UtxoEntry.AddressStatus> {
    public AddressCell() {
        super();
        setAlignment(Pos.CENTER_LEFT);
        setContentDisplay(ContentDisplay.RIGHT);
        getStyleClass().add("address-cell");
    }

    @Override
    protected void updateItem(UtxoEntry.AddressStatus addressStatus, boolean empty) {
        super.updateItem(addressStatus, empty);

        UtxoEntry utxoEntry = addressStatus == null ? null : addressStatus.getUtxoEntry();
        EntryCell.applyRowStyles(this, utxoEntry);

        if (empty) {
            setText(null);
            setGraphic(null);
        } else {
            if(utxoEntry != null) {
                Address address = addressStatus.getAddress();
                setText(address.toString());
                setContextMenu(new EntryCell.AddressContextMenu(address, utxoEntry.getOutputDescriptor(), new NodeEntry(utxoEntry.getWallet(), utxoEntry.getNode()), false, getTreeTableView()));
                Tooltip tooltip = new Tooltip();
                tooltip.setShowDelay(Duration.millis(250));
                tooltip.setText(getTooltipText(utxoEntry, addressStatus.isDuplicate(), addressStatus.isDustAttack()));
                setTooltip(tooltip);
                getStyleClass().add("address-cell");

                if(utxoEntry.getHashIndex().getStatus() == Status.PROTECTED) {
                    setGraphic(getProtectedGlyph());
                } else if(isHeld(utxoEntry)) {
                    setGraphic(getHeldGlyph());
                } else if(addressStatus.isDustAttack()) {
                    setGraphic(getDustAttackHyperlink(utxoEntry));
                } else if(addressStatus.isDuplicate()) {
                    setGraphic(getDuplicateGlyph());
                } else {
                    setGraphic(null);
                }
            }
        }
    }

    private String getTooltipText(UtxoEntry utxoEntry, boolean duplicate, boolean dustAttack) {
        return (utxoEntry.getNode().getWallet().isNested() ? utxoEntry.getNode().getWallet().getDisplayName() + " " : "" ) +
                utxoEntry.getNode().toString() + (duplicate ? " (Duplicate address)" : (dustAttack ? " (Possible dust attack)" : "")) +
                (utxoEntry.getHashIndex().getStatus() == Status.PROTECTED ? "\nCarries an inscription or runes. Only Collectibles can send it." : "") +
                (isHeld(utxoEntry) ? "\nMay carry an inscription or tokens. " + HeldOutputs.reason(utxoEntry.getWallet(), utxoEntry.getHashIndex()) +
                        ".\nHeld back from automatic coin selection; right-click to mark it as a plain coin." : "");
    }

    /** True when no inscriptions server checks the wallet and HeldOutputs holds this output. */
    private static boolean isHeld(UtxoEntry utxoEntry) {
        Wallet wallet = utxoEntry.getWallet();
        Wallet master = wallet.isNested() ? wallet.getMasterWallet() : wallet;
        return CollectiblesManager.applies(master) && !CollectiblesManager.checkedByServer(wallet) && HeldOutputs.isHeld(wallet, utxoEntry.getHashIndex());
    }

    public static Glyph getHeldGlyph() {
        Glyph heldGlyph = new Glyph(FontAwesome5.FONT_NAME, FontAwesome5.Glyph.QUESTION_CIRCLE);
        heldGlyph.getStyleClass().add("held-utxo");
        heldGlyph.setFontSize(12);
        return heldGlyph;
    }

    public static Glyph getProtectedGlyph() {
        Glyph protectedGlyph = new Glyph(FontAwesome5.FONT_NAME, FontAwesome5.Glyph.LOCK);
        protectedGlyph.getStyleClass().add("protected-utxo");
        protectedGlyph.setFontSize(12);
        return protectedGlyph;
    }

    public static Glyph getDuplicateGlyph() {
        Glyph duplicateGlyph = new Glyph(FontAwesome5.FONT_NAME, FontAwesome5.Glyph.EXCLAMATION_CIRCLE);
        duplicateGlyph.getStyleClass().add("duplicate-warning");
        duplicateGlyph.setFontSize(12);
        return duplicateGlyph;
    }

    public static Hyperlink getDustAttackHyperlink(UtxoEntry utxoEntry) {
        Glyph dustAttackGlyph = new Glyph(FontAwesome5.FONT_NAME, FontAwesome5.Glyph.EXCLAMATION_TRIANGLE);
        dustAttackGlyph.getStyleClass().add("dust-attack-warning");
        dustAttackGlyph.setFontSize(12);

        Hyperlink hyperlink = new Hyperlink(StatusTransitions.canFreeze(utxoEntry.getHashIndex().getStatus()) ? "Freeze?" : "", dustAttackGlyph);
        hyperlink.getStyleClass().add("freeze-dust-utxo");
        hyperlink.setOnAction(event -> {
            if(StatusTransitions.canFreeze(utxoEntry.getHashIndex().getStatus())) {
                hyperlink.setText("");
                utxoEntry.getHashIndex().setStatus(Status.FROZEN);
                EventManager.get().post(new WalletUtxoStatusChangedEvent(utxoEntry.getWallet(), Collections.singletonList(utxoEntry.getHashIndex())));
            }
        });

        return hyperlink;
    }
}
