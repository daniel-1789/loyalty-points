package com.loyalty.web;

import com.loyalty.App;
import com.loyalty.db.Database;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HTTP smoke tests for the earn endpoint, booting the real app against an in-memory database. */
class EarnEndpointTest {

    // POST purchase returns 201 with points earned and expiry date — the earn happy path over HTTP.
    @Test
    void postPurchaseReturns201WithPointsEarned() {
        JavalinTest.test(App.createApp(new Database("jdbc:sqlite::memory:")), (server, client) -> {
            var res = client.post("/customers/alice/purchases",
                    Map.of("purchaseId", "order-1", "amount", 100, "date", "2025-01-01"));

            assertEquals(201, res.code());
            String body = res.body().string();
            assertTrue(body.contains("\"pointsEarned\":100"), body);
            assertTrue(body.contains("\"expiresAt\":\"2026-01-01\""), body);
        });
    }

    // Replaying the same purchaseId returns 409 — confirms idempotency guards against double-crediting points.
    @Test
    void postDuplicatePurchaseReturns409() {
        JavalinTest.test(App.createApp(new Database("jdbc:sqlite::memory:")), (server, client) -> {
            var body = Map.of("purchaseId", "order-1", "amount", 100, "date", "2025-01-01");
            assertEquals(201, client.post("/customers/alice/purchases", body).code());
            assertEquals(409, client.post("/customers/alice/purchases", body).code());
        });
    }

    // An impossible calendar date returns 400 with a JSON error body — validates clean error handling over HTTP.
    @Test
    void postPurchaseWithInvalidDateReturns400Json() {
        JavalinTest.test(App.createApp(new Database("jdbc:sqlite::memory:")), (server, client) -> {
            // 2026 is not a leap year, so Feb 29 is not a real date.
            var res = client.post("/customers/bob/purchases",
                    Map.of("purchaseId", "order-3", "amount", 106, "date", "2026-02-29"));

            assertEquals(400, res.code());
            assertTrue(res.header("Content-Type").contains("application/json"));
            assertTrue(res.body().string().contains("error"));
        });
    }

    // A non-numeric amount returns 400 — ensures malformed input is rejected before any points are credited.
    @Test
    void postPurchaseWithNonNumericAmountReturns400() {
        JavalinTest.test(App.createApp(new Database("jdbc:sqlite::memory:")), (server, client) -> {
            var res = client.post("/customers/bob/purchases",
                    Map.of("purchaseId", "order-4", "amount", "abc"));

            assertEquals(400, res.code());
        });
    }

    // A non-positive amount returns 400 — prevents zero or negative purchases from minting bogus points.
    @Test
    void postPurchaseRejectsNonPositiveAmountWith400() {
        JavalinTest.test(App.createApp(new Database("jdbc:sqlite::memory:")), (server, client) -> {
            var res = client.post("/customers/alice/purchases",
                    Map.of("purchaseId", "order-1", "amount", -5, "date", "2025-01-01"));

            assertEquals(400, res.code());
        });
    }
}
