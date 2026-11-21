#!/usr/bin/env bash
# Fails when core.yaml or cart.yaml changed in a breaking way (per oasdiff) without a major version
# bump in package.json.
#
#   check-breaking.sh <base-contracts-dir> <head-contracts-dir>
#
# Each directory holds `openapi/` and `package.json` (the `contracts/` folder of a checkout).
# A spec absent from the base directory is new and passes. oasdiff runs from a pinned Docker image;
# set OASDIFF_CMD (for tests) to a command that is called as
# `$OASDIFF_CMD <base-spec> <head-spec>` and must follow oasdiff's exit codes:
# 0 no breaking change, 1 breaking change, anything else is a tool error.
set -euo pipefail

OASDIFF_IMAGE=${OASDIFF_IMAGE:-tufin/oasdiff:v1.39.0}
SPECS=(core.yaml cart.yaml)

if [[ $# -ne 2 ]]; then
  echo "usage: check-breaking.sh <base-contracts-dir> <head-contracts-dir>" >&2
  exit 64
fi
base=$(cd "$1" && pwd)
head=$(cd "$2" && pwd)

major() { node -p "Number(require('$1/package.json').version.split('.')[0])"; }

# Prints oasdiff output, returns its exit code.
run_oasdiff() { # spec
  if [[ -n ${OASDIFF_CMD:-} ]]; then
    # shellcheck disable=SC2086
    $OASDIFF_CMD "$base/openapi/$1" "$head/openapi/$1"
  else
    docker run --rm -v "$base:/base:ro" -v "$head:/head:ro" "$OASDIFF_IMAGE" \
      breaking "/base/openapi/$1" "/head/openapi/$1" --fail-on ERR
  fi
}

breaking=()
for spec in "${SPECS[@]}"; do
  if [[ ! -f $base/openapi/$spec ]]; then
    echo "ok   $spec: new spec, nothing to compare"
    continue
  fi
  if [[ ! -f $head/openapi/$spec ]]; then
    echo "::error::$spec was removed, which is a breaking change"
    breaking+=("$spec")
    continue
  fi
  rc=0
  run_oasdiff "$spec" || rc=$?
  case $rc in
    0) echo "ok   $spec: no breaking changes" ;;
    1) echo "breaking change in $spec"; breaking+=("$spec") ;;
    *) echo "::error::oasdiff failed on $spec (exit $rc)" >&2; exit 2 ;;
  esac
done

if [[ ${#breaking[@]} -eq 0 ]]; then
  exit 0
fi

base_major=$(major "$base")
head_major=$(major "$head")
if ((head_major > base_major)); then
  echo "ok   breaking changes in ${breaking[*]} come with a major bump ($base_major -> $head_major)"
  exit 0
fi
echo "::error::Breaking changes in ${breaking[*]} without a major version bump (contracts/package.json major is still $head_major). Bump the major version, or make the change additive."
exit 1
