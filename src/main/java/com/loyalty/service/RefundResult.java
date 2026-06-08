package com.loyalty.service;

import java.time.LocalDate;

/**
 * Outcome of refunding a purchase.
 *
 * @param pointsReversed  points removed from the available balance (voided + reclaimed)
 * @param debtIncurred    portion that couldn't be covered and became debt
 * @param balanceRemaining the customer's net balance after the refund (may be negative)
 */
public record RefundResult(
        String customerId,
        String purchaseId,
        int pointsReversed,
        int debtIncurred,
        int balanceRemaining,
        LocalDate refundedAt) {
}
