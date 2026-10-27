package com.sparrowwallet.sparrow.wallet.collectibles;

import com.sparrowwallet.drongo.ordinals.OrdinalsScan;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionInput;
import com.sparrowwallet.drongo.wallet.BlockTransaction;
import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.Status;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.net.ord.OrdOutput;

import java.util.*;

/**
 * Decides, for every output of a wallet, whether it carries an inscription or runes. In order:
 * <ol>
 * <li>A PROTECTED or RELEASED output carries (the stored status outlives any server answer).</li>
 * <li>An indexed server answer decides.</li>
 * <li>An output of the wallet's own transaction is clean when every input is a clean output of this wallet and the
 * transaction has no inscription envelope and no runestone: ord's sat flow cannot put anything on such outputs.</li>
 * <li>Anything else is unknown, including coins received from others before the server has indexed them.</li>
 * </ol>
 */
public final class CollectiblesClassifier {
    private static final int MAX_DEPTH = 50;

    private CollectiblesClassifier() {
    }

    /** A wallet output as the classifier sees it: outpoint "txid:index", stored status, height (0 or less when unconfirmed). */
    public record Txo(String outpoint, Status status, int height, String txid) {
    }

    public static Map<String, CollectibleState> classify(Collection<Txo> walletTxos, Map<String, Transaction> walletTxs, Map<String, OrdOutput> serverAnswers) {
        Map<String, Txo> byOutpoint = new HashMap<>();
        for(Txo txo : walletTxos) {
            byOutpoint.put(txo.outpoint(), txo);
        }

        Map<String, CollectibleState> states = new HashMap<>();
        for(Txo txo : walletTxos) {
            classify(txo, byOutpoint, walletTxs, serverAnswers, states, new HashSet<>(), 0);
        }
        return states;
    }

    private static CollectibleState classify(Txo txo, Map<String, Txo> byOutpoint, Map<String, Transaction> walletTxs, Map<String, OrdOutput> answers,
                                             Map<String, CollectibleState> states, Set<String> inProgress, int depth) {
        CollectibleState known = states.get(txo.outpoint());
        if(known != null) {
            return known;
        }

        CollectibleState state;
        if(txo.status() == Status.PROTECTED || txo.status() == Status.RELEASED) {
            state = CollectibleState.CARRIES;
        } else if(answers.get(txo.outpoint()) != null && answers.get(txo.outpoint()).indexed()) {
            state = answers.get(txo.outpoint()).carries() ? CollectibleState.CARRIES : CollectibleState.CLEAN;
        } else if(depth >= MAX_DEPTH || !inProgress.add(txo.outpoint())) {
            //Too long a chain of unconfirmed transactions to follow; decided again on the next check, so not cached
            return CollectibleState.UNKNOWN;
        } else {
            state = provenance(txo, byOutpoint, walletTxs, answers, states, inProgress, depth);
            inProgress.remove(txo.outpoint());
            if(state == CollectibleState.UNKNOWN && depth > 0) {
                return state;
            }
        }

        states.put(txo.outpoint(), state);
        return state;
    }

    private static CollectibleState provenance(Txo txo, Map<String, Txo> byOutpoint, Map<String, Transaction> walletTxs, Map<String, OrdOutput> answers,
                                               Map<String, CollectibleState> states, Set<String> inProgress, int depth) {
        Transaction tx = walletTxs.get(txo.txid());
        if(tx == null || OrdinalsScan.hasEnvelope(tx) || OrdinalsScan.hasRunestone(tx)) {
            return CollectibleState.UNKNOWN;
        }
        for(TransactionInput input : tx.getInputs()) {
            Txo spent = byOutpoint.get(input.getOutpoint().getHash().toString() + ":" + input.getOutpoint().getIndex());
            if(spent == null || classify(spent, byOutpoint, walletTxs, answers, states, inProgress, depth + 1) != CollectibleState.CLEAN) {
                return CollectibleState.UNKNOWN;
            }
        }
        return CollectibleState.CLEAN;
    }

    public static List<Txo> txos(Wallet wallet) {
        List<Txo> txos = new ArrayList<>();
        for(BlockTransactionHashIndex txo : wallet.getWalletTxos().keySet()) {
            txos.add(new Txo(txo.toString(), txo.getStatus(), txo.getHeight(), txo.getHash().toString()));
        }
        return txos;
    }

    public static Map<String, Transaction> transactions(Wallet wallet) {
        Map<String, Transaction> txs = new HashMap<>();
        for(BlockTransaction blockTransaction : wallet.getWalletTransactions().values()) {
            if(blockTransaction.getTransaction() != null) {
                txs.put(blockTransaction.getHash().toString(), blockTransaction.getTransaction());
            }
        }
        return txs;
    }
}
