package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.wallet.*;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.event.WalletImportEvent;
import com.sparrowwallet.sparrow.io.SeedWallets;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * File > Import Wallet entry that restores a wallet from one coin wallet app's recovery phrase, applying that app's
 * derivation and built-in passphrase from {@link SeedWallets}. One pane per app, like {@link CakeWalletImportPane}.
 */
public class SeedWalletImportPane extends MnemonicKeystorePane {
    private static final Logger log = LoggerFactory.getLogger(SeedWalletImportPane.class);

    private final SeedWallets.SeedWallet seedWallet;
    private Button nextButton;

    public SeedWalletImportPane(SeedWallets.SeedWallet seedWallet) {
        super(seedWallet.name(), seedWallet.coins().stream().map(Coin::toDisplayString).collect(Collectors.joining(", ")) + " seed import",
                "Restore a " + seedWallet.name() + " wallet from its recovery phrase.", WalletModel.SEED);
        this.seedWallet = seedWallet;
        defaultWordSizeProperty.set(12);
    }

    @Override
    protected void enterMnemonic(int numWords) {
        super.enterMnemonic(numWords);
        setDescription("Enter the " + seedWallet.name() + " recovery phrase");
    }

    @Override
    protected List<Node> createRightButtons() {
        nextButton = new Button("Next");
        nextButton.setDisable(true);
        nextButton.setDefaultButton(true);
        nextButton.setOnAction(event -> {
            setContent(getOptionsEntry());
            setExpanded(true);
        });

        return List.of(nextButton);
    }

    @Override
    protected void onWordChange(boolean empty, boolean validWords, boolean validChecksum) {
        boolean valid = !empty && validWords && SeedWallets.isValidPhrase(wordEntriesProperty.get());
        nextButton.setDisable(!valid);
        validLabel.setVisible(valid);
        invalidLabel.setVisible(!valid && !empty);
    }

    private Node getOptionsEntry() {
        List<Coin> coins = seedWallet.coins().stream().filter(coin -> coin.supports(com.sparrowwallet.drongo.Network.get())).toList();
        ComboBox<Coin> coinCombo = new ComboBox<>(FXCollections.observableArrayList(coins));
        coinCombo.setValue(coins.contains(Coin.get()) ? Coin.get() : coins.getFirst());
        coinCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Coin coin) {
                return coin == null ? "" : coin.toDisplayString();
            }

            @Override
            public Coin fromString(String string) {
                return null;
            }
        });
        Label coinLabel = new Label("Coin:");
        coinLabel.managedProperty().bind(coinLabel.visibleProperty());
        coinCombo.managedProperty().bind(coinCombo.visibleProperty());
        coinLabel.setVisible(coins.size() > 1);
        coinCombo.setVisible(coins.size() > 1);

        HelpLabel helpLabel = new HelpLabel();
        helpLabel.setHelpText(seedWallet.help());

        HBox optionsBox = new HBox(10, coinLabel, coinCombo, helpLabel);
        optionsBox.setAlignment(Pos.CENTER_LEFT);
        coinLabel.setTranslateY(1);

        Region region = new Region();
        HBox.setHgrow(region, Priority.SOMETIMES);

        Button importButton = new Button("Import");
        importButton.setDefaultButton(true);
        importButton.setOnAction(event -> {
            Coin coin = coinCombo.getValue();
            if(seedWallet.accountFor(coin).hasHiddenPassphrase() && !passphraseProperty.get().isEmpty()) {
                setError("Import Error", seedWallet.name() + " adds its own passphrase and does not take another one. Clear the passphrase and import again.");
                return;
            }

            showHideLink.setVisible(true);
            setExpanded(false);
            try {
                Wallet wallet = SeedWallets.createWallet(seedWallet, coin, wordEntriesProperty.get(), passphraseProperty.get());
                EventManager.get().post(new WalletImportEvent(wallet));
            } catch(Exception e) {
                log.error("Error importing " + seedWallet.name() + " seed", e);
                String errorMessage = e.getMessage();
                if(e.getCause() != null && e.getCause().getMessage() != null && !e.getCause().getMessage().isEmpty()) {
                    errorMessage = e.getCause().getMessage();
                }
                setError("Import Error", errorMessage);
            }
        });

        HBox contentBox = new HBox(20, optionsBox, region, importButton);
        contentBox.setAlignment(Pos.CENTER_RIGHT);
        contentBox.setPadding(new Insets(10, 30, 10, 30));
        contentBox.setPrefHeight(60);

        return new VBox(contentBox);
    }
}
