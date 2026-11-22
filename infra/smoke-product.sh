#!/usr/bin/env bash
# Product smoke check against a running `make demo`: list, detail, availability and flash sale
# through the gateway, then an internal price change directly on core and the product.updated
# record on Redpanda. Needs bash, curl, jq and docker compose.
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: infra/smoke-product.sh [--help]

Environment:
  GATEWAY_URL         gateway base URL (default http://localhost:8080)
  CORE_URL            core base URL, not through the gateway (default http://localhost:8081)
  INTERNAL_API_TOKEN  internal token (default: contents of infra/.secrets/internal-api-token)
  SMOKE_SLUG          product slug (default tai-nghe-khong-day-anc-pro)
  SMOKE_SKU           SKU checked for availability and sale price (default SKU-ANC-PRO-BLK)
  SMOKE_PATCH_SKU     SKU whose price is changed; must not be in the running sale, because the
                      PATCH response shows the sale price while a sale runs (default SKU-MOUSE-SIL)

The check changes the price of SMOKE_PATCH_SKU; run `make seed` to restore it.
Start the stack first with: make demo
USAGE
}

case "${1:-}" in
  -h | --help) usage; exit 0 ;;
  "") ;;
  *) usage >&2; exit 2 ;;
esac

for tool in jq curl docker; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "FAIL: $tool is required (macOS: brew install $tool; Debian/Ubuntu: apt-get install $tool)" >&2
    exit 1
  }
done

root="$(cd "$(dirname "$0")/.." && pwd)"
GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
CORE_URL="${CORE_URL:-http://localhost:8081}"
SMOKE_SLUG="${SMOKE_SLUG:-tai-nghe-khong-day-anc-pro}"
SMOKE_SKU="${SMOKE_SKU:-SKU-ANC-PRO-BLK}"
SMOKE_PATCH_SKU="${SMOKE_PATCH_SKU:-SKU-MOUSE-SIL}"
if [ -z "${INTERNAL_API_TOKEN:-}" ]; then
  INTERNAL_API_TOKEN="$(cat "$root/infra/.secrets/internal-api-token" 2>/dev/null || true)"
fi
[ -n "$INTERNAL_API_TOKEN" ] || { echo "FAIL: no internal token; run make demo first" >&2; exit 1; }

BODY="$(mktemp)"
trap 'rm -f "$BODY"' EXIT

fail() {
  echo "FAIL: $*" >&2
  echo "--- last response body ---" >&2
  cat "$BODY" >&2 2>/dev/null || true
  echo >&2
  exit 1
}

# call <expected-status> <curl args...>; leaves the body in $BODY
call() {
  local expected="$1" status
  shift
  status="$(curl -sS -o "$BODY" -w '%{http_code}' "$@")" || fail "curl failed: $*"
  [ "$status" = "$expected" ] || fail "expected HTTP $expected, got $status: $*"
}

# expect_jq <jq filter> <message>: the filter must print true for the last body
expect_jq() {
  [ "$(jq -r "$1" "$BODY")" = "true" ] || fail "$2"
}

echo "1. GET /api/products lists 10 products"
call 200 "$GATEWAY_URL/api/products?limit=50"
expect_jq '(.items | length) == 10' "expected 10 products"
echo "   200, 10 products"

echo "2. GET /api/products/$SMOKE_SLUG has three variants"
call 200 "$GATEWAY_URL/api/products/$SMOKE_SLUG"
expect_jq '(.variants | length) == 3' "expected 3 variants"
echo "   200, 3 variants"

echo "3. availability of $SMOKE_SKU is LOW, 40 left, sale price 890000"
call 200 "$GATEWAY_URL/api/products/$SMOKE_SKU/availability"
expect_jq '.stockLevel == "LOW" and .approxQuantity == 40 and .price.amount == 890000' \
  "expected LOW, approxQuantity 40 and price 890000"
echo "   200, LOW, 40, 890000"

echo "4. GET /api/flash-sales/current"
call 200 "$GATEWAY_URL/api/flash-sales/current"
echo "   200"

new_price=$((1000000 + $(date +%s) % 100000))
patch=(-X PATCH -H 'Content-Type: application/json' -H "X-Internal-Token: $INTERNAL_API_TOKEN"
  -d "{\"price\":{\"amount\":$new_price,\"currency\":\"VND\"}}")

echo "5. PATCH /internal/products/$SMOKE_PATCH_SKU through the gateway is not routed"
call 404 "${patch[@]}" "$GATEWAY_URL/internal/products/$SMOKE_PATCH_SKU"
echo "   404"

echo "6. PATCH /internal/products/$SMOKE_PATCH_SKU directly on core"
call 200 "${patch[@]}" "$CORE_URL/internal/products/$SMOKE_PATCH_SKU"
expect_jq ".price.amount == $new_price" "price was not changed to $new_price"
echo "   200, price $new_price"

echo "7. product.updated has a record keyed $SMOKE_PATCH_SKU"
compose=(docker compose -f "$root/infra/docker-compose.yml")
record=""
for _ in 1 2 3 4 5 6 7 8 9 10; do
  record="$("${compose[@]}" exec -T redpanda \
    rpk topic consume product.updated -o -1 -n 1 -f '%k\n' 2>/dev/null || true)"
  [ "$record" = "$SMOKE_PATCH_SKU" ] && break
  sleep 1
done
[ "$record" = "$SMOKE_PATCH_SKU" ] || fail "latest product.updated record key is '$record', expected $SMOKE_PATCH_SKU"
echo "   record with key $SMOKE_PATCH_SKU"

echo "product smoke check passed"
