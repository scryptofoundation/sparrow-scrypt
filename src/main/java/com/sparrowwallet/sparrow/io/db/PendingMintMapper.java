package com.sparrowwallet.sparrow.io.db;

import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.wallet.PendingMint;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;

public class PendingMintMapper implements RowMapper<PendingMint> {
    @Override
    public PendingMint map(ResultSet rs, StatementContext ctx) throws SQLException {
        PendingMint mint = new PendingMint(PendingMint.Kind.values()[rs.getInt("kind")], rs.getString("keyPath"), rs.getBytes("scriptHash"),
                Sha256Hash.wrap(rs.getBytes("commitTxid")), rs.getInt("commitVout"), rs.getLong("commitValue"), rs.getString("contentType"),
                PendingMint.decodeEtching(rs.getString("etching")));
        mint.setId(rs.getLong("id"));
        return mint;
    }
}
