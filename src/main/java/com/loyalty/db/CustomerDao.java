package com.loyalty.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

    /** Outstanding refund debt for a customer; 0 if the customer is unknown. */
    public int getDebt(String customerId) {
        String sql = "SELECT point_debt FROM customers WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("point_debt") : 0;
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to read debt for customer " + customerId, e);
        }
    }

    public void setDebt(String customerId, int debt) {
        String sql = "UPDATE customers SET point_debt = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, debt);
            ps.setString(2, customerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Failed to update debt for customer " + customerId, e);
        }
    }
}
