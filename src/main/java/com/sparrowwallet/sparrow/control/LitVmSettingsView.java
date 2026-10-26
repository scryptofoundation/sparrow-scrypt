package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.evm.LitVmNetwork;
import com.sparrowwallet.drongo.wallet.LitVmLink;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/** The Settings view: where the linked account comes from, the networks it is used on, and unlinking it. */
class LitVmSettingsView extends VBox {
    LitVmSettingsView(LitVmContext context) {
        super(14);
        setPadding(new Insets(20, 25, 20, 25));
        LitVmLink link = context.link();

        Label title = new Label("Settings");
        title.setStyle("-fx-font-size: 1.2em;");
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.add(new Label("Address"), 0, 0);
        grid.add(new Label(link.getAddress().toString()), 1, 0);
        grid.add(new Label("Account"), 0, 1);
        grid.add(new Label(link.getSource().getDisplayName() + (link.getDerivationPath() == null ? "" : " at " + link.getDerivationPath())), 1, 1);
        grid.add(new Label("Wallet network"), 0, 2);
        LitVmNetwork wallet = LitVmNetwork.LITEFORGE_TESTNET;
        grid.add(new Label(wallet.getDisplayName() + " (chain " + wallet.getChainId() + ", " + wallet.getSymbol() + ")"), 1, 2);
        grid.add(new Label("Wallet RPC"), 0, 3);
        grid.add(new Label(wallet.getRpcUrl()), 1, 3);

        Label unlinkNote = new Label("Unlinking removes the LitVM tab from this wallet. Coins and tokens stay on the address. "
                + (link.isSeparateAccount() ? "This is a separate account: its seed words or key are removed from this wallet file, so keep a copy first."
                : "The account comes from this wallet's seed and can be linked again at any time."));
        unlinkNote.setWrapText(true);
        unlinkNote.setMaxWidth(620);
        Button unlink = new Button("Unlink LitVM...");
        unlink.setOnAction(e -> LitVmLinkDialog.unlink(context.masterWallet(), context.window()));

        getChildren().addAll(title, grid, unlinkNote, unlink);
    }
}
