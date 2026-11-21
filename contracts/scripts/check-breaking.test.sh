#!/usr/bin/env bash
# Fixture tests for check-breaking.sh. The decision logic is tested with a stub oasdiff that
# reports "breaking" when the head spec differs from the base spec in a marked way; the fixture
# pairs also run through the real pinned oasdiff image when Docker is available (CI).
# Run: bash contracts/scripts/check-breaking.test.sh
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
fx=$here/fixtures/breaking
script=$here/check-breaking.sh
failures=0

expect() { # name expected-exit actual-exit
  if [[ $2 == "$3" ]]; then echo "ok   - $1"; else
    echo "FAIL - $1 (expected exit $2, got $3)"
    failures=$((failures + 1))
  fi
}

# Stub: exit code comes from STUB_RC.
stub=$(mktemp)
trap 'rm -f "$stub"' EXIT
printf '#!/usr/bin/env bash\nexit "${STUB_RC:-0}"\n' >"$stub"
chmod +x "$stub"

run_stub() { # rc base head
  local rc=0
  STUB_RC=$1 OASDIFF_CMD=$stub bash "$script" "$fx/$2" "$fx/$3" >/dev/null 2>&1 || rc=$?
  echo "$rc"
}

expect "stub: breaking + no major bump -> fail" 1 "$(run_stub 1 base breaking)"
expect "stub: breaking + major bump -> pass" 0 "$(run_stub 1 base breaking-major)"
expect "stub: no breaking change -> pass" 0 "$(run_stub 0 base additive)"
expect "stub: spec new on the head -> pass" 0 "$(run_stub 0 base with-cart)"
expect "stub: oasdiff tool error -> fail" 2 "$(run_stub 100 base additive)"

if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  run_real() {
    local rc=0
    bash "$script" "$fx/$1" "$fx/$2" >/dev/null 2>&1 || rc=$?
    echo "$rc"
  }
  expect "oasdiff: breaking + no major bump -> fail" 1 "$(run_real base breaking)"
  expect "oasdiff: breaking + major bump -> pass" 0 "$(run_real base breaking-major)"
  expect "oasdiff: additive change -> pass" 0 "$(run_real base additive)"
  expect "oasdiff: spec new on the head -> pass" 0 "$(run_real base with-cart)"
else
  echo "note - Docker unavailable: real oasdiff fixture checks run in CI only"
fi

if ((failures > 0)); then
  echo "$failures failure(s)"
  exit 1
fi
