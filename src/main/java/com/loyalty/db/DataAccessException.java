package com.loyalty.db;

/**
 * Wraps the checked {@link java.sql.SQLException} as an unchecked exception so the service and
 * web layers don't have to handle low-level SQL plumbing.
 */
public class DataAccessException extends RuntimeException {
    public DataAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
