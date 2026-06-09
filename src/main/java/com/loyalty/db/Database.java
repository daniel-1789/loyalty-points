package com.loyalty.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Owns the SQLite connection and applies the schema on startup.
 *
 * <p>Holds a single long-lived {@link Connection}. Because a JDBC {@link Connection} is not safe
 * for concurrent use — and {@link #transaction} toggles connection-wide autocommit — all access is
 * serialized through {@link #lock}: writes via {@link #transaction}, reads via {@link #read}. That
 * keeps concurrent HTTP requests from interleaving on the shared connection, at the cost of
 * serializing DB access (fine for SQLite, which already allows only one writer). A production system
 * with real load would use a connection pool (one connection per request) instead.
 *
 * <p>The single connection also lets tests use an in-memory database, which lives only as long as
 * its connection is open.
 */
public class Database implements AutoCloseable {

    /** Default: a file in the working directory, created on first connect. */
    public static final String DEFAULT_URL = "jdbc:sqlite:loyalty.db";

    private static final String SCHEMA_RESOURCE = "/schema.sql";

    private final Connection connection;

    /** Serializes all access to the single shared connection. Reentrant so reads/txns can't deadlock. */
    private final ReentrantLock lock = new ReentrantLock();

    public Database(String jdbcUrl) {
        try {
            this.connection = DriverManager.getConnection(jdbcUrl);
            enableForeignKeys();
            applySchema();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize database at " + jdbcUrl, e);
        }
    }

    /** Open the database from the LOYALTY_DB_URL env var, falling back to {@link #DEFAULT_URL}. */
    public static Database fromEnv() {
        String url = System.getenv("LOYALTY_DB_URL");
        return new Database(url != null ? url : DEFAULT_URL);
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
        lock.lock();
        try {
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
        } finally {
            lock.unlock();
        }
    }

    /**
     * Run a read-only {@code work} block holding the connection lock, so reads don't race a
     * concurrent {@link #transaction} on the shared connection.
     */
    public <T> T read(java.util.function.Supplier<T> work) {
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
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
        lock.lock();
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT 1")) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        } finally {
            lock.unlock();
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
