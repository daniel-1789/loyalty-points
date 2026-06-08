package com.loyalty.service;

/** Thrown when a purchase that was already refunded is refunded again (surfaced as HTTP 409). */
public class AlreadyRefundedException extends RuntimeException {
    public AlreadyRefundedException(String purchaseId) {
        super("Purchase '" + purchaseId + "' has already been refunded");
    }
}
