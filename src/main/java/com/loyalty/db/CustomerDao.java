package com.loyalty.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Data access for the {@code customers} table. */
public class CustomerDao {

    private final Connection conn;

    public CustomerDao(Connection conn) {
        this.conn = conn;
    }

    /** Lazily create the customer (no dedicated create flow); a no-op if they already exist. */
    public void upsert(String customerId) {
        String sql = "INSERT OR IGNORE INTO customers(id) VALUES (?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, customerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Failed to upsert customer " + customerId, e);
        }
    }
}
