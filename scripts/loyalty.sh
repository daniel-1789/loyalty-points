#!/usr/bin/env bash
#
# Convenience wrapper around the loyalty API for manual experimentation.
#
# Usage:
#   scripts/loyalty.sh health
#   scripts/loyalty.sh rewards
#   scripts/loyalty.sh tiers
#   scripts/loyalty.sh earn    <user> <purchase-id> <amount> [date]
#   scripts/loyalty.sh balance <user> [asOf]
#   scripts/loyalty.sh redeem  <user> <reward-id> [date]
#   scripts/loyalty.sh demo    <user>
#
# Examples:
#   scripts/loyalty.sh earn alice order-123 100 2025-01-01
#   scripts/loyalty.sh redeem alice free-coffee 2025-06-01
#   scripts/loyalty.sh balance alice 2025-06-01
#
# The server must be running first (e.g. `java -jar target/loyalty-points.jar`).
# Override the host with BASE_URL, e.g. BASE_URL=http://localhost:7071 scripts/loyalty.sh health
# Responses are pretty-printed with jq if it is installed, otherwise shown raw.

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:7070}"

# Pretty-print JSON with jq when available; fall back to raw output if jq is missing or the
# response isn't valid JSON (so an error page never turns into a confusing jq parse error).
pp() {
    local out; out="$(cat)"
    if command -v jq >/dev/null 2>&1 && printf '%s' "$out" | jq . >/dev/null 2>&1; then
        printf '%s' "$out" | jq .
    else
        printf '%s\n' "$out"
    fi
}

usage() {
    sed -n '3,21p' "$0" | sed 's/^# \{0,1\}//'
    exit "${1:-0}"
}

earn() {
    local user="$1" purchase="$2" amount="$3" date="${4:-}"
    local body
    if [[ -n "$date" ]]; then
        body=$(printf '{"purchaseId":"%s","amount":%s,"date":"%s"}' "$purchase" "$amount" "$date")
    else
        body=$(printf '{"purchaseId":"%s","amount":%s}' "$purchase" "$amount")
    fi
    curl -s -X POST "$BASE_URL/customers/$user/purchases" \
        -H 'Content-Type: application/json' -d "$body" | pp
}

balance() {
    local user="$1" asof="${2:-}"
    local url="$BASE_URL/customers/$user/balance"
    [[ -n "$asof" ]] && url="$url?asOf=$asof"
    curl -s "$url" | pp
}

redeem() {
    local user="$1" reward="$2" date="${3:-}"
    local body
    if [[ -n "$date" ]]; then
        body=$(printf '{"rewardId":"%s","date":"%s"}' "$reward" "$date")
    else
        body=$(printf '{"rewardId":"%s"}' "$reward")
    fi
    curl -s -X POST "$BASE_URL/customers/$user/redemptions" \
        -H 'Content-Type: application/json' -d "$body" | pp
}

# Self-contained walkthrough showing points accruing and then expiring over time.
demo() {
    local user="${1:-demo-user}"
    echo "# Earning 100 pts on 2025-01-01 (expires 2026-01-01)"
    earn "$user" "demo-order-1" 100 2025-01-01
    echo "# Earning 50 pts on 2025-06-01 (expires 2026-06-01)"
    earn "$user" "demo-order-2" 50 2025-06-01
    echo "# Balance on 2025-07-01 (both valid, expect 150)"
    balance "$user" 2025-07-01
    echo "# Balance on 2026-02-01 (first lot expired, expect 50)"
    balance "$user" 2026-02-01
    echo "# Balance on 2026-07-01 (both expired, expect 0)"
    balance "$user" 2026-07-01
}

cmd="${1:-}"
[[ -n "$cmd" ]] && shift || true

case "$cmd" in
    health)  curl -s "$BASE_URL/health" | pp ;;
    rewards) curl -s "$BASE_URL/rewards" | pp ;;
    tiers)   curl -s "$BASE_URL/tiers" | pp ;;
    earn)    [[ $# -ge 3 ]] || usage 1; earn "$@" ;;
    balance) [[ $# -ge 1 ]] || usage 1; balance "$@" ;;
    redeem)  [[ $# -ge 2 ]] || usage 1; redeem "$@" ;;
    demo)    demo "$@" ;;
    -h|--help|help|"") usage 0 ;;
    *) echo "Unknown command: $cmd" >&2; usage 1 ;;
esac
