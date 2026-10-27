package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.evm.*;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.math.BigInteger;
import java.util.Optional;

/**
 * What a tracked token is and what this account can do with it: mint, burn, pause or unpause, hand on or give up
 * ownership, and publish the source of a token created here. Only the actions the token has, and that this account
 * may take, are offered; picking one closes the dialog and the Tokens view prepares the transaction.
 */
class LitVmManageTokenDialog extends Dialog<LitVmManageTokenDialog.Action> {
    enum Action { MINT, BURN, PAUSE, UNPAUSE, TRANSFER_OWNERSHIP, RENOUNCE_OWNERSHIP, PUBLISH_SOURCE, OPEN_EXPLORER }

    /** What the dialog shows, read off the FX thread. createdKey is the template key when the token was created here. */
    record Info(LitVmNetwork network, Erc20Token token, BigInteger totalSupply, BigInteger balance, Erc20Admin.Capabilities capabilities,
                String createdKey, boolean verified, Optional<KnownTokens.Known> official, Optional<String> lookalike) {}

    LitVmManageTokenDialog(Window owner, Info info, EvmAddress me) {
        initOwner(owner);
        setTitle("Manage Token");
        setHeaderText(info.token().symbol() + " on " + info.network().getDisplayName());
        Erc20Token token = info.token();
        Erc20Admin.Capabilities caps = info.capabilities();

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(6);
        int row = 0;
        grid.addRow(row++, new Label("Name:"), new Label(token.name()));
        grid.addRow(row++, new Label("Contract:"), selectable(token.contract().toString()));
        grid.addRow(row++, new Label("Total supply:"), new Label(token.format(info.totalSupply()) + " " + token.symbol()));
        grid.addRow(row++, new Label("Your balance:"), new Label(token.format(info.balance()) + " " + token.symbol()));
        caps.cap().ifPresent(c -> grid.addRow(grid.getRowCount(), new Label("Cap:"), new Label(token.format(c) + " " + token.symbol())));
        grid.addRow(grid.getRowCount(), new Label("Owner:"), new Label(caps.owner().map(o -> o + (o.equals(me) ? " (you)" : "")).orElse("none")));
        caps.paused().ifPresent(p -> grid.addRow(grid.getRowCount(), new Label("Transfers:"), new Label(p ? "paused" : "running")));
        String origin = info.official().isPresent() ? "Official contract" : info.createdKey() != null ? "Created here (" + describe(info.createdKey()) + ")" : "Added by contract address";
        grid.addRow(grid.getRowCount(), new Label("Source:"), wrap(origin));
        grid.addRow(grid.getRowCount(), new Label("Explorer:"), new Label(info.verified() ? "source verified" : "source not verified"));

        VBox content = new VBox(12, grid);
        info.lookalike().ifPresent(text -> {
            Label warning = wrap("Warning: " + text);
            warning.setStyle("-fx-text-fill: #c0392b; -fx-font-weight: bold;");
            content.getChildren().add(warning);
        });

        FlowPane actions = new FlowPane(8, 8);
        boolean owned = caps.ownedBy(me);
        if(owned && caps.mintable()) {
            actions.getChildren().add(button("Mint...", Action.MINT));
        }
        if(caps.burnable() && info.balance().signum() > 0) {
            actions.getChildren().add(button("Burn...", Action.BURN));
        }
        if(owned && caps.paused().isPresent()) {
            actions.getChildren().add(caps.paused().get() ? button("Unpause", Action.UNPAUSE) : button("Pause", Action.PAUSE));
        }
        if(owned) {
            actions.getChildren().add(button("Transfer Ownership...", Action.TRANSFER_OWNERSHIP));
            actions.getChildren().add(button("Renounce Ownership...", Action.RENOUNCE_OWNERSHIP));
        }
        if(info.createdKey() != null && !info.verified()) {
            actions.getChildren().add(button("Publish Source", Action.PUBLISH_SOURCE));
        }
        actions.getChildren().add(button("Open in Explorer", Action.OPEN_EXPLORER));
        if(!owned && caps.owner().isPresent()) {
            content.getChildren().add(wrap("Only the owner can mint, pause or change ownership."));
        }
        content.getChildren().add(actions);
        content.setPadding(new Insets(10, 10, 0, 10));
        content.setPrefWidth(560);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        setResultConverter(b -> null);
    }

    private Button button(String text, Action action) {
        Button button = new Button(text);
        button.setOnAction(e -> {
            setResult(action);
            close();
        });
        return button;
    }

    private static Label wrap(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(520);
        return label;
    }

    private static TextField selectable(String text) {
        TextField field = new TextField(text);
        field.setEditable(false);
        field.setPrefColumnCount(30);
        return field;
    }

    /** A template key in words, such as "mintable up to a cap, burnable, pausable". */
    static String describe(String key) {
        Erc20Templates.Options o = Erc20Templates.Options.fromKey(key);
        StringBuilder sb = new StringBuilder(switch(o.supply()) {
            case FIXED -> "fixed supply";
            case MINTABLE -> "mintable";
            case CAPPED -> "mintable up to a cap";
        });
        if(o.burnable()) sb.append(", burnable");
        if(o.pausable()) sb.append(", pausable");
        if(o.permit()) sb.append(", permit");
        if(o.votes()) sb.append(", votes");
        if(o.flashMint()) sb.append(", flash minting");
        if(o.erc1363()) sb.append(", ERC-1363");
        return sb.toString();
    }
}
