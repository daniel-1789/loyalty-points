package com.loyalty.web;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request body for recording a purchase.
 *
 * @param purchaseId identifier of the purchase (used later for refunds)
 * @param amount     purchase amount in dollars
 * @param date       optional purchase date; defaults to today when omitted
 */
public record EarnRequest(String purchaseId, BigDecimal amount, LocalDate date) {
}
