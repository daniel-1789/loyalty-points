package com.loyalty.web;

import com.loyalty.App;
import com.loyalty.db.Database;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HTTP tests for the balance endpoint, including the optional asOf date and expiry. */
class BalanceEndpointTest {

    // GET balance shows 100 points in-window then 0 after expiry via ?asOf= — happy path + expiry over real HTTP.
    @Test
    void balanceReflectsEarnedPointsAndExpiry() {
        JavalinTest.test(App.createApp(new Database("jdbc:sqlite::memory:")), (server, client) -> {
            client.post("/customers/alice/purchases",
                    Map.of("purchaseId", "order-1", "amount", 100, "date", "2025-01-01"));

            var inWindow = client.get("/customers/alice/balance?asOf=2025-06-01");
            assertEquals(200, inWindow.code());
            assertTrue(inWindow.body().string().contains("\"balance\":100"));

            var afterExpiry = client.get("/customers/alice/balance?asOf=2026-06-01");
            assertTrue(afterExpiry.body().string().contains("\"balance\":0"));
        });
    }

    // A malformed asOf query param returns HTTP 400, so the endpoint rejects bad date input cleanly.
    @Test
    void balanceRejectsMalformedAsOfWith400() {
        JavalinTest.test(App.createApp(new Database("jdbc:sqlite::memory:")), (server, client) -> {
            var res = client.get("/customers/alice/balance?asOf=not-a-date");
            assertEquals(400, res.code());
        });
    }

    // The balance response includes the computed tier (Gold for 500 points), exposing tier data over HTTP.
    @Test
    void balanceIncludesTier() {
        JavalinTest.test(App.createApp(new Database("jdbc:sqlite::memory:")), (server, client) -> {
            client.post("/customers/gina/purchases",
                    Map.of("purchaseId", "o1", "amount", 500, "date", "2025-01-01"));

            var res = client.get("/customers/gina/balance?asOf=2025-06-01");
            assertEquals(200, res.code());
            assertTrue(res.body().string().contains("\"tier\":\"Gold\""));
        });
    }

    // GET /tiers returns the seeded tier names and thresholds (Platinum, 2500), verifying tier config is served.
    @Test
    void listTiersReturnsThresholds() {
        JavalinTest.test(App.createApp(new Database("jdbc:sqlite::memory:")), (server, client) -> {
            var res = client.get("/tiers");
            assertEquals(200, res.code());
            String body = res.body().string();
            assertTrue(body.contains("Platinum"));
            assertTrue(body.contains("2500"));
        });
    }
}
