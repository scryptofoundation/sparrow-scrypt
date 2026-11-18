package com.sparrowwallet.sparrow.wallet.collectibles;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.ordinals.DoginalsMint;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionOutPoint;
import com.sparrowwallet.drongo.protocol.TransactionOutput;
import com.sparrowwallet.drongo.wallet.*;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.event.WalletDataChangedEvent;
import com.sparrowwallet.sparrow.event.WalletPendingMintsChangedEvent;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.net.ElectrumServer;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;

/**
 * Minting an inscription on Dogecoin, Pepecoin or Junkcoin, which have no taproot: the Doginals chain of
 * {@link DoginalsMint}, paid from and signed by the open software wallet with its next free receive key. The pending
 * record goes to the wallet's file before tx 0 is broadcast. Batch 0 (tx 0 and up to 23 steps) is broadcast at once; each
 * later batch of up to 24 waits until the one before confirms, and the user presses Continue mint for it. End mint gives
 * up and returns the locked value to the wallet.
 *
 * Which steps exist is read from the chain each time, never from the record: every step is rebuilt from the funding
 * outpoint and the key (deterministic signatures), and its txid looked up. A step's input spent by anything else (a
 * relayer may rewrite a piece, which the redeem script does not cover) stops the mint.
 *
 * The decisions are plain methods, tested without the UI; the sign, save and broadcast sequence is below them.
 */
public final class DoginalsFlow {
    private static final Logger log = LoggerFactory.getLogger(DoginalsFlow.class);

    private DoginalsFlow() {}

    /** What the mint dialog asks for. */
    public record Request(String contentType, byte[] body, Address destination, double feeRate) {}

    /** The record and server calls of one run, so the order (record first) is tested without a server. */
    public interface Io {
        void save(Wallet holder) throws Exception;

        void broadcast(Transaction transaction, long fee) throws Exception;
    }

    /** How far a run got: the last step broadcast (0 for tx 0 alone, -1 for none), and why it stopped early, if it did. */
    public record Outcome(int stepsBroadcast, String error) {}

    public static boolean usesDoginals(Coin coin) {
        return coin == Coin.DOGECOIN || coin == Coin.PEPECOIN || coin == Coin.JUNKCOIN;
    }

    /** Why this wallet cannot mint now, if it cannot. */
    public static Optional<String> refusal(Wallet wallet, CollectiblesManager.Snapshot snapshot, Coin focused) {
        Coin coin = wallet.getCoin();
        if(!usesDoginals(coin)) {
            return Optional.of("Doginals are minted from Dogecoin, Pepecoin and Junkcoin wallets only.");
        }
        Optional<String> keys = MintFlow.keyRefusal(wallet);
        if(keys.isPresent()) {
            return keys;
        }
        if(snapshot == null || !snapshot.enabled()) {
            return Optional.of("Minting needs the " + coin.getCapitalizedName() + " inscriptions server on, so that no output holding an inscription pays for the mint. "
                    + "Turn it on in Preferences > Server > Inscriptions.");
        }
        return CollectibleSend.focusProblem(focused, wallet);
    }

    /** The account that keeps this wallet's pending mints and whose inscription check pays for them: a nested wallet's master, else the wallet itself. */
    public static Wallet pendingHolder(Wallet wallet) {
        return wallet.isNested() ? wallet.getMasterWallet() : wallet;
    }

    /** The server's answer to which of these txids it has. */
    public interface Lookup {
        Map<Sha256Hash, BlockTransaction> find(Set<Sha256Hash> txids) throws Exception;
    }

    //Kept under the server batch page (100): one page with an unknown txid loses the whole request's answers
    static final int LOOKUP_CHUNK = 50;

