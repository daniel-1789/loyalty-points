# Design Notes (working doc)

Scratchpad for the data model and key decisions. Will be distilled into the README's
"Design" section before submission.

## Core idea: points as a ledger of "lots"

Every time a customer earns points, we create one immutable row (a **lot**) capturing that
earning event. A lot knows when it was earned and when it expires (12 months later). All the
required behaviour falls out of this:

- **Balance** = sum of remaining points across lots that haven't expired as of a given date
- **Expiry** = a `WHERE expires_at > asOf` filter — no background job needed
- **Redeem** = consume points from lots, **oldest-expiry-first** (gets the "redemption order"
  stretch goal almost for free)
- **Refund** = reverse the lot(s) created by the refunded purchase (details TBD)

### Why row-per-earn is necessary (not just tidy)

Expiry and redemption must be attributed to *specific* earning events, not to a flat
per-customer balance. Worked example:

- Jan 1: earn 100 (expires next Jan 1)
- Jun 1: earn 100 (expires next Jun 1)
- Mar 1: redeem 50  -> consumes from the Jan batch (closest to expiry)
- next Jan 1: Jan batch expires -> only the remaining 50 of it is lost; all of Jun survives

A single `balance = 150` counter can't answer "how much just expired?" because it has lost
which points the redemption came from. Per-lot tracking is what makes expiry correct.

## Tables (draft)

### `customers`
Thin but logically real. Lazily created on first earn (upsert) — no separate create endpoint.
- `id` (TEXT, PK) — the identity passed in requests (e.g. "alice")
- `name` (TEXT, nullable) — placeholder for a real customer record

### `point_lots`
One row per earning event. The heart of the model.
- `purchase_id` (TEXT, PK) — the store's purchase identifier; natural key, one lot per purchase
- `customer_id` (FK -> customers.id)
- `points_earned` (INTEGER) — original amount, immutable
- `points_remaining` (INTEGER) — decremented as points are redeemed (mutable-lots approach, see decision)
- `earned_at` (DATE/INSTANT)
- `expires_at` (DATE/INSTANT) — earned_at + 12 months

Balance as of date D = `SUM(points_remaining) WHERE customer_id = ? AND expires_at > D`.

### `rewards`  (catalog)
The spec says redeem "for a reward from a catalog", so reward costs live somewhere.
Could be a seeded table or a hardcoded constant — leaning table for clarity.
- `id` (TEXT, PK) — e.g. "free-coffee"
- `name` (TEXT)
- `cost_points` (INTEGER)

### `redemptions`  (audit / endpoint response)
Records that a redemption happened. Optional for bare MVP, but wanted for a meaningful
redeem response and for refund reasoning.
- `id` (PK)
- `customer_id` (FK)
- `reward_id` (FK -> rewards.id)
- `points_spent` (INTEGER)
- `redeemed_at` (DATE/INSTANT)

## Decision: mutable lots (not an append-only ledger)

**Decision:** model points as **mutable lots** — each earn is a row with a `points_remaining`
counter that is decremented (oldest-expiry-first) as points are redeemed — plus a `redemptions`
log and a non-expiring negative **adjustment** row for refund shortfalls.

**Alternative considered:** a full **append-only ledger** — immutable lots plus immutable
signed "movement" rows referencing the lot they draw down; balance and per-lot remaining are
*derived* by summing movements rather than stored.

### Why the ledger was attractive

- Append-only, so it carries a complete, replayable audit trail for free.
- Its headline pitch: "a refund is just one negative entry" — no in-place mutation.

### Why we did not use it

We walked the motivating refund case — earn 1000 (car), redeem 500 (mug), refund the car —
through both models row by row. The result that settled it:

- **The refund case is a wash, not a ledger win.** The ledger's "refund = one negative row"
  elegance only holds while the points are still fully available. Once the clawback exceeds
  what's left (exactly the buy -> redeem -> return case), the ledger *also* needs a special
  non-expiring negative entry — because a debt attached to the expiring lot would vanish when
  that lot expires. Both models need the identical special case, so refunds don't favour the
  ledger at all.
- With refunds neutralised, the ledger's only remaining edge is the richer audit trail — which
  this assignment does not ask for.
- Meanwhile the ledger costs us a more complex **balance query** (per-lot `points + SUM(movements)`
  with a join and group-by, on every read) versus mutable lots' trivial
  `SUM(points_remaining) WHERE not expired` over a single table.

### Summary

| | Mutable lots (chosen) | Append-only ledger |
|---|---|---|
| Earn / redeem | `UPDATE` remaining | `INSERT` movement |
| Balance query | `SUM(remaining)`, one table | per-lot `points + SUM(movements)`, join + group-by |
| Audit / history | needs `redemptions` log; loses which lot a redeem hit | full, immutable, replayable |
| Refund over-spend | non-expiring adjustment row | non-expiring negative entry (same complexity) |
| Code volume | less | more |

For a 2–3 hour scope, mutable lots is the simplest design that fully satisfies the
requirements and is easy to explain. **"Move to an append-only event ledger for full
auditability" is the natural next step with more time.**

## Catalog vs. redemptions log — two separate concerns

