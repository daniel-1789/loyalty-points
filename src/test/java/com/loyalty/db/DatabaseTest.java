package com.loyalty.db;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for the schema statement splitter. */
class DatabaseTest {

    @Test
    void splitsOnStatementTerminators() {
        List<String> stmts = Database.splitStatements("CREATE TABLE a(x);\nINSERT INTO a VALUES (1);");
        assertEquals(2, stmts.size());
        assertEquals("CREATE TABLE a(x)", stmts.get(0));
        assertEquals("INSERT INTO a VALUES (1)", stmts.get(1));
    }

    @Test
    void ignoresSemicolonInsideStringLiteral() {
        // The naive split(";") would shatter this into invalid fragments.
        List<String> stmts = Database.splitStatements("INSERT INTO r VALUES ('Buy 1; get 1 free');");
        assertEquals(1, stmts.size());
        assertEquals("INSERT INTO r VALUES ('Buy 1; get 1 free')", stmts.get(0));
    }

    @Test
    void stripsLineCommentsIncludingThoseWithSemicolons() {
        String sql = """
                -- a comment; with a semicolon
                CREATE TABLE a(x); -- trailing; comment
                """;
        List<String> stmts = Database.splitStatements(sql);
        assertEquals(1, stmts.size());
        assertEquals("CREATE TABLE a(x)", stmts.get(0));
    }

    @Test
    void handlesDoubledQuoteEscapeInsideLiteral() {
        List<String> stmts = Database.splitStatements("INSERT INTO r VALUES ('O''Brien; Co');");
        assertEquals(1, stmts.size());
        assertEquals("INSERT INTO r VALUES ('O''Brien; Co')", stmts.get(0));
    }
}
