package com.sparrowwallet.sparrow.wallet.collectibles;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.SecureString;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.ordinals.CollectibleTransactions;
import com.sparrowwallet.drongo.ordinals.Inscription;
import com.sparrowwallet.drongo.ordinals.InscriptionMint;
import com.sparrowwallet.drongo.ordinals.Runestone;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionOutput;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.wallet.*;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.control.WalletPasswordDialog;
import com.sparrowwallet.sparrow.event.SendActionEvent;
import com.sparrowwallet.sparrow.event.SpendUtxoEvent;
import com.sparrowwallet.sparrow.event.StorageEvent;
import com.sparrowwallet.sparrow.event.TimedEvent;
import com.sparrowwallet.sparrow.event.WalletDataChangedEvent;
import com.sparrowwallet.sparrow.event.WalletPendingMintsChangedEvent;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.net.ElectrumServer;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/**
 * Minting an inscription or etching a rune from a software wallet of the seed (spec: Goals 5 and 6). The commit is
 * paid from the open wallet and signed with its keystore; the reveal is signed with the taproot wallet's next receive
 * key, derived for that one signature, and lands its postage on that key's own address. Before the commit is
 * broadcast, a pending record (recovery data only, see {@link PendingMint}) is saved on the taproot wallet's master,
 * so a commit is never left on chain with nothing on disk to rebuild its script from. The record goes once the reveal
 * is broadcast. An etch always waits: ord accepts it only once the commit is {@link InscriptionMint#COMMIT_CONFIRMATIONS}
 * deep, so it is finished later from the pending list.
 *
 * The decisions are plain methods, tested without the UI; the sign, save and broadcast sequence is below them.
 */
public final class MintFlow {
    private static final Logger log = LoggerFactory.getLogger(MintFlow.class);

    private MintFlow() {}

    /** What a mint dialog asks for. An etch is never signed now. */
    public record Request(Inscription inscription, Runestone.Etching etching, double feeRate, boolean signNow) {
        public Request {
            if(etching != null && signNow) {
                throw new IllegalArgumentException("An etch is revealed only once its commit is " + InscriptionMint.COMMIT_CONFIRMATIONS + " blocks deep");
            }
        }
    }

    /** The wallet whose plain LTC pays the commit: the open wallet (an MWEB view first moves coins out, see {@link #needsPegOut}). */
    public static Wallet fundingWallet(Wallet open) {
        return open;
    }

    /** Why this wallet cannot mint, if it cannot. */
    public static Optional<String> refusal(Wallet wallet) {
        if(wallet.getCoin() != Coin.LITECOIN) {
            return Optional.of("Inscriptions and runes are minted from Litecoin wallets only.");
        }
        if(wallet.getPolicyType() != PolicyType.SINGLE) {
            return Optional.of("Minting works from single-signature wallets only.");
        }
        for(Keystore keystore : wallet.getKeystores()) {
            KeystoreSource source = keystore.getSource();
            if(source == KeystoreSource.HW_USB || source == KeystoreSource.HW_AIRGAPPED) {
                return Optional.of("This is a hardware wallet. Minting signs the commit and the reveal in this app with keys from the seed, "
                        + "which a hardware wallet cannot do for an inscription script. Mint from a wallet whose seed is in this app.");
            }
            if(source != KeystoreSource.SW_SEED) {
                return Optional.of("This is a watch-only wallet: it has no keys to sign the commit and the reveal. Mint from a wallet whose seed is in this app.");
            }
        }
        return Optional.empty();
    }

    /** The plan for a reveal to this taproot node: its untweaked key goes in the envelope, its address receives the postage. */
    public static InscriptionMint.Plan plan(WalletNode node, Inscription inscription, Runestone.Etching etching, double revealFeeRate) {
        Runestone.Etching checked = etching;
        if(checked != null && !Arrays.equals(inscription.runeCommitment(), checked.name().commitment())) {
            throw new IllegalArgumentException("The envelope does not commit to the rune being etched");
        }
        return new InscriptionMint.Plan(inscription, node.getPubKey(), node.getAddress(),
                etching == null ? null : Runestone.script(List.of(), 0, etching, null), revealFeeRate);
    }

    /** An etch's envelope: the given content, or by default the spaced rune name as text; with the rune's commitment. */
    public static Inscription etchInscription(Runestone.Etching etching, String contentType, byte[] content) {
        if(content == null) {
            return new Inscription(Inscription.TEXT, etching.name().toString().getBytes(StandardCharsets.UTF_8), etching.name().commitment());
        }
        return new Inscription(contentType, content, etching.name().commitment());
    }

