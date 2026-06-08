package com.loyalty.web;

import com.loyalty.App;
import com.loyalty.db.Database;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HTTP tests for the redeem and rewards endpoints. */
class RedeemEndpointTest {

    @Test
    void listRewardsReturnsCatalog() {
        JavalinTest.test(App.createApp(new Database("jdbc:sqlite::memory:")), (server, client) -> {
            var res = client.get("/rewards");
            assertEquals(200, res.code());
            assertTrue(res.body().string().contains("free-coffee"));
        });
    }

    @Test
    void redeemReturns201AndRemainingBalance() {
        JavalinTest.test(App.createApp(new Database("jdbc:sqlite::memory:")), (server, client) -> {
            client.post("/customers/alice/purchases",
                    Map.of("purchaseId", "order-1", "amount", 1000, "date", "2025-01-01"));

            var res = client.post("/customers/alice/redemptions",
                    Map.of("rewardId", "free-coffee", "date", "2025-06-01"));

            assertEquals(201, res.code());
            String body = res.body().string();
            assertTrue(body.contains("\"pointsSpent\":500"), body);
            assertTrue(body.contains("\"balanceRemaining\":500"), body);
        });
    }

    @Test
    void redeemUnknownRewardReturns404() {
        JavalinTest.test(App.createApp(new Database("jdbc:sqlite::memory:")), (server, client) -> {
            client.post("/customers/alice/purchases",
                    Map.of("purchaseId", "order-1", "amount", 1000, "date", "2025-01-01"));

            var res = client.post("/customers/alice/redemptions",
                    Map.of("rewardId", "no-such-reward", "date", "2025-06-01"));

            assertEquals(404, res.code());
        });
    }

    @Test
    void redeemInsufficientBalanceReturns422() {
        JavalinTest.test(App.createApp(new Database("jdbc:sqlite::memory:")), (server, client) -> {
            client.post("/customers/alice/purchases",
                    Map.of("purchaseId", "order-1", "amount", 100, "date", "2025-01-01"));

            var res = client.post("/customers/alice/redemptions",
                    Map.of("rewardId", "free-coffee", "date", "2025-06-01"));

            assertEquals(422, res.code());
        });
    }
}
