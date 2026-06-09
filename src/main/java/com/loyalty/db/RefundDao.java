package com.loyalty.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

/** Data access for the {@code refunds} log. */
public class RefundDao {

    private final Connection conn;

    public RefundDao(Connection conn) {
        this.conn = conn;
    }

    /** Whether this purchase has already been refunded (one refund per purchase). */
    public boolean exists(String purchaseId) {
        String sql = "SELECT 1 FROM refunds WHERE purchase_id = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, purchaseId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to check refund for " + purchaseId, e);
        }
    }

    public void insert(String purchaseId, String customerId, int pointsReversed, int debtIncurred,
                       LocalDate refundedAt) {
        String sql = """
                INSERT INTO refunds (purchase_id, customer_id, points_reversed, debt_incurred, refunded_at)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, purchaseId);
            ps.setString(2, customerId);
            ps.setInt(3, pointsReversed);
            ps.setInt(4, debtIncurred);
            ps.setString(5, refundedAt.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Failed to insert refund for " + purchaseId, e);
        }
    }
}
