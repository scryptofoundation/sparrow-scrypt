package com.sparrowwallet.sparrow.wallet.collectibles.track;

import com.sparrowwallet.drongo.ordinals.track.*;
import com.sparrowwallet.drongo.protocol.Script;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.wallet.InscriptionCheck;
import com.sparrowwallet.drongo.wallet.Status;
import com.sparrowwallet.sparrow.net.ord.OrdOutput;
import com.sparrowwallet.sparrow.net.ord.RuneBalance;
import com.sparrowwallet.sparrow.wallet.collectibles.CollectibleState;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.sparrowwallet.sparrow.wallet.collectibles.track.TrackFakes.*;

public class CheckPolicyTest {
    private static final Sha256Hash TXID = hash(1);
    private static final String OUTPOINT = TXID + ":0";
    private static final String ID = hash(7) + "i0";
    private static final Sha256Hash BLOCK = block(2_500_000);
    private static final ChainTx REVEAL = new ChainTx(spend(hash(2), 1_000), 2_400_000, block(2_400_000), 1_600_000_000L, true);

    private static TraceResult trace(Verdict verdict, List<Found> found, boolean usedChain) {
        return new TraceResult(verdict, found, verdict == Verdict.UNCHECKED ? "Lookup budget used up; use Check deeper" : null, List.of(), 3, usedChain);
    }

    private static OrdOutput answer(List<String> inscriptions) {
        return new OrdOutput(OUTPOINT, true, inscriptions, Map.of(), 10_000, false, false);
    }

    private static InscriptionCheck decide(OrdOutput answer, TraceResult trace, Map<String, ChainTx> reveals, Transaction creatingTx) {
        return CheckPolicy.decide(TXID, 0, answer, InscriptionCheck.Source.PUBLIC, trace, reveals, creatingTx, BLOCK);
    }

    @Test
    public void rule1RunesKeepTheVerdictAndMarkRunes() {
        OrdOutput runes = new OrdOutput(OUTPOINT, true, List.of(), Map.of("RUNE", new RuneBalance(java.math.BigInteger.ONE, 0, "R")), 10_000, false, false);
        InscriptionCheck check = decide(runes, trace(Verdict.CLEAN, List.of(), true), Map.of(), null);
        Assertions.assertEquals(Verdict.CLEAN, check.verdict());
        Assertions.assertTrue(check.runes());
        Assertions.assertEquals(CollectibleState.CARRIES, CheckPolicy.state(check, (Status)null));
    }

    @Test
    public void rule2IndexerInscriptionConfirmed() {
        Found traced = new Found(ID, 0, null, hash(7), 2_400_000, 1_600_000_000L);
        InscriptionCheck check = decide(answer(List.of(ID)), trace(Verdict.INSCRIBED, List.of(traced), true), Map.of(ID, REVEAL), null);
        Assertions.assertEquals(Verdict.INSCRIBED, check.verdict());
        Assertions.assertEquals(InscriptionCheck.Source.PUBLIC, check.source());
        Assertions.assertEquals(0, check.found().getFirst().offset());
        Assertions.assertEquals(BLOCK, check.blockHash());

        InscriptionCheck untraced = decide(answer(List.of(ID)), trace(Verdict.UNCHECKED, List.of(), true), Map.of(ID, REVEAL), null);
        Assertions.assertEquals(Verdict.INSCRIBED, untraced.verdict());
        Assertions.assertEquals(-1, untraced.found().getFirst().offset());
        Assertions.assertEquals(2_400_000, untraced.found().getFirst().mintHeight());
    }

    @Test
    public void rule3IndexerInscriptionNotOnChain() {
        Map<String, ChainTx> missing = new HashMap<>();
        missing.put(ID, null);
        InscriptionCheck check = decide(answer(List.of(ID)), trace(Verdict.CLEAN, List.of(), true), missing, null);
        Assertions.assertEquals(Verdict.UNCHECKED, check.verdict());
        Assertions.assertEquals("Indexer and chain disagree: " + ID + " not found on chain", check.reason());
    }

    @Test
    public void rule4IndexerCleanButChainShowsOne() {
        Found traced = new Found(ID, 0, null, hash(7), 2_400_000, 1L);
        InscriptionCheck check = decide(answer(List.of()), trace(Verdict.INSCRIBED, List.of(traced), true), Map.of(), null);
        Assertions.assertEquals(Verdict.UNCHECKED, check.verdict());
        Assertions.assertEquals("Indexer and chain disagree: chain shows " + ID, check.reason());
    }

    @Test
    public void rule5IndexerCleanStands() {
        Assertions.assertEquals(Verdict.CLEAN, decide(answer(List.of()), trace(Verdict.CLEAN, List.of(), true), Map.of(), null).verdict());
        InscriptionCheck budget = decide(answer(List.of()), trace(Verdict.UNCHECKED, List.of(), true), Map.of(), null);
        Assertions.assertEquals(Verdict.CLEAN, budget.verdict());
        Assertions.assertEquals(InscriptionCheck.Source.PUBLIC, budget.source());
        Assertions.assertNull(budget.reason());
    }

    @Test
    public void rule6NoAnswerTakesTheTrace() {
        InscriptionCheck own = decide(null, trace(Verdict.CLEAN, List.of(), false), Map.of(), null);
        Assertions.assertEquals(Verdict.CLEAN, own.verdict());
        Assertions.assertEquals(InscriptionCheck.Source.OWN, own.source());
        InscriptionCheck builtIn = decide(null, trace(Verdict.UNCHECKED, List.of(), true), Map.of(), null);
        Assertions.assertEquals(Verdict.UNCHECKED, builtIn.verdict());
        Assertions.assertEquals(InscriptionCheck.Source.BUILT_IN, builtIn.source());
        Assertions.assertEquals("Lookup budget used up; use Check deeper", builtIn.reason());
        Assertions.assertEquals(OrdOutput.unknown(OUTPOINT), CheckPolicy.asAnswer(builtIn, null, 10_000));
    }

