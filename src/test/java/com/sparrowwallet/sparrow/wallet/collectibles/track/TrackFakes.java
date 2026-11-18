package com.sparrowwallet.sparrow.wallet.collectibles.track;

import com.sparrowwallet.drongo.ordinals.Inscription;
import com.sparrowwallet.drongo.ordinals.track.BlockFee;
import com.sparrowwallet.drongo.ordinals.track.ChainSource;
import com.sparrowwallet.drongo.ordinals.track.ChainTx;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.protocol.*;

import java.io.IOException;
import java.util.*;

/** A small in-memory chain and transaction builders for the app-side tracker tests. */
final class TrackFakes {
    static final ECKey KEY = ECKey.fromPrivate(Utils.hexToBytes("0404040404040404040404040404040404040404040404040404040404040404"));

    private TrackFakes() {
    }

    static Sha256Hash hash(int n) {
        return Sha256Hash.wrap(String.format("%064x", n));
    }

    static Sha256Hash block(int height) {
        return Sha256Hash.wrap(String.format("%064x", 0xb10c0000L + height));
    }

    static Transaction spend(Sha256Hash prev, long... outputs) {
        Transaction tx = new Transaction();
        tx.setVersion(2);
        tx.addInput(prev, 0, new Script(new byte[0]));
        for(long value : outputs) {
            tx.addOutput(value, new Script(new byte[] {0x51}));
        }
        return tx;
    }

    static Transaction reveal(Sha256Hash prev, String text, long... outputs) {
        Transaction tx = new Transaction();
        tx.setVersion(2);
        TransactionWitness witness = new TransactionWitness(tx, List.of(new byte[64], Inscription.text(text).revealScript(KEY).getProgram(), new byte[33]));
        tx.addInput(prev, 0, new Script(new byte[0]), witness);
        for(long value : outputs) {
            tx.addOutput(value, new Script(new byte[] {0x51}));
        }
        return tx;
    }

    static final class Chain implements ChainSource {
        final Map<Sha256Hash, ChainTx> txs = new HashMap<>();
        int calls;

        Transaction add(Transaction tx, int height) {
            txs.put(tx.getTxId(), new ChainTx(tx, height, height == 0 ? null : block(height), 1_700_000_000L + height, true));
            return tx;
        }

        @Override
        public ChainTx transaction(Sha256Hash txid) throws IOException {
            calls++;
            ChainTx tx = txs.get(txid);
            if(tx == null) {
                throw new IOException("No such transaction " + txid);
            }
            return tx;
        }

        @Override
        public Optional<List<BlockFee>> blockFees(int height, Sha256Hash blockHash) {
            return Optional.empty();
        }

        @Override
        public Optional<Sha256Hash> txidAt(int height, int position) {
            return Optional.empty();
        }
    }
}
