package com.loyalty.service;

import com.loyalty.db.CustomerDao;
import com.loyalty.db.Database;
import com.loyalty.db.PointLotDao;
import com.loyalty.model.PointLot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/** Loyalty business logic. Handlers stay thin; all the rules live here. */
public class LoyaltyService {

    /** Points expire this many months after they are earned. */
    private static final int EXPIRY_MONTHS = 12;

    private final Database db;
    private final CustomerDao customers;
    private final PointLotDao pointLots;

    public LoyaltyService(Database db) {
        this.db = db;
        this.customers = new CustomerDao(db.connection());
        this.pointLots = new PointLotDao(db.connection());
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
                throw new DuplicatePurchaseException(purchaseId);
            }
            customers.upsert(customerId);
            pointLots.insert(new PointLot(customerId, purchaseId, points, points, when, expires));
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
        return new BalanceResponse(customerId, balance, when);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