    /**
     * The funding tx and the steps the server has, asked in chain order a chunk at a time, stopping at the first chunk
     * with a step it does not have: the steps after it cannot exist.
     */
    public static Map<Sha256Hash, BlockTransaction> scanChain(Sha256Hash funding, List<Sha256Hash> expected, Lookup lookup) throws Exception {
        Map<Sha256Hash, BlockTransaction> found = new HashMap<>();
        int from = 0;
        boolean first = true;
        while(first || from < expected.size()) {
            Set<Sha256Hash> request = new LinkedHashSet<>();
            if(first) {
                request.add(funding);
            }
            int to = Math.min(expected.size(), from + LOOKUP_CHUNK - request.size());
            request.addAll(expected.subList(from, to));
            Map<Sha256Hash, BlockTransaction> answer = lookup.find(request);
            boolean missing = false;
            for(Sha256Hash txid : request) {
                BlockTransaction tx = answer.get(txid);
                if(tx != null && tx.getTransaction() != null) {
                    found.put(txid, tx);
                } else {
                    missing = true;
                }
            }
            if(missing) {
                break;
            }
            first = false;
            from = to;
        }
        return found;
    }

    /** Whether a mint is done: every step is on chain and the last one has confirmed. Until then its record stays. */
    public static boolean complete(int nextStep, int steps, boolean lastConfirmed) {
        return nextStep > steps && lastConfirmed;
    }

    /** Whether a record can be forgotten: its funding tx is unknown to the server and to the wallet, so no coins of it moved. */
    public static boolean forgettable(boolean serverKnowsFunding, boolean walletHasFunding) {
        return !serverKnowsFunding && !walletHasFunding;
    }

    /** The output of a step a relayer changed: it still pays the next piece's script, since the signature covers outputs. */
    public static Optional<TransactionOutput> changedStepOutput(Collection<TransactionOutput> unspentAtNextScript) {
        return unspentAtNextScript.stream().filter(output -> output.getParent() != null).findFirst();
    }

    /** The Pending tab's status of a Doginals record, from the wallet's history and the record's step count. */
    public static String status(PendingMint mint, Wallet wallet) {
        if(!wallet.getTransactions().containsKey(mint.getCommitTxid())) {
            return "Funding not seen on the network yet";
        }
        int steps = DoginalsMint.pieces(mint.getContentType(), mint.getContent()).size();
        if(mint.getStepsBroadcast() >= steps) {
            return "All " + (steps + 1) + " transactions broadcast. Press Continue mint once the last confirms, to finish";
        }
        if(mint.getStepsBroadcast() == 0) {
            return "Funding broadcast, continue once it confirms";
        }
        return "Up to step " + mint.getStepsBroadcast() + " of " + steps + " broadcast, continue once the batch confirms";
    }

    /** The first fresh receive node that no pending mint holds: its key signs the whole chain. */
    public static WalletNode keyNode(Wallet wallet) {
        Set<String> taken = new HashSet<>();
        pendingHolder(wallet).getPendingMints().forEach(mint -> taken.add(mint.getKeyPath()));
        WalletNode node = wallet.getFreshNode(KeyPurpose.RECEIVE);
        while(taken.contains(node.getDerivationPath())) {
            node = wallet.getFreshNode(KeyPurpose.RECEIVE, node);
        }
        return node;
    }

    public static DoginalsMint.Chain chain(Wallet wallet, WalletNode keyNode, String contentType, byte[] body, Address destination, double feeRate) {
        return new DoginalsMint.Chain(contentType, body, keyNode.getPubKey(), destination, feeRate, wallet.getCoin());
    }

    /** The chain again from its record, refusing a record whose content does not give the script tx 0 pays. */
    public static DoginalsMint.Chain chain(Wallet wallet, PendingMint mint) {
        if(mint.getKind() != PendingMint.Kind.DOGINAL) {
            throw new IllegalArgumentException("This is not a Doginals mint");
        }
        Coin previous = Coin.beginScope(wallet.getCoin());
        try {
            Address destination = Address.fromString(mint.getDestination());
            DoginalsMint.Chain chain = chain(wallet, new WalletNode(wallet, mint.getKeyPath()), mint.getContentType(), mint.getContent(), destination, mint.getFeeRate());
            if(!Arrays.equals(firstScriptHash(chain), mint.getScriptHash())) {
                throw new IllegalArgumentException("This pending mint's record does not match its content, so its chain cannot be rebuilt.");
            }
            return chain;
        } catch(com.sparrowwallet.drongo.address.InvalidAddressException e) {
            throw new IllegalArgumentException("This pending mint's destination " + mint.getDestination() + " is not a " + wallet.getCoin().getCapitalizedName() + " address");
        } finally {
            Coin.endScope(previous);
        }
    }

