package com.loyalty.service;

import com.loyalty.db.CustomerDao;
import com.loyalty.db.Database;
import com.loyalty.db.PointLotDao;
import com.loyalty.db.RedemptionDao;
import com.loyalty.db.RefundDao;
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
    private final RefundDao refunds;
    private final TierDao tiers;

    public LoyaltyService(Database db) {
        this.db = db;
        this.customers = new CustomerDao(db.connection());
        this.pointLots = new PointLotDao(db.connection());
        this.rewards = new RewardDao(db.connection());
        this.redemptions = new RedemptionDao(db.connection());
        this.refunds = new RefundDao(db.connection());
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

            // New points pay down any outstanding refund debt first; the rest becomes spendable.
            int debt = customers.getDebt(customerId);
            int appliedToDebt = Math.min(points, debt);
            if (appliedToDebt > 0) {
                customers.setDebt(customerId, debt - appliedToDebt);
            }
            int remaining = points - appliedToDebt;

            // points_earned stays the full amount (it's gross spend, which drives tier); only the
            // spendable remainder is reduced by the debt paydown.
            pointLots.insert(new PointLot(customerId, purchaseId, points, remaining, when, expires));
            log.info("Earned {} points for customer '{}' (purchase '{}', earned {} expires {}); {} applied to debt",
                    points, customerId, purchaseId, when, expires, appliedToDebt);
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
        int balance = pointLots.balance(customerId, when) - customers.getDebt(customerId);
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
            int available = netBalance(customerId, when);
            if (available < reward.costPoints()) {
                log.warn("Redeem denied for customer '{}': reward '{}' costs {}, only {} available (asOf {})",
                        customerId, rewardId, reward.costPoints(), available, when);
                throw new InsufficientBalanceException(rewardId, reward.costPoints(), available);
            }
            int consumed = consumeUpTo(customerId, when, reward.costPoints());
            if (consumed < reward.costPoints()) {
                // Unreachable: net balance was checked >= cost within this same transaction.
                throw new IllegalStateException(
                        "Inconsistent state: consumed " + consumed + " of " + reward.costPoints()
                                + " for " + customerId);
            }
            redemptions.insert(customerId, rewardId, reward.costPoints(), when);
            int remaining = netBalance(customerId, when);
            log.info("Redeemed '{}' ({} points) for customer '{}'; balance {} -> {} (asOf {})",
                    rewardId, reward.costPoints(), customerId, available, remaining, when);
            return new RedeemResult(customerId, rewardId, reward.costPoints(), when, remaining);
        });
    }

    /**
     * Refund a purchase: reverse its points and, for any portion already spent that the current
     * balance can't cover, record debt. Consumes oldest-expiry-first when reclaiming.
     *
     * @param asOf date to evaluate against; defaults to today when null
     * @throws PurchaseNotFoundException if the purchase doesn't exist for this customer
     * @throws AlreadyRefundedException  if the purchase was already refunded
     */
    public RefundResult refund(String customerId, String purchaseId, LocalDate asOf) {
        requireText(customerId, "customerId");
        requireText(purchaseId, "purchaseId");
        LocalDate when = asOf != null ? asOf : LocalDate.now();

        return db.transaction(() -> {
            PointLot lot = pointLots.findByPurchaseId(purchaseId)
                    .filter(l -> l.customerId().equals(customerId))
                    .orElseThrow(() -> {
                        log.warn("Refund rejected: no purchase '{}' for customer '{}'", purchaseId, customerId);
                        return new PurchaseNotFoundException(purchaseId);
                    });
            if (refunds.exists(purchaseId)) {
                log.warn("Refund rejected: purchase '{}' already refunded (customer '{}')", purchaseId, customerId);
                throw new AlreadyRefundedException(purchaseId);
            }

            int stillAvailable = lot.pointsRemaining();        // unspent points in this purchase's lot
            int spent = lot.pointsEarned() - stillAvailable;   // portion already redeemed from it

            // 1. Void this purchase's own lot (remove its still-available points).
            pointLots.updateRemaining(purchaseId, 0);
            pointLots.markRefunded(purchaseId, when);

            // 2. Reclaim the spent portion from the customer's current balance; shortfall -> debt.
            int reclaimed = consumeUpTo(customerId, when, spent);
            int shortfall = spent - reclaimed;
            if (shortfall > 0) {
                customers.setDebt(customerId, customers.getDebt(customerId) + shortfall);
            }

            int pointsReversed = stillAvailable + reclaimed;   // points actually removed from balance
            refunds.insert(purchaseId, customerId, pointsReversed, shortfall, when);
            int remaining = netBalance(customerId, when);
            log.info("Refunded purchase '{}' for customer '{}': reversed {} points, debt +{} (balance now {}, asOf {})",
                    purchaseId, customerId, pointsReversed, shortfall, remaining, when);
            return new RefundResult(customerId, purchaseId, pointsReversed, shortfall, remaining, when);
        });
    }

    /** Net balance = available (unexpired) points minus outstanding refund debt. */
    private int netBalance(String customerId, LocalDate asOf) {
        return pointLots.balance(customerId, asOf) - customers.getDebt(customerId);
    }

    /**
     * Draw down lots in expiry order by up to {@code amount} points, returning how many were
     * actually consumed (which may be less than {@code amount} if the balance is short).
     */
    private int consumeUpTo(String customerId, LocalDate asOf, int amount) {
        int toConsume = amount;
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
        return amount - toConsume;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
