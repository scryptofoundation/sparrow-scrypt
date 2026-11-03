package com.sparrowwallet.sparrow.wallet.collectibles;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.ordinals.CollectibleTransactions;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.wallet.*;
import com.sparrowwallet.sparrow.net.ord.OrdOutput;
import com.sparrowwallet.sparrow.net.ord.RuneBalance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Instant;
import java.util.*;

public class CollectibleSendTest {
    private static final List<String> WORDS = List.of("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" "));
    private static final String RUNE = "TESTRUNE•LITECOIN";
    private Coin previousCoin;
    private Wallet wallet;
    private List<WalletNode> nodes;
    private final Map<String, CollectibleState> states = new HashMap<>();
    private final Map<String, OrdOutput> answers = new HashMap<>();

    @BeforeEach
    public void setUp() throws Exception {
        previousCoin = Coin.beginScope(Coin.LITECOIN);
        useWallet(Coin.LITECOIN, ScriptType.P2TR);
    }

    /** Replaces the test wallet with a fresh single-sig one; the caller holds the coin's scope. */
    private void useWallet(Coin coin, ScriptType scriptType) throws Exception {
        DeterministicSeed seed = new DeterministicSeed(new ArrayList<>(WORDS), "", 0, DeterministicSeed.Type.BIP39);
        wallet = new Wallet(coin.getName());
        wallet.setPolicyType(PolicyType.SINGLE_HD);
        wallet.setScriptType(scriptType);
        wallet.getKeystores().add(Keystore.fromSeed(seed, PolicyType.SINGLE_HD, scriptType.getDefaultDerivation()));
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE_HD, scriptType, wallet.getKeystores(), 1));
        wallet.setCoin(coin);
        wallet.getNode(KeyPurpose.RECEIVE).fillToIndex(20);
        nodes = new ArrayList<>(wallet.getNode(KeyPurpose.RECEIVE).getChildren());
        wallet.setStoredBlockHeight(200);
        nextNode = 0;
    }

    @AfterEach
    public void tearDown() {
        Coin.endScope(previousCoin);
    }

    private int nextNode = 0;

    /** One output to a fresh receive node in its own confirmed transaction; coinbase when asked. */
    private BlockTransactionHashIndex utxo(long value, CollectibleState state, boolean coinbase) {
        WalletNode node = nodes.get(nextNode++);
        Transaction tx = new Transaction();
        tx.addInput(Sha256Hash.ZERO_HASH, coinbase ? 0xFFFFFFFFL : nextNode, new Script(new byte[] {(byte)nextNode}));
        tx.addOutput(value, node.getAddress());
        Sha256Hash hash = tx.getTxId();
        wallet.updateTransactions(Map.of(hash, new BlockTransaction(hash, 150, new Date(), 0L, tx)));
        BlockTransactionHashIndex utxo = new BlockTransactionHashIndex(hash, 150, new Date(), 0L, 0, value);
        node.getTransactionOutputs().add(utxo);
        states.put(utxo.toString(), state);
        return utxo;
    }

    private void carries(BlockTransactionHashIndex utxo, List<String> inscriptions, long runeAmount) {
        Map<String, RuneBalance> runes = runeAmount > 0 ? Map.of(RUNE, new RuneBalance(BigInteger.valueOf(runeAmount), 2, "T")) : Map.of();
        answers.put(utxo.toString(), new OrdOutput(utxo.toString(), true, inscriptions, runes, utxo.getValue(), false, false));
    }

    private CollectiblesManager.Snapshot snapshot() {
        return new CollectiblesManager.Snapshot(states, answers, Instant.now(), "http://127.0.0.1:8085", null, true, true);
    }

    @Test
    public void feeCandidatesAreCleanOnly() {
        BlockTransactionHashIndex clean = utxo(50_000, CollectibleState.CLEAN, false);
        utxo(60_000, CollectibleState.UNKNOWN, false);
        utxo(70_000, CollectibleState.CARRIES, false);
        BlockTransactionHashIndex protectedUtxo = utxo(80_000, CollectibleState.CLEAN, false);
        protectedUtxo.setStatus(Status.PROTECTED);
        BlockTransactionHashIndex released = utxo(90_000, CollectibleState.CLEAN, false);
        released.setStatus(Status.RELEASED);
        BlockTransactionHashIndex frozen = utxo(95_000, CollectibleState.CLEAN, false);
        frozen.setStatus(Status.FROZEN);
        utxo(100_000, CollectibleState.CLEAN, true);
        List<CollectibleTransactions.Pinned> candidates = CollectibleSend.feeCandidates(wallet, snapshot());
        Assertions.assertEquals(List.of(clean), candidates.stream().map(CollectibleTransactions.Pinned::utxo).toList());
    }

    @Test
    public void immaturePegoutIsNotAFeeCandidate() {
        //A HogEx transaction (first output the MWEB HogEx script) paying a peg-out to the wallet, 2 confirmations at height 200
        WalletNode node = nodes.get(nextNode++);
        Transaction hogex = new Transaction();
        hogex.addInput(Sha256Hash.wrap("11".repeat(32)), 0, new Script(new byte[0]));
        hogex.addOutput(1_000_000, ScriptType.MWEB_HOGEX.getOutputScript(new byte[32]));
        hogex.addOutput(50_000, node.getAddress());
        Assertions.assertTrue(hogex.isHogEx());
        Sha256Hash hash = hogex.getTxId();
        wallet.updateTransactions(Map.of(hash, new BlockTransaction(hash, 199, new Date(), 0L, hogex)));
        BlockTransactionHashIndex pegout = new BlockTransactionHashIndex(hash, 199, new Date(), 0L, 1, 50_000);
        node.getTransactionOutputs().add(pegout);
        states.put(pegout.toString(), CollectibleState.CLEAN);
        BlockTransactionHashIndex clean = utxo(60_000, CollectibleState.CLEAN, false);
        List<CollectibleTransactions.Pinned> candidates = CollectibleSend.feeCandidates(wallet, snapshot());
        Assertions.assertEquals(List.of(clean), candidates.stream().map(CollectibleTransactions.Pinned::utxo).toList());
    }

    @Test
    public void noCandidatesWithoutAnEnabledSnapshot() {
        utxo(50_000, CollectibleState.CLEAN, false);
        Assertions.assertTrue(CollectibleSend.feeCandidates(wallet, null).isEmpty());
        Assertions.assertTrue(CollectibleSend.feeCandidates(wallet, new CollectiblesManager.Snapshot(states, answers, Instant.now(), null, null, false, true)).isEmpty());
    }

    @Test
    public void inscribedInputRefusesLargeOffsetOutput() {
        BlockTransactionHashIndex small = utxo(10_000, CollectibleState.CARRIES, false);
        BlockTransactionHashIndex large = utxo(500_000, CollectibleState.CARRIES, false);
        Assertions.assertEquals(small, CollectibleSend.inscribedInput(wallet, small.getHash() + ":0:0").pinned().utxo());
        Assertions.assertEquals(large, CollectibleSend.inscribedInput(wallet, large.getHash() + ":0:0").pinned().utxo(), "offset 0 in a large output is sent whole");
        Assertions.assertThrows(IllegalArgumentException.class, () -> CollectibleSend.inscribedInput(wallet, large.getHash() + ":0:1000"));
        Assertions.assertEquals(small, CollectibleSend.inscribedInput(wallet, small.getHash() + ":0:5000").pinned().utxo(), "a small output may hold it at any offset");
        Assertions.assertThrows(IllegalArgumentException.class, () -> CollectibleSend.inscribedInput(wallet, Utils.bytesToHex(new byte[32]) + ":0:0"), "not in this wallet");
    }

    @Test
    public void dogeOffsetAllowed() throws Exception {
        String litecoinMessage;
        BlockTransactionHashIndex ltcOutput = utxo(5_000_000, CollectibleState.CARRIES, false);
        IllegalArgumentException refused = Assertions.assertThrows(IllegalArgumentException.class,
                () -> CollectibleSend.inscribedInput(wallet, ltcOutput.getHash() + ":0:300000"));
        litecoinMessage = refused.getMessage();
        Assertions.assertEquals("The inscription sits 300000 litoshis into an output of 5000000 litoshis. Sending it would send the whole output; "
                + "splitting it off first is not supported yet.", litecoinMessage);

        Coin previous = Coin.beginScope(Coin.DOGECOIN);
        try {
            useWallet(Coin.DOGECOIN, ScriptType.P2PKH);
            BlockTransactionHashIndex output = utxo(5_000_000, CollectibleState.CARRIES, false);
            CollectibleSend.Inscribed inscribed = CollectibleSend.inscribedInput(wallet, output.getHash() + ":0:300000");
            Assertions.assertEquals(output, inscribed.pinned().utxo());
            Assertions.assertEquals(300_000, inscribed.offset());
            Assertions.assertEquals(300_000, CollectibleSend.splitOffset(Coin.DOGECOIN, inscribed));
        } finally {
            Coin.endScope(previous);
        }
    }

    @Test
    public void litecoinNeverSplits() {
        BlockTransactionHashIndex small = utxo(20_000, CollectibleState.CARRIES, false);
        CollectibleSend.Inscribed inscribed = CollectibleSend.inscribedInput(wallet, small.getHash() + ":0:5000");
        Assertions.assertEquals(5_000, inscribed.offset());
        Assertions.assertEquals(0, CollectibleSend.splitOffset(Coin.LITECOIN, inscribed), "Litecoin sends the whole output as before");
    }

    @Test
    public void inscriptionCountOnOutput() {
        String id = "ab".repeat(32) + "i0";
        Assertions.assertEquals(1, CollectibleSend.inscriptionsOnOutput(new OrdOutput("x:0", true, List.of(id), Map.of(), 1, false, false)));
        Assertions.assertEquals(2, CollectibleSend.inscriptionsOnOutput(new OrdOutput("x:0", true, List.of(id, "cd".repeat(32) + "i0"), Map.of(), 1, false, false)));
        Assertions.assertTrue(CollectibleSend.inscriptionsOnOutput(new OrdOutput("x:0", true, List.of(id), Map.of(), 1, false, true)) > 1, "unlisted: more than is known");
        Assertions.assertTrue(CollectibleSend.inscriptionsOnOutput(new OrdOutput("x:0", false, List.of(id), Map.of(), 1, false, false)) > 1, "not indexed: unknown");
    }

    @Test
    public void dogeSplitSendsOffsetPlusPostage() throws Exception {
        Coin previous = Coin.beginScope(Coin.DOGECOIN);
        try {
            useWallet(Coin.DOGECOIN, ScriptType.P2PKH);
            BlockTransactionHashIndex output = utxo(5_000_000, CollectibleState.CARRIES, false);
            utxo(500_000_000, CollectibleState.CLEAN, false);
            CollectibleSend.Inscribed inscribed = CollectibleSend.inscribedInput(wallet, output.getHash() + ":0:300000");
            Address recipient = ScriptType.P2PKH.getAddress(PolicyType.SINGLE_HD, ECKey.fromPrivate(BigInteger.valueOf(7)));
            WalletTransaction walletTransaction = CollectibleTransactions.sendInscription(wallet, inscribed.pinned(), CollectibleSend.splitOffset(Coin.DOGECOIN, inscribed),
                    1, recipient, CollectibleSend.feeCandidates(wallet, snapshot()), 1000, 100);
            Assertions.assertEquals(300_000 + CollectibleTransactions.postage(Coin.DOGECOIN, ScriptType.P2PKH),
                    walletTransaction.getTransaction().getOutputs().getFirst().getValue());
        } finally {
            Coin.endScope(previous);
        }
    }

    @Test
    public void recipientTextPerCoin() {
        Address ltcSegwit = ScriptType.P2WPKH.getAddress(PolicyType.SINGLE_HD, ECKey.fromPrivate(BigInteger.valueOf(7)));
        Assertions.assertEquals(Optional.of("Not a Dogecoin address"), CollectibleSend.recipientProblem(ltcSegwit.toString(), Coin.DOGECOIN));
        Assertions.assertEquals(Optional.of("Not a Litecoin address"), CollectibleSend.recipientProblem("not an address", Coin.LITECOIN));
        Assertions.assertEquals(Optional.of("This is not a taproot address. Make sure the receiving wallet keeps track of inscriptions and runes, or it may spend them as plain LTC."),
                CollectibleSend.recipientWarning(ltcSegwit, Coin.LITECOIN));

        Coin previous = Coin.beginScope(Coin.DOGECOIN);
        try {
            Address doge = ScriptType.P2PKH.getAddress(PolicyType.SINGLE_HD, ECKey.fromPrivate(BigInteger.valueOf(7)));
            Assertions.assertTrue(CollectibleSend.recipientProblem(doge.toString(), Coin.DOGECOIN).isEmpty());
            Assertions.assertTrue(CollectibleSend.recipientWarning(doge, Coin.DOGECOIN).isEmpty(), "Dogecoin has no taproot");
        } finally {
            Coin.endScope(previous);
        }

        previous = Coin.beginScope(Coin.BELLSCOIN);
        try {
            Address bells = ScriptType.P2WPKH.getAddress(PolicyType.SINGLE_HD, ECKey.fromPrivate(BigInteger.valueOf(7)));
            Assertions.assertTrue(bells.toString().startsWith("bel1q"), bells.toString());
            Assertions.assertTrue(CollectibleSend.recipientProblem(bells.toString(), Coin.BELLSCOIN).isEmpty());
            Optional<String> warning = CollectibleSend.recipientWarning(bells, Coin.BELLSCOIN);
            Assertions.assertTrue(warning.isPresent());
            Assertions.assertTrue(warning.get().contains("taproot") && warning.get().endsWith("plain BELLS."), warning.get());
            Assertions.assertFalse(warning.get().contains("runes"), warning.get());
        } finally {
            Coin.endScope(previous);
        }
    }

    @Test
    public void staleSatpointRefused() throws Exception {
        Coin previous = Coin.beginScope(Coin.DOGECOIN);
        try {
            useWallet(Coin.DOGECOIN, ScriptType.P2PKH);
            BlockTransactionHashIndex output = utxo(5_000_000, CollectibleState.CARRIES, false);
            IllegalArgumentException gone = Assertions.assertThrows(IllegalArgumentException.class,
                    () -> CollectibleSend.inscribedInput(wallet, "cd".repeat(32) + ":0:0"));
            Assertions.assertEquals("The inscription is not in an unspent output of this wallet", gone.getMessage());
            IllegalArgumentException otherVout = Assertions.assertThrows(IllegalArgumentException.class,
                    () -> CollectibleSend.inscribedInput(wallet, output.getHash() + ":1:0"));
            Assertions.assertEquals("The inscription is not in an unspent output of this wallet", otherVout.getMessage());
        } finally {
            Coin.endScope(previous);
        }
    }

    @Test
    public void carriedMustListTheInscription() {
        String id = "ab".repeat(32) + "i0";
        String other = "cd".repeat(32) + "i0";
        String outpoint = "ef".repeat(32) + ":1";
        CollectibleSend.checkCarried(outpoint + ":300000", new OrdOutput(outpoint, true, List.of(id), Map.of(), 5_000_000, false, false), id);
        IllegalArgumentException notListed = Assertions.assertThrows(IllegalArgumentException.class, () -> CollectibleSend.checkCarried(outpoint + ":300000",
                new OrdOutput(outpoint, true, List.of(other), Map.of(), 5_000_000, false, false), id));
        Assertions.assertEquals("The inscriptions server places the inscription in " + outpoint + ", but does not list it on that output. Refresh and try again.",
                notListed.getMessage());
        Assertions.assertThrows(IllegalArgumentException.class, () -> CollectibleSend.checkCarried(outpoint + ":0",
                new OrdOutput(outpoint, true, List.of(), Map.of(), 5_000_000, false, true), id), "unlisted output without the id");
        Assertions.assertThrows(IllegalArgumentException.class, () -> CollectibleSend.checkCarried("ef".repeat(32) + ":0:0",
                new OrdOutput(outpoint, true, List.of(id), Map.of(), 5_000_000, false, false), id), "answer for another output");
    }

    @Test
    public void focusMustBeTheWalletsCoin() {
        Assertions.assertTrue(CollectibleSend.focusProblem(Coin.LITECOIN, wallet).isEmpty());
        Optional<String> problem = CollectibleSend.focusProblem(Coin.DOGECOIN, wallet);
        Assertions.assertTrue(problem.isPresent());
        Assertions.assertTrue(problem.get().contains(wallet.getFullDisplayName()), problem.get());
    }

    @Test
    public void minRelayFeeRateIsTheWalletsCoin() {
        Assertions.assertEquals(100d, CollectibleSend.minRelayFeeRate(Coin.DOGECOIN, Coin.LITECOIN, 1d), "a Litecoin server's rate is not Dogecoin's");
        Assertions.assertEquals(100d, CollectibleSend.minRelayFeeRate(Coin.DOGECOIN, null, null));
        Assertions.assertEquals(150d, CollectibleSend.minRelayFeeRate(Coin.DOGECOIN, Coin.DOGECOIN, 150d));
    }

    @Test
    public void serverProblemSaysHowToTurnItOn() {
        String message = CollectibleSend.serverProblem("Connection refused.");
        Assertions.assertEquals("Could not check where the inscription is now: Connection refused. Make sure the inscriptions server is on and reachable "
                + "in Preferences > Server > Inscriptions.", message);
        Assertions.assertTrue(CollectibleSend.serverProblem(null).contains("Preferences > Server > Inscriptions"));
    }

    @Test
    public void runeInputsSkipInscribedOutputsAndCoverAmount() {
        BlockTransactionHashIndex a = utxo(10_000, CollectibleState.CARRIES, false);
        BlockTransactionHashIndex b = utxo(10_000, CollectibleState.CARRIES, false);
        BlockTransactionHashIndex withInscription = utxo(10_000, CollectibleState.CARRIES, false);
        carries(a, List.of(), 300);
        carries(b, List.of(), 500);
        carries(withInscription, List.of("ab".repeat(32) + "i0"), 10_000);
        List<BlockTransactionHashIndex> picked = CollectibleSend.runeInputs(wallet, snapshot(), RUNE, BigInteger.valueOf(400)).stream().map(CollectibleTransactions.Pinned::utxo).toList();
        Assertions.assertEquals(List.of(b), picked, "largest first, one is enough");
        picked = CollectibleSend.runeInputs(wallet, snapshot(), RUNE, BigInteger.valueOf(700)).stream().map(CollectibleTransactions.Pinned::utxo).toList();
        Assertions.assertEquals(List.of(b, a), picked);
    }

    @Test
    public void notEnoughRuneBalanceRefused() {
        BlockTransactionHashIndex a = utxo(10_000, CollectibleState.CARRIES, false);
        carries(a, List.of(), 300);
        Assertions.assertThrows(IllegalArgumentException.class, () -> CollectibleSend.runeInputs(wallet, snapshot(), RUNE, BigInteger.valueOf(301)));
        Assertions.assertThrows(IllegalArgumentException.class, () -> CollectibleSend.runeInputs(wallet, snapshot(), RUNE, BigInteger.ZERO));
    }

    @Test
    public void mwebAndOtherCoinRecipientsRefused() {
        Assertions.assertTrue(CollectibleSend.recipientProblem("not an address", Coin.LITECOIN).isPresent());
        Assertions.assertTrue(CollectibleSend.recipientProblem(new com.sparrowwallet.drongo.address.MwebAddress(new byte[66]).toString(), Coin.LITECOIN).isPresent());
        Coin previous = Coin.beginScope(Coin.DOGECOIN);
        String dogeAddress;
        try {
            dogeAddress = ScriptType.P2PKH.getAddress(PolicyType.SINGLE_HD, ECKey.fromPrivate(BigInteger.valueOf(7))).toString();
        } finally {
            Coin.endScope(previous);
        }
        Assertions.assertTrue(CollectibleSend.recipientProblem(dogeAddress, Coin.LITECOIN).isPresent());
        Address taproot = ScriptType.P2TR.getAddress(PolicyType.SINGLE_HD, ECKey.fromPrivate(BigInteger.valueOf(7)));
        Assertions.assertTrue(CollectibleSend.recipientProblem(taproot.toString(), Coin.LITECOIN).isEmpty());
    }

    @Test
    public void nonTaprootRecipientWarns() {
        Address segwit = ScriptType.P2WPKH.getAddress(PolicyType.SINGLE_HD, ECKey.fromPrivate(BigInteger.valueOf(7)));
        Address taproot = ScriptType.P2TR.getAddress(PolicyType.SINGLE_HD, ECKey.fromPrivate(BigInteger.valueOf(7)));
        Assertions.assertTrue(CollectibleSend.recipientWarning(segwit, Coin.LITECOIN).isPresent());
        Assertions.assertTrue(CollectibleSend.recipientWarning(taproot, Coin.LITECOIN).isEmpty());
    }

    @Test
    public void displayAmountToBaseUnits() {
        Assertions.assertEquals(BigInteger.valueOf(150), CollectibleSend.toBaseUnits("1.5", 2));
        Assertions.assertEquals(BigInteger.valueOf(100), CollectibleSend.toBaseUnits("1", 2));
        Assertions.assertEquals(BigInteger.valueOf(7), CollectibleSend.toBaseUnits("7", 0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> CollectibleSend.toBaseUnits("1.555", 2));
        Assertions.assertThrows(IllegalArgumentException.class, () -> CollectibleSend.toBaseUnits("1e9", 2));
        Assertions.assertThrows(IllegalArgumentException.class, () -> CollectibleSend.toBaseUnits("-1", 2));
    }
}