    private static byte[] firstScriptHash(DoginalsMint.Chain chain) {
        return Sha256Hash.hash(DoginalsMint.redeemScript(chain.pubKey(), chain.pieces().getFirst()).getProgram());
    }

    public static PendingMint pendingRecord(DoginalsMint.Chain chain, WalletNode keyNode, Sha256Hash fundingTxid) {
        return PendingMint.doginal(keyNode.getDerivationPath(), firstScriptHash(chain), fundingTxid, chain.carriedValue(), chain.contentType(), chain.body(),
                chain.feeRate(), address(chain.coin(), chain.destination()));
    }

    //An address keeps only its hash and prints with the prefix of the coin in scope
    private static String address(Coin coin, Address address) {
        Coin previous = Coin.beginScope(coin);
        try {
            return address.toString();
        } finally {
            Coin.endScope(previous);
        }
    }

    /** Tx 0, paid only from outputs the inscriptions server found clean. */
    public static WalletTransaction fund(Wallet wallet, DoginalsMint.Chain chain, CollectiblesManager.Snapshot snapshot, double minRelayFeeRate)
            throws InsufficientFundsException {
        Coin previous = Coin.beginScope(wallet.getCoin());
        try {
            return DoginalsMint.fund(wallet, chain, CollectibleSend.feeCandidates(wallet, snapshot), minRelayFeeRate);
        } finally {
            Coin.endScope(previous);
        }
    }

    /** The first step (1-based) whose txid the chain does not have; steps() + 1 when it has them all. */
    public static int nextStep(List<Sha256Hash> expected, Set<Sha256Hash> onChain) {
        for(int s = 1; s <= expected.size(); s++) {
            if(!onChain.contains(expected.get(s - 1))) {
                return s;
            }
        }
        return expected.size() + 1;
    }

    /** Why to wait before broadcasting from this step: it starts a new batch and the batch before has not confirmed. */
    public static Optional<String> continueWait(int nextStep, boolean previousConfirmed) {
        if(nextStep >= DoginalsMint.BATCH && nextStep % DoginalsMint.BATCH == 0 && !previousConfirmed) {
            return Optional.of("The previous batch has not confirmed yet. Each batch is broadcast once the one before it confirms, "
                    + "so that no node refuses a chain of more than " + DoginalsMint.BATCH + " unconfirmed transactions. Try Continue mint again after the next block.");
        }
        return Optional.empty();
    }

    /** Stops the mint when the output step s would spend is no longer unspent: something other than this mint's step spent it. */
    public static Optional<String> spentElsewhere(int step, TransactionOutPoint previous, Collection<TransactionOutput> unspentAtScript) {
        boolean unspent = unspentAtScript.stream().anyMatch(output -> output.getParent() != null && ((Transaction)output.getParent()).getTxId().equals(previous.getHash())
                && output.getIndex() == previous.getIndex());
        if(unspent) {
            return Optional.empty();
        }
        return Optional.of("Step " + step + " cannot be made: the output it spends was spent by a different transaction, so a step of this mint on chain is not the one "
                + "this app signed (a relayer may have changed it). The mint stops here; nothing more is signed for it.");
    }

