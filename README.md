# Loyalty Points System

Backend for a retail loyalty program: customers earn points on purchases, redeem them for
rewards, and are assigned a tier based on spending — with points expiring 12 months after they're
earned.

## What's implemented

- **Earn** — a purchase awards points (1 point per whole dollar).
- **Balance** — a customer's available (unexpired) points, with their tier.
- **Redeem** — spend points on a reward from the catalog, consuming points closest to expiry first.
- **Expiry** — points expire 12 months after they're earned and stop counting automatically.
- **Tier** (extended) — Silver / Gold / Platinum from rolling 12-month spend.
- **Refunds** (extended) — reverse a purchase's points; the balance can go negative and is paid
  down by future earnings.
- **CLI** (stretch) — a `points` command driving the same logic directly, no server required.

Everything in the brief (required + extended + stretch) is implemented. See [Design](#design).

## Tech stack

- **Java 21**
- **[Javalin](https://javalin.io/)** — lightweight HTTP framework (embedded Jetty)
- **SQLite** — embedded, file-based database (via `sqlite-jdbc`), plain JDBC (no ORM)
- **Maven** — build tool
- **JUnit 5** — tests (against an in-memory SQLite database)

## Prerequisites

You need **JDK 21** and **Maven**. On macOS with [Homebrew](https://brew.sh/):

```bash
brew install openjdk@21 maven
```

## Build & run

From the project root (`loyalty-points/`):

```bash
# 1. Point this shell at JDK 21 (Homebrew installs it "keg-only", so we set JAVA_HOME
#    explicitly. Maven otherwise defaults to whatever other JDK is on the system.)
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home

# 2. Build (compiles, runs tests, produces a runnable jar in target/)
mvn clean package

# 3. Run
java -jar target/loyalty-points.jar
```

The server listens on **http://localhost:7070**. The SQLite database file (`loyalty.db`) is created
and seeded automatically on first run; delete it to start fresh.

```bash
curl http://localhost:7070/health
# {"status":"ok","database":"connected"}
```

> **Tips**
> - `mvn exec:java` runs the app without building a jar — handy during development.
> - `PORT=8080` runs on a different port; `LOYALTY_DB_URL=jdbc:sqlite::memory:` uses a throwaway
>   in-memory database.
> - Stop the server with `Ctrl+C`.

## API

Customer identity is passed in the URL (no auth, per the brief). Dates are ISO-8601 (`YYYY-MM-DD`).
Most write/read operations accept an **optional date** (`date` in the body, or `?asOf=` on reads)
that defaults to today — this lets you simulate history and expiry without changing the clock.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/health` | Liveness + DB connectivity |
| `GET` | `/rewards` | Reward catalog |
| `GET` | `/tiers` | Tier thresholds |
| `POST` | `/customers/{id}/purchases` | **Earn** — body `{"purchaseId","amount","date?"}` |
| `GET` | `/customers/{id}/balance?asOf=` | **Balance** + tier |
| `POST` | `/customers/{id}/redemptions` | **Redeem** — body `{"rewardId","date?"}` |
| `POST` | `/customers/{id}/purchases/{purchaseId}/refund?asOf=` | **Refund** — reverse a purchase |

**Status codes:** `201` on earn/redeem, `200` on reads/refund, `400` invalid input,
`404` unknown reward/purchase, `409` duplicate purchase or already-refunded, `422` insufficient
balance, `500` unexpected. Every response (including errors) is JSON.

**Examples:**

```bash
# Earn 100 points for a $100 purchase
curl -X POST localhost:7070/customers/alice/purchases \
  -H 'Content-Type: application/json' \
  -d '{"purchaseId":"order-1","amount":100,"date":"2025-01-01"}'

# Check balance (and tier) as of a date
curl 'localhost:7070/customers/alice/balance?asOf=2025-06-01'
# {"customerId":"alice","balance":100,"tier":"Silver","spendLast12Months":100,"asOf":"2025-06-01"}

# Redeem a reward
curl -X POST localhost:7070/customers/alice/redemptions \
  -H 'Content-Type: application/json' \
  -d '{"rewardId":"free-coffee","date":"2025-06-01"}'
```

## CLI (stretch)

`scripts/points` is a command-line interface that drives the **same `LoyaltyService`** directly
against the database — **no server needed** (build with `mvn package` first):

```bash
scripts/points earn    --user=alice --purchase-id=order-123 --amount=100 --date=2025-01-01
scripts/points balance --user=alice --as-of=2025-06-01
scripts/points redeem  --user=alice --reward=free-coffee --date=2025-06-01
scripts/points refund  --user=alice --purchase-id=order-123 --date=2025-07-01
scripts/points rewards
scripts/points tiers
```

It shares all business logic with the REST API (the web layer and CLI are both thin shells over
the service). Override the database with `LOYALTY_DB_URL`. Exit codes: `0` ok, `1` operation error
(e.g. insufficient balance), `2` usage error.

## HTTP convenience script

`scripts/loyalty.sh` wraps the *running server's* API for manual experimentation (pretty-prints
with `jq` if present):

```bash
scripts/loyalty.sh earn alice order-1 100 2025-01-01
scripts/loyalty.sh balance alice 2025-06-01
scripts/loyalty.sh redeem alice free-coffee 2025-06-01
scripts/loyalty.sh refund alice order-1 2025-07-01
scripts/loyalty.sh demo alice         # earns + balances showing points expire over time
```

Override the host with `BASE_URL`, e.g. `BASE_URL=http://localhost:8080 scripts/loyalty.sh health`.

## Testing

```bash
mvn test
```

Tests run against a fresh **in-memory SQLite** database per test (no fixtures to clean up), and use
the optional `asOf` date to exercise time-based behavior (expiry, rolling-window tiers)
deterministically — without touching the system clock.

## Design

### Data model

Points are modeled as a **ledger of lots**. Every earn creates one immutable row capturing that
event; redemption and expiry are attributed to specific lots, which is what makes both correct.

| Table | Purpose |
|---|---|
| `customers` | Thin; lazily created on first earn (no separate create flow). Holds `point_debt` (outstanding refund debt, paid down as the customer earns). |
| `point_lots` | One row per earning event — **the heart of the model**. Keyed by `purchase_id` (the store's identifier). Carries `points_earned`, a mutable `points_remaining`, `earned_at`, `expires_at`, and `refunded_at`. |
| `rewards` | The redemption catalog (seeded). |
| `redemptions` | Log of redemptions (response + audit). |
| `refunds` | Log of refunds (audit + one-refund-per-purchase guard). |
| `tiers` | Tier thresholds (seeded Silver 0 / Gold 500 / Platinum 2500) — data, not code, so they're changeable. |

- **Balance** = `SUM(points_remaining)` over lots where `earned_at <= asOf < expires_at`.
- **Redeem** consumes lots **oldest-expiry-first** (satisfies the "redemption order" stretch goal),
  decrementing `points_remaining`, all in one transaction.
- **Tier** = highest threshold met by **gross points earned** in the trailing 12 months — so
  redeeming points never lowers a tier (it reflects spend, not balance).
- **Refund** reverses a purchase: void its lot, reclaim the already-spent portion from current
  balance, and record any shortfall as debt. The balance can go negative; the next earn pays the
  debt down first (and a refund removes that purchase from tier spend, so it can lower a tier).

### Key decisions & assumptions

- **`purchase_id` is the primary key of a points lot.** We assume it's a stable, globally-unique
  identifier supplied by the upstream commerce platform — one purchase, one earning event. That
  gives idempotency (a duplicate earn is a `409`) and a clean handle for refunds, for free.
- **Refunds allow a negative balance that's self-healing.** A refund reverses a purchase's points
  and, for any portion already spent, records debt — so the balance can go negative. Rather than
  letting that debt linger or expire, the *next* points earned pay it down first. This is also the
  anti-abuse property: you can't buy → redeem → return → wait it out.
- **Consequence of self-healing debt: no real-money recovery.** Keeping the clawback inside the
  points system (rather than touching money) fits this exercise and is customer-friendly, but it
  leaks: someone who redeemed points and then returned the purchase has effectively gotten the
  reward "on credit," and if they never shop again the debt is never collected. A common
  alternative — used by card programs like Apple Card Daily Cash and Amazon points — is to **bill
  the cash value** of the points already spent, which closes the leak but requires real money
  movement and a payment/refund integration (out of scope here, and outside a points-only system).
- **Refunds keep the customer id in the path as a sanity/ownership check.** Since `purchase_id` is
  globally unique, the customer isn't strictly needed to find the purchase — but we verify the
  purchase belongs to that customer (mismatch → `404`). There's no requirement for it; we kept it
  instinctively as a guard, with the understanding that a real auth token would carry that identity
  in production.
- **Tier thresholds are arbitrary and data-driven.** Seeded Silver 0 / Gold 500 / Platinum 2500 in
  a table (not code/env), so they're changeable. Setting Silver at 0 means everyone has a tier; a
  non-zero Silver floor would be equally valid and would simply mean low spenders have *no* tier.
- **Time is an explicit input (`asOf` / `date`), not a hidden `now()`.** Every operation takes an
  optional date, so history can be simulated and time-based behavior (expiry, rolling-window tiers)
  tested deterministically — no clock manipulation.
- **Tier reflects gross spend, not current balance.** It's based on `points_earned` over the
  trailing 12 months, so redeeming points never demotes a customer; only a refund (which un-does a
  purchase) reduces spend and can drop a tier.
- **Deliberately lightweight stack — Javalin + hand-written SQL + SQLite — chosen for turnaround.**
  As someone whose day-to-day backend work is in Python, I favored a minimal, fully-explainable
  stack over a heavier one (Spring Boot / an ORM) so I could move quickly and understand every line
  rather than fight framework magic within a ~3-hour box.
- **No Docker — a simple local run.** `mvn package` then `java -jar` (or the CLI) keeps the barrier
  to running it low. Containerization would be the next step toward deployment but adds nothing for
  reviewing the code.
- **1 point per whole dollar**, fractional dollars dropped (`$10.99` → 10).
- **Expiry boundary is exclusive** — a lot is active while `earned_at <= asOf < expires_at`.
- **`expires_at` is stored, not derived.** It's always `earned_at + 12 months`, so it could be
  computed on every read — but storing it trades a little space for simpler, faster queries (no date
  math in each `WHERE`), and, as an ancillary benefit, lets an individual lot's expiry be *overridden*
  (e.g. customer service extending points for an extenuating circumstance, or a promotion granting a
  longer window) — which a hard-coded formula couldn't accommodate.
- **Tier "spend" is counted in earned points** (≈ whole dollars), over the rolling 12 months.
- **Unknown customer → balance `0`** — not distinguished from a genuine zero balance.
- **Mutable lots over an append-only ledger** — the most significant trade-off, detailed next.

### A decision with a real trade-off: mutable lots vs. an append-only ledger

I decrement `points_remaining` on each lot rather than keeping an immutable, signed event ledger.

I explored the idea of using an append-only ledger — it intuitively seemed to offer a refund as
"just a negative entry." However, experimentation with Claude showed that in a buy → redeem →
return case, **once a clawback exceeds what's left, the ledger also needs a special non-expiring
negative entry**, making refunds a wash between the two designs. With that neutralized, the
ledger's only remaining edge is a richer audit trail (which, while attractive, is out of scope), at
the cost of a more complex balance query on every read. Mutable lots is the simpler design that
fully satisfies the requirements; in a scenario that didn't suggest a three-hour-or-so time-box,
I'd likely lean more toward the ledger implementation.

### What I'd add with more time

- **Append-only event ledger** for full auditability (the trade-off above) — would also let
  balance queries time-travel across redemptions/refunds, which mutable lots can't.
- **Flyway/Liquibase** migrations instead of hand-applied `schema.sql`. As someone used to working in Django and SQLAlchemy in Python, this seems like it would be handy.
- **Idempotent earn replay** (return the existing lot) instead of `409`, for at-least-once delivery.
- A connection pool (instead of a single shared connection) for real concurrency.

### AI tools

Built with **Claude Code** (Anthropic's agentic CLI) used as a pair programmer: talking through the
data-model trade-offs, scaffolding the project, writing implementation and tests, and catching bugs
(e.g. the missing lower bound on the balance window surfaced while experimenting). Design decisions
— framework choice, the lot model, mutable-lots-vs-ledger, tier semantics — were made by me. I used
the AI to test various assumptions and possibilities.

## Django Translation

For developers (like the author) more familiar with backend implementations in Python/Django,
here's what each directory under `src/main/java/com/loyalty/` maps to. The concepts are the same;
they're just split into more, smaller pieces. The biggest difference: Django's `Model` bundles the
data shape, the ORM/queries, and business logic together (Active Record), whereas this layout pulls
those apart into `model/` (data), `db/` (queries), and `service/` (logic) — the Repository/DAO pattern.

| Directory / file | Django equivalent | Notes |
|---|---|---|
| `web/LoyaltyController.java` | `urls.py` + `views.py` | `register(app)` is your URLconf; the handler methods are thin views (parse → call service → return JSON). |
| `web/EarnRequest`, `web/RedeemRequest` | input side of a DRF serializer | Define the request body; Jackson validates/deserializes into them (`serializer.is_valid()` / `validated_data`). |
| `service/LoyaltyService.java` | `services.py` / fat-model methods | Where the business rules live. Kept out of the views on purpose. |
| `service/EarnResult`, `BalanceResponse`, `RedeemResult` | output side of a serializer | The response shape (`serializer.data`); Jackson renders them to JSON. |
| `service/*Exception.java` | DRF `APIException` subclasses | Each maps to an HTTP status (mapping lives in `App.java`, like DRF's `EXCEPTION_HANDLER`). |
| `db/*Dao.java` | Model Managers / QuerySets (`.objects`) | e.g. `PointLotDao.balance(...)` ≈ `PointLot.objects.filter(...).aggregate(Sum(...))`. |
| `db/Database.java` | `settings.py DATABASES` + connection + `migrate` | Opens the connection and applies `schema.sql` on startup. |
| `db/DataAccessException.java` | wrapping `django.db.Error` | So upper layers don't handle raw SQL exceptions. |
| `model/*.java` | the field definitions of a Django model | Data only — the `.objects`/query part is moved to `db/`. |
| `App.java` | `manage.py` + `wsgi.py` + `settings.py` | Entry point; the `Javalin.create(...)` block is settings (JSON config, request logging); `app.exception(...)` is the central exception→status mapping. |
| `Cli.java` | a custom `manage.py` command (`BaseCommand`) | A second entry point over the same service — like a management command reusing your models/services. |
| `resources/schema.sql` | migrations | Hand-written DDL instead of generated migration files. |
| `resources/simplelogger.properties` | the `LOGGING` dict | Logging configuration. |

One more difference: Django auto-discovers apps, URLs, and models. Here, wiring is **explicit** — `App.createApp()`
constructs the objects and hands them to the controller (constructor injection by hand). Spring Boot would
restore the Django-like autowiring magic; this project keeps it explicit so there's no hidden behavior.
