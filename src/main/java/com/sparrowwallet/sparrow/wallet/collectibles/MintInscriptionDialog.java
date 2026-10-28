package com.sparrowwallet.sparrow.wallet.collectibles;

import com.sparrowwallet.drongo.ordinals.Inscription;
import com.sparrowwallet.drongo.wallet.PendingMint;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.control.FeeRangeSlider;
import com.sparrowwallet.sparrow.wallet.WalletForm;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Optional;

/**
 * Mint Inscription: text or a file, its content type, the fee rate, and whether to sign the reveal now or later.
 * The commit is paid from the open wallet; the inscription lands in the seed's taproot wallet (see {@link MintFlow}).
 */
public final class MintInscriptionDialog {
    private MintInscriptionDialog() {}

    record Content(String contentType, byte[] body) {}

    public static void start(Window owner, WalletForm walletForm) {
        Wallet open = walletForm.getWallet();
        Optional<String> refusal = MintFlow.refusal(MintFlow.fundingWallet(open));
        if(refusal.isPresent()) {
            AppServices.showErrorDialog("Cannot mint", refusal.get());
            return;
        }
        TaprootWallets.ensure(walletForm, owner, taproot -> ask(owner).ifPresent(request ->
                MintFlow.mint(owner, MintFlow.fundingWallet(open), taproot, request, message -> showDone(owner, message))));
    }

    public static void showDone(Window owner, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.initOwner(owner);
        alert.setTitle("Mint");
        alert.setHeaderText(null);
        alert.getDialogPane().setMinWidth(520);
        alert.showAndWait();
    }

    private static Optional<MintFlow.Request> ask(Window owner) {
        Dialog<MintFlow.Request> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Mint Inscription");
        dialog.setHeaderText("Mint an inscription into this seed's taproot wallet");

        ToggleGroup kind = new ToggleGroup();
        RadioButton textChoice = new RadioButton("Text");
        RadioButton fileChoice = new RadioButton("File");
        textChoice.setToggleGroup(kind);
        fileChoice.setToggleGroup(kind);
        textChoice.setSelected(true);
        TextArea text = new TextArea();
        text.setPrefRowCount(5);
        text.setWrapText(true);
        Button chooseFile = new Button("Choose file...");
        Label fileLabel = new Label("No file chosen");
        TextField contentType = new TextField(Inscription.TEXT);
        contentType.setPrefColumnCount(28);
        Label size = new Label();
        FeeRangeSlider feeRange = new FeeRangeSlider();
        feeRange.setFeeRate(AppServices.getDefaultFeeRate());
        Label feeLabel = new Label(String.format("%.2f lit/vB", feeRange.getFeeRate()));
        feeRange.valueProperty().addListener((o, a, b) -> feeLabel.setText(String.format("%.2f lit/vB", feeRange.getFeeRate())));
        ToggleGroup signing = new ToggleGroup();
        RadioButton signNow = new RadioButton("Sign now: broadcast the commit and the reveal together");
        RadioButton signLater = new RadioButton("Sign later: broadcast only the commit, finish from Pending mints");
        signNow.setToggleGroup(signing);
        signLater.setToggleGroup(signing);
        signNow.setSelected(true);

        byte[][] fileBody = new byte[1][];
        chooseFile.setOnAction(event -> chooseFile(owner).ifPresent(content -> {
            fileBody[0] = content.body();
            contentType.setText(content.contentType());
            fileLabel.setText(content.body().length + " bytes");
        }));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.addRow(0, new Label("Content:"), new HBox(10, textChoice, fileChoice));
        grid.addRow(1, new Label(""), text);
        grid.addRow(2, new Label(""), new HBox(10, chooseFile, fileLabel));
        grid.addRow(3, new Label("Content type:"), contentType);
        grid.addRow(4, new Label("Size:"), size);
        grid.addRow(5, new Label("Fee rate:"), new VBox(4, feeRange, feeLabel));
        grid.addRow(6, new Label("Reveal:"), new VBox(6, signNow, signLater));
        VBox content = new VBox(10, grid);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        Button ok = (Button)dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.setText("Continue");

        Runnable check = () -> {
            boolean isText = textChoice.isSelected();
            text.setDisable(!isText);
            chooseFile.setDisable(isText);
            if(isText) {
                contentType.setText(Inscription.TEXT);
            }
            byte[] body = isText ? text.getText().getBytes(StandardCharsets.UTF_8) : fileBody[0];
            String type = contentType.getText().trim();
            int max = Inscription.maxBodyBytes(type.isEmpty() ? Inscription.TEXT : type);
            int length = body == null ? 0 : body.length;
            size.setText(String.format("%,d bytes (at most %,d)", length, max));
            size.setStyle(length > max ? "-fx-text-fill: -fx-accent;" : "");
            ok.setDisable(length == 0 || length > max || type.isEmpty());
        };
        kind.selectedToggleProperty().addListener((o, a, b) -> check.run());
        text.textProperty().addListener((o, a, b) -> check.run());
        contentType.textProperty().addListener((o, a, b) -> check.run());
        fileLabel.textProperty().addListener((o, a, b) -> check.run());
        check.run();

        dialog.setResultConverter(button -> {
            if(button != ButtonType.OK) {
                return null;
            }
            byte[] body = textChoice.isSelected() ? text.getText().getBytes(StandardCharsets.UTF_8) : fileBody[0];
            return new MintFlow.Request(new Inscription(contentType.getText().trim(), body, null), null, feeRange.getFeeRate(), signNow.isSelected());
        });
        return dialog.showAndWait();
    }

    /** A file's bytes and content type, refusing one too large to inscribe before reading it. */
    static Optional<Content> chooseFile(Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose the file to inscribe");
        File file = chooser.showOpenDialog(owner);
        if(file == null) {
            return Optional.empty();
        }
        String contentType = Inscription.contentTypeFor(file.getName());
        int max = Inscription.maxBodyBytes(contentType);
        if(file.length() > max) {
            AppServices.showErrorDialog("File too large", String.format("%s is %,d bytes. At most %,d bytes fit in one inscription.", file.getName(), file.length(), max));
            return Optional.empty();
        }
        try {
            return Optional.of(new Content(contentType, Files.readAllBytes(file.toPath())));
        } catch(IOException e) {
            AppServices.showErrorDialog("Cannot read file", e.getMessage());
            return Optional.empty();
        }
    }

    /** Asks for the content a pending mint was started with: text for a text mint, else a file. */
    public static Optional<byte[]> askContent(Window owner, PendingMint mint) {
        if(!mint.getContentType().startsWith("text/plain")) {
            return chooseFile(owner).map(Content::body);
        }
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(owner);
        dialog.setTitle("Finish Mint");
        dialog.setHeaderText("Enter exactly the text this mint was started with");
        TextArea area = new TextArea();
        area.setPrefRowCount(5);
        area.setWrapText(true);
        dialog.getDialogPane().setContent(area);
        dialog.setResultConverter(button -> button == ButtonType.OK ? area.getText() : null);
        return dialog.showAndWait().map(value -> value.getBytes(StandardCharsets.UTF_8));
    }
}
