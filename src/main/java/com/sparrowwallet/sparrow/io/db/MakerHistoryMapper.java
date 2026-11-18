package com.sparrowwallet.sparrow.io.db;

import com.sparrowwallet.drongo.wallet.MakerHistoryEntry;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;

public class MakerHistoryMapper implements RowMapper<MakerHistoryEntry> {
    @Override
    public MakerHistoryEntry map(ResultSet rs, StatementContext ctx) throws SQLException {
        MakerHistoryEntry entry = new MakerHistoryEntry(rs.getString("makerPubKey"), rs.getInt("completed"), rs.getInt("failed"), rs.getInt("noShow"));
        entry.setId(rs.getLong("id"));
        return entry;
    }
}
