package com.sparrowwallet.sparrow.wallet.collectibles;

import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.InsufficientFundsException;
import com.sparrowwallet.drongo.wallet.TxoFilter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.Map;

public class CheckedTxoFilterTest {
    private static BlockTransactionHashIndex utxo(int index) {
        return new BlockTransactionHashIndex(Sha256Hash.ZERO_HASH, 100, new Date(), 0L, index, 10000);
    }

    @Test
    public void onlyCheckedCleanOutputsAreEligible() {
        BlockTransactionHashIndex clean = utxo(0);
        BlockTransactionHashIndex unknown = utxo(1);
        BlockTransactionHashIndex unseen = utxo(2);
        CollectiblesManager.Snapshot snapshot = new CollectiblesManager.Snapshot(
                Map.of(clean.toString(), CollectibleState.CLEAN, unknown.toString(), CollectibleState.UNKNOWN), Map.of(), Instant.now(), "http://127.0.0.1:8085", null, true, true);
        CheckedTxoFilter filter = new CheckedTxoFilter(snapshot);
        Assertions.assertTrue(filter.isEligible(clean));
        Assertions.assertFalse(filter.isEligible(unknown));
        Assertions.assertFalse(filter.isEligible(unseen));
    }

    @Test
    public void checkedCoinsAreTriedFirst() throws Exception {
        TxoFilter checked = new CheckedTxoFilter(null);
        List<List<TxoFilter>> attempts = new ArrayList<>();
        String result = CheckedTxoFilter.preferChecked(checked, List.of(), filters -> {
            attempts.add(filters);
            return "built";
        });
        Assertions.assertEquals("built", result);
        Assertions.assertEquals(1, attempts.size());
        Assertions.assertTrue(attempts.getFirst().contains(checked));
    }

    @Test
    public void fallsBackToAllCoinsWhenCheckedAreShort() throws Exception {
        TxoFilter checked = new CheckedTxoFilter(null);
        List<List<TxoFilter>> attempts = new ArrayList<>();
        String result = CheckedTxoFilter.preferChecked(checked, List.of(), filters -> {
            attempts.add(filters);
            if(filters.contains(checked)) {
                throw new InsufficientFundsException("short");
            }
            return "fallback";
        });
        Assertions.assertEquals("fallback", result);
        Assertions.assertEquals(2, attempts.size());
        Assertions.assertFalse(attempts.get(1).contains(checked));
    }

    @Test
    public void noPreferenceWhenChecksAreOff() throws Exception {
        List<List<TxoFilter>> attempts = new ArrayList<>();
        CheckedTxoFilter.preferChecked(null, List.of(), filters -> {
            attempts.add(filters);
            return "built";
        });
        Assertions.assertEquals(1, attempts.size());
    }

    @Test
    public void nothingIsCheckedBeforeTheFirstCheck() {
        Assertions.assertFalse(new CheckedTxoFilter(null).isEligible(utxo(0)));
    }
}
