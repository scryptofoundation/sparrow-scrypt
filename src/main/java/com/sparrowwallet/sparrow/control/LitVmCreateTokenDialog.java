package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.evm.*;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/**
 * Choosing an ERC-20 to deploy: name, symbol, initial supply, how supply can grow, and OpenZeppelin's optional
 * features. The token always has 18 decimals. What is chosen is checked as it is typed, against the same rules the
 * deployment uses, and a name or symbol that imitates a known token is refused.
 */
class LitVmCreateTokenDialog extends Dialog<LitVmCreateTokenDialog.Request> {
    static final int DECIMALS = 18;

    record Request(Erc20Templates.Options options, Erc20Templates.Params params, boolean publishSource) {}

    private final TextField name = new TextField();
    private final TextField symbol = new TextField();
    private final TextField supply = new TextField("1000000");
    private final RadioButton fixed = new RadioButton("Fixed: everything is minted now, and no more can ever be");
    private final RadioButton mintable = new RadioButton("Mintable: you can mint more at any time");
    private final RadioButton capped = new RadioButton("Mintable up to a cap:");
    private final TextField cap = new TextField();
    private final CheckBox burnable = new CheckBox("Burnable: holders can destroy their own tokens");
    private final CheckBox pausable = new CheckBox("Pausable: you can stop and restart all transfers");
    private final CheckBox permit = new CheckBox("Permit: approvals by signature, without gas (EIP-2612)");
    private final CheckBox votes = new CheckBox("Votes: delegation and voting power for governance");
    private final CheckBox flashMint = new CheckBox("Flash minting: tokens can be borrowed within one transaction (ERC-3156)");
    private final CheckBox erc1363 = new CheckBox("ERC-1363: transfers and approvals that call the receiving contract");
    private final CheckBox publish = new CheckBox("Publish the verified source on the explorer");
    private final Label error = new Label();

    LitVmCreateTokenDialog(Window owner, LitVmNetwork network, EvmAddress me) {
        initOwner(owner);
        setTitle("Create Token");
        setHeaderText("Create an ERC-20 token on " + network.getDisplayName());

        name.setPromptText("My Token");
        symbol.setPromptText("MYT");
        cap.setPromptText("10000000");
        ToggleGroup supplyGroup = new ToggleGroup();
        for(RadioButton r : List.of(fixed, mintable, capped)) {
            r.setToggleGroup(supplyGroup);
        }
        fixed.setSelected(true);
        cap.disableProperty().bind(capped.selectedProperty().not());
        publish.setSelected(true);
        error.setWrapText(true);
        error.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        error.setMaxWidth(540);
        error.setStyle("-fx-text-fill: -fx-accent;");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.addRow(0, new Label("Name:"), name);
        grid.addRow(1, new Label("Symbol:"), symbol);
        grid.addRow(2, new Label("Initial supply:"), supply);

        VBox supplyBox = new VBox(6, fixed, mintable, new javafx.scene.layout.HBox(8, capped, cap));
        VBox featureBox = new VBox(6, burnable, pausable, permit, votes, flashMint, erc1363);
        Label owns = new Label("Minting and pausing belong to the token's owner, which is your LitVM account " + LitVmContext.shortAddress(me)
                + ". Ownership can be handed on or given up later. Every token is standard OpenZeppelin Contracts 5.6.1 code with "
                + DECIMALS + " decimals.");
        owns.setWrapText(true);
        owns.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        VBox content = new VBox(12, grid, new Label("Supply"), supplyBox, new Label("Features"), featureBox, publish, owns, error);
        content.setPadding(new Insets(10, 10, 0, 10));
        content.setPrefWidth(560);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Button ok = (Button)getDialogPane().lookupButton(ButtonType.OK);
        ok.setText("Continue");

        Runnable check = () -> {
            Optional<String> problem = problem(me);
            error.setText(problem.orElse(""));
            ok.setDisable(problem.isPresent());
        };
        for(TextField f : List.of(name, symbol, supply, cap)) {
            f.textProperty().addListener((o, a, b) -> check.run());
        }
        for(ButtonBase b : List.of(fixed, mintable, capped, burnable, pausable, permit, votes, flashMint, erc1363)) {
            b.setOnAction(e -> check.run());
        }
        check.run();
        setResultConverter(button -> button == ButtonType.OK ? request(me) : null);
    }

    Erc20Templates.Options options() {
        Erc20Templates.Supply s = fixed.isSelected() ? Erc20Templates.Supply.FIXED : mintable.isSelected() ? Erc20Templates.Supply.MINTABLE : Erc20Templates.Supply.CAPPED;
        return new Erc20Templates.Options(s, burnable.isSelected(), pausable.isSelected(), permit.isSelected(), votes.isSelected(), flashMint.isSelected(), erc1363.isSelected());
    }

    private Request request(EvmAddress me) {
        Erc20Templates.Options options = options();
        BigInteger capUnits = options.supply() == Erc20Templates.Supply.CAPPED ? units(cap.getText()) : null;
        return new Request(options, new Erc20Templates.Params(name.getText(), symbol.getText(), me, units(supply.getText()), me, capUnits), publish.isSelected());
    }

    private Optional<String> problem(EvmAddress me) {
        try {
            Request request = request(me);
            request.params().check(request.options());
            Optional<String> imitation = KnownTokens.imitates(symbol.getText(), name.getText());
            if(imitation.isPresent()) {
                return Optional.of("Not allowed: " + imitation.get() + " is the name of a well-known coin or token, and a token by that name could be used to deceive people.");
            }
            return Optional.empty();
        } catch(IllegalArgumentException e) {
            return Optional.of(e.getMessage());
        }
    }

    /** A whole-token amount in base units; the pattern bounds the input before it is converted. */
    static BigInteger units(String text) {
        String t = text == null ? "" : text.trim().replace(",", "");
        if(!t.matches("\\d{1,60}(\\.\\d{1," + DECIMALS + "})?")) {
            throw new IllegalArgumentException(t.isEmpty() ? "Enter an amount" : "Not an amount: " + t);
        }
        return new BigDecimal(t).movePointRight(DECIMALS).toBigIntegerExact();
    }
}
