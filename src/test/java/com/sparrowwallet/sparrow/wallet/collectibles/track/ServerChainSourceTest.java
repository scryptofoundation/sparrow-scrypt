package com.sparrowwallet.sparrow.wallet.collectibles.track;

import com.sparrowwallet.drongo.ordinals.track.BlockFee;
import com.sparrowwallet.drongo.ordinals.track.ChainTx;
import com.sparrowwallet.drongo.protocol.Script;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.wallet.BlockTransaction;
import com.sparrowwallet.sparrow.net.ServerException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.*;

public class ServerChainSourceTest {
    private static Transaction tx(int n) {
        Transaction tx = new Transaction();
        tx.addInput(Sha256Hash.wrap(String.format("%064x", n)), 0, new Script(new byte[0]));
        tx.addOutput(1_000, new Script(new byte[] {0x51}));
        return tx;
    }

    private static BlockTransaction blockTx(Transaction tx, int height, Sha256Hash blockHash) {
        return new BlockTransaction(tx.getTxId(), height, new Date(1_700_000_000_000L), 0L, tx, blockHash);
    }

    private static final class FakeServer implements ServerChainSource.ServerCalls {
        final Map<Sha256Hash, BlockTransaction> served = new HashMap<>();
        int calls;
        boolean fail;

        @Override
        public Map<Sha256Hash, BlockTransaction> transactions(Set<Sha256Hash> txids) throws ServerException {
            calls++;
            if(fail) {
                throw new ServerException("connection reset");
            }
            Map<Sha256Hash, BlockTransaction> result = new HashMap<>();
            for(Sha256Hash txid : txids) {
                result.put(txid, served.get(txid));
            }
            return result;
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

    @Test
    public void walletTransactionsDoNotReachTheServer() throws Exception {
        Transaction own = tx(1);
        Sha256Hash block = Sha256Hash.wrap(String.format("%064x", 99));
        FakeServer server = new FakeServer();
        ServerChainSource source = new ServerChainSource(Map.of(own.getTxId(), blockTx(own, 500, block)), server);
        ChainTx chainTx = source.transaction(own.getTxId());
        Assertions.assertFalse(chainTx.fromServer());
        Assertions.assertEquals(500, chainTx.height());
        Assertions.assertEquals(block, chainTx.blockHash());
        Assertions.assertEquals(1_700_000_000L, chainTx.blockTime());
        Assertions.assertEquals(0, server.calls);
    }

    @Test
    public void serverTransactionsAreCounted() throws Exception {
        Transaction other = tx(2);
        FakeServer server = new FakeServer();
        server.served.put(other.getTxId(), blockTx(other, 0, Sha256Hash.ZERO_HASH));
        ChainTx chainTx = new ServerChainSource(Map.of(), server).transaction(other.getTxId());
        Assertions.assertTrue(chainTx.fromServer());
        Assertions.assertEquals(0, chainTx.height());
        Assertions.assertNull(chainTx.blockHash());
        Assertions.assertEquals(0, chainTx.blockTime());
    }

    @Test
    public void serverErrorsBecomeIoExceptions() {
        FakeServer server = new FakeServer();
        server.fail = true;
        Assertions.assertThrows(IOException.class, () -> new ServerChainSource(Map.of(), server).transaction(tx(3).getTxId()));
    }

    @Test
    public void missingTransactionIsAnIoException() {
        Assertions.assertThrows(IOException.class, () -> new ServerChainSource(Map.of(), new FakeServer()).transaction(tx(4).getTxId()));
    }
}