    /**
     * Broadcasts the transactions in order, txs[0] being step firstStep (0 for tx 0). A new record is saved before the
     * first broadcast and the record's step count after each one. The first failure stops the run and keeps the record.
     */
    public static Outcome run(Wallet holder, PendingMint record, boolean newRecord, List<Transaction> txs, List<Long> fees, int firstStep, Io io) {
        if(newRecord) {
            holder.getPendingMints().add(record);
            try {
                io.save(holder);
            } catch(Exception e) {
                log.error("Error saving pending mint", e);
                holder.getPendingMints().remove(record);
                return new Outcome(-1, "The pending mint could not be saved, so nothing was broadcast: " + e.getMessage());
            }
        }
        int done = firstStep - 1;
        for(int i = 0; i < txs.size(); i++) {
            int step = firstStep + i;
            try {
                io.broadcast(txs.get(i), fees.get(i));
            } catch(Exception e) {
                log.error("Error broadcasting Doginals step " + step, e);
                //The server may have taken it though the answer was lost, so the record stays and Continue mint reads the chain
                return new Outcome(done, (step == 0 ? "The funding transaction" : "Step " + step) + " could not be broadcast: " + e.getMessage()
                        + "\n\nThe mint stays under Pending mints. Continue mint later picks up from the first step the network does not have.");
            }
            done = step;
            replace(holder, record.withStepsBroadcast(Math.max(done, 0)));
            try {
                io.save(holder);
            } catch(Exception e) {
                //Only the hint is stale: the chain says which steps exist
                log.warn("Could not save the pending mint's step count", e);
            }
        }
        return new Outcome(done, null);
    }

    private static void replace(Wallet holder, PendingMint mint) {
        holder.getPendingMints().replaceAll(pending -> pending.getKind() == PendingMint.Kind.DOGINAL && pending.getCommitTxid().equals(mint.getCommitTxid()) ? mint : pending);
    }

    /** Every step, signed, from tx 0's output 0. */
    static List<Transaction> steps(DoginalsMint.Chain chain, Sha256Hash fundingTxid, ECKey key, int upTo) {
        List<Transaction> steps = new ArrayList<>();
        TransactionOutPoint previous = new TransactionOutPoint(fundingTxid, 0);
        long value = chain.carriedValue();
        for(int s = 1; s <= upTo; s++) {
            Transaction step = DoginalsMint.step(chain, s, previous, value, key);
            steps.add(step);
            previous = new TransactionOutPoint(step.getTxId(), 0);
            value = step.getOutputs().getFirst().getValue();
        }
        return steps;
    }

    static String feeUnit(Coin coin) {
        return coin.getSubunit() + "/vB";
    }

    public static String confirmText(Wallet wallet, DoginalsMint.Chain chain, WalletTransaction funding) {
        Coin coin = wallet.getCoin();
        String unit = coin.getSubunit();
        int transactions = chain.steps() + 1;
        int batches = chain.batches();
        long total = funding.getFee() + chain.carriedValue();
        StringBuilder text = new StringBuilder();
        text.append("Inscription: ").append(String.format("%,d", chain.body().length)).append(" bytes of ").append(chain.contentType()).append(".\n");
        text.append("Lands at: ").append(address(coin, chain.destination())).append(".\n\n");
        text.append("The mint is ").append(transactions).append(" transactions in ").append(batches).append(batches == 1 ? " batch" : " batches").append(". ");
        if(batches == 1) {
            text.append("All of them are broadcast now.\n");
        } else {
            text.append("The first batch is broadcast now. After each batch confirms, press Continue mint under Pending mints for the next one, "
                    + "until all ").append(batches).append(" are done.\n");
        }
        text.append("Fee rate: ").append(chain.feeRate()).append(' ').append(feeUnit(coin)).append(".\n");
        text.append("Funding transaction fee: ").append(String.format("%,d", funding.getFee())).append(' ').append(unit).append(".\n");
        text.append("Locked for the chain: ").append(String.format("%,d", chain.carriedValue())).append(' ').append(unit).append(" (")
                .append(String.format("%,d", DoginalsMint.POSTAGE)).append(' ').append(unit).append(" postage for the inscription plus the fee of every later transaction).\n");
        if(coin.getSoftDustSurcharge() > 0) {
            text.append("The fees include ").append(coin.getCapitalizedName()).append("'s ").append(String.format("%,d", coin.getSoftDustSurcharge())).append(' ').append(unit)
                    .append(" charge for each output under ").append(String.format("%,d", coin.getDustLimit())).append(' ').append(unit).append(".\n");
        }
        text.append("Total: ").append(String.format("%,d", total)).append(' ').append(unit).append(" (").append(String.format("%.8f", total / 100_000_000d)).append(' ')
                .append(coin.getTicker()).append(").\n\n");
        text.append("Until the last transaction confirms, End mint under Pending mints returns what is still locked to this wallet.");
        return text.toString();
    }

