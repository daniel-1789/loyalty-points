package com.loyalty.service;

import java.time.LocalDate;

/**
 * A customer's available point balance as of a given date.
 *
 * @param asOf the date the balance was computed for (echoed back so the answer is unambiguous)
 */
public record BalanceResponse(String customerId, int balance, LocalDate asOf) {
}
