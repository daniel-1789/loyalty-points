package com.loyalty.service;

/**
 * Thrown when a purchase that has already earned points is submitted again. A purchase happens
 * once, so a second earn for the same {@code purchaseId} is rejected (surfaced as HTTP 409).
 */
public class DuplicatePurchaseException extends RuntimeException {
    public DuplicatePurchaseException(String purchaseId) {
        super("Purchase '" + purchaseId + "' has already been recorded");
    }
}