Worth keeping distinct (they were initially conflated):

- **`rewards` (catalog):** the *menu* of what can be redeemed and its point cost. Reference
  data, read-mostly. Spec implies it exists ("redeem for a reward from a catalog").
- **`redemptions` (log):** a record that a redemption *happened*. This is the one we debated
  folding into the lots. The refund edge case below is the main argument for keeping it.

## Refunds: the clawback-after-spend problem

Motivating scenario: buy a $50k car -> earn 50,000 pts -> redeem all 50,000 for a coffee mug
-> return the car. We must claw back 50,000 pts that no longer exist.

Options:

| Option | Behaviour | Problem |
|---|---|---|
| Claw back only what's left | spent points stand | gameable: buy -> redeem -> return = free reward |
| Revoke the redemption | take the reward back too | often impossible (physical goods) |
| **Allow negative balance** (chosen) | claw back full granted amount; balance can go < 0 | none significant; matches real programs |

**Decision:** always claw back exactly what the purchase granted. If the points were already
spent, the balance goes negative and must be earned back. Uniform across all cases (points
still available, already redeemed, or expired) and not gameable.

**Schema implication:** this is the strongest argument for the append-only **ledger** (option
B). In a ledger a refund is just a `-50,000` entry and a negative balance is automatic. With
mutable lots we'd need a negative-adjustment row for any shortfall — effectively a ledger
entry anyway. Pragmatic middle ground: mutable lots for normal earn/redeem/expiry + a
negative-adjustment row when a clawback exceeds a lot's remaining. (Revisit if we adopt the
full ledger.)

## Considered & rejected: a single flat "points_activity" table

Tempting: one table of signed entries (earn `+1000`, redeem `-500`, refund `-1000`), balance =
`SUM(points)`. Trivial appends, free audit trail. **Rejected** because expiry doesn't compose
with a flat SUM:

1. Only earns expire; redemptions/refunds are movements that shouldn't expire — so rows have
   different semantics jammed into one table ("does a `-1000` refund expire after a year?" — no).
2. Worse, a flat SUM computes the *wrong balance* once anything expires. Example:
   - Jan 1 Y1: earn `+1000` (expires Jan 1 Y2)
   - Mar 1 Y1: redeem `-500`
   - Balance on Feb 1 Y2 (earn now expired): truth is **0** (500 remained, then expired).
   - Flat ledger drops the expired `+1000`, keeps the `-500` -> **-500**. A phantom negative,
     because the redemption outlived the earn it drew from.

The fix requires attributing each redemption to the specific lot it consumes — which is exactly
the per-lot tracking our chosen model already has. Table shape can't avoid that complexity, so a
flat single table is strictly worse: same complexity, plus silent wrong answers.

## Decision: purchase_id is the primary key of point_lots (natural key)

A purchase is a one-time event with a store-provided identifier, so `purchase_id` is the
**primary key** of `point_lots` — a natural key, mirroring how `customers.id` is `"alice"`
rather than a synthetic integer. This gives one lot per purchase for free, prevents double-earn,
and is what refunds key off. A duplicate earn is rejected with **HTTP 409** (enforced by the PK
constraint plus an explicit service check for a clean domain error).

- Chose a **natural key** over a surrogate `id` for consistency with `customers` and because the
  store owns the identifier. Trade-off: a surrogate key would insulate internal references if the
  store ever reformats its IDs, and would let `point_lots` also hold non-purchase rows (e.g. refund
  adjustments) — so refund adjustments will live in their own table, keeping `point_lots` pure.
- Chose **reject (409)** over **idempotent replay** (returning the existing lot) for explicitness.
  Idempotent replay is a reasonable "with more time" alternative for at-least-once delivery.

## Other decisions / simplifying assumptions

- **Derived, not stored:** no stored balance, no stored "expired" column. Both are computed
  from lots + the as-of date. Storing them would require a process to keep them in sync.
- **`asOf` date is an optional request argument** (defaults to now). Lets us simulate expiry
  over time without manipulating the system clock — important since we'll test time-based
  behaviour heavily. To be noted as an assumption in the README.
- **Customer upsert on first earn** — no dedicated customer-creation flow.
- **Expiry boundary is exclusive:** a lot is valid while `asOf < expires_at`, i.e. points are
  usable up to but not including the expiry date.
- **Leap-year expiry** is handled by `java.time` (`LocalDate.plusMonths(12)`): earning on
  2024-02-29 expires 2025-02-28. No hand-rolled date math.
- **Unknown customer balance is 0** — we don't distinguish "never seen" from "zero points".

## Open questions (to resolve)

- **Points granularity:** 1 point per $1. Do we floor partial dollars (e.g. $10.99 -> 10)?
  Leaning: integer points, floor. Confirm.
- **Reward catalog:** seed a few rewards at startup (free-coffee, etc.)?
- **Redemption when insufficient balance:** reject with a clear error (no partial redemptions).
- **Money type:** store purchase amount as integer cents, or just take points directly?
- **Refunds:** decided — claw back full granted amount, allow negative balance (see above).
  Open sub-question: mutable-lots + adjustment row, or flip to full append-only ledger?