    public static PendingMint pendingRecord(InscriptionMint.Plan plan, WalletNode node, Transaction commit, Runestone.Etching etching) {
        TransactionOutput commitOutput = commit.getOutputs().getFirst();
        return new PendingMint(etching == null ? PendingMint.Kind.INSCRIPTION : PendingMint.Kind.ETCH, node.getDerivationPath(), plan.scriptHash(),
                commit.getTxId(), 0, commitOutput.getValue(), plan.inscription().contentType(), etching);
    }

    /**
     * The plan again from the record and the content, refusing content whose reveal script differs from the one the
     * commit pays to. A null content rebuilds an etch's default envelope. The fee rate does not matter here: the
     * reveal pays postage and leaves the rest of the commit output as its fee.
     */
    public static InscriptionMint.Plan rebuild(PendingMint mint, Wallet taproot, byte[] content) {
        WalletNode node = new WalletNode(taproot, mint.getKeyPath());
        Inscription inscription;
        if(mint.getKind() == PendingMint.Kind.ETCH) {
            inscription = etchInscription(mint.getEtching(), content == null ? null : mint.getContentType(), content);
        } else {
            if(content == null) {
                throw new IllegalArgumentException("Choose the content this mint was started with");
            }
            inscription = new Inscription(mint.getContentType(), content, null);
        }
        InscriptionMint.Plan plan = plan(node, inscription, mint.getEtching(), 0);
        if(!Arrays.equals(plan.scriptHash(), mint.getScriptHash())) {
            throw new IllegalArgumentException("This is not the content this mint was started with. Choose exactly the same file or text"
                    + (mint.getKind() == PendingMint.Kind.ETCH ? " that was inscribed with the etching." : "."));
        }
        return plan;
    }

    /** Refuses a commit output that no longer pays the mint's address (the commit was replaced). */
    public static void checkCommit(PendingMint mint, InscriptionMint.Plan plan, TransactionOutput commitOutput) {
        if(commitOutput == null || !commitOutput.getScript().equals(plan.commitAddress().getOutputScript()) || commitOutput.getValue() < CollectibleTransactions.POSTAGE) {
            throw new IllegalArgumentException("The commit " + mint.getCommitTxid() + " was replaced or changed: its output " + mint.getCommitVout()
                    + " no longer pays this mint's address. Nothing can be revealed from it.");
        }
    }

    /** Why the reveal cannot be signed yet: an etch's commit is not yet deep enough. */
    public static Optional<String> revealWait(PendingMint mint, int commitConfirmations) {
        if(mint.getKind() == PendingMint.Kind.ETCH && commitConfirmations < InscriptionMint.COMMIT_CONFIRMATIONS) {
            return Optional.of("The etching's commit has " + commitConfirmations + " of " + InscriptionMint.COMMIT_CONFIRMATIONS
                    + " confirmations. ord accepts an etching only once its commit is " + InscriptionMint.COMMIT_CONFIRMATIONS + " blocks deep.");
        }
        return Optional.empty();
    }

    /** The wallet that keeps the taproot wallet's pending mints. */
    public static Wallet pendingHolder(Wallet taproot) {
        return taproot.isMasterWallet() ? taproot : taproot.getMasterWallet();
    }

    /** The first fresh receive node of the taproot wallet that no pending mint has taken. */
    static WalletNode revealNode(Wallet taproot) {
        Set<String> taken = new HashSet<>();
        pendingHolder(taproot).getPendingMints().forEach(mint -> taken.add(mint.getKeyPath()));
        WalletNode node = taproot.getFreshNode(KeyPurpose.RECEIVE);
        while(taken.contains(node.getDerivationPath())) {
            node = taproot.getFreshNode(KeyPurpose.RECEIVE, node);
        }
        return node;
    }

    //A commit spending one taproot output to the commit output and change, in virtual bytes
    private static final int COMMIT_VBYTES = 154;
    //A rune mint spending one taproot output to the postage, the runestone and change
    private static final int RUNE_MINT_VBYTES = 175;

    /** True when a mint from this wallet starts by moving coins out of MWEB: ord does not see MWEB coins. */
    public static boolean needsPegOut(Wallet open) {
        return open.getScriptType() == ScriptType.MWEB;
    }

