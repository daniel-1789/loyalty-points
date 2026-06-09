package com.loyalty;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.loyalty.db.Database;
import com.loyalty.service.AlreadyRefundedException;
import com.loyalty.service.DuplicatePurchaseException;
import com.loyalty.service.InsufficientBalanceException;
import com.loyalty.service.LoyaltyService;
import com.loyalty.service.PurchaseNotFoundException;
import com.loyalty.service.RewardNotFoundException;
import com.loyalty.web.LoyaltyController;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Application entry point. Boots the database and HTTP server, and wires routes.
 */
public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    /** Default port; overridable via the PORT env var for flexibility when running locally. */
    private static final int DEFAULT_PORT = 7070;

    public static void main(String[] args) {
        Database db = Database.fromEnv();
        Javalin app = createApp(db);
        app.start(port());
    }

    /** Builds the Javalin app against the given database. Public so tests can boot it in-memory. */
    public static Javalin createApp(Database db) {
        LoyaltyService service = new LoyaltyService(db);

        Javalin app = Javalin.create(config -> {
            config.jsonMapper(jsonMapper());
            // One line per request so behaviour is traceable from logs alone.
            config.requestLogger.http((ctx, ms) ->
                    log.info("{} {} -> {} ({} ms)", ctx.method(), ctx.path(), ctx.statusCode(), Math.round(ms)));
        });

        app.get("/health", ctx ->
                ctx.json(new HealthStatus("ok", db.ping() ? "connected" : "unavailable")));

        // Malformed JSON, wrong field types, or an invalid date fail during body deserialization.
        app.exception(JacksonException.class, (e, ctx) ->
                ctx.status(400).json(Map.of("error",
                        "Invalid request body: check field types and that any date is a real YYYY-MM-DD")));

        // Invalid input from the service surfaces as a 400 with a clear message.
        app.exception(IllegalArgumentException.class, (e, ctx) ->
                ctx.status(400).json(Map.of("error", String.valueOf(e.getMessage()))));

        // Re-submitting an already-recorded purchase is a conflict, not a bad request.
        app.exception(DuplicatePurchaseException.class, (e, ctx) ->
                ctx.status(409).json(Map.of("error", String.valueOf(e.getMessage()))));

        // Unknown reward, or refunding a purchase that doesn't exist for the customer -> 404.
        app.exception(RewardNotFoundException.class, (e, ctx) ->
                ctx.status(404).json(Map.of("error", String.valueOf(e.getMessage()))));
        app.exception(PurchaseNotFoundException.class, (e, ctx) ->
                ctx.status(404).json(Map.of("error", String.valueOf(e.getMessage()))));

        // Refunding an already-refunded purchase -> 409.
        app.exception(AlreadyRefundedException.class, (e, ctx) ->
                ctx.status(409).json(Map.of("error", String.valueOf(e.getMessage()))));

        // Redeeming more than the available balance -> 422 (well-formed but unfulfillable).
        app.exception(InsufficientBalanceException.class, (e, ctx) ->
                ctx.status(422).json(Map.of("error", String.valueOf(e.getMessage()))));

        // Anything unexpected is logged and still returned as JSON (never plain-text/HTML).
        app.exception(Exception.class, (e, ctx) -> {
            log.error("Unhandled exception handling {} {}", ctx.method(), ctx.path(), e);
            ctx.status(500).json(Map.of("error", "Internal server error"));
        });

        new LoyaltyController(service).register(app);
        return app;
    }

    /** Jackson configured to read/write java.time types as ISO-8601 strings (not numeric arrays). */
    private static JavalinJackson jsonMapper() {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new JavalinJackson(mapper, false);
    }

    private static int port() {
        String fromEnv = System.getenv("PORT");
        return fromEnv == null ? DEFAULT_PORT : Integer.parseInt(fromEnv);
    }

    /** Simple response body for the health check. */
    record HealthStatus(String status, String database) {}
}
