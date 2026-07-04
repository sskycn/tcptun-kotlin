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
ANDROIDBRIDGE_AAR_OUT="$OUT" ANDROID_API="${ANDROID_API:-24}" "$TCPTUN_GO_DIR/scripts/build-androidbridge.sh"
