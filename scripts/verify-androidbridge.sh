#!/usr/bin/env bash
set -euo pipefail

MODE="${1:?verification mode is required}"
AAR="${2:?AAR path is required}"
EXPECTED_API="${3:?expected API version is required}"
EXPECTED_COMMIT="${4:-}"

reject() {
  if [ "$MODE" = "strict" ]; then
    printf 'Bridge verification failed: %s\n' "$*" >&2
    exit 1
  fi
  printf 'WARNING: %s\n' "$*" >&2
  exit 0
}

[ -f "$AAR" ] || reject "androidbridge.aar is missing; run ./scripts/build-androidbridge.sh"
command -v unzip >/dev/null 2>&1 || reject "unzip is required to verify androidbridge.aar"

if ! METADATA="$(unzip -p "$AAR" bridge-version.properties 2>/dev/null)" || [ -z "$METADATA" ]; then
  reject "androidbridge.aar has no bridge-version.properties; rebuild it from a known tcptun-go checkout"
fi

property() {
  printf '%s\n' "$METADATA" | sed -n "s/^$1=//p" | tail -n 1
}

CORE_COMMIT="$(property coreCommit)"
CORE_VERSION="$(property coreVersion)"
BRIDGE_API="$(property bridgeApiVersion)"

[[ "$CORE_COMMIT" =~ ^[0-9a-f]{12,40}$ ]] || reject "Bridge metadata coreCommit is missing or malformed"
[ -n "$CORE_VERSION" ] || reject "Bridge metadata coreVersion is missing"
[[ "$BRIDGE_API" =~ ^[0-9]+$ ]] || reject "Bridge metadata bridgeApiVersion is malformed"
[ "$BRIDGE_API" = "$EXPECTED_API" ] || reject "Bridge API mismatch: app expects $EXPECTED_API but AAR reports $BRIDGE_API"
[ -z "$EXPECTED_COMMIT" ] || [ "$CORE_COMMIT" = "$EXPECTED_COMMIT" ] || \
  reject "Bridge core commit mismatch: expected $EXPECTED_COMMIT but AAR reports $CORE_COMMIT"

printf 'Verified androidbridge: core=%s version=%s api=%s\n' "$CORE_COMMIT" "$CORE_VERSION" "$BRIDGE_API"
