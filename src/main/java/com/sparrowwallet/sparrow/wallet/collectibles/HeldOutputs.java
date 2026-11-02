package com.sparrowwallet.sparrow.wallet.collectibles;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.ordinals.CarrierScan;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.wallet.BlockTransaction;
import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.Status;
import com.sparrowwallet.drongo.wallet.TxoFilter;
import com.sparrowwallet.drongo.wallet.Wallet;

import java.math.BigDecimal;
import java.util.*;

/**
 * For a wallet whose coin has no inscriptions server set, the outputs CarrierScan flags are held: automatic coin
 * selection leaves them out, and a send that uses them asks first. Holding is not locking: the user can mark one as a
 * plain coin (RELEASED), and an output a server showed to carry something is PROTECTED instead.
 */
public final class HeldOutputs {
    public static final String CREATED_BY_INSCRIPTION = "It was created by a transaction that writes an inscription or tokens";

    private HeldOutputs() {
    }

    public static boolean isHeld(Coin coin, BlockTransactionHashIndex utxo, Map<Sha256Hash, BlockTransaction> transactions) {
        if(utxo.getStatus() == Status.RELEASED || utxo.getStatus() == Status.PROTECTED) {
            return false;
        }
        return CarrierScan.mayCarry(coin, creatingTransaction(utxo, transactions), (int)utxo.getIndex(), utxo.getValue());
    }

    /** Why the output is held, for the warning and the tooltip. */
    public static String reason(Coin coin, BlockTransactionHashIndex utxo, Map<Sha256Hash, BlockTransaction> transactions) {
        Transaction tx = creatingTransaction(utxo, transactions);
        if(tx != null && CarrierScan.marksOutputs(tx)) {
            return CREATED_BY_INSCRIPTION;
        }
        return "It holds " + BigDecimal.valueOf(utxo.getValue(), 8).stripTrailingZeros().toPlainString() + " " + coin.getTicker() + ", the usual value of an inscription";
    }

    public static boolean isHeld(Wallet wallet, BlockTransactionHashIndex utxo) {
        return isHeld(wallet.getCoin(), utxo, transactions(wallet));
    }

    public static String reason(Wallet wallet, BlockTransactionHashIndex utxo) {
        return reason(wallet.getCoin(), utxo, transactions(wallet));
    }

    public static List<BlockTransactionHashIndex> held(Wallet wallet, Collection<BlockTransactionHashIndex> utxos) {
        Map<Sha256Hash, BlockTransaction> transactions = transactions(wallet);
        return utxos.stream().filter(utxo -> isHeld(wallet.getCoin(), utxo, transactions)).toList();
    }

    /** The wallet's transactions, and its master's for a nested wallet, by txid. */
    static Map<Sha256Hash, BlockTransaction> transactions(Wallet wallet) {
        if(!wallet.isNested()) {
            return wallet.getWalletTransactions();
        }
        Map<Sha256Hash, BlockTransaction> transactions = new HashMap<>(wallet.getMasterWallet().getWalletTransactions());
        transactions.putAll(wallet.getWalletTransactions());
        return transactions;
    }

    private static Transaction creatingTransaction(BlockTransactionHashIndex utxo, Map<Sha256Hash, BlockTransaction> transactions) {
        BlockTransaction blockTransaction = transactions.get(utxo.getHash());
        return blockTransaction == null ? null : blockTransaction.getTransaction();
    }

    /** Admits only outputs that are not held. */
    public static class Filter implements TxoFilter {
        private final Coin coin;
        private final Map<Sha256Hash, BlockTransaction> transactions;

        public Filter(Coin coin, Map<Sha256Hash, BlockTransaction> transactions) {
            this.coin = coin;
            this.transactions = transactions;
        }

        public Filter(Wallet wallet) {
            this(wallet.getCoin(), transactions(wallet));
        }

        @Override
        public boolean isEligible(BlockTransactionHashIndex candidate) {
            return !isHeld(coin, candidate, transactions);
        }
    }
}
