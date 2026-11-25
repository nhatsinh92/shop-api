#!/usr/bin/env bash
# Checkout smoke check against a running `make demo`, through the gateway: reservation, shipping
# options, payment methods, a COD order with its idempotent replay, and the failed and timed-out
# mock payments with the stock given back. Needs bash, curl, jq and docker compose.
# Cookies are tracked by hand (not with a curl cookie jar) so the Secure flag does not matter on
# plain http://localhost.
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: infra/smoke-checkout.sh [--help]

Signs in a demo user and checks, in order:
  1. reserve two seeded SKUs: 201, ACTIVE, expiresAt 10 minutes from now (+- 5 s)
  2. shipping options and payment methods
  3. COD order: 201, PLACED, number MY-MMdd-NNNN
  4. replay with the same Idempotency-Key: same body, Idempotent-Replayed: true
  5. same key, changed body: 422 IDEMPOTENCY_KEY_REUSED
  6. a new order on the consumed reservation: 410 CHECKOUT_RESERVATION_CONSUMED
  7. new reservation + EWALLET order with X-Mock-Payment-Outcome: fail: PAYMENT_FAILED / FAILED
  8. the same with timeout: PAYMENT_FAILED / TIMED_OUT in under 10 s
  9. the stock of the SKUs of the failed payments (product.stock in the core database) equals its
     value before their reservations
Prints a PASS/FAIL line per step and exits non-zero at the first failure.

