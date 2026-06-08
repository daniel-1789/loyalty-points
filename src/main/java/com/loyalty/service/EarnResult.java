package com.loyalty.service;

import java.time.LocalDate;

/** Outcome of recording a purchase: the lot that was created (identified by purchaseId). */
public record EarnResult(
        String customerId,
        String purchaseId,
        int pointsEarned,
        LocalDate earnedAt,
        LocalDate expiresAt) {
}
