#!/usr/bin/env bash
# Cart smoke check against a running `make demo`, through the gateway: the seven cart operations
# for a guest and for a signed-in user, the 401 for an expired session and the write-behind
# snapshot in the cart database. Needs bash, curl, jq and docker compose.
# Cookies are tracked by hand (not with a curl cookie jar) so the Secure flag does not matter on
# plain http://localhost.
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: infra/smoke-cart.sh [--help]

Guest: add two SKUs (guest_cart is set), read the cart and the summary, update a quantity, remove a
line, and get 422 CART_QTY_LIMIT above 10. Then a cart call with logged_in=1 and no access cookie
is 401. Signed in: log in, merge the guest cart (guest_cart cleared), read the user cart, and
check that the cart_snapshot row has the same version as the Redis cart after the last write.
Prints a PASS/FAIL line per step and exits non-zero at the first failure.

Environment:
  GATEWAY_URL     gateway base URL (default http://localhost:8080)
  WEB_ORIGIN      allowed web origin (default http://localhost:3000)
  SMOKE_EMAIL     demo user email (default minh@example.com, see seed/README.md)
  SMOKE_PASSWORD  demo user password (default: see seed/README.md for minh@example.com)
  SMOKE_SKU_A     first SKU (default SKU-KB-75)
  SMOKE_SKU_B     second SKU (default SKU-BOTTLE-750)

The check changes the cart of the demo user. Start the stack first with: make demo
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
SMOKE_SKU_B="${SMOKE_SKU_B:-SKU-BOTTLE-750}"
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

# call <expected-status> <curl args...>; sets $STATUS, leaves headers and body in files
call() {
  local expected="$1"
  shift
  STATUS="$(curl -sS -o "$BODY" -D "$HEADERS" -w '%{http_code}' "$@")" || fail "curl failed: $*"
  [ "$STATUS" = "$expected" ] || fail "expected HTTP $expected, got $STATUS: $*"
}

expect_jq() {
  [ "$(jq -r "$1" "$BODY")" = "true" ] || fail "$2"
}

# cookie_value <name>: the value of the last Set-Cookie for <name> in the last response
cookie_value() {
  tr -d '\r' <"$HEADERS" | grep -i "^set-cookie: $1=" | tail -n 1 | sed -E "s/^[^=]*=([^;]*).*/\\1/" || true
}

json=(-H 'Content-Type: application/json')
origin=(-H "Origin: $WEB_ORIGIN")

# ---------- guest ----------
begin "guest adds $SMOKE_SKU_A x2 and receives guest_cart"
call 200 -X POST "${json[@]}" "${origin[@]}" -d "{\"sku\":\"$SMOKE_SKU_A\",\"quantity\":2}" \
  "$GATEWAY_URL/api/cart/items"
guest="$(cookie_value guest_cart)"
[ -n "$guest" ] || fail "no non-empty Set-Cookie for guest_cart"
gcookie=(-H "Cookie: guest_cart=$guest")
expect_jq ".items | length == 1 and .[0].sku == \"$SMOKE_SKU_A\" and .[0].quantity == 2" \
  "expected one line of $SMOKE_SKU_A x2"
pass "200, guest_cart set"

begin "guest adds $SMOKE_SKU_B x1"
call 200 -X POST "${json[@]}" "${origin[@]}" "${gcookie[@]}" \
  -d "{\"sku\":\"$SMOKE_SKU_B\",\"quantity\":1}" "$GATEWAY_URL/api/cart/items"
expect_jq '.items | length == 2' "expected two lines"
pass "200, two lines"

begin "guest reads the cart: enriched lines"
call 200 "${gcookie[@]}" "$GATEWAY_URL/api/cart"
expect_jq '.itemCount == 3 and (.items | all(.stockLevel != null and (has("variantLabel"))))' \
  "expected itemCount 3 and variantLabel and stockLevel on every line"
pass "200, itemCount 3, variantLabel and stockLevel present"

begin "guest reads the summary"
call 200 "${gcookie[@]}" "$GATEWAY_URL/api/cart/summary"
expect_jq '.itemCount == 3' "expected itemCount 3"
pass "200, itemCount 3"

begin "guest sets the quantity of $SMOKE_SKU_A to 4"
call 200 -X PATCH "${json[@]}" "${origin[@]}" "${gcookie[@]}" -d '{"quantity":4}' \
  "$GATEWAY_URL/api/cart/items/$SMOKE_SKU_A"
expect_jq "(.items[] | select(.sku == \"$SMOKE_SKU_A\") | .quantity) == 4" "quantity was not 4"
pass "200, quantity 4"

begin "guest removes $SMOKE_SKU_B"
call 200 -X DELETE "${origin[@]}" "${gcookie[@]}" "$GATEWAY_URL/api/cart/items/$SMOKE_SKU_B"
expect_jq ".items | length == 1 and .[0].sku == \"$SMOKE_SKU_A\"" "expected only $SMOKE_SKU_A left"
pass "200, one line left"

begin "quantity above 10 is 422 CART_QTY_LIMIT"
call 422 -X PATCH "${json[@]}" "${origin[@]}" "${gcookie[@]}" -d '{"quantity":11}' \
  "$GATEWAY_URL/api/cart/items/$SMOKE_SKU_A"
expect_jq '.code == "CART_QTY_LIMIT"' "expected code CART_QTY_LIMIT"
pass "422 CART_QTY_LIMIT"

begin "logged_in=1 without an access cookie is 401"
call 401 -H 'Cookie: logged_in=1' "$GATEWAY_URL/api/cart"
pass "401"

# ---------- signed in ----------
[ -n "$SMOKE_PASSWORD" ] || SMOKE_PASSWORD="$(grep -i "$SMOKE_EMAIL" "$root/seed/README.md" \
  | grep -Eo '[a-z]+-[a-z]+-[0-9]{4}' | head -n 1 || true)"
[ -n "$SMOKE_PASSWORD" ] || fail "no password for $SMOKE_EMAIL; set SMOKE_PASSWORD (see seed/README.md)"

begin "log in as $SMOKE_EMAIL"
call 200 -X POST "${json[@]}" "${origin[@]}" \
  -d "{\"email\":\"$SMOKE_EMAIL\",\"password\":\"$SMOKE_PASSWORD\"}" "$GATEWAY_URL/api/auth/login"
access="$(cookie_value access_token)"
[ -n "$access" ] || fail "no access_token cookie after login"
ucookie="access_token=$access; logged_in=1; guest_cart=$guest"
call 200 -H "Cookie: access_token=$access" "$GATEWAY_URL/api/auth/me"
user_id="$(jq -r '.id' "$BODY")"
[ -n "$user_id" ] && [ "$user_id" != "null" ] || fail "no user id in /api/auth/me"
pass "200, user $user_id"

begin "merge the guest cart"
call 200 -X POST "${origin[@]}" -H "Cookie: $ucookie" "$GATEWAY_URL/api/cart/merge"
tr -d '\r' <"$HEADERS" | grep -i '^set-cookie: guest_cart=' | grep -Eiq 'Max-Age=0|Expires=Thu, 01 Jan 1970' \
  || fail "guest_cart is not cleared"
expect_jq ".items | any(.sku == \"$SMOKE_SKU_A\")" "merged cart lacks $SMOKE_SKU_A"
pass "200, guest_cart cleared, $SMOKE_SKU_A present"

begin "read the user cart"
call 200 -H "Cookie: access_token=$access" "$GATEWAY_URL/api/cart"
expect_jq ".items | any(.sku == \"$SMOKE_SKU_A\")" "user cart lacks $SMOKE_SKU_A"
pass "200"

begin "last write: set $SMOKE_SKU_A to 5 on the user cart"
call 200 -X PATCH "${json[@]}" "${origin[@]}" -H "Cookie: access_token=$access" -d '{"quantity":5}' \
  "$GATEWAY_URL/api/cart/items/$SMOKE_SKU_A"
pass "200"

begin "cart_snapshot has the version of the last write"
version="$("${compose[@]}" exec -T redis redis-cli HGET "cart:$user_id" version | tr -d '\r')"
[[ "$version" =~ ^[0-9]+$ ]] || fail "no numeric version in Redis for cart:$user_id (got '$version')"
snapshot=""
for _ in $(seq 1 20); do
  snapshot="$("${compose[@]}" exec -T postgres psql -U cart -d cart -tA \
    -c "SELECT version FROM cart_snapshot WHERE cart_key = 'cart:$user_id'" | tr -d '\r' || true)"
  [ "$snapshot" = "$version" ] && break
  sleep 1
done
[ "$snapshot" = "$version" ] || fail "cart_snapshot version is '$snapshot', expected $version"
pass "version $version"

echo "cart smoke check passed"
