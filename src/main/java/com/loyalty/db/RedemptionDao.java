package com.loyalty.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;

/** Data access for the {@code redemptions} log. */
public class RedemptionDao {

    private final Connection conn;

    public RedemptionDao(Connection conn) {
        this.conn = conn;
    }

    /** Record that a redemption happened. */
    public void insert(String customerId, String rewardId, int pointsSpent, LocalDate redeemedAt) {
        String sql = """
                INSERT INTO redemptions (customer_id, reward_id, points_spent, redeemed_at)
                VALUES (?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, customerId);
            ps.setString(2, rewardId);
            ps.setInt(3, pointsSpent);
            ps.setString(4, redeemedAt.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Failed to insert redemption", e);
        }
    }
}
