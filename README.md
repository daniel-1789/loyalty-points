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

Not yet built: a dedicated **CLI** (stretch) — though `scripts/loyalty.sh` covers manual
interaction. See [Design](#design) for more.

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

## Convenience script

`scripts/loyalty.sh` wraps the API for manual experimentation (pretty-prints with `jq` if present):

```bash
scripts/loyalty.sh earn alice order-1 100 2025-01-01
scripts/loyalty.sh balance alice 2025-06-01
scripts/loyalty.sh redeem alice free-coffee 2025-06-01
scripts/loyalty.sh refund alice order-1 2025-07-01
scripts/loyalty.sh rewards            # catalog
scripts/loyalty.sh tiers              # thresholds
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

The full design log — including alternatives considered and rejected — is in
[`DESIGN.md`](DESIGN.md). The essentials:

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

### A decision with a real trade-off: mutable lots vs. an append-only ledger

We decrement `points_remaining` on each lot rather than keeping an immutable, signed event ledger.
The ledger's headline appeal is "a refund is just one negative entry" — but we walked the
buy → redeem → return case and found that **once a clawback exceeds what's left, the ledger also
needs a special non-expiring negative entry**, so refunds are a wash between the two designs. With
that neutralized, the ledger's only remaining edge is a richer audit trail (which the brief doesn't
require), at the cost of a more complex balance query on every read. Mutable lots is the simpler
design that fully satisfies the requirements; the ledger is the natural "with more time" evolution.

### Key assumptions

- 1 point per whole dollar; fractional dollars dropped (`$10.99` → 10).
- A lot is active while `earned_at <= asOf < expires_at` (expiry exclusive on its date).
- `purchase_id` is globally unique — one earn per purchase (duplicate → `409`).
- Tier "spend" is measured in earned points (≈ whole dollars), over a rolling 12 months.
- Unknown customer → balance `0` (not distinguished from a zero balance).

### What I'd add with more time

- **Append-only event ledger** for full auditability (the trade-off above) — would also let
  balance queries time-travel across redemptions/refunds, which mutable lots can't.
- **Flyway/Liquibase** migrations instead of hand-applied `schema.sql`.
- **Idempotent earn replay** (return the existing lot) instead of `409`, for at-least-once delivery.
- A connection pool (instead of a single shared connection) for real concurrency.
- A dedicated **CLI** (the stretch goal); `scripts/loyalty.sh` currently covers manual use.

### AI tools

Built with **Claude Code** (Anthropic's agentic CLI) used as a pair programmer: talking through the
data-model trade-offs, scaffolding the project, writing implementation and tests, and catching bugs
(e.g. the missing lower bound on the balance window surfaced while experimenting). Design decisions
— framework choice, the lot model, mutable-lots-vs-ledger, tier semantics — were made by me, with
the AI used to pressure-test reasoning and accelerate the typing.

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
| `resources/schema.sql` | migrations | Hand-written DDL instead of generated migration files. |
| `resources/simplelogger.properties` | the `LOGGING` dict | Logging configuration. |

One more difference: Django auto-discovers apps, URLs, and models. Here, wiring is **explicit** — `App.createApp()`
constructs the objects and hands them to the controller (constructor injection by hand). Spring Boot would
restore the Django-like autowiring magic; this project keeps it explicit so there's no hidden behavior.