    //The sign, save and broadcast sequence

    /** Builds tx 0, confirms, signs tx 0 and batch 0, saves the record and broadcasts them in order. */
    public static void mint(Window owner, Wallet wallet, Request request, Consumer<String> done) {
        Coin coin = wallet.getCoin();
        Wallet holder = pendingHolder(wallet);
        CollectiblesManager.Snapshot snapshot = CollectiblesManager.get().getSnapshot(holder);
        Optional<String> refusal = refusal(wallet, snapshot, Coin.get());
        if(refusal.isPresent()) {
            AppServices.showErrorDialog("Cannot mint", refusal.get());
            return;
        }
        Storage storage = AppServices.get().getOpenWallets().get(holder);
        if(storage == null) {
            AppServices.showErrorDialog("Cannot mint", "The wallet " + holder.getFullDisplayName() + " is not open, so the pending mint cannot be saved.");
            return;
        }
        double minRelayFeeRate = CollectibleSend.minRelayFeeRate(coin, ElectrumServer.getConnectedCoin(), AppServices.getMinimumRelayFeeRate());
        WalletNode keyNode;
        DoginalsMint.Chain chain;
        WalletTransaction funding;
        Coin previous = Coin.beginScope(coin);
        try {
            keyNode = keyNode(wallet);
            chain = chain(wallet, keyNode, request.contentType(), request.body(), request.destination(), Math.max(request.feeRate(), minRelayFeeRate));
            funding = fund(wallet, chain, snapshot, minRelayFeeRate);
        } catch(Exception e) {
            AppServices.showErrorDialog("Cannot mint", e.getMessage());
            return;
        } finally {
            Coin.endScope(previous);
        }
        if(!confirm(owner, "Mint inscription?", confirmText(wallet, chain, funding))) {
            return;
        }

        MintFlow.withDecrypted(owner, List.of(wallet), decrypted -> {
            List<Transaction> txs = new ArrayList<>();
            List<Long> fees = new ArrayList<>();
            Coin scope = Coin.beginScope(coin);
            try {
                Transaction tx0 = MintFlow.signCommit(decrypted.get(wallet), funding);
                ECKey key = decrypted.get(wallet).getKeystores().getFirst().getKey(new WalletNode(decrypted.get(wallet), keyNode.getDerivationPath()));
                txs.add(tx0);
                fees.add(funding.getFee());
                List<Integer> batch = DoginalsMint.batch(chain, 1);
                for(Transaction step : steps(chain, tx0.getTxId(), key, batch.getLast())) {
                    txs.add(step);
                    fees.add(chain.fee(txs.size() - 1));
                }
            } catch(Exception e) {
                AppServices.showErrorDialog("Cannot sign the mint", e.getMessage());
                return;
            } finally {
                Coin.endScope(scope);
                decrypted.values().forEach(Wallet::clearPrivate);
            }
            PendingMint record = pendingRecord(chain, keyNode, txs.getFirst().getTxId());
            Sha256Hash inscription = DoginalsMint.inscriptionTxid(txs.get(1));
            background("Mint Doginal", coin, () -> run(holder, record, true, txs, fees, 0, io(storage)), outcome -> {
                changed(holder);
                if(outcome.error() != null) {
                    AppServices.showErrorDialog(outcome.stepsBroadcast() < 0 ? "Cannot mint" : "Could not broadcast the mint", outcome.error());
                } else if(outcome.stepsBroadcast() == chain.steps()) {
                    //The record stays until the last step confirms: a dropped step would otherwise leave locked coins with nothing to rebuild them from
                    done.accept("Inscription " + inscription + "i0: all " + (chain.steps() + 1) + " transactions broadcast. Once they confirm, press Continue mint under "
                            + "Pending mints to finish. It shows in this wallet once the inscriptions server indexes it.");
                } else {
                    done.accept("Batch 1 of " + chain.batches() + " broadcast (" + txs.size() + " of " + (chain.steps() + 1) + " transactions). The inscription will be "
                            + inscription + "i0. Once this batch confirms, press Continue mint under Pending mints.");
                }
            });
        });
    }

