package com.loyalty.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Owns the SQLite connection and applies the schema on startup.
 *
 * <p>Holds a single long-lived {@link Connection}. SQLite serializes access internally, which is
 * sufficient for this application's modest concurrency; it also lets us use an in-memory database
 * for tests (an in-memory DB lives only as long as its connection is open). A production system
 * with real load would use a connection pool instead.
 */
public class Database implements AutoCloseable {

    /** Default: a file in the working directory, created on first connect. */
    public static final String DEFAULT_URL = "jdbc:sqlite:loyalty.db";

    private static final String SCHEMA_RESOURCE = "/schema.sql";

    private final Connection connection;

    public Database(String jdbcUrl) {
        try {
            this.connection = DriverManager.getConnection(jdbcUrl);
            enableForeignKeys();
            applySchema();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize database at " + jdbcUrl, e);
        }
    }

    /** The shared connection, handed to DAOs. */
    public Connection connection() {
        return connection;
    }

    /**
     * Run {@code work} inside a single transaction, committing on success and rolling back if it
     * throws. Used for operations that touch multiple rows/tables and must be atomic (e.g. earn:
     * upsert the customer and insert the lot together; redeem: decrement several lots).
     */
    public <T> T transaction(java.util.function.Supplier<T> work) {
        try {
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new DataAccessException("Failed to begin transaction", e);
        }
        try {
            T result = work.get();
            connection.commit();
            return result;
        } catch (RuntimeException e) {
            rollbackQuietly();
            throw e;
        } catch (SQLException e) {
            rollbackQuietly();
            throw new DataAccessException("Failed to commit transaction", e);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // best effort; connection is closed on shutdown anyway
            }
        }
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // nothing actionable
        }
    }

    /** Lightweight connectivity check for the health endpoint. */
    public boolean ping() {
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT 1")) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    /** SQLite does not enforce foreign keys unless this pragma is set, per-connection. */
    private void enableForeignKeys() throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA foreign_keys = ON");
        }
    }

    private void applySchema() throws SQLException {
        // Strip `--` line comments first, then split on ';'. JDBC executes one statement per call,
        // and stripping comments keeps a semicolon inside a comment from breaking the split. (Our
        // schema has no `--` or ';' inside string literals, so this stays correct.)
        String ddl = readResource(SCHEMA_RESOURCE).replaceAll("(?m)--.*$", "");
        try (Statement s = connection.createStatement()) {
            for (String statement : ddl.split(";")) {
                String trimmed = statement.strip();
                if (!trimmed.isEmpty()) {
                    s.execute(trimmed);
                }
            }
        }
    }

    private static String readResource(String path) {
        try (InputStream in = Database.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Schema resource not found on classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read schema resource: " + path, e);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            // Nothing actionable on shutdown; swallow.
        }
    }
}
