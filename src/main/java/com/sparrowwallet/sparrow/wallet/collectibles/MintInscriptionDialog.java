package com.sparrowwallet.sparrow.wallet.collectibles;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.address.InvalidAddressException;
import com.sparrowwallet.drongo.ordinals.DoginalsMint;
import com.sparrowwallet.drongo.ordinals.Inscription;
import com.sparrowwallet.drongo.ordinals.InscriptionMint;
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
import java.util.EnumSet;
import java.util.Optional;

/**
 * Mint Inscription: text or a file, its content type, the fee rate, and whether to sign the reveal now or later.
 * The commit is paid from the open wallet; the inscription lands in the seed's taproot wallet (see {@link MintFlow}).
 * On Dogecoin, Pepecoin and Junkcoin the mint is a Doginals chain (see {@link DoginalsFlow}): no taproot wallet and no
 * Sign later (each batch is signed when the user continues it), and the inscription lands on a chosen address.
 */
public final class MintInscriptionDialog {
    private MintInscriptionDialog() {}

    record Content(String contentType, byte[] body) {}

    /** What the dialog asks for; the destination is set only for a Doginals mint. */
    record Answer(String contentType, byte[] body, double feeRate, boolean signNow, Address destination) {
        MintFlow.Request request() {
            return new MintFlow.Request(new Inscription(contentType, body, null), null, feeRate, signNow);
        }
    }

    /** The largest body this coin's mint takes: a Doginals chain takes up to 2 MB, a reveal what fits its weight. */
    static long maxBody(Coin coin, String contentType) {
        return DoginalsFlow.usesDoginals(coin) ? DoginalsMint.MAX_BODY : Inscription.maxBodyBytes(contentType);
    }

    static String feeUnit(Coin coin) {
        return coin.getSubunit() + "/vB";
    }

    /** An entered destination, refused unless it is an address of the wallet's coin and network. */
    static Address destination(Wallet wallet, String entered) {
        Coin coin = wallet.getCoin();
        Coin previous = Coin.beginScope(coin);
        try {
            return Address.fromString(entered.trim());
        } catch(InvalidAddressException e) {
            throw new IllegalArgumentException("This is not a " + coin.getCapitalizedName() + " address on this network.");
        } finally {
            Coin.endScope(previous);
        }
    }

    public static void start(Window owner, WalletForm walletForm) {
        Wallet open = walletForm.getWallet();
        if(DoginalsFlow.usesDoginals(open.getCoin())) {
            Optional<String> refusal = DoginalsFlow.refusal(open, CollectiblesManager.get().getSnapshot(DoginalsFlow.pendingHolder(open)), Coin.get());
            if(refusal.isPresent()) {
                AppServices.showErrorDialog("Cannot mint", refusal.get());
                return;
            }
            ask(owner, open).ifPresent(answer -> DoginalsFlow.mint(owner, open, new DoginalsFlow.Request(answer.contentType(), answer.body(), answer.destination(),
                    answer.feeRate()), message -> showDone(owner, message)));
            return;
        }
        Optional<String> refusal = MintFlow.refusal(MintFlow.fundingWallet(open));
        if(refusal.isPresent()) {
            AppServices.showErrorDialog("Cannot mint", refusal.get());
            return;
        }
        TaprootWallets.ensure(walletForm, owner, taproot -> ask(owner, open).map(Answer::request).ifPresent(request -> {
            if(MintFlow.needsPegOut(open)) {
                try {
                    InscriptionMint.Plan plan = MintFlow.plan(MintFlow.revealNode(taproot), request.inscription(), null, request.feeRate());
                    MintFlow.startFromMweb(owner, open, taproot, MintFlow.pegOutForMint(taproot, plan, request.feeRate()), "minting an inscription");
                } catch(Exception e) {
                    AppServices.showErrorDialog("Cannot mint", e.getMessage());
                }
                return;
            }
            MintFlow.mint(owner, MintFlow.fundingWallet(open), taproot, request, message -> showDone(owner, message));
        }));
    }

    public static void showDone(Window owner, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.initOwner(owner);
        alert.setTitle("Mint");
        alert.setHeaderText(null);
        alert.getDialogPane().setMinWidth(520);
        alert.showAndWait();
    }