    /**
     * The peg-out that funds a mint from the MWEB view: to a taproot receive address (not the reveal's), for the
     * commit output (postage and reveal fee) plus the commit's own fee. The commit is then paid from the taproot wallet.
     */
    public static Payment pegOutForMint(Wallet taproot, InscriptionMint.Plan plan, double feeRate) {
        WalletNode node = taproot.getFreshNode(KeyPurpose.RECEIVE, revealNode(taproot));
        return new Payment(node.getAddress(), "Mint funding", plan.commitValue() + (long)Math.ceil(COMMIT_VBYTES * feeRate), false);
    }

    /** The peg-out that funds a rune mint from the MWEB view: postage plus the mint's fee, to a taproot receive address. */
    public static Payment pegOutForRuneMint(Wallet taproot, double feeRate) {
        WalletNode node = taproot.getFreshNode(KeyPurpose.RECEIVE);
        return new Payment(node.getAddress(), "Rune mint funding", CollectibleTransactions.POSTAGE + (long)Math.ceil(RUNE_MINT_VBYTES * feeRate), false);
    }

    /**
     * Step 1 of minting from the MWEB view: explains the three steps and opens the MWEB wallet's Send tab with the
     * peg-out to the taproot wallet filled in. Once it has PEGOUT_MATURITY_THRESHOLD confirmations, the mint is
     * made from the taproot wallet, paid from those coins.
     */
    public static void startFromMweb(Window owner, Wallet mweb, Wallet taproot, Payment pegOut, String what) {
        String text = "Coins in MWEB are not seen by ord, so " + what + " from MWEB takes three steps:\n\n"
                + "1. Move " + pegOut.getAmount() + " litoshis out of MWEB to " + pegOut.getAddress() + " in " + taproot.getFullDisplayName()
                + " (postage and fees). The Send tab opens with this filled in: sign and broadcast it there.\n"
                + "2. Once that move has " + Transaction.PEGOUT_MATURITY_THRESHOLD + " confirmations, open Collectibles in "
                + taproot.getFullDisplayName() + " and mint again. The commit is paid from the moved coins.\n"
                + "3. The reveal lands the result in the taproot wallet.\n\nNothing is sent to MWEB at any step.";
        Optional<ButtonType> answer = AppServices.showAlertDialog("Mint from MWEB", text, Alert.AlertType.CONFIRMATION, ButtonType.CANCEL, ButtonType.OK);
        if(answer.isPresent() && answer.get() == ButtonType.OK) {
            EventManager.get().post(new SendActionEvent(mweb, new ArrayList<>(mweb.getSpendableUtxos().keySet())));
            javafx.application.Platform.runLater(() -> EventManager.get().post(new SpendUtxoEvent(mweb, List.of(pegOut), null, null)));
        }
    }

    /** Confirmations of a wallet transaction as the wallet knows it, 0 when unknown or unconfirmed. */
    public static int confirmations(Collection<Wallet> wallets, Sha256Hash txid) {
        Integer tip = AppServices.getCurrentBlockHeight();
        for(Wallet wallet : wallets) {
            BlockTransaction transaction = wallet.getTransactions().get(txid);
            if(transaction != null && transaction.getHeight() > 0 && tip != null) {
                return tip - transaction.getHeight() + 1;
            }
        }
        return 0;
    }

    //The sign, save and broadcast sequence

