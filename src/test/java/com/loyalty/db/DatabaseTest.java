package com.loyalty.db;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for the schema statement splitter. */
class DatabaseTest {

    // Verifies real ';' terminators split into separate statements, the basic schema-loading case the splitter exists for.
    @Test
    void splitsOnStatementTerminators() {
        List<String> stmts = Database.splitStatements("CREATE TABLE a(x);\nINSERT INTO a VALUES (1);");
        assertEquals(2, stmts.size());
        assertEquals("CREATE TABLE a(x)", stmts.get(0));
        assertEquals("INSERT INTO a VALUES (1)", stmts.get(1));
    }

    // Verifies a ';' inside a string literal is not treated as a terminator, so valid SQL stays intact.
    @Test
    void ignoresSemicolonInsideStringLiteral() {
        // The naive split(";") would shatter this into invalid fragments.
        List<String> stmts = Database.splitStatements("INSERT INTO r VALUES ('Buy 1; get 1 free');");
        assertEquals(1, stmts.size());
        assertEquals("INSERT INTO r VALUES ('Buy 1; get 1 free')", stmts.get(0));
    }

    // Verifies '--' line comments are stripped, even ones containing ';', so comment text never spawns bogus statements.
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

    // Verifies SQLite's doubled-quote '' escape keeps the literal open, so an embedded ';' isn't mistaken for a terminator.
    @Test
    void handlesDoubledQuoteEscapeInsideLiteral() {
        List<String> stmts = Database.splitStatements("INSERT INTO r VALUES ('O''Brien; Co');");
        assertEquals(1, stmts.size());
        assertEquals("INSERT INTO r VALUES ('O''Brien; Co')", stmts.get(0));
    }
}
