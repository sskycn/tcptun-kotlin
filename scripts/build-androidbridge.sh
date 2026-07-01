#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT_DIR/app/libs/androidbridge.aar"
GO_BIN="$(go env GOPATH)/bin"
export PATH="$GO_BIN:$PATH"

GOMOBILE="${GOMOBILE:-}"
if [ -z "$GOMOBILE" ]; then
  if command -v gomobile >/dev/null 2>&1; then
    GOMOBILE="$(command -v gomobile)"
  else
    GOMOBILE="$(go env GOPATH)/bin/gomobile"
  fi
fi

if [ ! -x "$GOMOBILE" ]; then
  cat >&2 <<'MSG'
gomobile is not installed.

Install it first:
  go install golang.org/x/mobile/cmd/gomobile@latest
  gomobile init
MSG
  exit 1
fi

mkdir -p "$ROOT_DIR/app/libs"
cd "$ROOT_DIR/mobile/androidbridge"
GOSUMDB="${GOSUMDB:-off}" "$GOMOBILE" bind -target=android -androidapi=24 -o "$OUT" .
echo "wrote $OUT"
