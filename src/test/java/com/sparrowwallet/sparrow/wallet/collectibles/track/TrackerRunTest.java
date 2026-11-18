package com.sparrowwallet.sparrow.wallet.collectibles.track;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.ordinals.track.TxCache;
import com.sparrowwallet.drongo.ordinals.track.Verdict;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.wallet.InscriptionCheck;
import com.sparrowwallet.sparrow.wallet.collectibles.CollectiblesClassifier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.sparrowwallet.sparrow.wallet.collectibles.track.TrackFakes.*;

public class TrackerRunTest {
    private final Chain chain = new Chain();
    private final List<Integer> progress = new ArrayList<>();

    private static CollectiblesClassifier.Txo txo(Transaction tx, int height) {
        return new CollectiblesClassifier.Txo(tx.getTxId() + ":0", null, height, tx.getTxId().toString());
    }

    /** A coin at 2,500,000 whose parent is below Litecoin's first inscription: clean after two lookups. */
    private Transaction cleanCoin(int n) {
        Transaction parent = chain.add(spend(hash(n), 10_000), 2_000_000);
        return chain.add(spend(parent.getTxId(), 9_000), 2_500_000);
    }

    private Map<String, InscriptionCheck> run(List<CollectiblesClassifier.Txo> utxos, Map<String, InscriptionCheck> stored, Map<String, Sha256Hash> blocks,
                                              Set<String> deeper) {
        return TrackerRun.run(Coin.LITECOIN, Network.MAINNET, new TxCache(), chain, utxos, stored, Map.of(), blocks, Map.of(), null, deeper,
                (done, total) -> progress.add(done));
    }

    @Test
    public void storedFinalRowIsKeptWithoutLookups() {
        Transaction coin = cleanCoin(1);
        InscriptionCheck stored = new InscriptionCheck(coin.getTxId(), 0, Verdict.CLEAN, InscriptionCheck.Source.BUILT_IN, false, List.of(), null, block(2_500_000), List.of());
        Map<String, InscriptionCheck> result = run(List.of(txo(coin, 2_500_000)), Map.of(stored.outpoint(), stored), Map.of(coin.getTxId().toString(), block(2_500_000)), Set.of());
        Assertions.assertSame(stored, result.get(stored.outpoint()));
        Assertions.assertEquals(0, chain.calls);
    }

    @Test
    public void reorgedRowIsTracedAgain() {
        Transaction coin = cleanCoin(1);
        InscriptionCheck stored = new InscriptionCheck(coin.getTxId(), 0, Verdict.CLEAN, InscriptionCheck.Source.BUILT_IN, false, List.of(), null, block(1), List.of());
        Map<String, InscriptionCheck> result = run(List.of(txo(coin, 2_500_000)), Map.of(stored.outpoint(), stored), Map.of(coin.getTxId().toString(), block(2_500_000)), Set.of());
        Assertions.assertTrue(chain.calls > 0);
        Assertions.assertEquals(Verdict.CLEAN, result.get(stored.outpoint()).verdict());
        Assertions.assertEquals(block(2_500_000), result.get(stored.outpoint()).blockHash());
    }

    @Test
    public void deeperUsesTheDeeperBudget() {
        Transaction previous = chain.add(spend(hash(1), 10_000), 2_000_000);
        for(int i = 0; i < 250; i++) {
            previous = chain.add(spend(previous.getTxId(), 10_000), 2_500_000 + i);
        }
        String outpoint = previous.getTxId() + ":0";
        Map<String, Sha256Hash> blocks = Map.of(previous.getTxId().toString(), block(2_500_249));
        Assertions.assertEquals(Verdict.UNCHECKED, run(List.of(txo(previous, 2_500_249)), Map.of(), blocks, Set.of()).get(outpoint).verdict());
        Assertions.assertEquals(Verdict.CLEAN, run(List.of(txo(previous, 2_500_249)), Map.of(), blocks, Set.of(outpoint)).get(outpoint).verdict());
    }

    @Test
    public void unconfirmedCoinHasNoBlockHash() {
        Transaction parent = chain.add(spend(hash(1), 10_000), 2_000_000);
        Transaction coin = chain.add(spend(parent.getTxId(), 9_000), 0);
        InscriptionCheck check = run(List.of(txo(coin, 0)), Map.of(), Map.of(), Set.of()).get(coin.getTxId() + ":0");
        Assertions.assertEquals(Verdict.CLEAN, check.verdict());
        Assertions.assertNull(check.blockHash());
    }

