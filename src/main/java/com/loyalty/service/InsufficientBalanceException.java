package com.loyalty.service;

/**
 * Thrown when a customer tries to redeem a reward they can't afford. Surfaced as HTTP 422
 * (Unprocessable Entity): the request is well-formed, but can't be fulfilled given current state.
 */
public class InsufficientBalanceException extends RuntimeException {
    public InsufficientBalanceException(String rewardId, int cost, int available) {
        super("Insufficient balance to redeem '" + rewardId + "': costs " + cost
                + ", but only " + available + " available");
    }
}
