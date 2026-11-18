package com.sparrowwallet.sparrow.wallet.collectibles.track;

import com.sparrowwallet.drongo.ordinals.track.BlockFee;
import com.sparrowwallet.drongo.ordinals.track.ChainSource;
import com.sparrowwallet.drongo.ordinals.track.ChainTx;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.wallet.BlockTransaction;
import com.sparrowwallet.sparrow.net.ElectrumServer;
import com.sparrowwallet.sparrow.net.ServerException;

import java.io.IOException;
import java.util.*;

/**
 * The tracer's view of the chain: the wallet's own transactions first, then the app's connection to the coin's server
 * (Electrum or Esplora). Callers scope the thread to the coin, as every server call needs.
 */
public class ServerChainSource implements ChainSource {
    /** The server calls the source makes; ElectrumServer in the app, a fake in tests. */
    public interface ServerCalls {
        Map<Sha256Hash, BlockTransaction> transactions(Set<Sha256Hash> txids) throws ServerException;

        Optional<List<BlockFee>> blockFees(int height, Sha256Hash blockHash) throws ServerException;

        Optional<Sha256Hash> txidAt(int height, int position) throws ServerException;

        default Optional<Sha256Hash> blockHash(int height) throws ServerException {
            return Optional.empty();
        }
    }

    private final Map<Sha256Hash, BlockTransaction> walletTransactions;
    private final ServerCalls server;

    public ServerChainSource(Map<Sha256Hash, BlockTransaction> walletTransactions, ServerCalls server) {
        this.walletTransactions = walletTransactions;
        this.server = server;
    }

    public static ServerChainSource of(Map<Sha256Hash, BlockTransaction> walletTransactions, ElectrumServer electrumServer) {
        return new ServerChainSource(walletTransactions, new ServerCalls() {
            @Override
            public Map<Sha256Hash, BlockTransaction> transactions(Set<Sha256Hash> txids) throws ServerException {
                return electrumServer.getReferencedTransactions(txids, null);
            }

            @Override
            public Optional<List<BlockFee>> blockFees(int height, Sha256Hash blockHash) throws ServerException {
                return electrumServer.getBlockFees(height, blockHash);
            }

            @Override
            public Optional<Sha256Hash> txidAt(int height, int position) throws ServerException {
                return electrumServer.getTransactionIdAt(height, position);
            }

            @Override
            public Optional<Sha256Hash> blockHash(int height) throws ServerException {
                return electrumServer.getBlockHashAt(height);
            }
        });
    }

    @Override
    public ChainTx transaction(Sha256Hash txid) throws IOException {
        BlockTransaction own = walletTransactions.get(txid);
        if(own != null && own.getTransaction() != null) {
            return chainTx(own, false);
        }
        try {
            BlockTransaction fetched = server.transactions(Set.of(txid)).get(txid);
            if(fetched == null || fetched.getTransaction() == null) {
                throw new IOException("The server did not return transaction " + txid);
            }
            return chainTx(fetched, true);
        } catch(ServerException | RuntimeException e) {
            throw new IOException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
        }
    }

    @Override
    public Optional<List<BlockFee>> blockFees(int height, Sha256Hash blockHash) throws IOException {
        try {
            return server.blockFees(height, blockHash);
        } catch(ServerException | RuntimeException e) {
            throw new IOException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
        }
    }

    @Override
    public Optional<Sha256Hash> txidAt(int height, int position) throws IOException {
        try {
            return server.txidAt(height, position);
        } catch(ServerException | RuntimeException e) {
            throw new IOException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
        }
    }

    @Override
    public Optional<Sha256Hash> blockHash(int height) throws IOException {
        try {
            return server.blockHash(height);
        } catch(ServerException | RuntimeException e) {
            throw new IOException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
        }
    }

    private static ChainTx chainTx(BlockTransaction blockTransaction, boolean fromServer) {
        boolean confirmed = blockTransaction.getHeight() > 0;
        Sha256Hash blockHash = confirmed && blockTransaction.getBlockHash() != null && !Sha256Hash.ZERO_HASH.equals(blockTransaction.getBlockHash())
                ? blockTransaction.getBlockHash() : null;
        long time = confirmed && blockTransaction.getDate() != null ? blockTransaction.getDate().getTime() / 1000 : 0;
        return new ChainTx(blockTransaction.getTransaction(), confirmed ? blockTransaction.getHeight() : 0, blockHash, time, fromServer);
    }
}
