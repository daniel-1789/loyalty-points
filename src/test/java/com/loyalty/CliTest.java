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

    // CLI earn then balance prints points + tier — proves the CLI drives the same service/tier logic as the API.
    @Test
    void earnThenBalanceReportsPointsAndTier() {
        assertEquals(0, cli.run(new String[]{
                "earn", "--user=alice", "--purchase-id=order-1", "--amount=600", "--date=2025-01-01"}));
        assertEquals(0, cli.run(new String[]{"balance", "--user=alice", "--as-of=2025-06-01"}));

        assertTrue(stdout().contains("Earned 600 points for alice"), stdout());
        assertTrue(stdout().contains("600 points, tier Gold"), stdout());
    }

    // Redeem deducts the reward cost and reports remaining points — confirms redemption actually consumes the balance.
    @Test
    void redeemConsumesPoints() {
        cli.run(new String[]{
                "earn", "--user=alice", "--purchase-id=order-1", "--amount=600", "--date=2025-01-01"});
        assertEquals(0, cli.run(new String[]{
                "redeem", "--user=alice", "--reward=free-coffee", "--date=2025-06-01"}));
        assertTrue(stdout().contains("redeemed free-coffee for 500 points; 100 remaining"), stdout());
    }

    // Redeeming beyond the balance exits non-zero with an error — ensures the CLI signals failed redemptions to scripts.
    @Test
    void insufficientBalanceExitsNonZeroWithMessage() {
        cli.run(new String[]{
                "earn", "--user=alice", "--purchase-id=order-1", "--amount=100", "--date=2025-01-01"});
        int code = cli.run(new String[]{
                "redeem", "--user=alice", "--reward=free-coffee", "--date=2025-06-01"});

        assertEquals(1, code);
        assertTrue(stderr().contains("Insufficient balance"), stderr());
    }

    // Omitting a required flag yields exit 2 and a clear message — protects users from silent misuse of commands.
    @Test
    void missingRequiredFlagIsUsageError() {
        int code = cli.run(new String[]{"balance"}); // no --user
        assertEquals(2, code);
        assertTrue(stderr().contains("--user is required"), stderr());
    }

    // An unrecognized command exits 2 with "Unknown command" — guards against typos being treated as valid actions.
    @Test
    void unknownCommandIsUsageError() {
        assertEquals(2, cli.run(new String[]{"frobnicate"}));
        assertTrue(stderr().contains("Unknown command"), stderr());
    }

    // A malformed date maps to a clean exit-2 usage error — gives parity with the web layer's 400 for bad input.
    @Test
    void malformedDateIsUsageErrorWithCleanMessage() {
        int code = cli.run(new String[]{"balance", "--user=alice", "--as-of=not-a-date"});
        assertEquals(2, code);
        assertTrue(stderr().contains("must be an ISO date"), stderr());
    }

    // A non-numeric amount becomes a clean exit-2 usage error — mirrors the API's 400 for unparseable input.
    @Test
    void nonNumericAmountIsUsageErrorWithCleanMessage() {
        int code = cli.run(new String[]{
                "earn", "--user=alice", "--purchase-id=p1", "--amount=abc"});
        assertEquals(2, code);
        assertTrue(stderr().contains("must be a number"), stderr());
    }

    // A negative amount rejected by the service surfaces as exit 2 — service-level validation maps to a usage error.
    @Test
    void invalidAmountFromServiceIsUsageError() {
        int code = cli.run(new String[]{
                "earn", "--user=alice", "--purchase-id=p1", "--amount=-5"});
        assertEquals(2, code);
        assertTrue(stderr().contains("greater than zero"), stderr());
    }

    // The rewards command lists the catalog including free-coffee — lets users discover redeemable rewards from the CLI.
    @Test
    void rewardsListsCatalog() {
        assertEquals(0, cli.run(new String[]{"rewards"}));
        assertTrue(stdout().contains("free-coffee"), stdout());
    }
}
