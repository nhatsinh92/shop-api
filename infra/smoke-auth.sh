#!/usr/bin/env bash
# Auth smoke check against a running `make demo`, through the gateway: login, me, refresh, logout,
# reuse of the revoked refresh token and the Origin check. Needs only bash and curl.
# Cookies are tracked by hand (not with a curl cookie jar) so the Secure flag does not matter on
# plain http://localhost.
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: infra/smoke-auth.sh [--help]

Runs login -> me -> refresh -> me -> logout -> refresh (revoked) and a cross-origin login
through the gateway and stops at the first unexpected result.

Environment:
  GATEWAY_URL     gateway base URL (default http://localhost:8080)
  SMOKE_EMAIL     demo user email (default linh@example.com, see seed/README.md)
  SMOKE_PASSWORD  demo user password (default linh-gold-2026)
  WEB_ORIGIN      allowed web origin (default http://localhost:3000)
  REQUIRE_ORIGIN_CHECK
                  1 = fail (instead of skipping with a note) when the gateway does not reject a
                  foreign Origin; set it once the gateway Origin check (CORS task) is merged

Start the stack first with: make demo
USAGE
}

case "${1:-}" in
  -h | --help) usage; exit 0 ;;
  "") ;;
  *) usage >&2; exit 2 ;;
esac

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
SMOKE_EMAIL="${SMOKE_EMAIL:-linh@example.com}"
SMOKE_PASSWORD="${SMOKE_PASSWORD:-linh-gold-2026}"
WEB_ORIGIN="${WEB_ORIGIN:-http://localhost:3000}"

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT
HEADERS="$workdir/headers"
BODY="$workdir/body"

fail() {
  echo "FAIL: $*" >&2
  echo "--- last response headers ---" >&2
  cat "$HEADERS" >&2 2>/dev/null || true
  echo "--- last response body ---" >&2
  cat "$BODY" >&2 2>/dev/null || true
  echo >&2
  exit 1
}

# call <expected-status> <curl args...>; sets $STATUS and leaves headers and body in files
call() {
  local expected="$1"
  shift
  STATUS="$(curl -sS -o "$BODY" -D "$HEADERS" -w '%{http_code}' "$@")" || fail "curl failed: $*"
  [ "$STATUS" = "$expected" ] || fail "expected HTTP $expected, got $STATUS: $*"
}

# cookie_value <name>: the value of the last Set-Cookie for <name> in the last response
cookie_value() {
  tr -d '\r' <"$HEADERS" | grep -i "^set-cookie: $1=" | tail -n 1 | sed -E "s/^[^=]*=([^;]*).*/\\1/"
}

# cookie_header: the Cookie header for the three cookies of the last response
cookie_header() {
  echo "access_token=$(cookie_value access_token); refresh_token=$(cookie_value refresh_token); logged_in=$(cookie_value logged_in)"
}

expect_cookies_set() {
  local name value
  for name in access_token refresh_token logged_in; do
    value="$(cookie_value "$name")"
    [ -n "$value" ] || fail "no non-empty Set-Cookie for $name"
  done
}

expect_cookies_cleared() {
  local name
  for name in access_token refresh_token logged_in; do
    tr -d '\r' <"$HEADERS" | grep -i "^set-cookie: $name=" | grep -Eiq 'Max-Age=0|Expires=Thu, 01 Jan 1970' \
      || fail "Set-Cookie for $name does not clear the cookie"
    [ -z "$(cookie_value "$name")" ] || fail "cookie $name is cleared but still has a value"
  done
}

expect_body_contains() {
  grep -q "$1" "$BODY" || fail "response body does not contain: $1"
}

login_json="{\"email\":\"$SMOKE_EMAIL\",\"password\":\"$SMOKE_PASSWORD\"}"
json=(-H 'Content-Type: application/json')
origin=(-H "Origin: $WEB_ORIGIN")

echo "1. login as $SMOKE_EMAIL"
call 200 -X POST "${json[@]}" "${origin[@]}" -d "$login_json" "$GATEWAY_URL/api/auth/login"
expect_cookies_set
cookies1="$(cookie_header)"
echo "   200, access_token, refresh_token and logged_in set"

echo "2. GET /api/auth/me"
call 200 -H "Cookie: $cookies1" "$GATEWAY_URL/api/auth/me"
expect_body_contains "$SMOKE_EMAIL"
echo "   200, email matches"

echo "3. refresh"
call 204 -X POST "${origin[@]}" -H "Cookie: $cookies1" "$GATEWAY_URL/api/auth/refresh"
expect_cookies_set
cookies2="$(cookie_header)"
echo "   204, new cookies set"

echo "4. GET /api/auth/me with the new access cookie"
call 200 -H "Cookie: $cookies2" "$GATEWAY_URL/api/auth/me"
expect_body_contains "$SMOKE_EMAIL"
echo "   200"

echo "5. logout"
call 204 -X POST "${origin[@]}" -H "Cookie: $cookies2" "$GATEWAY_URL/api/auth/logout"
expect_cookies_cleared
echo "   204, cookies cleared"

echo "6. refresh with the old refresh cookie"
call 401 -X POST "${origin[@]}" -H "Cookie: $cookies1" "$GATEWAY_URL/api/auth/refresh"
expect_body_contains AUTH_REFRESH_INVALID
echo "   401 AUTH_REFRESH_INVALID"

echo "7. login from a foreign Origin"
STATUS="$(curl -sS -o "$BODY" -D "$HEADERS" -w '%{http_code}' -X POST "${json[@]}" \
  -H 'Origin: https://evil.example' -d "$login_json" "$GATEWAY_URL/api/auth/login")" \
  || fail "curl failed for the foreign-origin login"
if [ "$STATUS" = "403" ]; then
  expect_body_contains AUTH_FORBIDDEN
  echo "   403 AUTH_FORBIDDEN"
elif [ "${REQUIRE_ORIGIN_CHECK:-0}" = "1" ]; then
  fail "expected HTTP 403 AUTH_FORBIDDEN for a foreign Origin, got $STATUS"
else
  echo "   SKIPPED: got $STATUS, the gateway Origin check is not merged yet (REQUIRE_ORIGIN_CHECK=1 makes this fatal)"
fi

echo "auth smoke check passed"