Environment:
  GATEWAY_URL     gateway base URL (default http://localhost:8080)
  WEB_ORIGIN      allowed web origin (default http://localhost:3000)
  SMOKE_EMAIL     demo user email (default minh@example.com, see seed/README.md)
  SMOKE_PASSWORD  demo user password (default: see seed/README.md for minh@example.com)
  SMOKE_SKU_A     first SKU (default SKU-KB-75)
  SMOKE_SKU_B     second SKU (default SKU-ANC-PRO-BLK)
  SMOKE_WARMUP_S  seconds to wait before retrying a 503 once while the stack warms up (default 2)

The check places three orders for the demo user and changes the stock of the two SKUs (the COD
order keeps its stock). Start the stack first with: make demo
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
WEB_ORIGIN="${WEB_ORIGIN:-http://localhost:3000}"
SMOKE_EMAIL="${SMOKE_EMAIL:-minh@example.com}"
SMOKE_PASSWORD="${SMOKE_PASSWORD:-}"
SMOKE_SKU_A="${SMOKE_SKU_A:-SKU-KB-75}"
SMOKE_SKU_B="${SMOKE_SKU_B:-SKU-ANC-PRO-BLK}"
SMOKE_WARMUP_S="${SMOKE_WARMUP_S:-2}"
compose=(docker compose -f "$root/infra/docker-compose.yml")

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT
HEADERS="$workdir/headers"
BODY="$workdir/body"
: >"$HEADERS"
: >"$BODY"

step=0
fail() {
  echo "FAIL: $*" >&2
  echo "--- last response headers ---" >&2
  cat "$HEADERS" >&2 2>/dev/null || true
  echo "--- last response body ---" >&2
  cat "$BODY" >&2 2>/dev/null || true
  echo >&2
  exit 1
}

begin() {
  step=$((step + 1))
  echo "$step. $*"
}

pass() {
  echo "   PASS: $*"
}

# call <expected-status> <curl args...>; a 503 is retried once after SMOKE_WARMUP_S seconds (the
# first calls of a freshly started stack can exceed a budget). Sets $STATUS, leaves headers and body
# in files.
call() {
  local expected="$1"
  shift
  STATUS="$(curl -sS -o "$BODY" -D "$HEADERS" -w '%{http_code}' "$@")" || fail "curl failed: $*"
  if [ "$STATUS" = 503 ]; then
    echo "   warm-up: 503, retrying once"
    sleep "$SMOKE_WARMUP_S"
    STATUS="$(curl -sS -o "$BODY" -D "$HEADERS" -w '%{http_code}' "$@")" || fail "curl failed: $*"
  fi
  [ "$STATUS" = "$expected" ] || fail "expected HTTP $expected, got $STATUS: $*"
}

expect_jq() {
  [ "$(jq -r "$1" "$BODY")" = "true" ] || fail "$2"
}

# cookie_value <name>: the value of the last Set-Cookie for <name> in the last response
cookie_value() {
  tr -d '\r' <"$HEADERS" | grep -i "^set-cookie: $1=" | tail -n 1 | sed -E "s/^[^=]*=([^;]*).*/\\1/" || true
}

new_key() {
  if [ -r /proc/sys/kernel/random/uuid ]; then
    cat /proc/sys/kernel/random/uuid
  else
    uuidgen | tr 'A-Z' 'a-z'
  fi
}

# stock <sku>: the quantity in the core database (the availability endpoint is cached and
# approximate, so it cannot prove a give-back)
stock() {
  local q
  q="$("${compose[@]}" exec -T postgres psql -U shop -d shop -tA \
    -c "SELECT quantity FROM product.stock WHERE sku = '$1'" | tr -d '\r')" || q=""
  [[ "$q" =~ ^[0-9]+$ ]] || fail "no stock row for $1 in product.stock (got '$q')"
  echo "$q"
}

json=(-H 'Content-Type: application/json')
origin=(-H "Origin: $WEB_ORIGIN")

# ---------- sign in ----------
[ -n "$SMOKE_PASSWORD" ] || SMOKE_PASSWORD="$(grep -i "$SMOKE_EMAIL" "$root/seed/README.md" \
  | grep -Eo '[a-z]+-[a-z]+-[0-9]{4}' | head -n 1 || true)"
[ -n "$SMOKE_PASSWORD" ] || fail "no password for $SMOKE_EMAIL; set SMOKE_PASSWORD (see seed/README.md)"

begin "log in as $SMOKE_EMAIL"
call 200 -X POST "${json[@]}" "${origin[@]}" \
  -d "{\"email\":\"$SMOKE_EMAIL\",\"password\":\"$SMOKE_PASSWORD\"}" "$GATEWAY_URL/api/auth/login"
access="$(cookie_value access_token)"
[ -n "$access" ] || fail "no access_token cookie after login"
auth=(-H "Cookie: access_token=$access; logged_in=1")
pass "200"

reserve() {
  call 201 -X POST "${json[@]}" "${origin[@]}" "${auth[@]}" \
    -d "{\"items\":[{\"sku\":\"$SMOKE_SKU_A\",\"quantity\":1},{\"sku\":\"$SMOKE_SKU_B\",\"quantity\":1}]}" \
    "$GATEWAY_URL/api/checkout/reservations"
  RESERVATION_ID="$(jq -r '.id' "$BODY")"
  [ -n "$RESERVATION_ID" ] && [ "$RESERVATION_ID" != "null" ] || fail "no reservation id"
}

# order_body <reservation> <method> <note>
order_body() {
  jq -nc --arg r "$1" --arg m "$2" --arg n "$3" '{
    reservationId: $r, shippingOptionId: "STANDARD", paymentMethod: $m,
    shippingAddress: {fullName: "Nguyen Van An", phone: "0901234567", line1: "12 Nguyen Hue",
      ward: "Ben Nghe", district: "District 1", province: "Ho Chi Minh City"},
    note: $n}'
}

# ---------- reserve ----------
begin "reserve $SMOKE_SKU_A and $SMOKE_SKU_B"
before_reserve=$(date +%s)
reserve
cod_reservation="$RESERVATION_ID"
expect_jq '.status == "ACTIVE" and (.items | length == 2)' "expected an ACTIVE reservation of two lines"
expires="$(jq -r '.expiresAt' "$BODY")"
expires_epoch="$(date -d "$expires" +%s 2>/dev/null || date -j -u -f '%Y-%m-%dT%H:%M:%S' "${expires%%.*}" +%s)"
delta=$((expires_epoch - before_reserve))
{ [ "$delta" -ge 595 ] && [ "$delta" -le 605 ]; } || fail "expiresAt is $delta s from now, expected 600 +- 5"
pass "201, ACTIVE, expires in $delta s"

begin "shipping options and payment methods"
call 200 "${auth[@]}" "$GATEWAY_URL/api/checkout/shipping-options?reservationId=$cod_reservation"
expect_jq '.items | any(.id == "STANDARD")' "expected the STANDARD shipping option"
call 200 "${auth[@]}" "$GATEWAY_URL/api/checkout/payment-methods"
expect_jq '.items | any(.id == "COD" and .available) and any(.id == "EWALLET" and .available)' \
  "expected COD and EWALLET available"
pass "200, STANDARD, COD and EWALLET"

# ---------- COD order and idempotency ----------
begin "place a COD order"
key="$(new_key)"
cod_body="$(order_body "$cod_reservation" COD "smoke cod")"
call 201 -X POST "${json[@]}" "${origin[@]}" "${auth[@]}" -H "Idempotency-Key: $key" \
  -d "$cod_body" "$GATEWAY_URL/api/orders"
expect_jq '.status == "PLACED" and .payment.status == "NOT_REQUIRED"' "expected PLACED / NOT_REQUIRED"
expect_jq '.number | test("^MY-[0-9]{4}-[0-9]{4,}$")' "order number does not match MY-MMdd-NNNN"
first_body="$(jq -S -c . "$BODY")"
pass "201, PLACED, $(jq -r '.number' "$BODY")"

begin "replay with the same key"
call 201 -X POST "${json[@]}" "${origin[@]}" "${auth[@]}" -H "Idempotency-Key: $key" \
  -d "$cod_body" "$GATEWAY_URL/api/orders"
tr -d '\r' <"$HEADERS" | grep -iq '^idempotent-replayed: true' || fail "missing Idempotent-Replayed: true"
[ "$(jq -S -c . "$BODY")" = "$first_body" ] || fail "replayed body differs from the original"
pass "201, same body, Idempotent-Replayed: true"

begin "same key with a changed body"
call 422 -X POST "${json[@]}" "${origin[@]}" "${auth[@]}" -H "Idempotency-Key: $key" \
  -d "$(order_body "$cod_reservation" COD "smoke cod changed")" "$GATEWAY_URL/api/orders"
expect_jq '.code == "IDEMPOTENCY_KEY_REUSED"' "expected code IDEMPOTENCY_KEY_REUSED"
pass "422 IDEMPOTENCY_KEY_REUSED"

begin "a new order on the consumed reservation"
call 410 -X POST "${json[@]}" "${origin[@]}" "${auth[@]}" -H "Idempotency-Key: $(new_key)" \
  -d "$cod_body" "$GATEWAY_URL/api/orders"
expect_jq '.code == "CHECKOUT_RESERVATION_CONSUMED"' "expected code CHECKOUT_RESERVATION_CONSUMED"
pass "410 CHECKOUT_RESERVATION_CONSUMED"

# ---------- failed and timed-out payments ----------
stock_a="$(stock "$SMOKE_SKU_A")"
stock_b="$(stock "$SMOKE_SKU_B")"

begin "e-wallet order with X-Mock-Payment-Outcome: fail"
reserve
call 201 -X POST "${json[@]}" "${origin[@]}" "${auth[@]}" -H "Idempotency-Key: $(new_key)" \
  -H 'X-Mock-Payment-Outcome: fail' -d "$(order_body "$RESERVATION_ID" EWALLET "smoke fail")" \
  "$GATEWAY_URL/api/orders"
expect_jq '.status == "PAYMENT_FAILED" and .payment.status == "FAILED"' "expected PAYMENT_FAILED / FAILED"
pass "201, PAYMENT_FAILED, payment FAILED"

begin "e-wallet order with X-Mock-Payment-Outcome: timeout"
reserve
started=$SECONDS
call 201 -X POST "${json[@]}" "${origin[@]}" "${auth[@]}" -H "Idempotency-Key: $(new_key)" \
  -H 'X-Mock-Payment-Outcome: timeout' -d "$(order_body "$RESERVATION_ID" EWALLET "smoke timeout")" \
  "$GATEWAY_URL/api/orders"
elapsed=$((SECONDS - started))
expect_jq '.status == "PAYMENT_FAILED" and .payment.status == "TIMED_OUT"' "expected PAYMENT_FAILED / TIMED_OUT"
[ "$elapsed" -lt 10 ] || fail "the timed-out payment took $elapsed s, expected under 10 s"
pass "201, PAYMENT_FAILED, payment TIMED_OUT in $elapsed s"

begin "stock of the failed payments is given back"
after_a="$stock_a"
after_b="$stock_b"
for _ in $(seq 1 10); do
  after_a="$(stock "$SMOKE_SKU_A")"
  after_b="$(stock "$SMOKE_SKU_B")"
  [ "$after_a" = "$stock_a" ] && [ "$after_b" = "$stock_b" ] && break
  sleep 1
done
[ "$after_a" = "$stock_a" ] || fail "stock of $SMOKE_SKU_A is $after_a, expected $stock_a"
[ "$after_b" = "$stock_b" ] || fail "stock of $SMOKE_SKU_B is $after_b, expected $stock_b"
pass "$SMOKE_SKU_A $after_a, $SMOKE_SKU_B $after_b (unchanged)"

echo "checkout smoke check passed"
