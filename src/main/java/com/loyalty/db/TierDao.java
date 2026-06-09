package com.loyalty.db;

import com.loyalty.model.Tier;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Data access for the {@code tiers} thresholds table. */
public class TierDao {

    private final Connection conn;

    public TierDao(Connection conn) {
        this.conn = conn;
    }

    /** The highest tier whose {@code min_spend} does not exceed the given spend. */
    public String tierForSpend(int spend) {
        String sql = "SELECT name FROM tiers WHERE min_spend <= ? ORDER BY min_spend DESC LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, spend);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("name");
                }
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to resolve tier for spend " + spend, e);
        }
        // Spend is below every threshold (only possible if the lowest tier's min_spend > 0);
        // fall back to the entry tier so a customer always has a tier.
        return lowestTierName();
    }

    public List<Tier> findAll() {
        String sql = "SELECT name, min_spend FROM tiers ORDER BY min_spend";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<Tier> tiers = new ArrayList<>();
            while (rs.next()) {
                tiers.add(new Tier(rs.getString("name"), rs.getInt("min_spend")));
            }
            return tiers;
        } catch (SQLException e) {
            throw new DataAccessException("Failed to load tiers", e);
        }
    }

    private String lowestTierName() {
        String sql = "SELECT name FROM tiers ORDER BY min_spend ASC LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString("name") : "Unranked";
        } catch (SQLException e) {
            throw new DataAccessException("Failed to load lowest tier", e);
        }
    }
}
