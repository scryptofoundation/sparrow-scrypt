package com.sparrowwallet.sparrow.io.db;

import com.sparrowwallet.drongo.wallet.InscriptionCheck;
import com.sparrowwallet.drongo.wallet.Wallet;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;

public interface InscriptionCheckDao {
    @SqlQuery("select txid, vout, verdict, source, runes, found, reason, blockHash, openPieces from inscriptionCheck where wallet = ? order by id")
    @RegisterRowMapper(InscriptionCheckMapper.class)
    List<InscriptionCheck> getForWalletId(Long id);

    @SqlUpdate("insert into inscriptionCheck (txid, vout, verdict, source, runes, found, reason, blockHash, openPieces, wallet) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
    void insert(byte[] txid, int vout, int verdict, int source, boolean runes, String found, String reason, byte[] blockHash, String openPieces, long wallet);

    @SqlUpdate("delete from inscriptionCheck where wallet = ?")
    void deleteForWalletId(long wallet);

    default void addInscriptionChecks(Wallet wallet) {
        if(!wallet.getInscriptionChecks().isEmpty()) {
            replace(wallet);
        }
    }

    /** Whatever is stored is replaced by the wallet's current checks. */
    default void replace(Wallet wallet) {
        deleteForWalletId(wallet.getId());
        for(InscriptionCheck check : wallet.getInscriptionChecks()) {
            insert(check.txid().getBytes(), check.vout(), check.verdict().ordinal(), check.source().ordinal(), check.runes(),
                    InscriptionCheck.encodeFound(check.found()), check.reason(), check.blockHash() == null ? null : check.blockHash().getBytes(),
                    InscriptionCheck.encodePieces(check.open()), wallet.getId());
        }
    }
}
