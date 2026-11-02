package com.sparrowwallet.sparrow.wallet.collectibles;

import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.wallet.Status;
import com.sparrowwallet.sparrow.net.ord.OrdOutput;
import com.sparrowwallet.sparrow.net.ord.RuneBalance;
import com.sparrowwallet.sparrow.wallet.collectibles.CollectiblesClassifier.Txo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class CollectiblesClassifierTest {
    private static final Sha256Hash FOREIGN = Sha256Hash.wrap("11".repeat(32));

    private final List<Txo> txos = new ArrayList<>();
    private final Map<String, Transaction> txs = new HashMap<>();
    private final Map<String, OrdOutput> answers = new HashMap<>();

    private static List<byte[]> plainWitness() {
        return List.of(new byte[72], new byte[33]);
    }

    private static List<byte[]> envelopeWitness() {
        ByteArrayOutputStream script = new ByteArrayOutputStream();
        script.write(0x20);
        script.writeBytes(new byte[32]);
        script.writeBytes(new byte[] {(byte)0xac, 0x00, 0x63, 0x03});
        script.writeBytes("ord".getBytes(StandardCharsets.US_ASCII));
        script.writeBytes(new byte[] {0x01, 0x01, 0x00, 0x02, 'h', 'i', 0x68});
        return List.of(new byte[64], script.toByteArray(), new byte[33]);
    }

    /** A transaction spending the given outpoints, with one output; adds the output as a wallet txo. */
    private String tx(List<String> inputs, List<byte[]> witness, int height, Status status, String... extraOutputScripts) {
        Transaction tx = new Transaction();
        tx.setSegwitFlag(Transaction.DEFAULT_SEGWIT_FLAG);
        for(String input : inputs) {
            String[] parts = input.split(":");
            tx.addInput(Sha256Hash.wrap(parts[0]), Long.parseLong(parts[1]), new Script(List.of()), new TransactionWitness(tx, witness));
        }
        tx.addOutput(10000, new Script(Utils.hexToBytes("0014" + "00".repeat(20))));
        for(String script : extraOutputScripts) {
            tx.addOutput(0, new Script(Utils.hexToBytes(script)));
        }
        String txid = tx.getTxId().toString();
        txs.put(txid, tx);
        String outpoint = txid + ":0";
        txos.add(new Txo(outpoint, status, height, txid));
        return outpoint;
    }

    private String received(int height) {
        return tx(List.of(FOREIGN + ":0"), plainWitness(), height, null);
    }

    private void answer(String outpoint, boolean indexed, boolean carries) {
        answers.put(outpoint, new OrdOutput(outpoint, indexed, carries ? List.of("00".repeat(32) + "i0") : List.of(), Map.of(), 10000, false, false));
    }

    private CollectibleState state(String outpoint) {
        return CollectiblesClassifier.classify(txos, txs, answers).get(outpoint);
    }

    /** A doginals-style envelope sits in the scriptSig, not a witness: its outputs are not clean by provenance either. */
    @Test
    public void scriptSigEnvelopeIsNotCleanByProvenance() {
        String a = received(100);
        answer(a, true, false);
        Transaction tx = new Transaction();
        String[] parts = a.split(":");
        //push "ord", OP_1, push "text/plain"
        tx.addInput(Sha256Hash.wrap(parts[0]), Long.parseLong(parts[1]), new Script(Utils.hexToBytes("036f7264510a746578742f706c61696e")));
        tx.addOutput(100000, new Script(Utils.hexToBytes("76a914" + "00".repeat(20) + "88ac")));
        String txid = tx.getTxId().toString();
        txs.put(txid, tx);
        txos.add(new Txo(txid + ":0", null, 101, txid));
        Assertions.assertEquals(CollectibleState.UNKNOWN, state(txid + ":0"));
    }

    @Test
    public void serverCarriesWins() {
        String a = received(100);
        answer(a, true, false);
        String b = tx(List.of(a), plainWitness(), 101, null);
        answer(b, true, true);
        Assertions.assertEquals(CollectibleState.CARRIES, state(b));
    }

    @Test
    public void serverEmptyIsClean() {
        String a = received(100);
        answer(a, true, false);
        Assertions.assertEquals(CollectibleState.CLEAN, state(a));
    }

    @Test
    public void ownChangeFromCleanInputsIsClean() {
        String a = received(100);
        answer(a, true, false);
        String b = tx(List.of(a), plainWitness(), 0, null);
        Assertions.assertEquals(CollectibleState.CLEAN, state(b));
    }

    @Test
    public void receivedUnconfirmedIsUnknown() {
        String a = received(0);
        Assertions.assertEquals(CollectibleState.UNKNOWN, state(a));
    }

    @Test
    public void unindexedAnswerIsUnknown() {
        String a = received(0);
        answer(a, false, false);
        Assertions.assertEquals(CollectibleState.UNKNOWN, state(a));
    }

    @Test
    public void envelopeMakesOutputsUnknown() {
        String a = received(100);
        answer(a, true, false);
        String b = tx(List.of(a), envelopeWitness(), 0, null);
        Assertions.assertEquals(CollectibleState.UNKNOWN, state(b));
    }

    @Test
    public void runestoneMakesOutputsUnknown() {
        String a = received(100);
        answer(a, true, false);
        String b = tx(List.of(a), plainWitness(), 0, null, "6a5d00");
        Assertions.assertEquals(CollectibleState.UNKNOWN, state(b));
    }

    @Test
    public void releasedInputMakesOutputsUnknown() {
        String a = tx(List.of(FOREIGN + ":1"), plainWitness(), 100, Status.RELEASED);
        String b = tx(List.of(a), plainWitness(), 0, null);
        Assertions.assertEquals(CollectibleState.CARRIES, state(a));
        Assertions.assertEquals(CollectibleState.UNKNOWN, state(b));
    }

    @Test
    public void protectedInputMakesOutputsUnknown() {
        String a = tx(List.of(FOREIGN + ":2"), plainWitness(), 100, Status.PROTECTED);
        String b = tx(List.of(a), plainWitness(), 0, null);
        Assertions.assertEquals(CollectibleState.UNKNOWN, state(b));
    }

    @Test
    public void protectedStaysCarriesEvenIfServerSaysEmpty() {
        String a = tx(List.of(FOREIGN + ":3"), plainWitness(), 100, Status.PROTECTED);
        answer(a, true, false);
        Assertions.assertEquals(CollectibleState.CARRIES, state(a));
    }

    @Test
    public void serverUnreachableKeepsProtectionAndMarksUnknown() {
        String received = received(100);
        String guarded = tx(List.of(FOREIGN + ":4"), plainWitness(), 100, Status.PROTECTED);
        Map<String, CollectibleState> states = CollectiblesClassifier.classify(txos, txs, Map.of());
        Assertions.assertEquals(CollectibleState.UNKNOWN, states.get(received));
        Assertions.assertEquals(CollectibleState.CARRIES, states.get(guarded));
    }

    @Test
    public void longUnconfirmedChainIsCapped() {
        String previous = received(100);
        answer(previous, true, false);
        for(int i = 0; i < 60; i++) {
            previous = tx(List.of(previous), plainWitness(), 0, null);
        }
        //Starting from the tip, the chain is deeper than the classifier follows: no stack overflow, and the tip stays unknown
        Collections.reverse(txos);
        Assertions.assertEquals(CollectibleState.UNKNOWN, state(previous));
    }

    @Test
    public void runesCount() {
        String a = received(100);
        answers.put(a, new OrdOutput(a, true, List.of(), Map.of("A", new RuneBalance(BigInteger.ONE, 0, "x")), 546, false, false));
        Assertions.assertEquals(CollectibleState.CARRIES, state(a));
    }
}
