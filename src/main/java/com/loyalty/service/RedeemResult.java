package com.loyalty.service;

import java.time.LocalDate;

/** Outcome of a redemption: what was spent and the balance left afterwards. */
public record RedeemResult(
        String customerId,
        String rewardId,
        int pointsSpent,
        LocalDate redeemedAt,
        int balanceRemaining) {
}
