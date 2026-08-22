#!/usr/bin/env bash
set -euo pipefail

PREFLIGHT_MODE=none
case "${1:-}" in
  --verify-lock) PREFLIGHT_MODE=local; shift ;;
  --verify-release) PREFLIGHT_MODE=release; shift ;;
esac
[ "$#" -eq 0 ] || {
  echo "usage: scripts/build-androidbridge.sh [--verify-lock|--verify-release]" >&2
  exit 2
}

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TCPTUN_GO_DIR="${TCPTUN_GO_DIR:-"$ROOT_DIR/../tcptun-go"}"
OUT="${ANDROIDBRIDGE_AAR_OUT:-"$ROOT_DIR/app/libs/androidbridge.aar"}"
LOCK_FILE="${BRIDGE_LOCK_FILE:-$ROOT_DIR/bridge.lock}"

lock_property() {
  local key="$1"
  local count
  count="$(awk -F= -v key="$key" '$1 == key { count++ } END { print count + 0 }' "$LOCK_FILE")"
  [ "$count" -eq 1 ] || { echo "bridge.lock must contain exactly one $key property" >&2; exit 1; }
  awk -F= -v key="$key" '$1 == key { sub(/^[^=]*=/, ""); print }' "$LOCK_FILE"
}

[ -f "$LOCK_FILE" ] || { echo "bridge.lock is required" >&2; exit 1; }
PINNED_CORE_COMMIT="$(lock_property coreCommit)"
PINNED_BRIDGE_API="$(lock_property bridgeApiVersion)"
[[ "$PINNED_CORE_COMMIT" =~ ^[0-9a-f]{40}$ ]] || {
  echo "bridge.lock coreCommit must be a full lowercase 40-character Git SHA" >&2
  exit 1
}
[[ "$PINNED_BRIDGE_API" =~ ^[1-9][0-9]*$ ]] || {
  echo "bridge.lock bridgeApiVersion must be a positive integer" >&2
  exit 1
}

if [ ! -d "$TCPTUN_GO_DIR" ]; then
  cat >&2 <<MSG
tcptun-go checkout was not found.

Expected sibling checkout:
  $TCPTUN_GO_DIR

Set TCPTUN_GO_DIR=/path/to/tcptun-go and retry.
MSG
  exit 1
fi

command -v git >/dev/null 2>&1 || { echo "git is required to verify Bridge provenance" >&2; exit 1; }
CORE_COMMIT="$(git -C "$TCPTUN_GO_DIR" rev-parse HEAD)"
CORE_STATUS="$(git -C "$TCPTUN_GO_DIR" status --porcelain --untracked-files=normal)"
CORE_DIRTY=false
[ -z "$CORE_STATUS" ] || CORE_DIRTY=true
if [ "$PREFLIGHT_MODE" = release ] && [ "${ALLOW_UNPINNED_BRIDGE:-0}" = "1" ]; then
  echo "ALLOW_UNPINNED_BRIDGE is forbidden for release verification" >&2
  exit 1
fi
if [ "${ALLOW_UNPINNED_BRIDGE:-0}" != "1" ]; then
  [ "$CORE_COMMIT" = "$PINNED_CORE_COMMIT" ] || {
    echo "tcptun-go HEAD $CORE_COMMIT does not match bridge.lock $PINNED_CORE_COMMIT" >&2
    echo "Checkout the locked commit or deliberately set ALLOW_UNPINNED_BRIDGE=1 for a local-only build." >&2
    exit 1
  }
  [ "$CORE_DIRTY" = false ] || {
    echo "tcptun-go working tree is dirty; Bridge builds require a clean locked checkout" >&2
    exit 1
  }
fi

