-- Loyalty points schema (SQLite).
--
-- Applied idempotently on startup (CREATE TABLE IF NOT EXISTS), so the database file is
-- created and seeded automatically on first run. See DESIGN.md for the data model rationale.
--
-- Dates are stored as ISO-8601 strings (TEXT). SQLite has no native date type, and ISO-8601
-- sorts/compares correctly as text, so "expires_at > :asOf" works with plain string comparison.

-- Customers: thin, lazily created (upserted) on first earn. No dedicated create flow.
CREATE TABLE IF NOT EXISTS customers (
    id   TEXT PRIMARY KEY,           -- identity passed in requests, e.g. "alice"
    name TEXT                        -- placeholder for a real customer record
);

-- Point lots: one row per earning event. The heart of the model.
-- points_remaining is decremented as points are redeemed (oldest-expiry-first).
-- purchase_id is the natural primary key (the store's purchase identifier): one lot per
-- purchase. This prevents double-earn and is what refunds key off.
CREATE TABLE IF NOT EXISTS point_lots (
    purchase_id      TEXT    PRIMARY KEY NOT NULL,
    customer_id      TEXT    NOT NULL REFERENCES customers(id),
    points_earned    INTEGER NOT NULL,         -- original grant, immutable
    points_remaining INTEGER NOT NULL,         -- decremented on redemption
    earned_at        TEXT    NOT NULL,         -- ISO-8601 date
    expires_at       TEXT    NOT NULL          -- earned_at + 12 months
);

CREATE INDEX IF NOT EXISTS idx_point_lots_customer ON point_lots(customer_id, expires_at);

-- Rewards catalog: the menu of what can be redeemed and its point cost.
CREATE TABLE IF NOT EXISTS rewards (
    id          TEXT    PRIMARY KEY,           -- e.g. "free-coffee"
    name        TEXT    NOT NULL,
    cost_points INTEGER NOT NULL
);

-- Redemptions log: records that a redemption happened (for response + audit).
CREATE TABLE IF NOT EXISTS redemptions (
    id          INTEGER PRIMARY KEY,
    customer_id TEXT    NOT NULL REFERENCES customers(id),
    reward_id   TEXT    NOT NULL REFERENCES rewards(id),
    points_spent INTEGER NOT NULL,
    redeemed_at TEXT    NOT NULL                -- ISO-8601 date
);

-- NOTE: refund handling (clawback / non-expiring negative adjustment) is added later,
-- when we implement the Refunds extended requirement. See DESIGN.md.

-- Seed a small reward catalog. INSERT OR IGNORE keeps startup idempotent.
INSERT OR IGNORE INTO rewards(id, name, cost_points) VALUES
    ('free-coffee',  'Free Coffee',        500),
    ('gift-card-10', '$10 Gift Card',     1000),
    ('movie-ticket', 'Movie Ticket',      1500);
