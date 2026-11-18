package com.sparrowwallet.sparrow.io.db;

import com.sparrowwallet.drongo.ordinals.track.Verdict;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.wallet.InscriptionCheck;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;

public class InscriptionCheckMapper implements RowMapper<InscriptionCheck> {
    @Override
    public InscriptionCheck map(ResultSet rs, StatementContext ctx) throws SQLException {
        byte[] blockHash = rs.getBytes("blockHash");
        return new InscriptionCheck(Sha256Hash.wrap(rs.getBytes("txid")), rs.getInt("vout"), Verdict.values()[rs.getInt("verdict")],
                InscriptionCheck.Source.values()[rs.getInt("source")], rs.getBoolean("runes"), InscriptionCheck.decodeFound(rs.getString("found")),
                rs.getString("reason"), blockHash == null ? null : Sha256Hash.wrap(blockHash), InscriptionCheck.decodePieces(rs.getString("openPieces")));
    }
}
