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
        DeterministicSeed seed = new DeterministicSeed(new ArrayList<>(WORDS), "", 0, DeterministicSeed.Type.BIP39);
        wallet = new Wallet("taproot");
        wallet.setPolicyType(PolicyType.SINGLE);
        wallet.setScriptType(ScriptType.P2TR);
        wallet.getKeystores().add(Keystore.fromSeed(seed, ScriptType.P2TR.getDefaultDerivation()));
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE, ScriptType.P2TR, wallet.getKeystores(), 1));
        wallet.setCoin(Coin.LITECOIN);
        wallet.getNode(KeyPurpose.RECEIVE).fillToIndex(20);
        nodes = new ArrayList<>(wallet.getNode(KeyPurpose.RECEIVE).getChildren());
        wallet.setStoredBlockHeight(200);
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
        Assertions.assertEquals(small, CollectibleSend.inscribedInput(wallet, small.getHash() + ":0:0").utxo());
        Assertions.assertEquals(large, CollectibleSend.inscribedInput(wallet, large.getHash() + ":0:0").utxo(), "offset 0 in a large output is sent whole");
        Assertions.assertThrows(IllegalArgumentException.class, () -> CollectibleSend.inscribedInput(wallet, large.getHash() + ":0:1000"));
        Assertions.assertEquals(small, CollectibleSend.inscribedInput(wallet, small.getHash() + ":0:5000").utxo(), "a small output may hold it at any offset");
        Assertions.assertThrows(IllegalArgumentException.class, () -> CollectibleSend.inscribedInput(wallet, Utils.bytesToHex(new byte[32]) + ":0:0"), "not in this wallet");
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
        Assertions.assertTrue(CollectibleSend.recipientProblem("not an address").isPresent());
        Assertions.assertTrue(CollectibleSend.recipientProblem(new com.sparrowwallet.drongo.address.MwebAddress(new byte[66]).toString()).isPresent());
        Coin previous = Coin.beginScope(Coin.DOGECOIN);
        String dogeAddress;
        try {
            dogeAddress = ScriptType.P2PKH.getAddress(ECKey.fromPrivate(BigInteger.valueOf(7))).toString();
        } finally {
            Coin.endScope(previous);
        }
        Assertions.assertTrue(CollectibleSend.recipientProblem(dogeAddress).isPresent());
        Address taproot = ScriptType.P2TR.getAddress(ECKey.fromPrivate(BigInteger.valueOf(7)));
        Assertions.assertTrue(CollectibleSend.recipientProblem(taproot.toString()).isEmpty());
    }

    @Test
    public void nonTaprootRecipientWarns() {
        Address segwit = ScriptType.P2WPKH.getAddress(ECKey.fromPrivate(BigInteger.valueOf(7)));
        Address taproot = ScriptType.P2TR.getAddress(ECKey.fromPrivate(BigInteger.valueOf(7)));
        Assertions.assertTrue(CollectibleSend.recipientWarning(segwit).isPresent());
        Assertions.assertTrue(CollectibleSend.recipientWarning(taproot).isEmpty());
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
