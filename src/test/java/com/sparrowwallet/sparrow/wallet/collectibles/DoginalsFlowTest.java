package com.sparrowwallet.sparrow.wallet.collectibles;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.ordinals.DoginalsMint;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.wallet.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;

public class DoginalsFlowTest {
    private static final List<String> WORDS = List.of("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" "));
    private Coin previousCoin;
    private Wallet wallet;

    private static Wallet wallet(Coin coin, KeystoreSource source) throws Exception {
        DeterministicSeed seed = new DeterministicSeed(new ArrayList<>(WORDS), "", 0, DeterministicSeed.Type.BIP39);
        Wallet wallet = new Wallet(coin.getName());
        wallet.setCoin(coin);
        wallet.setPolicyType(PolicyType.SINGLE_HD);
        wallet.setScriptType(ScriptType.P2PKH);
        Keystore keystore = Keystore.fromSeed(seed, PolicyType.SINGLE_HD, ScriptType.P2PKH.getDefaultDerivation());
        if(source != KeystoreSource.SW_SEED) {
            keystore.setSeed(null);
            keystore.setMasterPrivateExtendedKey(null);
            keystore.setSource(source);
        }
        wallet.getKeystores().add(keystore);
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE_HD, ScriptType.P2PKH, wallet.getKeystores(), 1));
        wallet.getNode(KeyPurpose.RECEIVE).fillToIndex(10);
        wallet.getNode(KeyPurpose.CHANGE).fillToIndex(10);
        return wallet;
    }

    @BeforeEach
    public void setUp() throws Exception {
        previousCoin = Coin.beginScope(Coin.DOGECOIN);
        wallet = wallet(Coin.DOGECOIN, KeystoreSource.SW_SEED);
    }

    @AfterEach
    public void tearDown() {
        Coin.endScope(previousCoin);
    }

    /** Pays each value to a receive node in turn; returns the outputs. */
    private List<BlockTransactionHashIndex> fund(long... values) {
        List<WalletNode> nodes = new ArrayList<>(wallet.getNode(KeyPurpose.RECEIVE).getChildren());
        Transaction tx = new Transaction();
        tx.addInput(Sha256Hash.ZERO_HASH, 3, new Script(new byte[0]));
        for(int i = 0; i < values.length; i++) {
            tx.addOutput(values[i], nodes.get(i).getAddress());
        }
        Sha256Hash hash = tx.getTxId();
        wallet.updateTransactions(Map.of(hash, new BlockTransaction(hash, 100, new Date(), 0L, tx)));
        List<BlockTransactionHashIndex> utxos = new ArrayList<>();
        for(int i = 0; i < values.length; i++) {
            BlockTransactionHashIndex utxo = new BlockTransactionHashIndex(hash, 100, new Date(), 0L, i, values[i]);
            nodes.get(i).getTransactionOutputs().add(utxo);
            utxos.add(utxo);
        }
        return utxos;
    }

    private static CollectiblesManager.Snapshot snapshot(Map<String, CollectibleState> states) {
        return new CollectiblesManager.Snapshot(states, Map.of(), Instant.now(), "http://localhost", null, true, false);
    }

    private DoginalsMint.Chain chain(int bodyLength) {
        byte[] body = new byte[bodyLength];
        new Random(bodyLength).nextBytes(body);
        WalletNode keyNode = DoginalsFlow.keyNode(wallet);
        return DoginalsFlow.chain(wallet, keyNode, "image/png", body, wallet.getFreshNode(KeyPurpose.RECEIVE, keyNode).getAddress(), 1000);
    }

    @Test
    public void onlyDogeFamilyUsesDoginals() {
        Assertions.assertTrue(DoginalsFlow.usesDoginals(Coin.DOGECOIN));
        Assertions.assertTrue(DoginalsFlow.usesDoginals(Coin.PEPECOIN));
        Assertions.assertTrue(DoginalsFlow.usesDoginals(Coin.JUNKCOIN));
        for(Coin coin : List.of(Coin.LITECOIN, Coin.BELLSCOIN, Coin.LUCKYCOIN, Coin.DINGOCOIN)) {
            Assertions.assertFalse(DoginalsFlow.usesDoginals(coin), coin.toString());
        }
    }

    @Test
    public void resumeAtFirstMissingStep() {
        DoginalsMint.Chain chain = chain(40_000);
        Assertions.assertTrue(chain.batches() >= 2);
        List<Sha256Hash> expected = IntStream.rangeClosed(1, chain.steps()).mapToObj(s -> Sha256Hash.of(new byte[] {(byte)s})).toList();
        Sha256Hash funding = Sha256Hash.of(new byte[] {(byte)0xf0});
        Set<Sha256Hash> onChain = new HashSet<>(Set.of(funding));
        onChain.addAll(expected.subList(0, 9));
        //The record says 7 were broadcast, but the chain has 9: the chain decides
        PendingMint record = PendingMint.doginal("m/44'/3'/0'/0/0", new byte[32], funding, chain.carriedValue(), "image/png", chain.body(), 1000, "D").withStepsBroadcast(7);
        Assertions.assertEquals(7, record.getStepsBroadcast());
        Assertions.assertEquals(10, DoginalsFlow.nextStep(expected, onChain));
        Assertions.assertEquals(1, DoginalsFlow.nextStep(expected, Set.of(funding)));
        Assertions.assertEquals(chain.steps() + 1, DoginalsFlow.nextStep(expected, new HashSet<>(expected)));
        //Step 10 is mid-batch: the resumed batch runs to the batch's end, not 24 more
        Assertions.assertEquals(IntStream.rangeClosed(10, 23).boxed().toList(), DoginalsMint.batch(chain, 10));
    }

    @Test
    public void waitWhileLastStepUnconfirmed() {
        //A new batch waits for the one before it to confirm
        Assertions.assertTrue(DoginalsFlow.continueWait(24, false).isPresent());
        Assertions.assertTrue(DoginalsFlow.continueWait(48, false).isPresent());
        Assertions.assertTrue(DoginalsFlow.continueWait(24, false).get().contains("confirm"));
        Assertions.assertTrue(DoginalsFlow.continueWait(24, true).isEmpty());
        //A batch cut short (the app closed mid-batch) finishes at once: its own steps are not a new batch
        Assertions.assertTrue(DoginalsFlow.continueWait(10, false).isEmpty());
        Assertions.assertTrue(DoginalsFlow.continueWait(1, false).isEmpty());
    }

    @Test
    public void malleatedStepStopsTheMint() {
        TransactionOutPoint previous = new TransactionOutPoint(Sha256Hash.of(new byte[] {9}), 0);
        Transaction parent = new Transaction();
        parent.addInput(Sha256Hash.ZERO_HASH, 0, new Script(new byte[0]));
        TransactionOutput other = parent.addOutput(5_000_000, ScriptType.P2PKH.getAddress(new byte[20]));
        //Step 12's input was spent by a transaction other than this mint's step 12
        Optional<String> stop = DoginalsFlow.spentElsewhere(12, previous, List.of(other));
        Assertions.assertTrue(stop.isPresent());
        Assertions.assertTrue(stop.get().contains("12"), stop.get());

        Transaction previousTx = new Transaction();
        previousTx.addInput(Sha256Hash.ZERO_HASH, 0, new Script(new byte[0]));
        TransactionOutput unspent = previousTx.addOutput(5_000_000, ScriptType.P2PKH.getAddress(new byte[20]));
        Assertions.assertTrue(DoginalsFlow.spentElsewhere(12, new TransactionOutPoint(previousTx.getTxId(), 0), List.of(unspent)).isEmpty());
    }

    @Test
    public void recordWrittenBeforeBroadcast() throws Exception {
        DoginalsMint.Chain chain = chain(3_000);
        Transaction tx0 = new Transaction();
        tx0.addInput(Sha256Hash.ZERO_HASH, 1, new Script(new byte[0]));
        tx0.addOutput(chain.carriedValue(), chain.firstScriptAddress());
        Transaction step1 = new Transaction();
        step1.addInput(tx0.getTxId(), 0, new Script(new byte[0]));
        step1.addOutput(DoginalsMint.POSTAGE, chain.destination());
        PendingMint record = PendingMint.doginal("m/44'/3'/0'/0/0", new byte[32], tx0.getTxId(), chain.carriedValue(), "image/png", chain.body(), 1000, "D");

        List<String> events = new ArrayList<>();
        List<List<PendingMint>> saved = new ArrayList<>();
        DoginalsFlow.Io io = new DoginalsFlow.Io() {
            @Override
            public void save(Wallet holder) {
                saved.add(holder.getPendingMints().stream().map(PendingMint::copy).toList());
                events.add("save");
            }

            @Override
            public void broadcast(Transaction transaction, long fee) {
                Assertions.assertFalse(saved.isEmpty(), "broadcast before the record was saved");
                Assertions.assertEquals(record.getCommitTxid(), saved.getFirst().getFirst().getCommitTxid());
                events.add("broadcast " + transaction.getTxId());
            }
        };
        DoginalsFlow.Outcome outcome = DoginalsFlow.run(wallet, record, true, List.of(tx0, step1), List.of(1000L, 2000L), 0, io);
        Assertions.assertNull(outcome.error());
        Assertions.assertEquals(1, outcome.stepsBroadcast());
        Assertions.assertEquals(List.of("save", "broadcast " + tx0.getTxId(), "save", "broadcast " + step1.getTxId(), "save"), events);
        Assertions.assertEquals(1, wallet.getPendingMints().getFirst().getStepsBroadcast());

        //A failed broadcast stops the batch, keeps the record and names the step
        wallet.getPendingMints().clear();
        DoginalsFlow.Io failing = new DoginalsFlow.Io() {
            @Override
            public void save(Wallet holder) {}

            @Override
            public void broadcast(Transaction transaction, long fee) throws Exception {
                if(transaction == step1) {
                    throw new Exception("txn-mempool-conflict");
                }
            }
        };
        DoginalsFlow.Outcome failed = DoginalsFlow.run(wallet, record, true, List.of(tx0, step1), List.of(1000L, 2000L), 0, failing);
        Assertions.assertNotNull(failed.error());
        Assertions.assertTrue(failed.error().contains("Step 1"), failed.error());
        Assertions.assertEquals(1, wallet.getPendingMints().size());
    }

    @Test
    public void heldCoinsNeverFund() throws Exception {
        List<BlockTransactionHashIndex> utxos = fund(900_000_000L, 300_000_000L);
        utxos.getFirst().setStatus(Status.PROTECTED);
        CollectiblesManager.Snapshot snapshot = snapshot(Map.of(utxos.get(0).toString(), CollectibleState.CARRIES, utxos.get(1).toString(), CollectibleState.CLEAN));
        DoginalsMint.Chain chain = chain(3_000);
        Transaction tx0 = DoginalsFlow.fund(wallet, chain, snapshot, 100).getTransaction();
        for(TransactionInput input : tx0.getInputs()) {
            Assertions.assertFalse(input.getOutpoint().getHash().equals(utxos.getFirst().getHash()) && input.getOutpoint().getIndex() == 0, "the held output funds the mint");
        }
        Assertions.assertEquals(1, tx0.getInputs().size());
    }

    @Test
    public void refusesWithServerOff() throws Exception {
        CollectiblesManager.Snapshot off = new CollectiblesManager.Snapshot(Map.of(), Map.of(), Instant.now(), null, null, false, false);
        Optional<String> refusal = DoginalsFlow.refusal(wallet, off, Coin.DOGECOIN);
        Assertions.assertTrue(refusal.isPresent());
        Assertions.assertTrue(refusal.get().contains("inscriptions server"), refusal.get());
        Assertions.assertTrue(DoginalsFlow.refusal(wallet, null, Coin.DOGECOIN).isPresent());
        Assertions.assertTrue(DoginalsFlow.refusal(wallet, snapshot(Map.of()), Coin.DOGECOIN).isEmpty());
        //Another coin's tab is selected: the broadcast would reach that coin's server
        Assertions.assertTrue(DoginalsFlow.refusal(wallet, snapshot(Map.of()), Coin.LITECOIN).isPresent());
        Assertions.assertTrue(DoginalsFlow.refusal(wallet(Coin.DOGECOIN, KeystoreSource.HW_USB), snapshot(Map.of()), Coin.DOGECOIN).isPresent());
        Assertions.assertTrue(DoginalsFlow.refusal(wallet(Coin.DOGECOIN, KeystoreSource.SW_WATCH), snapshot(Map.of()), Coin.DOGECOIN).isPresent());
        Assertions.assertTrue(DoginalsFlow.refusal(wallet(Coin.LITECOIN, KeystoreSource.SW_SEED), snapshot(Map.of()), Coin.LITECOIN).isPresent());
    }

    @Test
    public void costNamesTheCoin() throws Exception {
        fund(900_000_000L);
        List<BlockTransactionHashIndex> utxos = new ArrayList<>(wallet.getWalletUtxos().keySet());
        CollectiblesManager.Snapshot snapshot = snapshot(Map.of(utxos.getFirst().toString(), CollectibleState.CLEAN));
        DoginalsMint.Chain chain = chain(40_000);
        WalletTransaction tx0 = DoginalsFlow.fund(wallet, chain, snapshot, 100);
        String text = DoginalsFlow.confirmText(wallet, chain, tx0);
        Assertions.assertTrue(text.contains("DOGE"), text);
        Assertions.assertTrue(text.contains("sats/vB"), text);
        Assertions.assertTrue(text.contains(chain.steps() + 1 + " transactions"), text);
        Assertions.assertTrue(text.contains(chain.batches() + " batches"), text);
        Assertions.assertFalse(text.toLowerCase(Locale.ROOT).contains("lit"), text);
        Assertions.assertFalse(text.contains("LTC"), text);
        Assertions.assertFalse(text.contains("—"), text);
    }

    @Test
    public void longChainScanFindsItsTip() throws Exception {
        //A 150-step chain with 120 steps out: the server errs for each unknown txid, and a page with an error loses the rest
        Sha256Hash funding = Sha256Hash.of(new byte[] {(byte)0xf0});
        List<Sha256Hash> expected = IntStream.rangeClosed(1, 150).mapToObj(s -> Sha256Hash.of(new byte[] {(byte)s, 1})).toList();
        Set<Sha256Hash> present = new HashSet<>(expected.subList(0, 120));
        present.add(funding);
        Transaction any = new Transaction();
        List<Set<Sha256Hash>> requests = new ArrayList<>();
        DoginalsFlow.Lookup server = txids -> {
            requests.add(txids);
            Assertions.assertTrue(txids.size() <= DoginalsFlow.LOOKUP_CHUNK, "request of " + txids.size());
            Map<Sha256Hash, BlockTransaction> found = new HashMap<>();
            for(Sha256Hash txid : txids) {
                if(present.contains(txid)) {
                    found.put(txid, new BlockTransaction(txid, 10, new Date(), 0L, any));
                }
            }
            return found;
        };
        Map<Sha256Hash, BlockTransaction> found = DoginalsFlow.scanChain(funding, expected, server);
        Assertions.assertTrue(found.containsKey(funding));
        Assertions.assertEquals(121, DoginalsFlow.nextStep(expected, found.keySet()));
        //Walks in chain order and stops at the first request with a missing step
        Assertions.assertTrue(requests.getLast().contains(expected.get(120)));
        Assertions.assertFalse(requests.stream().anyMatch(request -> request.contains(expected.get(149))));
    }

    @Test
    public void recordStaysUntilLastStepConfirms() {
        Assertions.assertFalse(DoginalsFlow.complete(43, 42, false));
        Assertions.assertTrue(DoginalsFlow.complete(43, 42, true));
        Assertions.assertFalse(DoginalsFlow.complete(30, 42, true));
    }

    @Test
    public void unbroadcastFundingCanBeForgotten() throws Exception {
        Assertions.assertTrue(DoginalsFlow.forgettable(false, false));
        //The wallet saw it, or the server knows it: its coins are spent, so the record must stay
        Assertions.assertFalse(DoginalsFlow.forgettable(false, true));
        Assertions.assertFalse(DoginalsFlow.forgettable(true, false));

        DoginalsMint.Chain chain = chain(3_000);
        PendingMint record = DoginalsFlow.pendingRecord(chain, DoginalsFlow.keyNode(wallet), Sha256Hash.of(new byte[] {3}));
        Assertions.assertTrue(DoginalsFlow.status(record, wallet).contains("not seen"), DoginalsFlow.status(record, wallet));
        Transaction tx0 = new Transaction();
        tx0.addInput(Sha256Hash.ZERO_HASH, 0, new Script(new byte[0]));
        wallet.updateTransactions(Map.of(record.getCommitTxid(), new BlockTransaction(record.getCommitTxid(), 0, new Date(), 0L, tx0)));
        Assertions.assertTrue(DoginalsFlow.status(record, wallet).contains("Funding broadcast"), DoginalsFlow.status(record, wallet));
        Assertions.assertTrue(DoginalsFlow.status(record.withStepsBroadcast(2), wallet).contains("2 of " + chain.steps()), DoginalsFlow.status(record.withStepsBroadcast(2), wallet));
        Assertions.assertTrue(DoginalsFlow.status(record.withStepsBroadcast(chain.steps()), wallet).contains("All"), DoginalsFlow.status(record.withStepsBroadcast(chain.steps()), wallet));
    }

    @Test
    public void endAfterMalleationTakesTheChangedStepsOutput() {
        Transaction changed = new Transaction();
        changed.addInput(Sha256Hash.ZERO_HASH, 0, new Script(new byte[0]));
        TransactionOutput output = changed.addOutput(9_000_000, ScriptType.P2PKH.getAddress(new byte[20]));
        Assertions.assertEquals(Optional.of(output), DoginalsFlow.changedStepOutput(List.of(output)));
        Assertions.assertEquals(Optional.empty(), DoginalsFlow.changedStepOutput(List.of()));
    }

    @Test
    public void secondAccountKeepsItsOwnRecords() throws Exception {
        Wallet account = wallet.addChildWallet(StandardAccount.ACCOUNT_1);
        account.setCoin(Coin.DOGECOIN);
        Assertions.assertSame(account, DoginalsFlow.pendingHolder(account));
        Assertions.assertSame(wallet, DoginalsFlow.pendingHolder(wallet));
    }

    @Test
    public void chainRebuildsFromItsRecord() throws Exception {
        DoginalsMint.Chain chain = chain(5_000);
        WalletNode keyNode = DoginalsFlow.keyNode(wallet);
        //The record writes the destination with Dogecoin's prefix whatever coin is in scope
        Coin outer = Coin.beginScope(Coin.LITECOIN);
        PendingMint record;
        try {
            record = DoginalsFlow.pendingRecord(chain, keyNode, Sha256Hash.of(new byte[] {1}));
        } finally {
            Coin.endScope(outer);
        }
        Assertions.assertTrue(record.getDestination().startsWith("D"), record.getDestination());
        DoginalsMint.Chain rebuilt = DoginalsFlow.chain(wallet, record);
        Assertions.assertEquals(chain.carriedValue(), rebuilt.carriedValue());
        Assertions.assertEquals(chain.firstScriptAddress(), rebuilt.firstScriptAddress());
        Assertions.assertEquals(chain.destination(), rebuilt.destination());
        //A record whose script hash does not match its content is refused
        PendingMint wrong = PendingMint.doginal(record.getKeyPath(), new byte[32], record.getCommitTxid(), record.getCommitValue(), record.getContentType(),
                record.getContent(), record.getFeeRate(), record.getDestination());
        Assertions.assertThrows(IllegalArgumentException.class, () -> DoginalsFlow.chain(wallet, wrong));
        //A fresh mint takes a key no pending mint holds
        wallet.getPendingMints().add(record);
        Assertions.assertNotEquals(keyNode.getDerivationPath(), DoginalsFlow.keyNode(wallet).getDerivationPath());
    }
}
