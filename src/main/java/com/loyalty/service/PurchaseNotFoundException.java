package com.loyalty.service;

/**
 * Thrown when a refund references a purchase that doesn't exist for the given customer
 * (surfaced as HTTP 404).
 */
public class PurchaseNotFoundException extends RuntimeException {
    public PurchaseNotFoundException(String purchaseId) {
        super("No purchase found with id '" + purchaseId + "'");
    }
}
