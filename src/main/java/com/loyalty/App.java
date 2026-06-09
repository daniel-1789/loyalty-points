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

        // Domain/validation exceptions: each maps to a status and echoes its message as JSON.
        mapError(app, IllegalArgumentException.class, 400);       // invalid input
        mapError(app, DuplicatePurchaseException.class, 409);     // purchase already recorded
        mapError(app, RewardNotFoundException.class, 404);        // unknown reward
        mapError(app, PurchaseNotFoundException.class, 404);      // no such purchase for customer
        mapError(app, AlreadyRefundedException.class, 409);       // purchase already refunded
        mapError(app, InsufficientBalanceException.class, 422);   // can't afford the reward

        // Malformed JSON / wrong field types / invalid date fail in body deserialization; give a
        // fixed message rather than leaking Jackson's verbose internals.
        app.exception(JacksonException.class, (e, ctx) ->
                ctx.status(400).json(Map.of("error",
                        "Invalid request body: check field types and that any date is a real YYYY-MM-DD")));

        // Anything unexpected is logged and still returned as JSON (never plain-text/HTML).
        app.exception(Exception.class, (e, ctx) -> {
            log.error("Unhandled exception handling {} {}", ctx.method(), ctx.path(), e);
            ctx.status(500).json(Map.of("error", "Internal server error"));
        });

        new LoyaltyController(service).register(app);
        return app;
    }

    /** Register a handler that maps an exception type to a status code, echoing its message as JSON. */
    private static <E extends Exception> void mapError(Javalin app, Class<E> type, int status) {
        app.exception(type, (e, ctx) ->
                ctx.status(status).json(Map.of("error", String.valueOf(e.getMessage()))));
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
