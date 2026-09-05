#!/usr/bin/env bash
set -euo pipefail

MODE="${1:?verification mode is required}"
AAR="${2:?AAR path is required}"
LOCK_FILE="${3:?bridge.lock path is required}"
ASSERTED_COMMIT="${4:-}"

reject() {
  printf 'Bridge verification failed: %s\n' "$*" >&2
  exit 1
}

[ "$MODE" = "strict" ] || reject "unsupported verification mode: $MODE"
[ -f "$AAR" ] || reject "bundled androidbridge.aar is missing; restore app/libs/androidbridge.aar from Git"
[ -f "$LOCK_FILE" ] || reject "bridge.lock is missing"
command -v unzip >/dev/null 2>&1 || reject "unzip is required to verify androidbridge.aar"
unzip -tqq "$AAR" >/dev/null 2>&1 || reject "androidbridge.aar is not a valid, intact ZIP archive"

if ! AAR_ENTRIES="$(unzip -Z1 "$AAR" 2>/dev/null)"; then
  reject "androidbridge.aar entries cannot be read"
fi

require_entry() {
  printf '%s\n' "$AAR_ENTRIES" | grep -Fx "$1" >/dev/null || \
    reject "androidbridge.aar is missing required entry $1"
}

require_entry classes.jar
require_entry jni/armeabi-v7a/libgojni.so
require_entry jni/arm64-v8a/libgojni.so
require_entry jni/x86_64/libgojni.so
require_entry bridge-version.properties

lock_property() {
  local key="$1"
  local count
  count="$(awk -F= -v key="$key" '$1 == key { count++ } END { print count + 0 }' "$LOCK_FILE")"
  [ "$count" -eq 1 ] || reject "bridge.lock must contain exactly one $key property"
  awk -F= -v key="$key" '$1 == key { sub(/^[^=]*=/, ""); print }' "$LOCK_FILE"
}

EXPECTED_COMMIT="$(lock_property coreCommit)"
EXPECTED_API="$(lock_property bridgeApiVersion)"
[[ "$EXPECTED_COMMIT" =~ ^[0-9a-f]{40}$ ]] || reject "bridge.lock coreCommit must be a full lowercase 40-character Git SHA"
[[ "$EXPECTED_API" =~ ^[1-9][0-9]*$ ]] || reject "bridge.lock bridgeApiVersion must be a positive integer"

if ! METADATA="$(unzip -p "$AAR" bridge-version.properties 2>/dev/null)" || [ -z "$METADATA" ]; then
  reject "androidbridge.aar has no bridge-version.properties; rebuild it from a known tcptun-go checkout"
fi

property() {
  printf '%s\n' "$METADATA" | sed -n "s/^$1=//p" | tail -n 1
}

CORE_COMMIT="$(property coreCommit)"
CORE_VERSION="$(property coreVersion)"
CORE_DIRTY="$(property coreDirty)"
BRIDGE_API="$(property bridgeApiVersion)"

[[ "$CORE_COMMIT" =~ ^[0-9a-f]{40}$ ]] || reject "Bridge metadata coreCommit must be a full lowercase 40-character Git SHA"
[ -n "$CORE_VERSION" ] || reject "Bridge metadata coreVersion is missing"
[[ "$CORE_VERSION" != *-dirty* ]] || reject "Bridge metadata reports a dirty core version"
[ "$CORE_DIRTY" = false ] || reject "Bridge metadata coreDirty must be false"
[[ "$BRIDGE_API" =~ ^[0-9]+$ ]] || reject "Bridge metadata bridgeApiVersion is malformed"
[ "$BRIDGE_API" = "$EXPECTED_API" ] || reject "Bridge API mismatch: app expects $EXPECTED_API but AAR reports $BRIDGE_API"
[ "$CORE_COMMIT" = "$EXPECTED_COMMIT" ] || \
  reject "Bridge core commit mismatch: expected $EXPECTED_COMMIT but AAR reports $CORE_COMMIT"
[ -z "$ASSERTED_COMMIT" ] || [ "$CORE_COMMIT" = "$ASSERTED_COMMIT" ] || \
  reject "Bridge core commit does not match the additional assertion $ASSERTED_COMMIT"

printf 'Verified androidbridge: core=%s version=%s dirty=%s api=%s\n' \
  "$CORE_COMMIT" "$CORE_VERSION" "$CORE_DIRTY" "$BRIDGE_API"
