#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TCPTUN_GO_DIR="${TCPTUN_GO_DIR:-"$ROOT_DIR/../tcptun-go"}"
OUT="${ANDROIDBRIDGE_AAR_OUT:-"$ROOT_DIR/app/libs/androidbridge.aar"}"

if [ ! -d "$TCPTUN_GO_DIR" ]; then
  cat >&2 <<MSG
tcptun-go checkout was not found.

Expected sibling checkout:
  $TCPTUN_GO_DIR

Set TCPTUN_GO_DIR=/path/to/tcptun-go and retry.
MSG
  exit 1
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

command -v git >/dev/null 2>&1 || { echo "git is required to record Bridge provenance" >&2; exit 1; }
command -v zip >/dev/null 2>&1 || { echo "zip is required to embed Bridge provenance" >&2; exit 1; }

CORE_COMMIT="$(git -C "$TCPTUN_GO_DIR" rev-parse HEAD)"
CORE_VERSION="$(git -C "$TCPTUN_GO_DIR" describe --tags --always --dirty)"
BRIDGE_API_VERSION="${BRIDGE_API_VERSION:-1}"
case "$BRIDGE_API_VERSION" in
  ''|*[!0-9]*) echo "BRIDGE_API_VERSION must be a positive integer" >&2; exit 1 ;;
esac
[ "$BRIDGE_API_VERSION" -gt 0 ] || { echo "BRIDGE_API_VERSION must be positive" >&2; exit 1; }

METADATA_DIR="$(mktemp -d "${TMPDIR:-/tmp}/tcptun-bridge-metadata.XXXXXX")"
trap 'rm -rf "$METADATA_DIR"' EXIT
{
  printf 'coreCommit=%s\n' "$CORE_COMMIT"
  printf 'coreVersion=%s\n' "$CORE_VERSION"
  printf 'bridgeApiVersion=%s\n' "$BRIDGE_API_VERSION"
} > "$METADATA_DIR/bridge-version.properties"
(
  cd "$METADATA_DIR"
  zip -q -u "$OUT" bridge-version.properties
)

echo "Embedded Bridge metadata: core=$CORE_COMMIT version=$CORE_VERSION api=$BRIDGE_API_VERSION"