    @Test
    public void rule7NoAnswerAndARunestoneMayCarryRunes() {
        Transaction runestone = spend(hash(3), 1_000);
        runestone.addOutput(0, new Script(new byte[] {0x6a, 0x5d, 0x00}));
        InscriptionCheck check = decide(null, trace(Verdict.CLEAN, List.of(), true), Map.of(), runestone);
        Assertions.assertEquals(Verdict.UNCHECKED, check.verdict());
        Assertions.assertEquals("May carry runes", check.reason());
    }

    @Test
    public void unindexedAnswerCountsAsNone() {
        InscriptionCheck check = decide(OrdOutput.unknown(OUTPOINT), trace(Verdict.CLEAN, List.of(), true), Map.of(), null);
        Assertions.assertEquals(InscriptionCheck.Source.BUILT_IN, check.source());
    }

    @Test
    public void asAnswerAndState() {
        InscriptionCheck clean = new InscriptionCheck(TXID, 0, Verdict.CLEAN, InscriptionCheck.Source.BUILT_IN, false, List.of(), null, BLOCK, List.of());
        Assertions.assertEquals(new OrdOutput(OUTPOINT, true, List.of(), Map.of(), 10_000, false, false), CheckPolicy.asAnswer(clean, null, 10_000));
        Assertions.assertEquals(CollectibleState.CLEAN, CheckPolicy.state(clean, (Status)null));
        Assertions.assertEquals(CollectibleState.CARRIES, CheckPolicy.state(clean, Status.PROTECTED));

        InscriptionCheck inscribed = new InscriptionCheck(TXID, 0, Verdict.INSCRIBED, InscriptionCheck.Source.BUILT_IN, false,
                List.of(new Found(ID, 0, null, hash(7), 1, 1), new Found("?i0", 0, null, hash(8), 1, 1)), null, BLOCK, List.of());
        Assertions.assertEquals(new OrdOutput(OUTPOINT, true, List.of(ID), Map.of(), 10_000, false, true), CheckPolicy.asAnswer(inscribed, null, 10_000));
        Assertions.assertEquals(CollectibleState.CARRIES, CheckPolicy.state(inscribed, (Status)null));

        OrdOutput indexer = answer(List.of(ID));
        InscriptionCheck agreeing = new InscriptionCheck(TXID, 0, Verdict.INSCRIBED, InscriptionCheck.Source.PUBLIC, false,
                List.of(new Found(ID, 0, null, hash(7), 1, 1)), null, BLOCK, List.of());
        Assertions.assertSame(indexer, CheckPolicy.asAnswer(agreeing, indexer, 10_000));

        InscriptionCheck unchecked = new InscriptionCheck(TXID, 0, Verdict.UNCHECKED, InscriptionCheck.Source.PUBLIC, false, List.of(), "x", BLOCK, List.of());
        Assertions.assertEquals(OrdOutput.unknown(OUTPOINT), CheckPolicy.asAnswer(unchecked, indexer, 10_000));
        Assertions.assertEquals(CollectibleState.UNKNOWN, CheckPolicy.state(unchecked, (Status)null));
        Assertions.assertEquals(CollectibleState.UNKNOWN, CheckPolicy.state(null, (Status)null));
    }

    @Test
    public void revealCheck() throws Exception {
        Chain chain = new Chain();
        Transaction reveal = chain.add(TrackFakes.reveal(hash(2), "hi", 10_000), 2_400_000);
        Transaction plain = chain.add(spend(hash(3), 1_000), 2_400_000);
        Assertions.assertNotNull(RevealCheck.reveal(reveal.getTxId() + "i0", chain));
        Assertions.assertNull(RevealCheck.reveal(reveal.getTxId() + "i1", chain));
        Assertions.assertNull(RevealCheck.reveal(plain.getTxId() + "i0", chain));
    }

    /** Review C2: a trace that found an inscription but stopped short keeps its reason and open pieces, and is never split on send. */
    @Test
    public void incompleteInscribedKeepsReasonAndOpen() {
        Piece open = new Piece(hash(5), 0, new UnitRange(0, 100), 200);
        Found found = new Found(ID, 0, null, hash(7), 2_400_000, 1L);
        TraceResult cut = new TraceResult(Verdict.INSCRIBED, List.of(found), "Lookup budget used up; use Check deeper", List.of(open), 200, true);
        InscriptionCheck check = decide(null, cut, Map.of(), null);
        Assertions.assertEquals(Verdict.INSCRIBED, check.verdict());
        Assertions.assertEquals("Lookup budget used up; use Check deeper", check.reason());
        Assertions.assertEquals(List.of(open), check.open());
        Assertions.assertTrue(CheckPolicy.asAnswer(check, null, 10_000).unlisted());
        Assertions.assertFalse(CheckPolicy.complete(check));
    }

    /** Review I3: an inscription the trace finds that the indexer did not list holds the coin. */
    @Test
    public void indexerMissedAnInscription() {
        String other = hash(8) + "i0";
        List<Found> traced = List.of(new Found(ID, 0, null, hash(7), 1, 1), new Found(other, 500, null, hash(8), 1, 1));
        InscriptionCheck check = decide(answer(List.of(ID)), trace(Verdict.INSCRIBED, traced, true), Map.of(ID, REVEAL), null);
        Assertions.assertEquals(Verdict.UNCHECKED, check.verdict());
        Assertions.assertEquals("Indexer and chain disagree: chain shows " + other, check.reason());
    }
}