    /**
     * Builds the commit from the funding wallet, confirms with the user, signs (one password per wallet file), saves the
     * pending record, broadcasts the commit and, for Sign now, the reveal.
     */
    public static void mint(Window owner, Wallet funding, Wallet taproot, Request request, Consumer<String> confirmText) {
        Optional<String> refusal = refusal(funding).or(() -> refusal(taproot));
        if(refusal.isPresent()) {
            AppServices.showErrorDialog("Cannot mint", refusal.get());
            return;
        }
        WalletNode node = revealNode(taproot);
        InscriptionMint.Plan plan;
        WalletTransaction commit;
        try {
            plan = plan(node, request.inscription(), request.etching(), request.feeRate());
            Wallet master = funding.isNested() ? funding.getMasterWallet() : funding;
            CollectiblesManager.Snapshot snapshot = CollectiblesManager.get().getSnapshot(master);
            if(snapshot == null || !snapshot.enabled()) {
                throw new IllegalStateException("This wallet's outputs have not been checked for inscriptions yet, so no output can safely pay for the mint.");
            }
            commit = InscriptionMint.commit(funding, plan, CollectibleSend.feeCandidates(funding, snapshot), request.feeRate(), AppServices.getMinimumRelayFeeRate());
        } catch(Exception e) {
            AppServices.showErrorDialog("Cannot mint", e.getMessage());
            return;
        }
        if(!confirm(owner, funding, taproot, request, plan, commit)) {
            return;
        }

        Wallet holder = pendingHolder(taproot);
        boolean needsReveal = request.signNow();
        withDecrypted(owner, master(funding), decryptedFunding -> {
            Transaction commitTx;
            try {
                commitTx = signCommit(child(decryptedFunding, funding), commit);
            } catch(Exception e) {
                AppServices.showErrorDialog("Cannot sign the commit", e.getMessage());
                return;
            } finally {
                if(!needsReveal || master(taproot) != master(funding)) {
                    decryptedFunding.clearPrivate();
                }
            }
            if(!needsReveal) {
                broadcast(owner, holder, plan, node, commitTx, commit.getFee(), null, request.etching(), confirmText);
                return;
            }
            Consumer<Wallet> signReveal = decryptedTaprootMaster -> {
                Transaction reveal;
                try {
                    reveal = signReveal(child(decryptedTaprootMaster, taproot), node.getDerivationPath(), plan, commitTx.getOutputs().getFirst());
                } catch(Exception e) {
                    AppServices.showErrorDialog("Cannot sign the reveal", e.getMessage());
                    return;
                } finally {
                    decryptedTaprootMaster.clearPrivate();
                }
                broadcast(owner, holder, plan, node, commitTx, commit.getFee(), reveal, null, confirmText);
            };
            if(master(taproot) == master(funding)) {
                signReveal.accept(decryptedFunding);
            } else {
                withDecrypted(owner, master(taproot), signReveal);
            }
        });
    }

    /** Finishes a pending mint: fetches its commit, checks it, signs the reveal and broadcasts it. */
    public static void finish(Window owner, Wallet taproot, PendingMint mint, byte[] content, Consumer<String> done) {
        InscriptionMint.Plan plan;
        try {
            plan = rebuild(mint, taproot, content);
        } catch(Exception e) {
            AppServices.showErrorDialog("Cannot finish the mint", e.getMessage());
            return;
        }
        ElectrumServer.TransactionReferenceService lookup = new ElectrumServer.TransactionReferenceService(Set.of(mint.getCommitTxid()));
        lookup.setOnSucceeded(event -> {
            BlockTransaction commit = lookup.getValue().get(mint.getCommitTxid());
            if(commit == null) {
                AppServices.showErrorDialog("Cannot finish the mint", "The server does not know the commit " + mint.getCommitTxid()
                        + ". It may not have been broadcast, or it was replaced. If it never confirms, Forget this pending mint.");
                return;
            }
            Integer tip = AppServices.getCurrentBlockHeight();
            int confirmations = commit.getHeight() > 0 && tip != null ? tip - commit.getHeight() + 1 : 0;
            Optional<String> wait = revealWait(mint, confirmations);
            if(wait.isPresent()) {
                AppServices.showAlertDialog("Not ready yet", wait.get(), Alert.AlertType.INFORMATION, ButtonType.OK);
                return;
            }
            List<TransactionOutput> outputs = commit.getTransaction().getOutputs();
            TransactionOutput commitOutput = mint.getCommitVout() < outputs.size() ? outputs.get(mint.getCommitVout()) : null;
            try {
                checkCommit(mint, plan, commitOutput);
            } catch(IllegalArgumentException e) {
                AppServices.showErrorDialog("Cannot finish the mint", e.getMessage());
                return;
            }
            withDecrypted(owner, master(taproot), decrypted -> {
                Transaction reveal;
                try {
                    reveal = signReveal(child(decrypted, taproot), mint.getKeyPath(), plan, commitOutput);
                } catch(Exception e) {
                    AppServices.showErrorDialog("Cannot sign the reveal", e.getMessage());
                    return;
                } finally {
                    decrypted.clearPrivate();
                }
                broadcastReveal(pendingHolder(taproot), mint, reveal, done);
            });
        });
        lookup.setOnFailed(event -> AppServices.showErrorDialog("Cannot finish the mint", "Could not fetch the commit: " + lookup.getException().getMessage()));
        lookup.start();
    }

