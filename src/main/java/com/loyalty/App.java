package com.loyalty;

import com.loyalty.db.Database;
import io.javalin.Javalin;

/**
 * Application entry point. Boots the database and the HTTP server, and wires routes.
 *
 * <p>For now this only exposes a health check (which confirms DB connectivity). Services and the
 * loyalty routes will be added as we build out the model.
 */
public class App {

    /** Default port; overridable via the PORT env var for flexibility when running locally. */
    private static final int DEFAULT_PORT = 7070;

    public static void main(String[] args) {
        Database db = new Database(databaseUrl());
        Javalin app = createApp(db);
        app.start(port());
    }

    /** Builds the Javalin app and registers routes. Separated from {@link #main} so tests can boot it. */
    static Javalin createApp(Database db) {
        Javalin app = Javalin.create();
        app.get("/health", ctx -> {
            ctx.json(new HealthStatus("ok", db.ping() ? "connected" : "unavailable"));
        });
        return app;
    }

    private static int port() {
        String fromEnv = System.getenv("PORT");
        return fromEnv == null ? DEFAULT_PORT : Integer.parseInt(fromEnv);
    }

    private static String databaseUrl() {
        String fromEnv = System.getenv("LOYALTY_DB_URL");
        return fromEnv == null ? Database.DEFAULT_URL : fromEnv;
    }

    /** Simple response body for the health check. */
    record HealthStatus(String status, String database) {}
}
