package com.sparrowwallet.sparrow.wallet.collectibles;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.ordinals.RuneName;
import com.sparrowwallet.drongo.ordinals.Runestone;
import com.sparrowwallet.drongo.ordinals.InscriptionMint;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.control.FeeRangeSlider;
import com.sparrowwallet.sparrow.net.ord.OrdClient;
import com.sparrowwallet.sparrow.net.ord.OrdStatus;
import com.sparrowwallet.sparrow.wallet.WalletForm;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Etch Rune: a new rune's name, divisibility, symbol, premine and optional open mint terms, with an optional file for
 * its inscription (else the name as text). The commit is broadcast now; the reveal is finished from Pending mints once
 * the commit is 6 blocks deep. The ord server is asked for the shortest name the next block accepts and whether the
 * name is taken before anything is built.
 */
public final class EtchRuneDialog {
    private EtchRuneDialog() {}

    private record Form(Runestone.Etching etching, String contentType, byte[] content, double feeRate) {}

    private record ServerCheck(RuneName minimum, boolean etched) {}

    public static void start(Window owner, WalletForm walletForm) {
        Wallet funding = MintFlow.fundingWallet(walletForm.getWallet());
        if(MintFlow.needsPegOut(funding)) {
            TaprootWallets.ensure(walletForm, owner, taproot -> ask(owner).ifPresent(form -> {
                try {
                    InscriptionMint.Plan plan = MintFlow.plan(MintFlow.revealNode(taproot), MintFlow.etchInscription(form.etching(), form.contentType(), form.content()),
                            form.etching(), form.feeRate());
                    MintFlow.startFromMweb(owner, funding, taproot, MintFlow.pegOutForMint(taproot, plan, form.feeRate()), "etching a rune");
                } catch(Exception e) {
                    AppServices.showErrorDialog("Cannot etch", e.getMessage());
                }
            }));
            return;
        }
        Optional<String> refusal = MintFlow.refusal(funding);
        if(refusal.isPresent()) {
            AppServices.showErrorDialog("Cannot etch", refusal.get());
            return;
        }
        String url = CollectiblesManager.serverUrl(funding);
        if(url == null) {
            AppServices.showErrorDialog("Cannot etch", "Inscription checks are off. Turn them on in Preferences > Server > Inscriptions.");
            return;
        }
        TaprootWallets.ensure(walletForm, owner, taproot -> ask(owner).ifPresent(form -> {
            Task<ServerCheck> check = new Task<>() {
                @Override
                protected ServerCheck call() throws Exception {
                    Coin previousCoin = Coin.beginScope(funding.getCoin());
                    try {
                        OrdClient client = OrdClient.forApp(url);
                        OrdStatus status = client.status();
                        if(!status.runeIndex()) {
                            throw new IllegalStateException("The inscriptions server does not index runes (start ord with --index-runes), so it cannot check the name.");
                        }
                        boolean etched;
                        try {
                            client.rune(form.etching().name().toString());
                            etched = true;
                        } catch(OrdClient.HttpStatusException e) {
                            if(e.getStatus() != 404) {
                                throw e;
                            }
                            etched = false;
                        }
                        return new ServerCheck(status.minimumRune() == null ? null : RuneName.parse(status.minimumRune()), etched);
                    } finally {
                        Coin.endScope(previousCoin);
                    }
                }
            };
            check.setOnSucceeded(event -> {
                Optional<String> problem = RuneMinting.etchProblem(form.etching().name(), check.getValue().minimum(), check.getValue().etched());
                if(problem.isPresent()) {
                    AppServices.showErrorDialog("Cannot etch", problem.get());
                    return;
                }
                MintFlow.Request request = new MintFlow.Request(MintFlow.etchInscription(form.etching(), form.contentType(), form.content()), form.etching(), form.feeRate(), false);
                MintFlow.mint(owner, funding, taproot, request, message -> MintInscriptionDialog.showDone(owner, message));
            });
            check.setOnFailed(event -> AppServices.showErrorDialog("Cannot etch", "Could not check the name: " + check.getException().getMessage()));
            Thread thread = new Thread(check, "Rune name check");
            thread.setDaemon(true);
            thread.start();
        }));
    }

