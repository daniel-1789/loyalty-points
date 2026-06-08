package com.loyalty.service;

import com.loyalty.db.CustomerDao;
import com.loyalty.db.Database;
import com.loyalty.db.PointLotDao;
import com.loyalty.db.RedemptionDao;
import com.loyalty.db.RewardDao;
import com.loyalty.db.TierDao;
import com.loyalty.model.PointLot;
import com.loyalty.model.Reward;
import com.loyalty.model.Tier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/** Loyalty business logic. Handlers stay thin; all the rules live here. */
public class LoyaltyService {

    private static final Logger log = LoggerFactory.getLogger(LoyaltyService.class);

    /** Points expire this many months after they are earned. */
    private static final int EXPIRY_MONTHS = 12;

    /** Tier is based on spend over this trailing window. */
    private static final int TIER_WINDOW_MONTHS = 12;

    private final Database db;
    private final CustomerDao customers;
    private final PointLotDao pointLots;
    private final RewardDao rewards;
    private final RedemptionDao redemptions;
    private final TierDao tiers;

    public LoyaltyService(Database db) {
        this.db = db;
        this.customers = new CustomerDao(db.connection());
        this.pointLots = new PointLotDao(db.connection());
        this.rewards = new RewardDao(db.connection());
        this.redemptions = new RedemptionDao(db.connection());
        this.tiers = new TierDao(db.connection());
    }

    /**
     * Record a purchase and award points: 1 point per whole dollar spent, fractional dollars
     * dropped (e.g. $10.99 -> 10). Creates the customer on first earn.
     *
     * @param earnedAt date of the purchase; defaults to today when null (supports simulating
     *                 history and testing expiry deterministically)
     */
    public EarnResult earn(String customerId, String purchaseId, BigDecimal amount, LocalDate earnedAt) {
        requireText(customerId, "customerId");
        requireText(purchaseId, "purchaseId");
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be greater than zero");
        }

        int points = amount.setScale(0, RoundingMode.FLOOR).intValueExact();
        LocalDate when = earnedAt != null ? earnedAt : LocalDate.now();
        LocalDate expires = when.plusMonths(EXPIRY_MONTHS);

        return db.transaction(() -> {
            // A purchase earns points once. The DB also enforces this (unique purchase_id); the
            // explicit check lets us return a clean domain error rather than a constraint violation.
            if (pointLots.existsByPurchaseId(purchaseId)) {
                log.warn("Earn rejected: purchase '{}' already recorded (customer '{}')",
                        purchaseId, customerId);
                throw new DuplicatePurchaseException(purchaseId);
            }
            customers.upsert(customerId);
            pointLots.insert(new PointLot(customerId, purchaseId, points, points, when, expires));
            log.info("Earned {} points for customer '{}' (purchase '{}', earned {} expires {})",
                    points, customerId, purchaseId, when, expires);
            return new EarnResult(customerId, purchaseId, points, when, expires);
        });
    }

    /**
     * Current available balance: the sum of unexpired points.
     *
     * @param asOf date to evaluate against; defaults to today when null. An unknown customer
     *             reports a balance of 0 (we don't distinguish "unknown" from "zero balance").
     */
    public BalanceResponse balance(String customerId, LocalDate asOf) {
        requireText(customerId, "customerId");
        LocalDate when = asOf != null ? asOf : LocalDate.now();
        int balance = pointLots.balance(customerId, when);
        int spend = pointLots.spendSince(customerId, when.minusMonths(TIER_WINDOW_MONTHS), when);
        String tier = tiers.tierForSpend(spend);
        log.debug("Balance for customer '{}' as of {} = {} (tier {}, {}-month spend {})",
                customerId, when, balance, tier, TIER_WINDOW_MONTHS, spend);
        return new BalanceResponse(customerId, balance, tier, spend, when);
    }

    /** The full reward catalog. */
    public List<Reward> catalog() {
        return rewards.findAll();
    }

    /** The configured tier thresholds. */
    public List<Tier> tierThresholds() {
        return tiers.findAll();
    }

    /**
     * Redeem a reward for a customer, consuming points oldest-expiry-first.
     *
     * @param asOf date to evaluate against; defaults to today when null
     * @throws RewardNotFoundException      if the reward isn't in the catalog
     * @throws InsufficientBalanceException if the available (unexpired) balance can't cover the cost
     */
    public RedeemResult redeem(String customerId, String rewardId, LocalDate asOf) {
        requireText(customerId, "customerId");
        requireText(rewardId, "rewardId");
        LocalDate when = asOf != null ? asOf : LocalDate.now();

        Reward reward = rewards.findById(rewardId)
                .orElseThrow(() -> {
                    log.warn("Redeem rejected: unknown reward '{}' (customer '{}')", rewardId, customerId);
                    return new RewardNotFoundException(rewardId);
                });

        return db.transaction(() -> {
            int available = pointLots.balance(customerId, when);
            if (available < reward.costPoints()) {
                log.warn("Redeem denied for customer '{}': reward '{}' costs {}, only {} available (asOf {})",
                        customerId, rewardId, reward.costPoints(), available, when);
                throw new InsufficientBalanceException(rewardId, reward.costPoints(), available);
            }
            consumeOldestExpiryFirst(customerId, when, reward.costPoints());
            redemptions.insert(customerId, rewardId, reward.costPoints(), when);
            int remaining = pointLots.balance(customerId, when);
            log.info("Redeemed '{}' ({} points) for customer '{}'; balance {} -> {} (asOf {})",
                    rewardId, reward.costPoints(), customerId, available, remaining, when);
            return new RedeemResult(customerId, rewardId, reward.costPoints(), when, remaining);
        });
    }

    /** Draw down lots in expiry order until {@code cost} points have been consumed. */
    private void consumeOldestExpiryFirst(String customerId, LocalDate asOf, int cost) {
        int toConsume = cost;
        for (PointLot lot : pointLots.activeLots(customerId, asOf)) {
            if (toConsume <= 0) {
                break;
            }
            int take = Math.min(lot.pointsRemaining(), toConsume);
            pointLots.updateRemaining(lot.purchaseId(), lot.pointsRemaining() - take);
            log.debug("Consumed {} points from lot '{}' ({} -> {} remaining, expires {})",
                    take, lot.purchaseId(), lot.pointsRemaining(), lot.pointsRemaining() - take,
                    lot.expiresAt());
            toConsume -= take;
        }
        if (toConsume > 0) {
            // Unreachable: balance was checked >= cost within this same transaction.
            throw new IllegalStateException(
                    "Inconsistent state: could not consume " + cost + " points for " + customerId);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
