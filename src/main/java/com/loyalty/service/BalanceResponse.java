package com.loyalty.service;

import java.time.LocalDate;

/**
 * A customer's available point balance and tier as of a given date.
 *
 * @param balance           available (unexpired) points
 * @param tier              tier earned from rolling-12-month spend
 * @param spendLast12Months gross points earned in the trailing 12 months (what determines tier)
 * @param asOf              the date this was computed for (echoed back so the answer is unambiguous)
 */
public record BalanceResponse(
        String customerId,
        int balance,
        String tier,
        int spendLast12Months,
        LocalDate asOf) {
}