    /** Removes a pending mint after the user agrees that its commit output is given up. */
    public static void forget(Wallet taproot, PendingMint mint) {
        Optional<ButtonType> answer = AppServices.showWarningDialog("Forget pending mint?", "The commit output (" + mint.getCommitValue()
                + " litoshis) can only be spent by revealing this exact content. Forgetting the mint removes what is needed to rebuild it; "
                + "the content alone is then not enough, since the reveal key's path is lost too.\n\nForget it anyway?", ButtonType.CANCEL, ButtonType.OK);
        if(answer.isPresent() && answer.get() == ButtonType.OK) {
            removePending(pendingHolder(taproot), mint);
        }
    }

    private static Wallet master(Wallet wallet) {
        return wallet.isMasterWallet() ? wallet : wallet.getMasterWallet();
    }

    /** The copy of the wallet inside a decrypted copy of its master. */
    private static Wallet child(Wallet decryptedMaster, Wallet wallet) {
        return wallet.isMasterWallet() ? decryptedMaster : decryptedMaster.getChildWallet(wallet.getName());
    }

    private static Transaction signCommit(Wallet decryptedFunding, WalletTransaction commit) throws Exception {
        PSBT psbt = commit.createPSBT();
        decryptedFunding.sign(psbt);
        decryptedFunding.finalise(psbt);
        return psbt.extractTransaction();
    }

    private static Transaction signReveal(Wallet decryptedTaproot, String keyPath, InscriptionMint.Plan plan, TransactionOutput commitOutput) throws MnemonicException {
        ECKey key = decryptedTaproot.getKeystores().getFirst().getKey(new WalletNode(decryptedTaproot, keyPath));
        return InscriptionMint.reveal(plan, commitOutput, key);
    }

    /** Hands a decrypted copy of the master (with its children) to then, asking for the password when the file is encrypted. */
    private static void withDecrypted(Window owner, Wallet master, Consumer<Wallet> then) {
        if(!master.isEncrypted()) {
            then.accept(master.copy());
            return;
        }
        Storage storage = AppServices.get().getOpenWallets().get(master);
        WalletPasswordDialog dlg = new WalletPasswordDialog(master.getMasterName(), WalletPasswordDialog.PasswordRequirement.LOAD);
        dlg.initOwner(owner);
        Optional<SecureString> password = dlg.showAndWait();
        if(password.isEmpty()) {
            return;
        }
        String walletId = storage.getWalletId(master);
        Storage.DecryptWalletService decryptWalletService = new Storage.DecryptWalletService(master.copy(), password.get());
        decryptWalletService.setOnSucceeded(event -> {
            EventManager.get().post(new StorageEvent(walletId, TimedEvent.Action.END, "Done"));
            then.accept(decryptWalletService.getValue());
        });
        decryptWalletService.setOnFailed(event -> {
            EventManager.get().post(new StorageEvent(walletId, TimedEvent.Action.END, "Failed"));
            AppServices.showErrorDialog("Incorrect Password", decryptWalletService.getException().getMessage());
        });
        EventManager.get().post(new StorageEvent(walletId, TimedEvent.Action.START, "Decrypting wallet..."));
        decryptWalletService.start();
    }

    private static void broadcast(Window owner, Wallet holder, InscriptionMint.Plan plan, WalletNode node, Transaction commitTx, long commitFee,
                                  Transaction reveal, Runestone.Etching etching, Consumer<String> done) {
        PendingMint mint = pendingRecord(plan, node, commitTx, etching);
        addPending(holder, mint);
        ElectrumServer.BroadcastTransactionService commitBroadcast = new ElectrumServer.BroadcastTransactionService(commitTx, commitFee);
        commitBroadcast.setOnSucceeded(event -> {
            if(reveal != null) {
                broadcastReveal(holder, mint, reveal, done);
            } else if(etching != null) {
                done.accept("Commit " + commitTx.getTxId() + " broadcast. The etching of " + etching.name() + " can be revealed from Pending mints once the commit is "
                        + InscriptionMint.COMMIT_CONFIRMATIONS + " blocks deep. Until the reveal confirms, someone else can etch the same name first.");
            } else {
                done.accept("Commit " + commitTx.getTxId() + " broadcast. Finish the mint from Pending mints with the same content.");
            }
        });
        commitBroadcast.setOnFailed(event -> {
            log.error("Error broadcasting mint commit", commitBroadcast.getException());
            removePending(holder, mint);
            AppServices.showErrorDialog("Could not broadcast the commit", commitBroadcast.getException().getMessage());
        });
        commitBroadcast.start();
    }

