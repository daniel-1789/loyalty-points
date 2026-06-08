package com.loyalty.web;

import java.time.LocalDate;

/**
 * Request body for redeeming a reward.
 *
 * @param rewardId catalog id of the reward to redeem
 * @param date     optional date to evaluate against; defaults to today when omitted
 */
public record RedeemRequest(String rewardId, LocalDate date) {
}
