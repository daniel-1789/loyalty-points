package com.loyalty.web;

import com.loyalty.service.BalanceResponse;
import com.loyalty.service.EarnResult;
import com.loyalty.service.LoyaltyService;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Registers the loyalty HTTP routes. Handlers parse the request, call the service, return JSON. */
public class LoyaltyController {

    private final LoyaltyService service;

    public LoyaltyController(LoyaltyService service) {
        this.service = service;
    }

    public void register(Javalin app) {
        app.post("/customers/{id}/purchases", this::earn);
        app.get("/customers/{id}/balance", this::balance);
    }

    /** Earn: record a purchase and award points. */
    private void earn(Context ctx) {
        EarnRequest req = ctx.bodyAsClass(EarnRequest.class);
        EarnResult result = service.earn(
                ctx.pathParam("id"), req.purchaseId(), req.amount(), req.date());
        ctx.status(201).json(result);
    }

    /** Balance: a customer's available points, optionally as of a given date (?asOf=YYYY-MM-DD). */
    private void balance(Context ctx) {
        BalanceResponse result = service.balance(ctx.pathParam("id"), parseAsOf(ctx));
        ctx.json(result);
    }

    /** Parse the optional {@code asOf} query param; returns null when absent (service defaults to today). */
    private static LocalDate parseAsOf(Context ctx) {
        String raw = ctx.queryParam("asOf");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("asOf must be an ISO date (YYYY-MM-DD)");
        }
    }
}