BRIDGE_API_VERSION="${BRIDGE_API_VERSION:-$PINNED_BRIDGE_API}"
[[ "$BRIDGE_API_VERSION" =~ ^[1-9][0-9]*$ ]] || {
  echo "BRIDGE_API_VERSION must be a positive integer" >&2
  exit 1
}
if [ "${ALLOW_UNPINNED_BRIDGE:-0}" != "1" ] && [ "$BRIDGE_API_VERSION" != "$PINNED_BRIDGE_API" ]; then
  echo "BRIDGE_API_VERSION $BRIDGE_API_VERSION does not match bridge.lock $PINNED_BRIDGE_API" >&2
  exit 1
fi

if [ "$PREFLIGHT_MODE" = release ]; then
  CORE_REMOTE="${TCPTUN_GO_REMOTE:-origin}"
  [[ "$CORE_REMOTE" =~ ^[A-Za-z0-9._-]+$ ]] || {
    echo "TCPTUN_GO_REMOTE must name a configured Git remote" >&2
    exit 1
  }
  git -C "$TCPTUN_GO_DIR" fetch --quiet --prune "$CORE_REMOTE" || {
    echo "tcptun-go remote $CORE_REMOTE is unavailable; release cannot verify bridge.lock" >&2
    exit 1
  }
  REMOTE_CONTAINS_PIN=false
  while IFS= read -r remote_ref; do
    if git -C "$TCPTUN_GO_DIR" merge-base --is-ancestor "$PINNED_CORE_COMMIT" "$remote_ref"; then
      REMOTE_CONTAINS_PIN=true
      break
    fi
  done < <(git -C "$TCPTUN_GO_DIR" for-each-ref \
    --format='%(refname)' "refs/remotes/$CORE_REMOTE/")
  [ "$REMOTE_CONTAINS_PIN" = true ] || {
    echo "bridge.lock coreCommit $PINNED_CORE_COMMIT is not published on remote $CORE_REMOTE" >&2
    exit 1
  }
fi

if [ "$PREFLIGHT_MODE" != none ]; then
  echo "Verified tcptun-go checkout: core=$CORE_COMMIT dirty=$CORE_DIRTY api=$BRIDGE_API_VERSION"
  exit 0
fi

if command -v go >/dev/null 2>&1; then
  GO_BIN="$(go env GOPATH)/bin"
  export PATH="$GO_BIN:$PATH"
fi

if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ] && [ -f "$ROOT_DIR/local.properties" ]; then
  SDK_DIR="$(sed -n 's/^sdk\.dir=//p' "$ROOT_DIR/local.properties" | tail -n 1)"
  if [ -n "$SDK_DIR" ]; then
    export ANDROID_HOME="$SDK_DIR"
  fi
fi

mkdir -p "$ROOT_DIR/app/libs"
ANDROIDBRIDGE_AAR_OUT="$OUT" \
  ANDROID_API="${ANDROID_API:-24}" \
  ANDROID_TARGET="${ANDROID_TARGET:-android/arm,android/arm64,android/amd64}" \
  "$TCPTUN_GO_DIR/scripts/build-androidbridge.sh"

[ -f "$OUT" ] || {
  echo "androidbridge build did not produce $OUT" >&2
  exit 1
}

command -v zip >/dev/null 2>&1 || { echo "zip is required to embed Bridge provenance" >&2; exit 1; }

CORE_VERSION="$(git -C "$TCPTUN_GO_DIR" describe --tags --always --dirty)"

METADATA_DIR="$(mktemp -d "${TMPDIR:-/tmp}/tcptun-bridge-metadata.XXXXXX")"
trap 'rm -rf "$METADATA_DIR"' EXIT
{
  printf 'coreCommit=%s\n' "$CORE_COMMIT"
  printf 'coreVersion=%s\n' "$CORE_VERSION"
  printf 'coreDirty=%s\n' "$CORE_DIRTY"
  printf 'bridgeApiVersion=%s\n' "$BRIDGE_API_VERSION"
} > "$METADATA_DIR/bridge-version.properties"
(
  cd "$METADATA_DIR"
  zip -q -u "$OUT" bridge-version.properties
)

echo "Embedded Bridge metadata: core=$CORE_COMMIT version=$CORE_VERSION dirty=$CORE_DIRTY api=$BRIDGE_API_VERSION"