    private static Optional<Answer> ask(Window owner, Wallet open) {
        Coin coin = open.getCoin();
        boolean doginals = DoginalsFlow.usesDoginals(coin);
        Dialog<Answer> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Mint Inscription");
        dialog.setHeaderText(doginals ? "Mint a " + coin.getCapitalizedName() + " inscription (Doginals)" : "Mint an inscription into this seed's taproot wallet");

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
        Label feeLabel = new Label(String.format("%.2f %s", feeRange.getFeeRate(), feeUnit(coin)));
        feeRange.valueProperty().addListener((o, a, b) -> feeLabel.setText(String.format("%.2f %s", feeRange.getFeeRate(), feeUnit(coin))));
        ToggleGroup signing = new ToggleGroup();
        RadioButton signNow = new RadioButton("Sign now: broadcast the commit and the reveal together");
        RadioButton signLater = new RadioButton("Sign later: broadcast only the commit, finish from Pending mints");
        signNow.setToggleGroup(signing);
        signLater.setToggleGroup(signing);
        signNow.setSelected(true);
        ToggleGroup destinationChoice = new ToggleGroup();
        Address fresh = doginals ? freshDestination(open) : null;
        RadioButton toWallet = new RadioButton(doginals ? "A new address of this wallet (" + printed(coin, fresh) + ")" : "");
        RadioButton toOther = new RadioButton("Another " + coin.getCapitalizedName() + " address:");
        toWallet.setToggleGroup(destinationChoice);
        toOther.setToggleGroup(destinationChoice);
        toWallet.setSelected(true);
        TextField otherAddress = new TextField();
        otherAddress.setPrefColumnCount(34);
        Label destinationProblem = new Label();

        byte[][] fileBody = new byte[1][];
        chooseFile.setOnAction(event -> chooseFile(owner, coin).ifPresent(content -> {
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
        if(doginals) {
            grid.addRow(6, new Label("Lands at:"), new VBox(6, toWallet, new HBox(10, toOther, otherAddress), destinationProblem));
        } else {
            grid.addRow(6, new Label("Reveal:"), new VBox(6, signNow, signLater));
        }
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
            long max = maxBody(coin, type.isEmpty() ? Inscription.TEXT : type);
            int length = body == null ? 0 : body.length;
            size.setText(String.format("%,d bytes (at most %,d)", length, max));
            size.setStyle(length > max ? "-fx-text-fill: -fx-accent;" : "");
            otherAddress.setDisable(!toOther.isSelected());
            boolean destinationOk = true;
            destinationProblem.setText("");
            if(doginals && toOther.isSelected()) {
                try {
                    destination(open, otherAddress.getText());
                } catch(IllegalArgumentException e) {
                    destinationOk = false;
                    destinationProblem.setText(otherAddress.getText().isBlank() ? "" : e.getMessage());
                }
            }
            ok.setDisable(length == 0 || length > max || type.isEmpty() || !destinationOk);
        };
        kind.selectedToggleProperty().addListener((o, a, b) -> check.run());
        text.textProperty().addListener((o, a, b) -> check.run());
        contentType.textProperty().addListener((o, a, b) -> check.run());
        fileLabel.textProperty().addListener((o, a, b) -> check.run());
        destinationChoice.selectedToggleProperty().addListener((o, a, b) -> check.run());
        otherAddress.textProperty().addListener((o, a, b) -> check.run());
        check.run();

        dialog.setResultConverter(button -> {
            if(button != ButtonType.OK) {
                return null;
            }
            byte[] body = textChoice.isSelected() ? text.getText().getBytes(StandardCharsets.UTF_8) : fileBody[0];
            Address destination = !doginals ? null : toOther.isSelected() ? destination(open, otherAddress.getText()) : fresh;
            return new Answer(contentType.getText().trim(), body, feeRange.getFeeRate(), !doginals && signNow.isSelected(), destination);
        });
        return dialog.showAndWait();
    }

    private static String printed(Coin coin, Address address) {
        Coin previous = Coin.beginScope(coin);
        try {
            return address.toString();
        } finally {
            Coin.endScope(previous);
        }
    }

    //A receive address after the one whose key signs the chain
    private static Address freshDestination(Wallet wallet) {
        Coin previous = Coin.beginScope(wallet.getCoin());
        try {
            return wallet.getFreshNode(KeyPurpose.RECEIVE, DoginalsFlow.keyNode(wallet)).getAddress();
        } finally {
            Coin.endScope(previous);
        }
    }

    /** A file's bytes and content type, refusing one too large to inscribe before reading it. */
    static Optional<Content> chooseFile(Window owner) {
        return chooseFile(owner, Coin.LITECOIN);
    }

    static Optional<Content> chooseFile(Window owner, Coin coin) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose the file to inscribe");
        File file = chooser.showOpenDialog(owner);
        if(file == null) {
            return Optional.empty();
        }
        String contentType = Inscription.contentTypeFor(file.getName());
        long max = maxBody(coin, contentType);
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

    enum Source { FILE, TEXT }

    /** How a pending mint's content can be given again: always as a file (a text file's line endings survive only that way), and as typed text for text. */
    static EnumSet<Source> finishSources(String contentType) {
        return contentType.startsWith("text/plain") ? EnumSet.of(Source.FILE, Source.TEXT) : EnumSet.of(Source.FILE);
    }

    /** Asks for the content a pending mint was started with: a file, or for a text mint the typed text. */
    public static Optional<byte[]> askContent(Window owner, PendingMint mint) {
        if(!finishSources(mint.getContentType()).contains(Source.TEXT)) {
            return chooseFile(owner).map(Content::body);
        }
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(owner);
        dialog.setTitle("Finish Mint");
        dialog.setHeaderText("Enter exactly the text this mint was started with, or choose the file it was made from");
        TextArea area = new TextArea();
        area.setPrefRowCount(5);
        area.setWrapText(true);
        dialog.getDialogPane().setContent(area);
        ButtonType fileButton = new ButtonType("Choose file...", ButtonBar.ButtonData.LEFT);
        dialog.getDialogPane().getButtonTypes().add(fileButton);
        boolean[] fromFile = {false};
        dialog.setResultConverter(button -> {
            fromFile[0] = button == fileButton;
            return button == ButtonType.OK ? area.getText() : null;
        });
        Optional<String> text = dialog.showAndWait();
        if(fromFile[0]) {
            return chooseFile(owner).map(Content::body);
        }
        return text.map(value -> value.getBytes(StandardCharsets.UTF_8));
    }
}
