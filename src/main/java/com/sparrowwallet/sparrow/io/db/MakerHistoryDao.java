package com.sparrowwallet.sparrow.io.db;

import com.sparrowwallet.drongo.wallet.MakerHistoryEntry;
import com.sparrowwallet.drongo.wallet.Wallet;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;

public interface MakerHistoryDao {
    @SqlQuery("select id, makerPubKey, completed, failed, noShow from makerHistory where wallet = ? order by id")
    @RegisterRowMapper(MakerHistoryMapper.class)
    List<MakerHistoryEntry> getForWalletId(Long id);

    @SqlUpdate("insert into makerHistory (makerPubKey, completed, failed, noShow, wallet) values (?, ?, ?, ?, ?)")
    @GetGeneratedKeys("id")
    long insert(String makerPubKey, int completed, int failed, int noShow, long wallet);

    @SqlUpdate("delete from makerHistory where wallet = ?")
    void deleteForWalletId(long wallet);

    default void addMakerHistory(Wallet wallet) {
        if(!wallet.getMakerHistory().isEmpty()) {
            replace(wallet);
        }
    }

    /** Whatever is stored is replaced by the wallet's current maker history. */
    default void replace(Wallet wallet) {
        deleteForWalletId(wallet.getId());
        for(MakerHistoryEntry entry : wallet.getMakerHistory()) {
            long id = insert(entry.getMakerPubKey(), entry.getCompleted(), entry.getFailed(), entry.getNoShow(), wallet.getId());
            entry.setId(id);
        }
    }
}
