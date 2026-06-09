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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

    // Earn awards 1 point per dollar and stores a lot expiring 12 months later — the core earn rule.
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

    // Fractional dollars floor to whole points (10.99 -> 10), so customers never get rounded-up credit.
    @Test
    void earnFloorsFractionalDollars() {
        EarnResult result = service.earn("bob", "order-2", new BigDecimal("10.99"), LocalDate.of(2025, 1, 1));
        assertEquals(10, result.pointsEarned());
    }

    // Earning for a brand-new customer auto-creates them, so the lot's foreign key holds without prior signup.
    @Test
    void earnLazilyCreatesCustomer() {
        // No explicit customer creation; the earn must succeed and satisfy the FK on point_lots.
        EarnResult result = service.earn("newcomer", "order-3", new BigDecimal("5"), LocalDate.of(2025, 1, 1));
        assertEquals(5, result.pointsEarned());
    }

    // Zero or negative purchase amounts are rejected, preventing meaningless or balance-draining earns.
    @Test
    void earnRejectsNonPositiveAmount() {
        assertThrows(IllegalArgumentException.class,
                () -> service.earn("alice", "order-4", new BigDecimal("0"), LocalDate.of(2025, 1, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> service.earn("alice", "order-5", new BigDecimal("-5"), LocalDate.of(2025, 1, 1)));
    }

    // Amounts exceeding Integer.MAX_VALUE raise a clean validation error rather than an overflow/500.
    @Test
    void earnRejectsAmountTooLargeForInt() {
        // Above Integer.MAX_VALUE: must be a clean validation error, not an ArithmeticException/500.
        assertThrows(IllegalArgumentException.class,
                () -> service.earn("alice", "order-big", new BigDecimal("9999999999"), LocalDate.of(2025, 1, 1)));
    }

    // Blank customer or purchase identifiers are rejected, guarding against orphaned or unattributable lots.
    @Test
    void earnRejectsBlankIdentifiers() {
        assertThrows(IllegalArgumentException.class,
                () -> service.earn("  ", "order-6", new BigDecimal("5"), LocalDate.of(2025, 1, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> service.earn("alice", "", new BigDecimal("5"), LocalDate.of(2025, 1, 1)));
    }

    // Re-earning the same purchase id is rejected and creates no second lot, preventing double-crediting.
    @Test
    void earnRejectsDuplicatePurchaseId() {
        service.earn("dan", "order-1", new BigDecimal("100"), LocalDate.of(2025, 1, 1));

        assertThrows(DuplicatePurchaseException.class,
                () -> service.earn("dan", "order-1", new BigDecimal("50"), LocalDate.of(2025, 2, 1)));

        // The duplicate must not have created a second lot.
        assertEquals(1, new PointLotDao(db.connection()).findByCustomer("dan").size());
    }

    // Purchase ids are globally unique, so even a different customer cannot reuse an existing order id.
    @Test
    void earnRejectsDuplicatePurchaseIdAcrossCustomers() {
        // purchase_id is globally unique: an order belongs to a single purchase, not a customer.
        service.earn("dan", "order-1", new BigDecimal("100"), LocalDate.of(2025, 1, 1));

        assertThrows(DuplicatePurchaseException.class,
                () -> service.earn("alice", "order-1", new BigDecimal("100"), LocalDate.of(2025, 1, 1)));
    }

    // Balance counts points through the day before expiry but drops them on the expiry date — exclusive window.
    @Test
    void balanceExcludesExpiredLots() {
        service.earn("alice", "order-1", new BigDecimal("100"), LocalDate.of(2025, 1, 1)); // expires 2026-01-01

        assertEquals(100, balanceOf("alice", LocalDate.of(2025, 6, 1)));  // well within window
        assertEquals(100, balanceOf("alice", LocalDate.of(2025, 12, 31))); // last valid day
        assertEquals(0, balanceOf("alice", LocalDate.of(2026, 1, 1)));    // expiry date: expired
        assertEquals(0, balanceOf("alice", LocalDate.of(2026, 6, 1)));    // long after
    }

    // Balance sums multiple active lots and drops each individually as its own expiry date passes.
    @Test
    void balanceSumsMultipleLotsAndDropsThemAsTheyExpire() {
        service.earn("alice", "order-1", new BigDecimal("100"), LocalDate.of(2025, 1, 1)); // expires 2026-01-01
        service.earn("alice", "order-2", new BigDecimal("50"), LocalDate.of(2025, 3, 1));  // expires 2026-03-01

        assertEquals(150, balanceOf("alice", LocalDate.of(2025, 6, 1)));  // both valid
        assertEquals(50, balanceOf("alice", LocalDate.of(2026, 2, 1)));   // first expired, second valid
        assertEquals(0, balanceOf("alice", LocalDate.of(2026, 3, 1)));    // both expired
    }

    // An unknown customer returns balance 0 rather than erroring, so queries are safe for non-existent ids.
    @Test
    void balanceForUnknownCustomerIsZero() {
        assertEquals(0, balanceOf("ghost", LocalDate.of(2025, 6, 1)));
    }

    // Points don't count before their earn date but do on it — the as-of lower bound is inclusive.
    @Test
    void balanceExcludesLotsNotYetEarnedAsOfDate() {
        service.earn("dan", "order-1", new BigDecimal("100"), LocalDate.of(2025, 12, 20)); // expires 2026-12-20

        // As of a date before the purchase happened, the points don't exist yet.
        assertEquals(0, balanceOf("dan", LocalDate.of(2025, 5, 1)));
        // On the earn date and after (and before expiry), they count.
        assertEquals(100, balanceOf("dan", LocalDate.of(2025, 12, 20)));
        assertEquals(100, balanceOf("dan", LocalDate.of(2026, 6, 1)));
    }

    // Redeeming as of a date before the earn fails, since not-yet-earned points can't fund a reward.
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

    // Tier matches spend thresholds inclusively (Silver default, 500 Gold, 2500 Platinum) — exact boundaries.
    @Test
    void tierScalesWithSpendAtThresholds() {
        // No spend -> the entry tier.
        assertEquals("Silver", tierOf("nobody", LocalDate.of(2025, 6, 1)));

        service.earn("bronze", "br1", new BigDecimal("499"), LocalDate.of(2025, 1, 1));
        assertEquals("Silver", tierOf("bronze", LocalDate.of(2025, 6, 1))); // just below Gold

        service.earn("gold", "g1", new BigDecimal("500"), LocalDate.of(2025, 1, 1));
        assertEquals("Gold", tierOf("gold", LocalDate.of(2025, 6, 1)));     // exactly at Gold

        service.earn("plat", "p1", new BigDecimal("2500"), LocalDate.of(2025, 1, 1));
        assertEquals("Platinum", tierOf("plat", LocalDate.of(2025, 6, 1))); // exactly at Platinum
    }

    // Tier is driven by gross spend, not remaining balance, so redeeming points never downgrades a customer.
    @Test
    void tierReflectsGrossSpendNotCurrentBalance() {
        service.earn("dan", "d1", new BigDecimal("600"), LocalDate.of(2025, 1, 1));
        service.redeem("dan", "free-coffee", LocalDate.of(2025, 6, 1)); // spends 500; balance now 100

        BalanceResponse resp = service.balance("dan", LocalDate.of(2025, 6, 1));
        assertEquals(100, resp.balance());
        assertEquals(600, resp.spendLast12Months());
        assertEquals("Gold", resp.tier()); // tier reflects the 600 spent, not the 100 remaining
    }

    // Tier uses a rolling trailing-12-month spend window, so old purchases age out and downgrade the tier.
    @Test
    void tierUsesRolling12MonthWindow() {
        service.earn("dan", "d1", new BigDecimal("3000"), LocalDate.of(2024, 1, 1));
        assertEquals("Platinum", tierOf("dan", LocalDate.of(2024, 6, 1))); // within the window

        // By mid-2025 the 2024-01 purchase has rolled out of the trailing 12 months.
        assertEquals("Silver", tierOf("dan", LocalDate.of(2025, 6, 1)));
    }

    private String tierOf(String customerId, LocalDate asOf) {
        return service.balance(customerId, asOf).tier();
    }

    // Concurrent earns on one connection all succeed with no lost updates, proving access is serialized safely.
    @Test
    void concurrentEarnsStayConsistent() throws InterruptedException {
        // All operations share one DB connection; this exercises the serialization that keeps
        // concurrent requests from interleaving on it. Each thread earns a distinct purchase.
        int threads = 16;
        int pointsEach = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int n = i;
            pool.submit(() -> {
                try {
                    start.await(); // line everyone up to maximize contention
                    service.earn("alice", "order-" + n, new BigDecimal(pointsEach), LocalDate.of(2025, 1, 1));
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(0, errors.get(), "no earn should fail under concurrency");
        assertEquals(threads * pointsEach, service.balance("alice", LocalDate.of(2025, 6, 1)).balance());
        assertEquals(threads, new PointLotDao(db.connection()).findByCustomer("alice").size());
    }

    // Redemption drains the soonest-to-expire lot first, minimizing points lost to expiry (FIFO-by-expiry).
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

    // Redeeming a reward costing more than the balance is rejected, preventing accidental negative balances.
    @Test
    void redeemRejectsInsufficientBalance() {
        service.earn("alice", "order-1", new BigDecimal("100"), LocalDate.of(2025, 1, 1));
        assertThrows(InsufficientBalanceException.class,
                () -> service.redeem("alice", "free-coffee", LocalDate.of(2025, 6, 1))); // needs 500
    }

    // Redeeming an unknown reward throws rather than silently spending points on a non-existent catalog item.
    @Test
    void redeemRejectsUnknownReward() {
        service.earn("alice", "order-1", new BigDecimal("1000"), LocalDate.of(2025, 1, 1));
        assertThrows(RewardNotFoundException.class,
                () -> service.redeem("alice", "no-such-reward", LocalDate.of(2025, 6, 1)));
    }

    // Expired points can't fund a redemption, so the as-of expiry filter applies to spending as well as balance.
    @Test
    void redeemCannotUseExpiredPoints() {
        service.earn("alice", "order-1", new BigDecimal("600"), LocalDate.of(2025, 1, 1)); // expires 2026-01-01
        // Points have expired by this date, so the balance is 0 and the redemption can't be afforded.
        assertThrows(InsufficientBalanceException.class,
                () -> service.redeem("alice", "free-coffee", LocalDate.of(2026, 2, 1)));
    }

    // Redeeming exactly the full balance is allowed and leaves zero — the off-by-one boundary on affordability.
    @Test
    void redeemExactBalanceLeavesZero() {
        service.earn("alice", "order-1", new BigDecimal("500"), LocalDate.of(2025, 1, 1));
        RedeemResult result = service.redeem("alice", "free-coffee", LocalDate.of(2025, 6, 1));
        assertEquals(0, result.balanceRemaining());
    }

    // Refunding an unspent purchase reverses its points with no debt and removes it from tier spend.
    @Test
    void refundOfUnredeemedPurchaseRemovesPointsWithoutDebt() {
        service.earn("dan", "order-1", new BigDecimal("600"), LocalDate.of(2025, 1, 1));

        RefundResult result = service.refund("dan", "order-1", LocalDate.of(2025, 6, 1));

        assertEquals(600, result.pointsReversed());
        assertEquals(0, result.debtIncurred());
        assertEquals(0, result.balanceRemaining());
        // Refunded purchase no longer counts toward tier spend.
        assertEquals("Silver", tierOf("dan", LocalDate.of(2025, 6, 1)));
    }

    // Refunding a partly-spent purchase reverses what remains and books the spent portion as negative debt.
    @Test
    void refundAfterSpendingDrivesBalanceNegative() {
        service.earn("dan", "order-1", new BigDecimal("600"), LocalDate.of(2025, 1, 1));
        service.redeem("dan", "free-coffee", LocalDate.of(2025, 6, 1)); // spend 500, 100 left in lot

        RefundResult result = service.refund("dan", "order-1", LocalDate.of(2025, 7, 1));

        assertEquals(100, result.pointsReversed()); // the 100 still in the lot
        assertEquals(500, result.debtIncurred());   // the 500 already spent -> debt
        assertEquals(-500, result.balanceRemaining());
    }

    // New earns first pay off outstanding refund debt, and once settled it never resurrects when points expire.
    @Test
    void earningPaysDownDebtAndItStaysSettled() {
        service.earn("dan", "order-1", new BigDecimal("600"), LocalDate.of(2025, 1, 1));
        service.redeem("dan", "free-coffee", LocalDate.of(2025, 6, 1));
        service.refund("dan", "order-1", LocalDate.of(2025, 7, 1)); // balance now -500

        // Earning 800 pays off the 500 debt, leaving 300 spendable.
        service.earn("dan", "order-2", new BigDecimal("800"), LocalDate.of(2025, 8, 1)); // expires 2026-08-01
        assertEquals(300, service.balance("dan", LocalDate.of(2025, 9, 1)).balance());

        // Even after those points expire, the debt stays settled (no resurrection).
        assertEquals(0, service.balance("dan", LocalDate.of(2026, 9, 1)).balance());
    }

    // A refund reclaims points from the customer's other lots before resorting to debt, minimizing negative balances.
    @Test
    void refundReclaimsFromOtherLotsBeforeCreatingDebt() {
        service.earn("dan", "order-1", new BigDecimal("500"), LocalDate.of(2025, 1, 1)); // expires first
        service.earn("dan", "order-2", new BigDecimal("500"), LocalDate.of(2025, 6, 1));
        service.redeem("dan", "free-coffee", LocalDate.of(2025, 7, 1)); // 500 consumes order-1 fully

        // Refund order-1 (its 500 were spent); reclaim from order-2's balance instead of debt.
        RefundResult result = service.refund("dan", "order-1", LocalDate.of(2025, 7, 1));
        assertEquals(500, result.pointsReversed());
        assertEquals(0, result.debtIncurred());
        assertEquals(0, result.balanceRemaining());
    }

    // Refunding a non-existent purchase throws, so bogus refund requests can't silently create debt.
    @Test
    void refundUnknownPurchaseThrows() {
        assertThrows(PurchaseNotFoundException.class,
                () -> service.refund("dan", "nope", LocalDate.of(2025, 6, 1)));
    }

    // Refunding the same purchase twice throws, preventing double-reversal that would over-deduct points.
    @Test
    void refundTwiceThrows() {
        service.earn("dan", "order-1", new BigDecimal("100"), LocalDate.of(2025, 1, 1));
        service.refund("dan", "order-1", LocalDate.of(2025, 6, 1));
        assertThrows(AlreadyRefundedException.class,
                () -> service.refund("dan", "order-1", LocalDate.of(2025, 6, 1)));
    }
}
