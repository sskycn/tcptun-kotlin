#!/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$ROOT_DIR"

ADB=${ADB:-adb}
GRADLE=${GRADLE:-./gradlew}
PACKAGE_NAME=${PACKAGE_NAME:-com.tcptun.client}
BUILD_BRIDGE=${BUILD_BRIDGE:-0}
FORCE_REINSTALL=${FORCE_REINSTALL:-0}
TCPTUN_GO_DIR=${TCPTUN_GO_DIR:-"$ROOT_DIR/../tcptun-go"}
TCPTUN_GO_REMOTE=${TCPTUN_GO_REMOTE:-origin}
TCPTUN_GO_BRANCH=${TCPTUN_GO_BRANCH:-main}
APK="$ROOT_DIR/app/build/outputs/apk/release/app-release.apk"
BRIDGE_AAR="$ROOT_DIR/app/libs/androidbridge.aar"

log() {
  printf '\n==> %s\n' "$*"
}

die() {
  printf 'release.bash: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "missing command: $1"
}

adb_devices() {
  "$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1 }'
}

device_count() {
  printf '%s\n' "$1" | awk 'NF { n++ } END { print n + 0 }'
}

require_adb_device() {
  devices=$(adb_devices)
  count=$(device_count "$devices")

  if [ "$count" -eq 0 ]; then
    log "No adb device found; restarting adb server"
    "$ADB" kill-server
    "$ADB" start-server
    devices=$(adb_devices)
    count=$(device_count "$devices")
  fi

  if [ "$count" -eq 0 ]; then
    die "no adb device found. Connect a device and run: adb devices"
  fi

  if [ "$count" -gt 1 ] && [ -z "${ANDROID_SERIAL:-}" ]; then
    printf '%s\n' "$devices" >&2
    die "multiple adb devices found. Set ANDROID_SERIAL=<device-id> and retry."
  fi
}

require_release_signing() {
  if [ -f "$ROOT_DIR/signing.properties" ]; then
    return 0
  fi

  if [ -n "${TCPTUN_RELEASE_STORE_FILE:-}" ] &&
    [ -n "${TCPTUN_RELEASE_STORE_PASSWORD:-}" ] &&
    [ -n "${TCPTUN_RELEASE_KEY_ALIAS:-}" ] &&
    [ -n "${TCPTUN_RELEASE_KEY_PASSWORD:-}" ]; then
    return 0
  fi

  die "release signing is not configured. Create signing.properties or set TCPTUN_RELEASE_* env vars."
}

install_apk() {
  if "$ADB" install -r "$APK"; then
    return 0
  fi

  if [ "$FORCE_REINSTALL" = "1" ]; then
    log "Install failed; FORCE_REINSTALL=1, uninstalling $PACKAGE_NAME and retrying"
    "$ADB" uninstall "$PACKAGE_NAME" >/dev/null || true
    "$ADB" install "$APK"
    return 0
  fi

  die "adb install failed. If this is a signature mismatch, run: FORCE_REINSTALL=1 sh release.bash"
}

build_latest_bridge() {
  require_command git
  [ -d "$TCPTUN_GO_DIR/.git" ] ||
    die "tcptun-go checkout not found: $TCPTUN_GO_DIR"

  log "Fetching latest tcptun-go ($TCPTUN_GO_REMOTE/$TCPTUN_GO_BRANCH)"
  git -C "$TCPTUN_GO_DIR" fetch --quiet --prune "$TCPTUN_GO_REMOTE" "$TCPTUN_GO_BRANCH" ||
    die "failed to fetch latest tcptun-go from $TCPTUN_GO_REMOTE"

  latest_core_commit=$(git -C "$TCPTUN_GO_DIR" rev-parse "$TCPTUN_GO_REMOTE/$TCPTUN_GO_BRANCH") ||
    die "failed to resolve latest tcptun-go commit"
  bridge_api_version=$(sed -n 's/^bridgeApiVersion=//p' "$ROOT_DIR/bridge.lock")
  [ -n "$bridge_api_version" ] || die "bridge.lock has no bridgeApiVersion"

  work_root=$(mktemp -d "${TMPDIR:-/tmp}/tcptun-go-release.XXXXXX") ||
    die "failed to create temporary tcptun-go worktree"
  worktree_dir="$work_root/checkout"
  lock_file="$work_root/bridge.lock"

  cleanup_bridge_worktree() {
    git -C "$TCPTUN_GO_DIR" worktree remove --force "$worktree_dir" >/dev/null 2>&1 || true
    rmdir "$work_root" >/dev/null 2>&1 || true
  }
  trap cleanup_bridge_worktree 0 1 2 3 15

  git -C "$TCPTUN_GO_DIR" worktree add --detach --quiet "$worktree_dir" "$latest_core_commit" ||
    die "failed to create tcptun-go worktree at $latest_core_commit"
  {
    printf 'coreCommit=%s\n' "$latest_core_commit"
    printf 'bridgeApiVersion=%s\n' "$bridge_api_version"
  } > "$lock_file"

  log "Building androidbridge from tcptun-go $latest_core_commit"
  TCPTUN_GO_DIR="$worktree_dir" \
    BRIDGE_LOCK_FILE="$lock_file" \
    ./scripts/build-androidbridge.sh ||
    die "failed to build androidbridge from latest tcptun-go"

  cp "$lock_file" "$ROOT_DIR/bridge.lock" ||
    die "failed to update bridge.lock for tcptun-go $latest_core_commit"
  trap - 0 1 2 3 15
  cleanup_bridge_worktree
}

require_command "$ADB"
require_release_signing
require_adb_device

if [ "$BUILD_BRIDGE" = "1" ] || [ ! -f "$BRIDGE_AAR" ]; then
  if [ "$BUILD_BRIDGE" = "1" ]; then
    build_latest_bridge
  else
    log "Building androidbridge AAR"
    ./scripts/build-androidbridge.sh
  fi
fi

log "Building signed release APK"
"$GRADLE" :app:assembleRelease

[ -f "$APK" ] || die "release APK not found: $APK"

log "Installing release APK"
install_apk

log "Installed $PACKAGE_NAME"
printf '%s\n' "$APK"
