package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.KeyDerivation;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.crypto.ChildNumber;
import com.sparrowwallet.drongo.protocol.ScriptType;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A dropdown of named derivation paths in front of a derivation text field, so a user picks "Litecoin Native
 * SegWit, account 0" instead of typing m/84'/2'/0'.
 *
 * Presets are only paths that are valid for this wallet's coin, network and script type: the same defaults
 * the rest of the app uses, at accounts 0 to 4. Paths belonging to another script type or network are left
 * out because the keystore screen rejects them anyway. "Custom..." keeps the text field for anything else.
 *
 * LitVM is deliberately not a preset. It is an Ethereum-style account at m/44'/60'/0'/0/0 that sits beside
 * the Litecoin keystore, not a derivation for it; choosing it here would make a Litecoin wallet derive its
 * Litecoin addresses at the Ethereum path. The note under the dropdown points to where LitVM lives instead.
 */
public class DerivationPresetBox extends VBox {
    private static final int ACCOUNTS = 5;

    public record Preset(String label, String path) {
        @Override
        public String toString() {
            return path == null ? label : label + "   " + path;
        }
    }

    private static final Preset CUSTOM = new Preset("Custom...", null);

    private final ComboBox<Preset> presets = new ComboBox<>();
    private final TextField derivationField;
    private boolean updating;

    public DerivationPresetBox(ScriptType scriptType, TextField derivationField) {
        this.derivationField = derivationField;
        setSpacing(6);

        List<Preset> items = buildPresets(scriptType);
        items.add(CUSTOM);
        presets.setItems(FXCollections.observableList(items));
        presets.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(presets, Priority.ALWAYS);

        presets.valueProperty().addListener((obs, oldValue, newValue) -> {
            if(updating || newValue == null) {
                return;
            }
            if(newValue == CUSTOM) {
                derivationField.setDisable(false);
                derivationField.requestFocus();
                derivationField.selectAll();
            } else {
                derivationField.setText(newValue.path());
            }
        });
        derivationField.textProperty().addListener((obs, oldValue, newValue) -> selectMatching(newValue));
        selectMatching(derivationField.getText());

        //Two clearly labelled rows: the chain's own path (which this dropdown changes) and the LitVM path (which it
        //never changes), so the two derivations are not mistaken for one another.
        Label coinLabel = new Label(Coin.get().toDisplayString() + " addresses:");
        coinLabel.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        presets.setMinWidth(160);
        presets.setPrefWidth(360);

        Label litVmLabel = new Label("LitVM address:");
        litVmLabel.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        Label litVmPath = new Label(com.sparrowwallet.drongo.evm.EvmAccount.DERIVATION_PREFIX + "0");
        Label litVmNote = new Label("automatic, from this same seed (not changed by the path above)");
        litVmNote.setStyle("-fx-opacity: 0.7;");
        litVmNote.setWrapText(true);
        litVmNote.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        HBox litVmValue = new HBox(10, litVmPath, litVmNote);
        litVmValue.setAlignment(Pos.CENTER_LEFT);

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.add(coinLabel, 0, 0);
        grid.add(presets, 1, 0);
        grid.add(litVmLabel, 0, 1);
        grid.add(litVmValue, 1, 1);
        javafx.scene.layout.GridPane.setHgrow(presets, Priority.ALWAYS);
        grid.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        getChildren().add(grid);
    }

    private void selectMatching(String path) {
        updating = true;
        try {
            Preset match = CUSTOM;
            if(path != null && KeyDerivation.isValid(path)) {
                List<ChildNumber> parsed = KeyDerivation.parsePath(path);
                for(Preset preset : presets.getItems()) {
                    if(preset.path() != null && KeyDerivation.parsePath(preset.path()).equals(parsed)) {
                        match = preset;
                        break;
                    }
                }
            }
            presets.setValue(match);
        } finally {
            updating = false;
        }
    }

    static List<Preset> buildPresets(ScriptType scriptType) {
        List<Preset> items = new ArrayList<>();
        String coinName = Coin.get().toDisplayString() +(Network.get() == Network.MAINNET ? "" : " " + Network.get().toDisplayString().toLowerCase(Locale.ROOT));
        for(int account = 0; account < ACCOUNTS; account++) {
            String path = KeyDerivation.writePath(scriptType.getDefaultDerivation(account));
            String label = coinName + " " + scriptType.getDescription() + (account == 0 ? " (recommended)" : ", account " + account);
            items.add(new Preset(label, path));
        }
        return items;
    }
}
