package com.loyalty.web;

import com.loyalty.App;
import com.loyalty.db.Database;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HTTP tests for the refund endpoint. */
class RefundEndpointTest {

    @Test
    void refundAfterSpendingReturnsNegativeBalance() {
        JavalinTest.test(App.createApp(new Database("jdbc:sqlite::memory:")), (server, client) -> {
            client.post("/customers/dan/purchases",
                    Map.of("purchaseId", "order-1", "amount", 600, "date", "2025-01-01"));
            client.post("/customers/dan/redemptions",
                    Map.of("rewardId", "free-coffee", "date", "2025-06-01"));

            var res = client.post("/customers/dan/purchases/order-1/refund?asOf=2025-07-01");

            assertEquals(200, res.code());
            String body = res.body().string();
            assertTrue(body.contains("\"debtIncurred\":500"), body);
            assertTrue(body.contains("\"balanceRemaining\":-500"), body);
        });
    }

    @Test
    void refundUnknownPurchaseReturns404() {
        JavalinTest.test(App.createApp(new Database("jdbc:sqlite::memory:")), (server, client) -> {
            var res = client.post("/customers/dan/purchases/nope/refund");
            assertEquals(404, res.code());
        });
    }

    @Test
    void refundTwiceReturns409() {
        JavalinTest.test(App.createApp(new Database("jdbc:sqlite::memory:")), (server, client) -> {
            client.post("/customers/dan/purchases",
                    Map.of("purchaseId", "order-1", "amount", 100, "date", "2025-01-01"));
            client.post("/customers/dan/purchases/order-1/refund?asOf=2025-06-01");

            var res = client.post("/customers/dan/purchases/order-1/refund?asOf=2025-06-01");
            assertEquals(409, res.code());
        });
    }
}
