package com.loyalty.db;

import com.loyalty.model.PointLot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Data access for the {@code point_lots} table. */
public class PointLotDao {

    private final Connection conn;

    public PointLotDao(Connection conn) {
        this.conn = conn;
    }

    /** Insert a new lot. The caller supplies the key (purchase_id). */
    public void insert(PointLot lot) {
        String sql = """
                INSERT INTO point_lots
                    (purchase_id, customer_id, points_earned, points_remaining, earned_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, lot.purchaseId());
            ps.setString(2, lot.customerId());
            ps.setInt(3, lot.pointsEarned());
            ps.setInt(4, lot.pointsRemaining());
            ps.setString(5, lot.earnedAt().toString());
            ps.setString(6, lot.expiresAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Failed to insert point lot " + lot.purchaseId(), e);
        }
    }

    /**
     * Available balance for a customer as of {@code asOf}: the sum of remaining points across
     * lots that have not yet expired. A lot is still valid while {@code asOf < expires_at}, so a
     * lot expires on its expiry date (not the day after). Returns 0 for an unknown customer.
     */
    public int balance(String customerId, LocalDate asOf) {
        String sql = """
                SELECT COALESCE(SUM(points_remaining), 0) AS balance
                FROM point_lots
                WHERE customer_id = ? AND expires_at > ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, customerId);
            ps.setString(2, asOf.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt("balance");
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to compute balance for " + customerId, e);
        }
    }

    /** Whether any lot already exists for this purchase (purchase_id is globally unique). */
    public boolean existsByPurchaseId(String purchaseId) {
        String sql = "SELECT 1 FROM point_lots WHERE purchase_id = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, purchaseId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to check purchase " + purchaseId, e);
        }
    }

    /** All lots for a customer, oldest-earned first. */
    public List<PointLot> findByCustomer(String customerId) {
        String sql = """
                SELECT customer_id, purchase_id, points_earned, points_remaining, earned_at, expires_at
                FROM point_lots
                WHERE customer_id = ?
                ORDER BY earned_at
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                List<PointLot> lots = new ArrayList<>();
                while (rs.next()) {
                    lots.add(map(rs));
                }
                return lots;
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to load point lots for " + customerId, e);
        }
    }

    private static PointLot map(ResultSet rs) throws SQLException {
        return new PointLot(
                rs.getString("customer_id"),
                rs.getString("purchase_id"),
                rs.getInt("points_earned"),
                rs.getInt("points_remaining"),
                LocalDate.parse(rs.getString("earned_at")),
                LocalDate.parse(rs.getString("expires_at")));
    }
}
