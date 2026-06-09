package com.loyalty;

import com.loyalty.db.Database;
import com.loyalty.service.LoyaltyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives the CLI against an in-memory database, capturing stdout/stderr. */
class CliTest {

    private Database db;
    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;
    private Cli cli;

    @BeforeEach
    void setUp() {
        db = new Database("jdbc:sqlite::memory:");
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        cli = new Cli(new LoyaltyService(db),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void earnThenBalanceReportsPointsAndTier() {
        assertEquals(0, cli.run(new String[]{
                "earn", "--user=alice", "--purchase-id=order-1", "--amount=600", "--date=2025-01-01"}));
        assertEquals(0, cli.run(new String[]{"balance", "--user=alice", "--as-of=2025-06-01"}));

        assertTrue(stdout().contains("Earned 600 points for alice"), stdout());
        assertTrue(stdout().contains("600 points, tier Gold"), stdout());
    }

    @Test
    void redeemConsumesPoints() {
        cli.run(new String[]{
                "earn", "--user=alice", "--purchase-id=order-1", "--amount=600", "--date=2025-01-01"});
        assertEquals(0, cli.run(new String[]{
                "redeem", "--user=alice", "--reward=free-coffee", "--date=2025-06-01"}));
        assertTrue(stdout().contains("redeemed free-coffee for 500 points; 100 remaining"), stdout());
    }

    @Test
    void insufficientBalanceExitsNonZeroWithMessage() {
        cli.run(new String[]{
                "earn", "--user=alice", "--purchase-id=order-1", "--amount=100", "--date=2025-01-01"});
        int code = cli.run(new String[]{
                "redeem", "--user=alice", "--reward=free-coffee", "--date=2025-06-01"});

        assertEquals(1, code);
        assertTrue(stderr().contains("Insufficient balance"), stderr());
    }

    @Test
    void missingRequiredFlagIsUsageError() {
        int code = cli.run(new String[]{"balance"}); // no --user
        assertEquals(2, code);
        assertTrue(stderr().contains("--user is required"), stderr());
    }

    @Test
    void unknownCommandIsUsageError() {
        assertEquals(2, cli.run(new String[]{"frobnicate"}));
        assertTrue(stderr().contains("Unknown command"), stderr());
    }

    @Test
    void malformedDateIsUsageErrorWithCleanMessage() {
        int code = cli.run(new String[]{"balance", "--user=alice", "--as-of=not-a-date"});
        assertEquals(2, code);
        assertTrue(stderr().contains("must be an ISO date"), stderr());
    }

    @Test
    void nonNumericAmountIsUsageErrorWithCleanMessage() {
        int code = cli.run(new String[]{
                "earn", "--user=alice", "--purchase-id=p1", "--amount=abc"});
        assertEquals(2, code);
        assertTrue(stderr().contains("must be a number"), stderr());
    }

    @Test
    void invalidAmountFromServiceIsUsageError() {
        int code = cli.run(new String[]{
                "earn", "--user=alice", "--purchase-id=p1", "--amount=-5"});
        assertEquals(2, code);
        assertTrue(stderr().contains("greater than zero"), stderr());
    }

    @Test
    void rewardsListsCatalog() {
        assertEquals(0, cli.run(new String[]{"rewards"}));
        assertTrue(stdout().contains("free-coffee"), stdout());
    }
}
