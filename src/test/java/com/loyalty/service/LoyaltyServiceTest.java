package com.loyalty.service;

import com.loyalty.db.Database;
import com.loyalty.db.PointLotDao;
import com.loyalty.model.PointLot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Exercises the loyalty rules against a fresh in-memory SQLite database per test. */
class LoyaltyServiceTest {

    private Database db;
    private LoyaltyService service;

    @BeforeEach
    void setUp() {
        db = new Database("jdbc:sqlite::memory:");
        service = new LoyaltyService(db);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void earnCreatesLotWithTwelveMonthExpiry() {
        EarnResult result = service.earn("alice", "order-1", new BigDecimal("100.00"), LocalDate.of(2025, 1, 1));

        assertEquals(100, result.pointsEarned());
        assertEquals("order-1", result.purchaseId());
        assertEquals(LocalDate.of(2025, 1, 1), result.earnedAt());
        assertEquals(LocalDate.of(2026, 1, 1), result.expiresAt());

        List<PointLot> lots = new PointLotDao(db.connection()).findByCustomer("alice");
        assertEquals(1, lots.size());
        PointLot lot = lots.get(0);
        assertEquals(100, lot.pointsEarned());
        assertEquals(100, lot.pointsRemaining());
        assertEquals("order-1", lot.purchaseId());
    }

    @Test
    void earnFloorsFractionalDollars() {
        EarnResult result = service.earn("bob", "order-2", new BigDecimal("10.99"), LocalDate.of(2025, 1, 1));
        assertEquals(10, result.pointsEarned());
    }

    @Test
    void earnLazilyCreatesCustomer() {
        // No explicit customer creation; the earn must succeed and satisfy the FK on point_lots.
        EarnResult result = service.earn("newcomer", "order-3", new BigDecimal("5"), LocalDate.of(2025, 1, 1));
        assertEquals(5, result.pointsEarned());
    }

    @Test
    void earnRejectsNonPositiveAmount() {
        assertThrows(IllegalArgumentException.class,
                () -> service.earn("alice", "order-4", new BigDecimal("0"), LocalDate.of(2025, 1, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> service.earn("alice", "order-5", new BigDecimal("-5"), LocalDate.of(2025, 1, 1)));
    }

    @Test
    void earnRejectsBlankIdentifiers() {
        assertThrows(IllegalArgumentException.class,
                () -> service.earn("  ", "order-6", new BigDecimal("5"), LocalDate.of(2025, 1, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> service.earn("alice", "", new BigDecimal("5"), LocalDate.of(2025, 1, 1)));
    }

    @Test
    void earnRejectsDuplicatePurchaseId() {
        service.earn("dan", "order-1", new BigDecimal("100"), LocalDate.of(2025, 1, 1));

        assertThrows(DuplicatePurchaseException.class,
                () -> service.earn("dan", "order-1", new BigDecimal("50"), LocalDate.of(2025, 2, 1)));

        // The duplicate must not have created a second lot.
        assertEquals(1, new PointLotDao(db.connection()).findByCustomer("dan").size());
    }

    @Test
    void earnRejectsDuplicatePurchaseIdAcrossCustomers() {
        // purchase_id is globally unique: an order belongs to a single purchase, not a customer.
        service.earn("dan", "order-1", new BigDecimal("100"), LocalDate.of(2025, 1, 1));

        assertThrows(DuplicatePurchaseException.class,
                () -> service.earn("alice", "order-1", new BigDecimal("100"), LocalDate.of(2025, 1, 1)));
    }

    @Test
    void balanceExcludesExpiredLots() {
        service.earn("alice", "order-1", new BigDecimal("100"), LocalDate.of(2025, 1, 1)); // expires 2026-01-01

        assertEquals(100, balanceOf("alice", LocalDate.of(2025, 6, 1)));  // well within window
        assertEquals(100, balanceOf("alice", LocalDate.of(2025, 12, 31))); // last valid day
        assertEquals(0, balanceOf("alice", LocalDate.of(2026, 1, 1)));    // expiry date: expired
        assertEquals(0, balanceOf("alice", LocalDate.of(2026, 6, 1)));    // long after
    }

    @Test
    void balanceSumsMultipleLotsAndDropsThemAsTheyExpire() {
        service.earn("alice", "order-1", new BigDecimal("100"), LocalDate.of(2025, 1, 1)); // expires 2026-01-01
        service.earn("alice", "order-2", new BigDecimal("50"), LocalDate.of(2025, 3, 1));  // expires 2026-03-01

        assertEquals(150, balanceOf("alice", LocalDate.of(2025, 6, 1)));  // both valid
        assertEquals(50, balanceOf("alice", LocalDate.of(2026, 2, 1)));   // first expired, second valid
        assertEquals(0, balanceOf("alice", LocalDate.of(2026, 3, 1)));    // both expired
    }

    @Test
    void balanceForUnknownCustomerIsZero() {
        assertEquals(0, balanceOf("ghost", LocalDate.of(2025, 6, 1)));
    }

    @Test
    void balanceExcludesLotsNotYetEarnedAsOfDate() {
        service.earn("dan", "order-1", new BigDecimal("100"), LocalDate.of(2025, 12, 20)); // expires 2026-12-20

        // As of a date before the purchase happened, the points don't exist yet.
        assertEquals(0, balanceOf("dan", LocalDate.of(2025, 5, 1)));
        // On the earn date and after (and before expiry), they count.
        assertEquals(100, balanceOf("dan", LocalDate.of(2025, 12, 20)));
        assertEquals(100, balanceOf("dan", LocalDate.of(2026, 6, 1)));
    }

    @Test
    void redeemCannotUsePointsNotYetEarnedAsOfDate() {
        service.earn("dan", "order-1", new BigDecimal("600"), LocalDate.of(2025, 12, 20));
        // Evaluated before the purchase date, the balance is 0, so the redemption can't be afforded.
        assertThrows(InsufficientBalanceException.class,
                () -> service.redeem("dan", "free-coffee", LocalDate.of(2025, 5, 1)));
    }

    private int balanceOf(String customerId, LocalDate asOf) {
        return service.balance(customerId, asOf).balance();
    }

    @Test
    void redeemConsumesOldestExpiryFirst() {
        // Earlier-earned lot expires first and must be drained before the later one.
        service.earn("alice", "order-1", new BigDecimal("300"), LocalDate.of(2025, 1, 1)); // expires 2026-01-01
        service.earn("alice", "order-2", new BigDecimal("300"), LocalDate.of(2025, 6, 1)); // expires 2026-06-01

        RedeemResult result = service.redeem("alice", "free-coffee", LocalDate.of(2025, 7, 1)); // costs 500

        assertEquals(500, result.pointsSpent());
        assertEquals(100, result.balanceRemaining());

        Map<String, PointLot> byPurchase = new PointLotDao(db.connection()).findByCustomer("alice")
                .stream().collect(Collectors.toMap(PointLot::purchaseId, lot -> lot));
        assertEquals(0, byPurchase.get("order-1").pointsRemaining());   // earliest expiry fully drained
        assertEquals(100, byPurchase.get("order-2").pointsRemaining()); // remainder taken from the next
    }

    @Test
    void redeemRejectsInsufficientBalance() {
        service.earn("alice", "order-1", new BigDecimal("100"), LocalDate.of(2025, 1, 1));
        assertThrows(InsufficientBalanceException.class,
                () -> service.redeem("alice", "free-coffee", LocalDate.of(2025, 6, 1))); // needs 500
    }

    @Test
    void redeemRejectsUnknownReward() {
        service.earn("alice", "order-1", new BigDecimal("1000"), LocalDate.of(2025, 1, 1));
        assertThrows(RewardNotFoundException.class,
                () -> service.redeem("alice", "no-such-reward", LocalDate.of(2025, 6, 1)));
    }

    @Test
    void redeemCannotUseExpiredPoints() {
        service.earn("alice", "order-1", new BigDecimal("600"), LocalDate.of(2025, 1, 1)); // expires 2026-01-01
        // Points have expired by this date, so the balance is 0 and the redemption can't be afforded.
        assertThrows(InsufficientBalanceException.class,
                () -> service.redeem("alice", "free-coffee", LocalDate.of(2026, 2, 1)));
    }

    @Test
    void redeemExactBalanceLeavesZero() {
        service.earn("alice", "order-1", new BigDecimal("500"), LocalDate.of(2025, 1, 1));
        RedeemResult result = service.redeem("alice", "free-coffee", LocalDate.of(2025, 6, 1));
        assertEquals(0, result.balanceRemaining());
    }
}