    private static Optional<Form> ask(Window owner) {
        Dialog<Form> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Etch Rune");
        dialog.setHeaderText("Etch a new rune into this seed's taproot wallet");

        TextField name = new TextField();
        name.setPromptText("SPARROW•SCRYPT•RUNE");
        name.setPrefColumnCount(30);
        Spinner<Integer> divisibility = new Spinner<>(0, 38, 0);
        TextField symbol = new TextField();
        symbol.setPrefColumnCount(3);
        TextField premine = new TextField("0");
        CheckBox openMints = new CheckBox("Open mints (anyone can mint)");
        TextField amount = new TextField();
        amount.setPromptText("per mint");
        TextField cap = new TextField();
        cap.setPromptText("number of mints");
        TextField heightStart = new TextField();
        TextField heightEnd = new TextField();
        TextField offsetStart = new TextField();
        TextField offsetEnd = new TextField();
        for(TextField field : new TextField[] {heightStart, heightEnd, offsetStart, offsetEnd}) {
            field.setPromptText("optional");
            field.setPrefColumnCount(8);
        }
        CheckBox turbo = new CheckBox("Turbo (opt in to future runes protocol changes)");
        Button chooseFile = new Button("Choose file...");
        Label fileLabel = new Label("None: the name is inscribed as text");
        FeeRangeSlider feeRange = new FeeRangeSlider();
        feeRange.setFeeRate(AppServices.getDefaultFeeRate());
        Label feeLabel = new Label(String.format("%.2f lit/vB", feeRange.getFeeRate()));
        feeRange.valueProperty().addListener((o, a, b) -> feeLabel.setText(String.format("%.2f lit/vB", feeRange.getFeeRate())));
        Label problem = new Label();
        problem.setWrapText(true);
        problem.setMaxWidth(560);
        Label warning = new Label(RuneMinting.FRONT_RUNNING_WARNING);
        warning.setWrapText(true);
        warning.setMaxWidth(560);

        String[] contentType = new String[1];
        byte[][] content = new byte[1][];
        chooseFile.setOnAction(event -> MintInscriptionDialog.chooseFile(owner).ifPresent(chosen -> {
            contentType[0] = chosen.contentType();
            content[0] = chosen.body();
            fileLabel.setText(chosen.body().length + " bytes of " + chosen.contentType());
        }));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        int row = 0;
        grid.addRow(row++, new Label("Name:"), name);
        grid.addRow(row++, new Label("Divisibility:"), divisibility);
        grid.addRow(row++, new Label("Symbol:"), symbol);
        grid.addRow(row++, new Label("Premine:"), premine);
        grid.addRow(row++, new Label(""), openMints);
        grid.addRow(row++, new Label("Amount:"), amount);
        grid.addRow(row++, new Label("Cap:"), cap);
        grid.addRow(row++, new Label("Heights:"), new HBox(6, heightStart, new Label("to"), heightEnd));
        grid.addRow(row++, new Label("Offsets:"), new HBox(6, offsetStart, new Label("to"), offsetEnd));
        grid.addRow(row++, new Label(""), turbo);
        grid.addRow(row++, new Label("Inscription:"), new HBox(10, chooseFile, fileLabel));
        grid.addRow(row, new Label("Fee rate:"), new VBox(4, feeRange, feeLabel));
        VBox box = new VBox(10, grid, problem, warning);
        box.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        Button ok = (Button)dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.setText("Continue");

        Form[] built = new Form[1];
        Runnable check = () -> {
            for(TextField field : new TextField[] {amount, cap, heightStart, heightEnd, offsetStart, offsetEnd}) {
                field.setDisable(!openMints.isSelected());
            }
            try {
                RuneName runeName = RuneName.parse(name.getText().trim().toUpperCase(java.util.Locale.ROOT));
                int places = divisibility.getValue();
                String symbolText = symbol.getText().trim();
                if(symbolText.codePointCount(0, symbolText.length()) > 1) {
                    throw new IllegalArgumentException("The symbol is one character");
                }
                Integer symbolPoint = symbolText.isEmpty() ? null : symbolText.codePointAt(0);
                BigInteger premineUnits = premine.getText().isBlank() ? BigInteger.ZERO : CollectibleSend.toBaseUnits(premine.getText(), places);
                Runestone.Terms terms = null;
                if(openMints.isSelected()) {
                    BigInteger perMint = CollectibleSend.toBaseUnits(amount.getText(), places);
                    BigInteger mints = new BigInteger(cap.getText().trim());
                    if(perMint.signum() <= 0 || mints.signum() <= 0) {
                        throw new IllegalArgumentException("Open mints need an amount and a cap above zero");
                    }
                    terms = new Runestone.Terms(perMint, mints, height(heightStart), height(heightEnd), height(offsetStart), height(offsetEnd));
                } else if(premineUnits.signum() == 0) {
                    throw new IllegalArgumentException("A rune with no premine and no open mints would have no supply");
                }
                Runestone.Etching etching = new Runestone.Etching(runeName, places, symbolPoint, premineUnits, terms, turbo.isSelected());
                built[0] = new Form(etching, contentType[0], content[0], feeRange.getFeeRate());
                problem.setText("");
                ok.setDisable(false);
            } catch(RuntimeException e) {
                built[0] = null;
                problem.setText(name.getText().isBlank() ? "" : e.getMessage());
                problem.setStyle("-fx-text-fill: -fx-accent;");
                ok.setDisable(true);
            }
        };
        name.textProperty().addListener((o, a, b) -> check.run());
        divisibility.valueProperty().addListener((o, a, b) -> check.run());
        symbol.textProperty().addListener((o, a, b) -> check.run());
        premine.textProperty().addListener((o, a, b) -> check.run());
        openMints.selectedProperty().addListener((o, a, b) -> check.run());
        turbo.selectedProperty().addListener((o, a, b) -> check.run());
        fileLabel.textProperty().addListener((o, a, b) -> check.run());
        feeRange.valueProperty().addListener((o, a, b) -> check.run());
        for(TextField field : new TextField[] {amount, cap, heightStart, heightEnd, offsetStart, offsetEnd}) {
            field.textProperty().addListener((o, a, b) -> check.run());
        }
        check.run();

        dialog.setResultConverter(button -> button == ButtonType.OK ? built[0] : null);
        return dialog.showAndWait();
    }

    private static Long height(TextField field) {
        String text = field.getText().trim();
        if(text.isEmpty()) {
            return null;
        }
        long value = Long.parseLong(text);
        if(value < 0) {
            throw new IllegalArgumentException("Heights and offsets cannot be negative");
        }
        return value;
    }
}
