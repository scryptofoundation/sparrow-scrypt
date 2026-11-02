package com.sparrowwallet.sparrow.wallet.collectibles;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.protocol.Script;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.wallet.BlockTransaction;
import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.Status;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.Map;

public class HeldOutputsTest {
    private static final String P2PKH = "76a914" + "11".repeat(20) + "88ac";

    private static Transaction tx(String scriptSigHex, long... values) {
        Transaction tx = new Transaction();
        tx.addInput(Sha256Hash.ZERO_HASH, 0, new Script(Utils.hexToBytes(scriptSigHex)));
        for(long value : values) {
            tx.addOutput(value, new Script(Utils.hexToBytes(P2PKH)));
        }
        return tx;
    }

    private static Map<Sha256Hash, BlockTransaction> txs(Transaction... transactions) {
        Map<Sha256Hash, BlockTransaction> map = new java.util.HashMap<>();
        for(Transaction transaction : transactions) {
            map.put(transaction.getTxId(), new BlockTransaction(transaction.getTxId(), 100, new Date(), 0L, transaction));
        }
        return map;
    }

    private static BlockTransactionHashIndex utxo(Transaction tx, int index, Status status) {
        BlockTransactionHashIndex txo = new BlockTransactionHashIndex(tx.getTxId(), 100, new Date(), 0L, index, tx.getOutputs().get(index).getValue());
        txo.setStatus(status);
        return txo;
    }

    //push "ord", OP_1, push "text/plain"
    private static final String DOGINALS_START = "036f7264510a746578742f706c61696e";
    //push 72 zero bytes, push 33 zero bytes: an ordinary P2PKH spend
    private static final String P2PKH_SPEND = "48" + "00".repeat(72) + "21" + "00".repeat(33);

    @Test
    public void inscriptionTransactionOutputsAreHeld() {
        Transaction reveal = tx(DOGINALS_START, 100000, 2500000);
        Map<Sha256Hash, BlockTransaction> txs = txs(reveal);
        Assertions.assertTrue(HeldOutputs.isHeld(Coin.DOGECOIN, utxo(reveal, 0, null), txs));
        Assertions.assertTrue(HeldOutputs.isHeld(Coin.DOGECOIN, utxo(reveal, 1, null), txs));
        Assertions.assertEquals(HeldOutputs.CREATED_BY_INSCRIPTION, HeldOutputs.reason(Coin.DOGECOIN, utxo(reveal, 1, null), txs));
    }

    @Test
    public void postageValueIsHeldEvenFromAPlainTransaction() {
        Transaction plain = tx(P2PKH_SPEND, 100000, 2500000);
        Map<Sha256Hash, BlockTransaction> txs = txs(plain);
        Assertions.assertTrue(HeldOutputs.isHeld(Coin.PEPECOIN, utxo(plain, 0, null), txs));
        Assertions.assertFalse(HeldOutputs.isHeld(Coin.PEPECOIN, utxo(plain, 1, null), txs));
        Assertions.assertEquals("It holds 0.001 PEP, the usual value of an inscription", HeldOutputs.reason(Coin.PEPECOIN, utxo(plain, 0, null), txs));
    }

    @Test
    public void releasedAndProtectedOutputsAreNotHeld() {
        Transaction reveal = tx(DOGINALS_START, 100000);
        Map<Sha256Hash, BlockTransaction> txs = txs(reveal);
        Assertions.assertFalse(HeldOutputs.isHeld(Coin.DOGECOIN, utxo(reveal, 0, Status.RELEASED), txs));
        Assertions.assertFalse(HeldOutputs.isHeld(Coin.DOGECOIN, utxo(reveal, 0, Status.PROTECTED), txs));
    }

    @Test
    public void missingTransactionLeavesTheValueRule() {
        Transaction plain = tx(P2PKH_SPEND, 10000, 20000);
        Map<Sha256Hash, BlockTransaction> none = Map.of();
        Assertions.assertTrue(HeldOutputs.isHeld(Coin.LITECOIN, utxo(plain, 0, null), none));
        Assertions.assertFalse(HeldOutputs.isHeld(Coin.LITECOIN, utxo(plain, 1, null), none));
    }

    @Test
    public void filterAdmitsOnlyOutputsNotHeld() {
        Transaction reveal = tx(DOGINALS_START, 100000);
        Transaction plain = tx(P2PKH_SPEND, 2500000);
        Map<Sha256Hash, BlockTransaction> txs = txs(reveal, plain);
        HeldOutputs.Filter filter = new HeldOutputs.Filter(Coin.DOGECOIN, txs);
        Assertions.assertFalse(filter.isEligible(utxo(reveal, 0, null)));
        Assertions.assertTrue(filter.isEligible(utxo(plain, 0, null)));
    }
}
