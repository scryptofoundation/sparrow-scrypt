package com.sparrowwallet.sparrow.io.db;

import com.sparrowwallet.drongo.wallet.SwapEntry;
import com.sparrowwallet.drongo.wallet.Wallet;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;

public interface SwapEntryDao {
    @SqlQuery("select id, swapId, keyIndex, status, sealed, updatedSeconds from swapEntry where wallet = ? order by id")
    @RegisterRowMapper(SwapEntryMapper.class)
    List<SwapEntry> getForWalletId(Long id);

    @SqlUpdate("insert into swapEntry (swapId, keyIndex, status, sealed, updatedSeconds, wallet) values (?, ?, ?, ?, ?, ?)")
    @GetGeneratedKeys("id")
    long insert(String swapId, int keyIndex, String status, byte[] sealed, long updatedSeconds, long wallet);

    @SqlUpdate("delete from swapEntry where wallet = ?")
    void deleteForWalletId(long wallet);

    default void addSwapEntries(Wallet wallet) {
        if(!wallet.getSwapEntries().isEmpty()) {
            replace(wallet);
        }
    }

    /** Whatever is stored is replaced by the wallet's current swap entries. */
    default void replace(Wallet wallet) {
        deleteForWalletId(wallet.getId());
        for(SwapEntry entry : wallet.getSwapEntries()) {
            long id = insert(entry.getSwapId(), entry.getKeyIndex(), entry.getStatus(), entry.getSealed(), entry.getUpdatedSeconds(), wallet.getId());
            entry.setId(id);
        }
    }
}