    private static void broadcastReveal(Wallet holder, PendingMint mint, Transaction reveal, Consumer<String> done) {
        ElectrumServer.BroadcastTransactionService revealBroadcast = new ElectrumServer.BroadcastTransactionService(reveal, mint.getCommitValue() - CollectibleTransactions.POSTAGE);
        revealBroadcast.setOnSucceeded(event -> {
            removePending(holder, mint);
            done.accept("Reveal " + reveal.getTxId() + " broadcast. The " + (mint.getKind() == PendingMint.Kind.ETCH ? "rune and its inscription appear" : "inscription appears")
                    + " in the taproot wallet once ord indexes it.");
        });
        revealBroadcast.setOnFailed(event -> {
            log.error("Error broadcasting mint reveal", revealBroadcast.getException());
            AppServices.showErrorDialog("Could not broadcast the reveal", "The commit is on its way, the reveal is not: " + revealBroadcast.getException().getMessage()
                    + "\n\nFinish it later from Pending mints with the same content.");
        });
        revealBroadcast.start();
    }

    private static void addPending(Wallet holder, PendingMint mint) {
        holder.getPendingMints().add(mint);
        changed(holder);
    }

    private static void removePending(Wallet holder, PendingMint mint) {
        holder.getPendingMints().removeIf(pending -> pending == mint || pending.getCommitTxid().equals(mint.getCommitTxid()));
        changed(holder);
    }

    private static void changed(Wallet holder) {
        EventManager.get().post(new WalletPendingMintsChangedEvent(holder));
        //The wallet form writes the marked change on its next background update, so ask for one now
        EventManager.get().post(new WalletDataChangedEvent(holder));
    }

    private static boolean confirm(Window owner, Wallet funding, Wallet taproot, Request request, InscriptionMint.Plan plan, WalletTransaction commit) {
        StringBuilder text = new StringBuilder();
        Inscription inscription = request.inscription();
        if(request.etching() != null) {
            Runestone.Etching etching = request.etching();
            text.append("Etch the rune ").append(etching.name()).append(" (divisibility ").append(etching.divisibility());
            if(etching.symbol() != null) {
                text.append(", symbol ").append(Character.toString(etching.symbol()));
            }
            text.append(").\n");
            if(etching.premine() != null && etching.premine().signum() > 0) {
                text.append("Premine: ").append(etching.premine()).append(" base units to the taproot wallet.\n");
            }
            if(etching.terms() != null) {
                text.append("Open mints: ").append(etching.terms().cap()).append(" of ").append(etching.terms().amount()).append(" base units each.\n");
            }
        }
        text.append("Inscription: ").append(inscription.body().length).append(" bytes of ").append(inscription.contentType()).append(".\n");
        text.append("Lands at: ").append(plan.destination()).append(" in ").append(taproot.getFullDisplayName()).append(".\n\n");
        text.append("Commit (paid from ").append(funding.getFullDisplayName()).append("): fee ").append(commit.getFee()).append(" litoshis.\n");
        text.append("Reveal: fee ").append(plan.revealFee()).append(" litoshis, postage ").append(CollectibleTransactions.POSTAGE).append(" litoshis.\n");
        text.append("Total: ").append(commit.getFee() + plan.commitValue()).append(" litoshis.\n\n");
        if(request.etching() != null) {
            text.append("The reveal can be signed once the commit is ").append(InscriptionMint.COMMIT_CONFIRMATIONS)
                    .append(" blocks deep (about 15 minutes). Until the reveal confirms, someone else can etch the same name first.\n");
        } else if(request.signNow()) {
            text.append("The commit and the reveal are signed and broadcast now.\n");
        } else {
            text.append("Only the commit is broadcast now. To finish, choose exactly the same content again from Pending mints.\n");
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "", ButtonType.CANCEL, ButtonType.OK);
        alert.initOwner(owner);
        alert.setTitle("Confirm");
        alert.setHeaderText(request.etching() != null ? "Etch rune?" : "Mint inscription?");
        javafx.scene.control.TextArea area = new javafx.scene.control.TextArea(text.toString());
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefRowCount(12);
        alert.getDialogPane().setContent(area);
        Optional<ButtonType> answer = alert.showAndWait();
        return answer.isPresent() && answer.get() == ButtonType.OK;
    }
}
