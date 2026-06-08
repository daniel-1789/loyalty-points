package com.loyalty.service;

/** Thrown when a redemption references a reward that isn't in the catalog (surfaced as HTTP 404). */
public class RewardNotFoundException extends RuntimeException {
    public RewardNotFoundException(String rewardId) {
        super("No reward found with id '" + rewardId + "'");
    }
}
