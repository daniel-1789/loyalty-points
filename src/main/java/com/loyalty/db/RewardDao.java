package com.loyalty.db;

import com.loyalty.model.Reward;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Data access for the {@code rewards} catalog. */
public class RewardDao {

    private final Connection conn;

    public RewardDao(Connection conn) {
        this.conn = conn;
    }

    public Optional<Reward> findById(String id) {
        String sql = "SELECT id, name, cost_points FROM rewards WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to load reward " + id, e);
        }
    }

    public List<Reward> findAll() {
        String sql = "SELECT id, name, cost_points FROM rewards ORDER BY cost_points";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<Reward> rewards = new ArrayList<>();
            while (rs.next()) {
                rewards.add(map(rs));
            }
            return rewards;
        } catch (SQLException e) {
            throw new DataAccessException("Failed to load rewards", e);
        }
    }

    private static Reward map(ResultSet rs) throws SQLException {
        return new Reward(rs.getString("id"), rs.getString("name"), rs.getInt("cost_points"));
    }
}
