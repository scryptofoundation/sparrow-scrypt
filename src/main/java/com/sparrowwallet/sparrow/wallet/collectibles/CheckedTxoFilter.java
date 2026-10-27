package com.sparrowwallet.sparrow.wallet.collectibles;

import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.InsufficientFundsException;
import com.sparrowwallet.drongo.wallet.TxoFilter;

import java.util.ArrayList;
import java.util.List;

/**
 * Admits only outputs shown to carry nothing. Automatic coin selection tries with this first, so unchecked coins
 * (which might hold an inscription or runes) are only spent, with a warning, when the checked ones are not enough.
 */
public class CheckedTxoFilter implements TxoFilter {
    private final CollectiblesManager.Snapshot snapshot;

    public CheckedTxoFilter(CollectiblesManager.Snapshot snapshot) {
        this.snapshot = snapshot;
    }

    @Override
    public boolean isEligible(BlockTransactionHashIndex candidate) {
        return CollectiblesManager.unknownIn(snapshot, List.of(candidate)).isEmpty();
    }

    public interface Attempt<T> {
        T build(List<TxoFilter> filters) throws InsufficientFundsException;
    }

    /** Builds with the checked filter added; if that cannot cover the payment, builds again with the filters as given. */
    public static <T> T preferChecked(TxoFilter checked, List<TxoFilter> filters, Attempt<T> attempt) throws InsufficientFundsException {
        if(checked != null) {
            List<TxoFilter> preferred = new ArrayList<>(filters);
            preferred.add(checked);
            try {
                return attempt.build(preferred);
            } catch(InsufficientFundsException e) {
                //Not enough in checked coins; unchecked ones may be used, and the send warns about them
            }
        }
        return attempt.build(filters);
    }
}
