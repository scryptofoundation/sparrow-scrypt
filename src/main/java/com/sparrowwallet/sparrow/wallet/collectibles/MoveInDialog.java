package com.sparrowwallet.sparrow.wallet.collectibles;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.ordinals.MoveIn;
import com.sparrowwallet.drongo.ordinals.OrdWalletDump;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionOutput;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletNode;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.control.DialogImage;
import com.sparrowwallet.sparrow.control.FeeRangeSlider;
import com.sparrowwallet.sparrow.control.ServiceProgressDialog;
import com.sparrowwallet.sparrow.event.ViewTransactionEvent;
import com.sparrowwallet.sparrow.net.ElectrumServer;
import com.sparrowwallet.sparrow.net.ord.OrdClient;
import com.sparrowwallet.sparrow.net.ord.OrdOutput;
import com.sparrowwallet.sparrow.net.ord.OrdStatus;
import com.sparrowwallet.sparrow.net.ord.RuneBalance;
import com.sparrowwallet.sparrow.wallet.WalletForm;
import com.sparrowwallet.sparrow.wallet.WalletLink;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Moves everything an ord-litecoin wallet holds into this seed, from a Litecoin Core dumpwallet file of ord's wallet:
 * every inscription to its own fresh address of the seed's taproot wallet, all runes to one more, and the plain LTC to
 * this wallet (its public side when this is an MWEB view, so nothing pegs in). The keys are read into memory only and
 * cleared once the transaction is signed or the move is abandoned; the signed transaction opens in a transaction tab
 * for review and broadcast.
 */
public final class MoveInDialog {
    private static final String ALTERNATIVE = "Instead of handing this wallet ord's keys, you can also send each inscription from ord itself:\n"
            + "  ord wallet send --fee-rate <rate> <address> <inscription id>\n"
            + "using receive addresses of this seed's taproot wallet.";

    private MoveInDialog() {}

    /** leftOut counts mined (coinbase) and MWEB peg-out outputs, which may not be spendable yet and are not moved. */
    private record Lookup(List<MoveIn.Found> found, List<OrdOutput> answers, int leftOut) {
        String leftOutNote() {
            return leftOut == 0 ? "" : "\n\n" + leftOut + " mined or MWEB peg-out output" + (leftOut == 1 ? " was" : "s were")
                    + " left out, as they may not be spendable yet. Send " + (leftOut == 1 ? "it" : "them") + " from ord or Litecoin Core once mature.";
        }
    }

    public static void start(Window owner, WalletForm walletForm) {
        Wallet open = walletForm.getWallet();
        String url = CollectiblesManager.serverUrl(open);
        if(url == null) {
            AppServices.showErrorDialog("Cannot move in", "Inscription checks are off. Turn them on in Preferences > Server > Inscriptions first, "
                    + "so every output of the ord wallet can be checked before it is moved.");
            return;
        }
        Optional<ButtonType> go = AppServices.showAlertDialog("Move in from ord-litecoin wallet", "This reads the private keys from a dump of ord's wallet "
                + "(litecoin-cli -rpcwallet=ord dumpwallet <file>), finds every output they hold, checks each with the inscriptions server, "
                + "and builds one signed transaction moving everything here. The keys stay in memory and are cleared afterwards.\n\n" + ALTERNATIVE,
                Alert.AlertType.INFORMATION, ButtonType.CANCEL, ButtonType.OK);
        if(go.isEmpty() || go.get() != ButtonType.OK) {
            return;
        }
        TaprootWallets.ensure(walletForm, owner, taproot -> chooseDump(owner, open, taproot, url));
    }

    private static void chooseDump(Window owner, Wallet open, Wallet taproot, String url) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open ord wallet dump");
        File file = chooser.showOpenDialog(owner);
        if(file == null) {
            return;
        }

        List<OrdWalletDump.DumpedKey> keys;
        try(Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            keys = OrdWalletDump.parse(reader, Network.get());
        } catch(Exception e) {
            AppServices.showErrorDialog("Cannot read the dump", e.getMessage());
            return;
        }
        if(keys.isEmpty()) {
            AppServices.showErrorDialog("Nothing to move", "The dump holds no used keys.");
            return;
        }