    @Test
    public void openPiecesResume() {
        Transaction previous = chain.add(spend(hash(1), 10_000), 2_000_000);
        for(int i = 0; i < 250; i++) {
            previous = chain.add(spend(previous.getTxId(), 10_000), 2_500_000 + i);
        }
        String outpoint = previous.getTxId() + ":0";
        Map<String, Sha256Hash> blocks = Map.of(previous.getTxId().toString(), block(2_500_249));
        InscriptionCheck first = run(List.of(txo(previous, 2_500_249)), Map.of(), blocks, Set.of()).get(outpoint);
        Assertions.assertFalse(first.open().isEmpty());
        InscriptionCheck second = run(List.of(txo(previous, 2_500_249)), Map.of(outpoint, first), blocks, Set.of()).get(outpoint);
        Assertions.assertEquals(Verdict.CLEAN, second.verdict());
    }

    @Test
    public void progressPerCoin() {
        Transaction a = cleanCoin(1);
        Transaction b = cleanCoin(2);
        run(List.of(txo(a, 2_500_000), txo(b, 2_500_000)), Map.of(), Map.of(), Set.of());
        Assertions.assertEquals(List.of(1, 2), progress);
    }

    @Test
    public void ownCoinsNeedNoServer() {
        Transaction coin = cleanCoin(1);
        Map<String, InscriptionCheck> result = run(List.of(txo(coin, 2_500_000)), Map.of(), Map.of(), Set.of());
        Assertions.assertEquals(InscriptionCheck.Source.BUILT_IN, result.get(coin.getTxId() + ":0").source());
    }

    /** Review I1: a row dropped for a reorg is not read back as the coin's own known result. */
    @Test
    public void reorgDropsAWrongRow() {
        Transaction coin = cleanCoin(1);
        InscriptionCheck wrong = new InscriptionCheck(coin.getTxId(), 0, Verdict.INSCRIBED, InscriptionCheck.Source.BUILT_IN, false,
                List.of(new com.sparrowwallet.drongo.ordinals.track.Found(hash(9) + "i0", 0, null, hash(9), 1, 1)), null, block(1), List.of());
        Map<String, InscriptionCheck> result = run(List.of(txo(coin, 2_500_000)), Map.of(wrong.outpoint(), wrong), Map.of(coin.getTxId().toString(), block(2_500_000)), Set.of());
        Assertions.assertEquals(Verdict.CLEAN, result.get(wrong.outpoint()).verdict());
    }

    /** Review C2: a stored Inscribed row whose trace stopped short is not trusted for the units it did not trace. */
    @Test
    public void incompleteInscribedRowIsNotTrusted() {
        //An old coin carries Y at offset 6,000 through a reveal; A spends it; A's row only knows X at 0 and stopped short
        Transaction parent = chain.add(spend(hash(1), 10_000), 2_000_000);
        Transaction revealY = chain.add(reveal(parent.getTxId(), "Y", 4_000), 2_500_000);
        Transaction older = chain.add(spend(hash(2), 6_000), 2_000_000);
        Transaction a = new Transaction();
        a.setVersion(2);
        a.addInput(older.getTxId(), 0, new com.sparrowwallet.drongo.protocol.Script(new byte[0]));
        a.addInput(revealY.getTxId(), 0, new com.sparrowwallet.drongo.protocol.Script(new byte[0]));
        a.addOutput(10_000, new com.sparrowwallet.drongo.protocol.Script(new byte[] {0x51}));
        chain.add(a, 2_500_001);
        InscriptionCheck aRow = new InscriptionCheck(a.getTxId(), 0, Verdict.INSCRIBED, InscriptionCheck.Source.BUILT_IN, false,
                List.of(new com.sparrowwallet.drongo.ordinals.track.Found(hash(9) + "i0", 0, null, hash(9), 1, 1)), "Lookup budget used up; use Check deeper",
                block(2_500_001), List.of(new com.sparrowwallet.drongo.ordinals.track.Piece(revealY.getTxId(), 0, new com.sparrowwallet.drongo.ordinals.track.UnitRange(0, 4_000), 6_000)));
        //The user sends X (the first 1,000 units) and keeps the change
        Transaction send = chain.add(spend(a.getTxId(), 1_000, 9_000), 2_500_002);
        String change = send.getTxId() + ":1";
        Map<String, InscriptionCheck> result = run(List.of(new CollectiblesClassifier.Txo(change, null, 2_500_002, send.getTxId().toString())),
                Map.of(aRow.outpoint(), aRow), Map.of(send.getTxId().toString(), block(2_500_002)), Set.of());
        Assertions.assertEquals(Verdict.INSCRIBED, result.get(change).verdict(), String.valueOf(result.get(change)));
    }
}