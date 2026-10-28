package com.sparrowwallet.sparrow.io.db;

import com.sparrowwallet.drongo.wallet.PendingMint;
import com.sparrowwallet.drongo.wallet.Wallet;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;

public interface PendingMintDao {
    @SqlQuery("select id, kind, keyPath, scriptHash, commitTxid, commitVout, commitValue, contentType, etching from pendingMint where wallet = ? order by id")
    @RegisterRowMapper(PendingMintMapper.class)
    List<PendingMint> getForWalletId(Long id);

    @SqlUpdate("insert into pendingMint (kind, keyPath, scriptHash, commitTxid, commitVout, commitValue, contentType, etching, wallet) values (?, ?, ?, ?, ?, ?, ?, ?, ?)")
    @GetGeneratedKeys("id")
    long insert(int kind, String keyPath, byte[] scriptHash, byte[] commitTxid, int commitVout, long commitValue, String contentType, String etching, long wallet);

    @SqlUpdate("delete from pendingMint where wallet = ?")
    void deleteForWalletId(long wallet);

    default void addPendingMints(Wallet wallet) {
        if(!wallet.getPendingMints().isEmpty()) {
            replace(wallet);
        }
    }

    /** Whatever is stored is replaced by the wallet's current pending mints. */
    default void replace(Wallet wallet) {
        deleteForWalletId(wallet.getId());
        for(PendingMint mint : wallet.getPendingMints()) {
            long id = insert(mint.getKind().ordinal(), mint.getKeyPath(), mint.getScriptHash(), mint.getCommitTxid().getBytes(), mint.getCommitVout(),
                    mint.getCommitValue(), mint.getContentType(), PendingMint.encodeEtching(mint.getEtching()), wallet.getId());
            mint.setId(id);
        }
    }
}