        Task<Lookup> lookup = new Task<>() {
            @Override
            protected Lookup call() throws Exception {
                Coin previousCoin = Coin.beginScope(Coin.LITECOIN);
                try {
                    if(!ElectrumServer.isConnectedTo(Coin.LITECOIN)) {
                        throw new IllegalStateException("Connect to a Litecoin server first.");
                    }
                    ElectrumServer electrumServer = new ElectrumServer();
                    record Owned(OrdWalletDump.DumpedKey key, ScriptType type) {}
                    Map<Address, Owned> owners = new LinkedHashMap<>();
                    for(OrdWalletDump.DumpedKey key : keys) {
                        for(Map.Entry<ScriptType, Address> entry : OrdWalletDump.addresses(key).entrySet()) {
                            owners.put(entry.getValue(), new Owned(key, entry.getKey()));
                        }
                    }
                    updateMessage("Looking up " + owners.size() + " addresses of " + keys.size() + " keys...");
                    List<MoveIn.Found> unclassified = new ArrayList<>();
                    int leftOut = 0;
                    for(Map.Entry<Address, List<TransactionOutput>> entry : electrumServer.getUtxos(new ArrayList<>(owners.keySet())).entrySet()) {
                        Owned owner = owners.get(entry.getKey());
                        for(TransactionOutput output : entry.getValue()) {
                            if(MoveIn.mayBeImmature(output)) {
                                leftOut++;
                            } else {
                                unclassified.add(new MoveIn.Found(output, owner.key(), owner.type(), List.of(), false));
                            }
                        }
                    }
                    if(unclassified.isEmpty()) {
                        return new Lookup(List.of(), List.of(), leftOut);
                    }

                    updateMessage("Checking " + unclassified.size() + " outputs with the inscriptions server...");
                    updateProgress(-1, 0);
                    OrdClient client = OrdClient.forApp(url);
                    OrdStatus status = client.status();
                    OrdClient.requireChain(status, Network.get());
                    List<String> outpoints = unclassified.stream().map(f -> f.output().getHash() + ":" + f.output().getIndex()).toList();
                    List<OrdOutput> answers = client.outputs(outpoints).stream().map(answer -> answer.forServer(status.runeIndex())).toList();
                    List<MoveIn.Found> found = new ArrayList<>();
                    for(int i = 0; i < unclassified.size(); i++) {
                        OrdOutput answer = answers.get(i);
                        if(!answer.indexed() || answer.unlisted()) {
                            throw new IllegalStateException("The inscriptions server cannot vouch for " + answer.outpoint() + " yet (not indexed, or it holds something it could not list). "
                                    + "Wait for it to catch up and try again.");
                        }
                        MoveIn.Found f = unclassified.get(i);
                        found.add(new MoveIn.Found(f.output(), f.key(), f.type(), answer.inscriptions(), !answer.runes().isEmpty()));
                    }
                    return new Lookup(found, answers, leftOut);
                } finally {
                    Coin.endScope(previousCoin);
                }
            }
        };
        lookup.setOnSucceeded(event -> {
            try {
                Lookup result = lookup.getValue();
                if(result.found().isEmpty()) {
                    AppServices.showAlertDialog("Nothing to move", "No unspent outputs were found at the dump's addresses." + result.leftOutNote(), Alert.AlertType.INFORMATION, ButtonType.OK);
                    return;
                }
                confirmAndSign(owner, open, taproot, result);
            } catch(Exception e) {
                AppServices.showErrorDialog("Cannot move in", e.getMessage());
            } finally {
                OrdWalletDump.clear(keys);
            }
        });
        lookup.setOnFailed(event -> {
            OrdWalletDump.clear(keys);
            AppServices.showErrorDialog("Cannot move in", lookup.getException().getMessage());
        });
        ServiceProgressDialog progress = new ServiceProgressDialog("Move In", "Finding what the ord wallet holds", new DialogImage(DialogImage.Type.SPARROW), lookup);
        progress.initOwner(owner);
        AppServices.moveToActiveWindowScreen(progress);
        Thread thread = new Thread(lookup, "Move in lookup");
        thread.setDaemon(true);
        thread.start();
    }

    /** Runs on the FX thread while the keys are still held; the caller clears them on every path. */
    private static void confirmAndSign(Window owner, Wallet open, Wallet taproot, Lookup lookup) {
        List<MoveIn.Found> found = lookup.found();
        long inscribed = found.stream().filter(MoveIn.Found::inscribed).count();
        boolean runic = found.stream().anyMatch(MoveIn.Found::runic);
        long total = found.stream().mapToLong(f -> f.output().getValue()).sum();

        Wallet plainWallet = plainWallet(open, taproot);
        List<Address> inscriptionDestinations = new ArrayList<>();
        WalletNode node = null;
        for(int i = 0; i < inscribed; i++) {
            node = node == null ? taproot.getFreshNode(KeyPurpose.RECEIVE) : taproot.getFreshNode(KeyPurpose.RECEIVE, node);
            inscriptionDestinations.add(node.getAddress());
        }
        WalletNode runesNode = node == null ? taproot.getFreshNode(KeyPurpose.RECEIVE) : taproot.getFreshNode(KeyPurpose.RECEIVE, node);
        Address runesDestination = runesNode.getAddress();
        Address plainDestination = (plainWallet == taproot ? taproot.getFreshNode(KeyPurpose.RECEIVE, runesNode) : plainWallet.getFreshNode(KeyPurpose.RECEIVE)).getAddress();

        StringBuilder text = new StringBuilder();
        text.append("Found ").append(found.size()).append(" unspent outputs, ").append(total).append(" litoshis in all.\n\n");
        for(OrdOutput answer : lookup.answers()) {
            for(String id : answer.inscriptions()) {
                text.append("  Inscription ").append(id).append("\n");
            }
            for(Map.Entry<String, RuneBalance> rune : answer.runes().entrySet()) {
                text.append("  ").append(rune.getValue().format()).append(" ").append(rune.getKey()).append("\n");
            }
        }
        text.append("\n");
        if(inscribed > 0) {
            text.append("Each output holding inscriptions moves whole, with its value, to its own new address of ").append(taproot.getFullDisplayName()).append(".\n");
        }
        if(runic) {
            text.append("All runes move to ").append(runesDestination).append(" in ").append(taproot.getFullDisplayName()).append(".\n");
        }
        text.append("The plain LTC, less the fee").append(runic ? " and the runes output" : "").append(", moves to ").append(plainDestination)
                .append(" in ").append(plainWallet.getFullDisplayName()).append(".").append(lookup.leftOutNote());

        Dialog<Double> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Move In");
        dialog.setHeaderText("Move everything in from the ord wallet?");
        TextArea area = new TextArea(text.toString());
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefRowCount(12);
        area.setPrefColumnCount(60);
        FeeRangeSlider feeRange = new FeeRangeSlider();
        feeRange.setFeeRate(AppServices.getDefaultFeeRate());
        Label feeLabel = new Label(String.format("Fee rate: %.2f lit/vB", feeRange.getFeeRate()));
        feeRange.valueProperty().addListener((o, a, b) -> feeLabel.setText(String.format("Fee rate: %.2f lit/vB", feeRange.getFeeRate())));
        VBox content = new VBox(10, area, feeRange, feeLabel);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        ((Button)dialog.getDialogPane().lookupButton(ButtonType.OK)).setText("Sign");
        dialog.setResultConverter(button -> button == ButtonType.OK ? feeRange.getFeeRate() : null);
        Optional<Double> feeRate = dialog.showAndWait();
        if(feeRate.isEmpty()) {
            return;
        }

        Transaction transaction;
        try {
            transaction = MoveIn.sweep(found, inscriptionDestinations, runesDestination, plainDestination, feeRate.get(), AppServices.getMinimumRelayFeeRate());
        } finally {
            //Signed or not, the keys are no longer needed (the caller clears them again on every other path)
            OrdWalletDump.clear(found.stream().map(MoveIn.Found::key).distinct().toList());
        }
        EventManager.get().post(new ViewTransactionEvent(owner, transaction));
        AppServices.showAlertDialog("Signed", "The move-in transaction is signed and open in a new tab. Check it and broadcast it from there.\n\n"
                + "The dump file still holds ord's private keys in plain text: delete it now (and empty the recycle bin).", Alert.AlertType.INFORMATION, ButtonType.OK);
    }

    /** Plain LTC lands on a public wallet of this seed, never an MWEB view, so the move never pegs in. */
    private static Wallet plainWallet(Wallet open, Wallet taproot) {
        if(open.getScriptType() != ScriptType.MWEB) {
            return open;
        }
        Wallet publicSide = open.isNested() ? open.getMasterWallet() : WalletLink.getPublicWallet(open);
        return publicSide != null && publicSide.getScriptType() != ScriptType.MWEB ? publicSide : taproot;
    }
}
