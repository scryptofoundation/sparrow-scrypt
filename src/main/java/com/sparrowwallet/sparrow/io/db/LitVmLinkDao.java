package com.sparrowwallet.sparrow.io.db;

import com.sparrowwallet.drongo.crypto.EncryptedData;
import com.sparrowwallet.drongo.wallet.LitVmLink;
import com.sparrowwallet.drongo.wallet.Wallet;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

public interface LitVmLinkDao {
    @SqlQuery("select id, source, address, accountIndex, secret, initialisationVector, encryptedBytes, keySalt, deriver, crypter, creationTimeSeconds from litVmLink where wallet = ?")
    @RegisterRowMapper(LitVmLinkMapper.class)
    LitVmLink getForWalletId(Long id);

    @SqlUpdate("insert into litVmLink (source, address, accountIndex, secret, initialisationVector, encryptedBytes, keySalt, deriver, crypter, creationTimeSeconds, wallet) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
    @GetGeneratedKeys("id")
    long insert(int source, String address, int accountIndex, byte[] secret, byte[] initialisationVector, byte[] encryptedBytes, byte[] keySalt, Integer deriver, Integer crypter, long creationTimeSeconds, long wallet);

    @SqlUpdate("delete from litVmLink where wallet = ?")
    void deleteForWalletId(long wallet);

    default void addLitVmLink(Wallet wallet) {
        if(wallet.getLitVmLink() != null) {
            replace(wallet);
        }
    }

    /** One link per wallet: whatever is stored is replaced by the wallet's current link, or removed if it has none. */
    default void replace(Wallet wallet) {
        deleteForWalletId(wallet.getId());

        LitVmLink link = wallet.getLitVmLink();
        if(link == null) {
            return;
        }

        long id;
        if(link.isEncrypted()) {
            EncryptedData data = link.getEncryptedData();
            id = insert(link.getSource().ordinal(), link.getAddress().toString(), link.getAccountIndex(), null, data.getInitialisationVector(), data.getEncryptedBytes(), data.getKeySalt(),
                    data.getEncryptionType().getDeriver().ordinal(), data.getEncryptionType().getCrypter().ordinal(), link.getCreationTimeSeconds(), wallet.getId());
        } else {
            id = insert(link.getSource().ordinal(), link.getAddress().toString(), link.getAccountIndex(), link.getSecretBytes(), null, null, null, null, null, link.getCreationTimeSeconds(), wallet.getId());
        }
        link.setId(id);
    }
}
