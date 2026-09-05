#!/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$ROOT_DIR"

ADB=${ADB:-adb}
GRADLE=${GRADLE:-./gradlew}
PACKAGE_NAME=${PACKAGE_NAME:-com.tcptun.client}
FORCE_REINSTALL=${FORCE_REINSTALL:-0}
APK="$ROOT_DIR/app/build/outputs/apk/release/app-release.apk"

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

require_command "$ADB"
require_release_signing
require_adb_device

log "Verifying bundled Bridge and building signed release APK"
"$GRADLE" qualityGate :app:verifyAndroidBridge :app:assembleRelease

[ -f "$APK" ] || die "release APK not found: $APK"

log "Installing release APK"
install_apk

log "Installed $PACKAGE_NAME"
printf '%s\n' "$APK"
