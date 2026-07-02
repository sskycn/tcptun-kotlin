#!/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$ROOT_DIR"

ADB=${ADB:-adb}
GRADLE=${GRADLE:-./gradlew}
PACKAGE_NAME=${PACKAGE_NAME:-com.sskycn.tcptun}
BUILD_BRIDGE=${BUILD_BRIDGE:-0}
FORCE_REINSTALL=${FORCE_REINSTALL:-0}
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

require_adb_device() {
  devices=$("$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1 }')
  count=$(printf '%s\n' "$devices" | awk 'NF { n++ } END { print n + 0 }')

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

require_command "$ADB"
require_release_signing
require_adb_device

if [ "$BUILD_BRIDGE" = "1" ] || [ ! -f "$BRIDGE_AAR" ]; then
  log "Building androidbridge AAR"
  ./scripts/build-androidbridge.sh
fi

log "Building signed release APK"
"$GRADLE" :app:assembleRelease

[ -f "$APK" ] || die "release APK not found: $APK"

log "Installing release APK"
install_apk

log "Installed $PACKAGE_NAME"
printf '%s\n' "$APK"
