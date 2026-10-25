package com.sparrowwallet.sparrow.io.db;

import com.sparrowwallet.drongo.crypto.EncryptedData;
import com.sparrowwallet.drongo.crypto.EncryptionType;
import com.sparrowwallet.drongo.evm.EvmAddress;
import com.sparrowwallet.drongo.wallet.LitVmLink;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;

public class LitVmLinkMapper implements RowMapper<LitVmLink> {
    @Override
    public LitVmLink map(ResultSet rs, StatementContext ctx) throws SQLException {
        LitVmLink.Source source = LitVmLink.Source.values()[rs.getInt("source")];
        EvmAddress address = EvmAddress.fromString(rs.getString("address"));
        int accountIndex = rs.getInt("accountIndex");
        byte[] secret = rs.getBytes("secret");

        EncryptedData encryptedSecret = null;
        byte[] encryptedBytes = rs.getBytes("encryptedBytes");
        if(encryptedBytes != null) {
            encryptedSecret = new EncryptedData(rs.getBytes("initialisationVector"), encryptedBytes, rs.getBytes("keySalt"),
                    EncryptionType.Deriver.values()[rs.getInt("deriver")], EncryptionType.Crypter.values()[rs.getInt("crypter")]);
        }

        LitVmLink link = LitVmLink.restore(source, address, accountIndex, secret, encryptedSecret, rs.getLong("creationTimeSeconds"));
        link.setId(rs.getLong("id"));
        return link;
    }
}
