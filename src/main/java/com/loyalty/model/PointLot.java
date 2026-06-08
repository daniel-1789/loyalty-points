package com.loyalty.model;

import java.time.LocalDate;

/**
 * One earning event, identified by {@code purchaseId} (the store's purchase identifier).
 * {@code pointsRemaining} starts equal to {@code pointsEarned} and is decremented as points are
 * redeemed (oldest-expiry-first). A lot is available only while {@code asOf < expiresAt}.
 */
public record PointLot(
        String customerId,
        String purchaseId,
        int pointsEarned,
        int pointsRemaining,
        LocalDate earnedAt,
        LocalDate expiresAt) {
}
