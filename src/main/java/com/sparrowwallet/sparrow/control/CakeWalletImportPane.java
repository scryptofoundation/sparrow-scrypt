package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.wallet.*;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.event.WalletImportEvent;
import com.sparrowwallet.sparrow.io.CakeWallet;
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

/**
 * File > Import Wallet entry that restores a Cake Wallet Litecoin or Dogecoin wallet from its recovery phrase.
 * The seed type is detected and the coin chosen here; {@link CakeWallet} applies Cake Wallet's own derivations.
 */
public class CakeWalletImportPane extends MnemonicKeystorePane {
    private static final Logger log = LoggerFactory.getLogger(CakeWalletImportPane.class);

    private Button nextButton;
    private DeterministicSeed.Type detectedType;

    public CakeWalletImportPane() {
        super("Cake Wallet", "Seed import", "Restore a Cake Wallet Litecoin (including MWEB) or Dogecoin wallet from its recovery phrase. " +
                "In Cake Wallet, find it under Settings > Recovery & Keys.", WalletModel.CUPCAKE);
        //Cake Wallet defaults to 12 words for both coins
        defaultWordSizeProperty.set(12);
    }

    @Override
    protected void enterMnemonic(int numWords) {
        super.enterMnemonic(numWords);
        setDescription("Enter the Cake Wallet recovery phrase");
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
        detectedType = null;
        if(!empty && validWords) {
            detectedType = CakeWallet.detectSeedType(wordEntriesProperty.get());
        }

        boolean valid = detectedType != null;
        validLabel.setText(detectedType == DeterministicSeed.Type.ELECTRUM ? "Valid Electrum seed" : "Valid checksum");
        nextButton.setDisable(!valid);
        validLabel.setVisible(valid);
        invalidLabel.setVisible(!valid && !empty);
    }

    private Node getOptionsEntry() {
        Label coinLabel = new Label("Coin:");
        List<Coin> coins = CakeWallet.COINS.stream().filter(coin -> coin.supports(com.sparrowwallet.drongo.Network.get())).toList();
        ComboBox<Coin> coinCombo = new ComboBox<>(FXCollections.observableArrayList(coins));
        coinCombo.setValue(coins.contains(Coin.get()) ? Coin.get() : Coin.LITECOIN);
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

        CheckBox mwebCheck = new CheckBox("Include MWEB (Private) balance");
        mwebCheck.setSelected(true);
        mwebCheck.managedProperty().bind(mwebCheck.visibleProperty());
        mwebCheck.visibleProperty().bind(coinCombo.valueProperty().isEqualTo(Coin.LITECOIN));

        HelpLabel helpLabel = new HelpLabel();
        helpLabel.setHelpText("The seed type is detected automatically (" + (detectedType == DeterministicSeed.Type.ELECTRUM ? "Electrum" : "BIP39") + " here).\n" +
                "Litecoin restores Native Segwit (ltc1) addresses, plus MWEB (ltcmweb1) as a Private sub-wallet.\n" +
                "Dogecoin restores Legacy (D) addresses.\n" +
                "Extra Cake Wallet accounts can be added afterwards from the wallet's Settings.");

        HBox optionsBox = new HBox(10, coinLabel, coinCombo, mwebCheck, helpLabel);
        optionsBox.setAlignment(Pos.CENTER_LEFT);
        coinLabel.setTranslateY(1);

        Region region = new Region();
        HBox.setHgrow(region, Priority.SOMETIMES);

        Button importButton = new Button("Import");
        importButton.setDefaultButton(true);
        importButton.setOnAction(event -> {
            Coin coin = coinCombo.getValue();
            if(coin == Coin.DOGECOIN && detectedType == DeterministicSeed.Type.ELECTRUM) {
                setError("Import Error", "Cake Wallet Dogecoin wallets use BIP39 seeds. This phrase is an Electrum seed.");
                return;
            }

            showHideLink.setVisible(true);
            setExpanded(false);
            try {
                Wallet wallet = CakeWallet.createWallet(coin, detectedType, wordEntriesProperty.get(), passphraseProperty.get(), coin == Coin.LITECOIN && mwebCheck.isSelected());
                EventManager.get().post(new WalletImportEvent(wallet));
            } catch(Exception e) {
                log.error("Error importing Cake Wallet seed", e);
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
