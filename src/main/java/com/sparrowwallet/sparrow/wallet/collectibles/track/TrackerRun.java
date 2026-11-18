package com.sparrowwallet.sparrow.wallet.collectibles.track;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.ordinals.track.*;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.wallet.InscriptionCheck;
import com.sparrowwallet.sparrow.net.ord.OrdOutput;
import com.sparrowwallet.sparrow.wallet.collectibles.CollectiblesClassifier;

import java.io.IOException;
import java.util.*;
import java.util.function.BiConsumer;

/** One check of a wallet's unspent coins: stored results kept where still good, the rest traced and decided. */
public final class TrackerRun {
    private TrackerRun() {
    }

    /**
     * Returns a check for every coin in utxos. A stored Clean or Inscribed check stands while its coin's confirming block
     * (confirmingBlocks, by txid) is unchanged and the indexer's answer does not contradict it; a changed block (a reorg)
     * drops it. Others are traced, resuming the open pieces of a stored Unchecked check, with the deeper budget and the
     * block walk for outpoints in deeper. progress is told (done, total) after each coin.
     */
    public static Map<String, InscriptionCheck> run(Coin coin, Network network, TxCache cache, ChainSource source, List<CollectiblesClassifier.Txo> utxos,
                                                    Map<String, InscriptionCheck> stored, Map<String, Transaction> walletTxs, Map<String, Sha256Hash> confirmingBlocks,
                                                    Map<String, OrdOutput> answers, InscriptionCheck.Source indexerSource, Set<String> deeper,
                                                    BiConsumer<Integer, Integer> progress) {
        Map<String, InscriptionCheck> results = new LinkedHashMap<>();
        //The coin being traced is never its own known result (a row dropped for a reorg must not come back that way),
        //and only complete checks stand for their units
        String[] tracing = new String[1];
        KnownResults known = (txid, vout) -> {
            String outpoint = txid + ":" + vout;
            if(outpoint.equals(tracing[0])) {
                return Optional.empty();
            }
            InscriptionCheck check = results.containsKey(outpoint) ? results.get(outpoint) : stored.get(outpoint);
            return CheckPolicy.complete(check) ? Optional.of(new KnownOutput(check.verdict(), check.found())) : Optional.empty();
        };
        InscriptionTracer tracer = new InscriptionTracer(coin, network, source, known, cache);

        int done = 0;
        for(CollectiblesClassifier.Txo utxo : utxos) {
            String outpoint = utxo.outpoint();
            Sha256Hash txid = Sha256Hash.wrap(utxo.txid());
            int vout = Integer.parseInt(outpoint.substring(outpoint.indexOf(':') + 1));
            Sha256Hash blockHash = confirmingBlocks.get(utxo.txid());
            OrdOutput answer = answers.get(outpoint);
            InscriptionCheck previous = stored.get(outpoint);
            if(previous != null && previous.blockHash() != null && !previous.blockHash().equals(blockHash)) {
                previous = null;
            }

            if(CheckPolicy.complete(previous) && previous.blockHash() != null && !deeper.contains(outpoint) && agrees(previous, answer)) {
                results.put(outpoint, previous);
            } else {
                boolean deep = deeper.contains(outpoint);
                tracing[0] = outpoint;
                //Only an Unchecked trace resumes; an Inscribed one that stopped short starts over, so what it found is found again
                List<Piece> resume = previous != null && previous.verdict() == Verdict.UNCHECKED ? previous.open() : List.of();
                TraceResult trace = tracer.trace(txid, vout, resume, deep ? InscriptionTracer.DEEPER_BUDGET : InscriptionTracer.DEFAULT_BUDGET, deep);
                InscriptionCheck check;
                OrdOutput indexed = answer != null && answer.indexed() ? answer : null;
                try {
                    Map<String, ChainTx> reveals = new HashMap<>();
                    if(indexed != null) {
                        for(String id : indexed.inscriptions()) {
                            reveals.put(id, RevealCheck.reveal(id, source));
                        }
                    }
                    check = CheckPolicy.decide(txid, vout, indexed, indexerSource, trace, reveals, walletTxs.get(utxo.txid()), blockHash);
                } catch(IOException e) {
                    check = new InscriptionCheck(txid, vout, Verdict.UNCHECKED, indexerSource == null ? InscriptionCheck.Source.BUILT_IN : indexerSource, false,
                            List.of(), String.format("Server error, will retry next sync: %s", e.getMessage()), blockHash, List.of());
                }
                results.put(outpoint, check);
            }
            progress.accept(++done, utxos.size());
        }
        return results;
    }

    /** A stored result stands unless an indexer now answers otherwise about whether the coin carries something. */
    private static boolean agrees(InscriptionCheck check, OrdOutput answer) {
        if(answer == null || !answer.indexed()) {
            return true;
        }
        return answer.carries() == (check.verdict() == Verdict.INSCRIBED || check.runes());
    }
}
