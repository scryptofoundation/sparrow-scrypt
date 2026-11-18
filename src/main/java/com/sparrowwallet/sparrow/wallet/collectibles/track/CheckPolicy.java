package com.sparrowwallet.sparrow.wallet.collectibles.track;

import com.sparrowwallet.drongo.ordinals.CarrierScan;
import com.sparrowwallet.drongo.ordinals.OrdinalsScan;
import com.sparrowwallet.drongo.ordinals.track.ChainTx;
import com.sparrowwallet.drongo.ordinals.track.Found;
import com.sparrowwallet.drongo.ordinals.track.TraceResult;
import com.sparrowwallet.drongo.ordinals.track.Verdict;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.wallet.InscriptionCheck;
import com.sparrowwallet.drongo.wallet.Status;
import com.sparrowwallet.sparrow.net.ord.OrdOutput;
import com.sparrowwallet.sparrow.wallet.collectibles.CollectibleState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Decides one coin from what an indexer said (if anything) and what the on-chain trace showed. Nothing is clean unless
 * shown clean; an indexer and the chain that disagree leave the coin held.
 */
public final class CheckPolicy {
    static final String DISAGREE_NOT_ON_CHAIN = "Indexer and chain disagree: %s not found on chain";
    static final String DISAGREE_CHAIN_SHOWS = "Indexer and chain disagree: chain shows %s";
    static final String MAY_CARRY_RUNES = "May carry runes";

    private CheckPolicy() {
    }

    /**
     * answer is the indexer's vetted answer, or null without one (no indexer, unreachable, or not indexed). reveals maps
     * each inscription id the indexer named to the transaction that creates it, or to null when the chain shows none.
     */
    public static InscriptionCheck decide(Sha256Hash txid, int vout, OrdOutput answer, InscriptionCheck.Source indexerSource, TraceResult trace,
                                          Map<String, ChainTx> reveals, Transaction creatingTx, Sha256Hash blockHash) {
        if(answer != null && answer.indexed()) {
            boolean runes = !answer.runes().isEmpty() || answer.unlisted();
            if(!answer.inscriptions().isEmpty()) {
                List<Found> found = new ArrayList<>();
                for(String id : answer.inscriptions()) {
                    ChainTx reveal = reveals.get(id);
                    if(reveal == null) {
                        return unchecked(txid, vout, indexerSource, runes, String.format(DISAGREE_NOT_ON_CHAIN, id), blockHash, trace);
                    }
                    Found traced = trace.found().stream().filter(f -> f.inscriptionId().equals(id)).findFirst().orElse(null);
                    found.add(traced != null ? traced : new Found(id, -1, null, reveal.tx().getTxId(), reveal.height(), reveal.blockTime()));
                }
                //An inscription on the chain the indexer did not list: the two disagree, so the coin is held
                for(Found traced : trace.found()) {
                    if(!answer.inscriptions().contains(traced.inscriptionId())) {
                        return unchecked(txid, vout, indexerSource, runes, String.format(DISAGREE_CHAIN_SHOWS, traced.inscriptionId()), blockHash, trace);
                    }
                }
                return new InscriptionCheck(txid, vout, Verdict.INSCRIBED, indexerSource, runes, found, null, blockHash, List.of());
            }
            if(trace.verdict() == Verdict.INSCRIBED) {
                return unchecked(txid, vout, indexerSource, runes, String.format(DISAGREE_CHAIN_SHOWS, trace.found().getFirst().inscriptionId()), blockHash, trace);
            }
            return new InscriptionCheck(txid, vout, Verdict.CLEAN, indexerSource, runes, List.of(), null, blockHash, List.of());
        }

        InscriptionCheck.Source source = trace.usedChain() ? InscriptionCheck.Source.BUILT_IN : InscriptionCheck.Source.OWN;
        if(trace.verdict() == Verdict.CLEAN && creatingTx != null && (OrdinalsScan.hasRunestone(creatingTx) || CarrierScan.hasDunestone(creatingTx))) {
            return new InscriptionCheck(txid, vout, Verdict.UNCHECKED, source, false, List.of(), MAY_CARRY_RUNES, blockHash, List.of());
        }
        //An Inscribed trace that stopped short keeps its reason and open pieces: it is not complete, and resumes
        return new InscriptionCheck(txid, vout, trace.verdict(), source, false, trace.found(), trace.verdict() == Verdict.CLEAN ? null : trace.reason(), blockHash,
                trace.verdict() == Verdict.CLEAN ? List.of() : trace.open());
    }

    private static InscriptionCheck unchecked(Sha256Hash txid, int vout, InscriptionCheck.Source source, boolean runes, String reason, Sha256Hash blockHash,
                                              TraceResult trace) {
        return new InscriptionCheck(txid, vout, Verdict.UNCHECKED, source, runes, trace.found(), reason, blockHash, List.of());
    }

    /**
     * The answer the rest of the app reads for a coin: the indexer's own when the check agrees with it, otherwise one
     * built from the check (an unknown one for an unchecked coin).
     */
    public static OrdOutput asAnswer(InscriptionCheck check, OrdOutput indexerAnswer, long value) {
        String outpoint = check.outpoint();
        if(check.verdict() == Verdict.UNCHECKED) {
            return OrdOutput.unknown(outpoint);
        }
        boolean carries = check.verdict() == Verdict.INSCRIBED || check.runes();
        if(indexerAnswer != null && indexerAnswer.indexed() && indexerAnswer.carries() == carries
                && !(check.verdict() == Verdict.INSCRIBED && !complete(check))) {
            return indexerAnswer;
        }
        List<String> ids = new ArrayList<>();
        //An Inscribed coin whose trace stopped short may carry more than it found: listed as carrying something unnamed,
        //so a send never splits it
        boolean unlisted = check.runes() || (check.verdict() == Verdict.INSCRIBED && !complete(check));
        for(Found found : check.found()) {
            if(found.inscriptionId().startsWith("?")) {
                unlisted = true;
            } else if(!ids.contains(found.inscriptionId())) {
                ids.add(found.inscriptionId());
            }
        }
        return new OrdOutput(outpoint, true, List.copyOf(ids), Map.of(), value, false, unlisted);
    }

    /**
     * Whether a check can stand for its coin's units in later traces: Clean, or Inscribed with nothing left to trace. An
     * Inscribed result that stopped short (a reason, open pieces) says nothing about the units it did not reach.
     */
    public static boolean complete(InscriptionCheck check) {
        if(check == null || check.runes() || check.reason() != null) {
            return false;
        }
        return check.verdict() == Verdict.CLEAN || (check.verdict() == Verdict.INSCRIBED && check.open().isEmpty());
    }

    /** The state the guard reads: a coin the user marked protected or released stays carrying, as before. */
    public static CollectibleState state(InscriptionCheck check, Status status) {
        if(status == Status.PROTECTED || status == Status.RELEASED) {
            return CollectibleState.CARRIES;
        }
        if(check == null || check.verdict() == Verdict.UNCHECKED) {
            return CollectibleState.UNKNOWN;
        }
        return check.verdict() == Verdict.INSCRIBED || check.runes() ? CollectibleState.CARRIES : CollectibleState.CLEAN;
    }
}