    private enum Problem { NONE, FUNDING_UNKNOWN, SPENT_ELSEWHERE, WAIT, BROADCAST }

    /** The state of a mint on chain, read with its key: the steps it has, and what the next step spends. */
    private record ChainState(List<Transaction> expected, int next, TransactionOutPoint previous, long previousValue, boolean lastConfirmed, Problem problem,
                              String message) {}

    private static ChainState readChain(DoginalsMint.Chain chain, PendingMint mint, ECKey key, Wallet wallet, ElectrumServer server) throws Exception {
        List<Transaction> expected = steps(chain, mint.getCommitTxid(), key, chain.steps());
        List<Sha256Hash> txids = expected.stream().map(Transaction::getTxId).toList();
        Map<Sha256Hash, BlockTransaction> found = scanChain(mint.getCommitTxid(), txids, request -> server.getReferencedTransactions(request, null));
        if(!found.containsKey(mint.getCommitTxid())) {
            String message = forgettable(false, wallet.getTransactions().containsKey(mint.getCommitTxid()))
                    ? "The funding transaction " + mint.getCommitTxid() + " never reached the network, so none of its coins were spent."
                    : "The server does not know the funding transaction " + mint.getCommitTxid() + " yet. Try again once it shows up.";
            return new ChainState(expected, 0, null, 0, false, Problem.FUNDING_UNKNOWN, message);
        }
        int next = nextStep(txids, found.keySet());
        if(next > chain.steps()) {
            return new ChainState(expected, next, null, 0, found.get(txids.getLast()).getHeight() > 0, Problem.NONE, null);
        }
        TransactionOutPoint previous = next == 1 ? new TransactionOutPoint(mint.getCommitTxid(), 0) : new TransactionOutPoint(txids.get(next - 2), 0);
        long previousValue = next == 1 ? mint.getCommitValue() : expected.get(next - 2).getOutputs().getFirst().getValue();
        Optional<String> spent = spentElsewhere(next, previous, server.getUtxos(DoginalsMint.spentScriptAddress(chain, next)));
        if(spent.isPresent()) {
            return new ChainState(expected, next, previous, previousValue, false, Problem.SPENT_ELSEWHERE, spent.get());
        }
        Optional<String> wait = continueWait(next, found.get(previous.getHash()).getHeight() > 0);
        return new ChainState(expected, next, previous, previousValue, false, wait.isPresent() ? Problem.WAIT : Problem.NONE, wait.orElse(null));
    }

    /** Signs and broadcasts the next batch, from the first step the chain does not have; once all are confirmed, clears the record. */
    public static void continueMint(Window owner, Wallet wallet, PendingMint mint, Consumer<String> done) {
        Coin coin = wallet.getCoin();
        Wallet holder = pendingHolder(wallet);
        Storage storage = AppServices.get().getOpenWallets().get(holder);
        DoginalsMint.Chain chain;
        try {
            Optional<String> refusal = refusal(wallet, CollectiblesManager.get().getSnapshot(holder), Coin.get());
            if(refusal.isPresent()) {
                throw new IllegalStateException(refusal.get());
            }
            chain = chain(wallet, mint);
        } catch(Exception e) {
            AppServices.showErrorDialog("Cannot continue the mint", e.getMessage());
            return;
        }
        withKey(owner, wallet, mint, key -> background("Continue Doginal", coin, () -> {
            ChainState state = readChain(chain, mint, key, wallet, new ElectrumServer());
            if(state.problem() != Problem.NONE || state.next() > chain.steps()) {
                return state;
            }
            List<Integer> batch = DoginalsMint.batch(chain, state.next());
            List<Transaction> txs = state.expected().subList(batch.getFirst() - 1, batch.getLast());
            List<Long> fees = batch.stream().map(chain::fee).toList();
            Outcome outcome = run(holder, mint, false, txs, fees, batch.getFirst(), io(storage));
            return new ChainState(state.expected(), outcome.stepsBroadcast() + 1, null, 0, false, outcome.error() == null ? Problem.NONE : Problem.BROADCAST,
                    outcome.error());
        }, state -> {
            changed(holder);
            Sha256Hash inscription = DoginalsMint.inscriptionTxid(state.expected().getFirst());
            if(state.problem() != Problem.NONE) {
                AppServices.showAlertDialog("Cannot continue the mint", state.message(), Alert.AlertType.INFORMATION, ButtonType.OK);
            } else if(complete(state.next(), chain.steps(), state.lastConfirmed())) {
                removeRecord(holder, mint, storage);
                done.accept("Inscription " + inscription + "i0 is complete: all " + (chain.steps() + 1)
                        + " transactions are confirmed. It shows in this wallet once the inscriptions server indexes it.");
            } else if(state.next() > chain.steps()) {
                done.accept("All " + (chain.steps() + 1) + " transactions of inscription " + inscription + "i0 are broadcast, but the last has not confirmed yet. "
                        + "Press Continue mint again after the next block to finish.");
            } else {
                done.accept("Broadcast up to step " + (state.next() - 1) + " of " + chain.steps() + ". Once this batch confirms, press Continue mint again.");
            }
        }));
    }

