package com.sparrowwallet.sparrow.io.db;

import com.sparrowwallet.drongo.wallet.SwapEntry;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;

public class SwapEntryMapper implements RowMapper<SwapEntry> {
    @Override
    public SwapEntry map(ResultSet rs, StatementContext ctx) throws SQLException {
        SwapEntry entry = new SwapEntry(rs.getString("swapId"), rs.getInt("keyIndex"), rs.getString("status"), rs.getBytes("sealed"), rs.getLong("updatedSeconds"));
        entry.setId(rs.getLong("id"));
        return entry;
    }
}