    private record EndResult(String problem, boolean forget) {}

    /**
     * Gives up a mint: spends the output its next step would spend back to a fresh address of this wallet (after a
     * changed step, that step's own output). A record whose funding never reached the network is forgotten instead.
     */
    public static void end(Window owner, Wallet wallet, PendingMint mint) {
        Coin coin = wallet.getCoin();
        Wallet holder = pendingHolder(wallet);
        Storage storage = AppServices.get().getOpenWallets().get(holder);
        DoginalsMint.Chain chain;
        Address back;
        try {
            Optional<String> focus = CollectibleSend.focusProblem(Coin.get(), wallet).or(() -> MintFlow.keyRefusal(wallet));
            if(focus.isPresent()) {
                throw new IllegalStateException(focus.get());
            }
            chain = chain(wallet, mint);
            Coin previous = Coin.beginScope(coin);
            try {
                back = wallet.getFreshNode(KeyPurpose.RECEIVE).getAddress();
            } finally {
                Coin.endScope(previous);
            }
        } catch(Exception e) {
            AppServices.showErrorDialog("Cannot end the mint", e.getMessage());
            return;
        }
        if(!confirm(owner, "End mint?", "Ending gives up this inscription: what is still locked in its chain (at most "
                + String.format("%,d", mint.getCommitValue()) + " " + coin.getSubunit() + ") returns to " + address(coin, back) + " in this wallet, less one fee. "
                + "The pieces already on chain stay there as an incomplete envelope, which is no inscription.\n\nEnd it?")) {
            return;
        }
        withKey(owner, wallet, mint, key -> background("End Doginal", coin, () -> {
            ElectrumServer server = new ElectrumServer();
            ChainState state = readChain(chain, mint, key, wallet, server);
            if(state.problem() == Problem.FUNDING_UNKNOWN) {
                boolean forget = forgettable(false, wallet.getTransactions().containsKey(mint.getCommitTxid()));
                return new EndResult(forget ? null : state.message(), forget);
            }
            if(state.next() > chain.steps()) {
                return new EndResult("All of this mint's transactions are already broadcast, so there is nothing to end.", false);
            }
            Transaction end;
            long fee;
            if(state.problem() == Problem.SPENT_ELSEWHERE) {
                //The changed step still pays the next piece's script: end from its output with the next piece's unlock
                if(state.next() >= chain.steps()) {
                    return new EndResult(state.message(), false);
                }
                Optional<TransactionOutput> changed = changedStepOutput(server.getUtxos(DoginalsMint.spentScriptAddress(chain, state.next() + 1)));
                if(changed.isEmpty()) {
                    return new EndResult(state.message(), false);
                }
                TransactionOutput output = changed.get();
                TransactionOutPoint outpoint = new TransactionOutPoint(((Transaction)output.getParent()).getTxId(), output.getIndex());
                end = DoginalsMint.end(chain, state.next() + 1, outpoint, output.getValue(), key, back);
                fee = output.getValue() - end.getOutputs().getFirst().getValue();
            } else {
                end = DoginalsMint.end(chain, state.next(), state.previous(), state.previousValue(), key, back);
                fee = state.previousValue() - end.getOutputs().getFirst().getValue();
            }
            server.broadcastTransaction(end, fee);
            return new EndResult(null, false);
        }, result -> {
            if(result.forget()) {
                if(confirm(owner, "Forget mint?", "The funding transaction " + mint.getCommitTxid() + " never reached the network, so none of its coins were spent "
                        + "and nothing is locked. Forget this pending mint?")) {
                    removeRecord(holder, mint, storage);
                }
                return;
            }
            if(result.problem() != null) {
                AppServices.showErrorDialog("Cannot end the mint", result.problem());
                return;
            }
            removeRecord(holder, mint, storage);
            AppServices.showAlertDialog("Mint ended", "The locked value is on its way back to " + address(coin, back) + ".", Alert.AlertType.INFORMATION, ButtonType.OK);
        }));
    }

    /** The mint's key, from a decrypted copy that is cleared before then runs; then holds it for one operation only. */
    private static void withKey(Window owner, Wallet wallet, PendingMint mint, Consumer<ECKey> then) {
        MintFlow.withDecrypted(owner, List.of(wallet), decrypted -> {
            ECKey key;
            try {
                Wallet copy = decrypted.get(wallet);
                key = copy.getKeystores().getFirst().getKey(new WalletNode(copy, mint.getKeyPath()));
            } catch(Exception e) {
                AppServices.showErrorDialog("Cannot sign the mint", e.getMessage());
                return;
            } finally {
                decrypted.values().forEach(Wallet::clearPrivate);
            }
            then.accept(key);
        });
    }

    private interface Work<T> {
        T call() throws Exception;
    }

    /** Runs the work off the FX thread under the wallet's coin (servers, addresses and dust rules are per coin), then hands back its result. */
    private static <T> void background(String name, Coin coin, Work<T> work, Consumer<T> then) {
        javafx.concurrent.Task<T> task = new javafx.concurrent.Task<>() {
            @Override
            protected T call() throws Exception {
                Coin previous = Coin.beginScope(coin);
                try {
                    return work.call();
                } finally {
                    Coin.endScope(previous);
                }
            }
        };
        task.setOnSucceeded(event -> then.accept(task.getValue()));
        task.setOnFailed(event -> {
            log.error("Error in " + name, task.getException());
            AppServices.showErrorDialog("The mint could not go on", task.getException().getMessage());
        });
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    private static Io io(Storage storage) {
        return new Io() {
            @Override
            public void save(Wallet holder) throws Exception {
                storage.savePendingMints(holder);
            }

            @Override
            public void broadcast(Transaction transaction, long fee) throws Exception {
                new ElectrumServer().broadcastTransaction(transaction, fee);
            }
        };
    }

    private static void removeRecord(Wallet holder, PendingMint mint, Storage storage) {
        holder.getPendingMints().removeIf(pending -> pending.getKind() == PendingMint.Kind.DOGINAL && pending.getCommitTxid().equals(mint.getCommitTxid()));
        changed(holder);
        if(storage != null) {
            Thread thread = new Thread(() -> {
                try {
                    storage.savePendingMints(holder);
                } catch(Exception e) {
                    log.error("Error saving pending mints", e);
                }
            }, "Save pending mints");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private static void changed(Wallet holder) {
        Platform.runLater(() -> {
            EventManager.get().post(new WalletPendingMintsChangedEvent(holder));
            EventManager.get().post(new WalletDataChangedEvent(holder));
        });
    }

    private static boolean confirm(Window owner, String header, String text) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "", ButtonType.CANCEL, ButtonType.OK);
        alert.initOwner(owner);
        alert.setTitle("Confirm");
        alert.setHeaderText(header);
        javafx.scene.control.TextArea area = new javafx.scene.control.TextArea(text);
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefRowCount(12);
        alert.getDialogPane().setContent(area);
        Optional<ButtonType> answer = alert.showAndWait();
        return answer.isPresent() && answer.get() == ButtonType.OK;
    }
}
