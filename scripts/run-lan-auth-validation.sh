#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_dir=$(cd "$script_dir/.." && pwd)
cd "$repo_dir"
# shellcheck source=android-validation-common.sh
source "$script_dir/android-validation-common.sh"

if [[ "${RUNTIME_STRESS_DISPOSABLE_DEBUG_DATA:-}" != "true" ]]; then
    echo "refusing LAN auth validation without RUNTIME_STRESS_DISPOSABLE_DEBUG_DATA=true" >&2
    exit 1
fi
password="${TCPTUN_TEST_PROXY_PASSWORD:-}"
[[ -n "$password" ]] || { echo "TCPTUN_TEST_PROXY_PASSWORD is required" >&2; exit 1; }
(( ${#password} <= 200 )) || { echo "TCPTUN_TEST_PROXY_PASSWORD is too long" >&2; exit 1; }

serial=$(validation_resolve_serial)
output_dir="${OUTPUT_DIR:-build/validation-gate}"
port="${TCPTUN_TEST_PROXY_PORT:-19080}"
debug_package="com.tcptun.client.debug"
test_package="com.tcptun.client.debug.test"
debug_apk="app/build/outputs/apk/debug/app-debug.apk"
test_apk="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
mkdir -p "$output_dir"

[[ "$port" =~ ^[0-9]+$ ]] && (( port >= 1024 && port <= 65535 )) || {
    echo "TCPTUN_TEST_PROXY_PORT is invalid" >&2
    exit 1
}
device_ip=$(adb -s "$serial" shell ip -4 addr show wlan0 | tr -d '\r' |
    sed -n 's/.*inet \([0-9.]*\)\/.*/\1/p' | head -1)
[[ "$device_ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
    echo "LAN SECOND CLIENT: BLOCKED (device has no wlan0 IPv4 address)" >&2
    exit 2
}

identity_output="$output_dir/identity.txt"
device_abi=$(adb -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')
activate_vpn_was=$(validation_read_appop_mode "$serial" "$debug_package" ACTIVATE_VPN)
: > "$identity_output"
validation_verify_bridge_identity "$debug_apk" "$device_abi" "$identity_output"
validation_verify_installed_apk "$serial" "$debug_apk" "$debug_package" "" "$identity_output"
validation_verify_installed_apk "$serial" "$test_apk" "$test_package" "$debug_package" "$identity_output"

instrumentation_pid=""
cleanup() {
    local status=$?
    trap - EXIT
    trap '' INT TERM
    set +e
    if [[ -n "$instrumentation_pid" ]]; then
        kill "$instrumentation_pid" >/dev/null 2>&1
        wait "$instrumentation_pid" >/dev/null 2>&1
    fi
    adb -s "$serial" shell setprop debug.tcptun.lan.lb none
    adb -s "$serial" shell setprop debug.tcptun.lan.auth none
    adb -s "$serial" shell setprop debug.tcptun.lan.persist none
    adb -s "$serial" shell am force-stop "$debug_package" >/dev/null 2>&1
    validation_restore_appop_mode "$serial" "$debug_package" ACTIVATE_VPN "$activate_vpn_was" >/dev/null 2>&1
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

wait_for_phase() {
    local phase="$1" deadline=$((SECONDS + 45))
    while (( SECONDS < deadline )); do
        if adb -s "$serial" logcat -d -v brief -s System.out:I '*:S' | grep -q "LAN_PHASE=$phase"; then
            return
        fi
        sleep 1
    done
    echo "timed out waiting for LAN phase $phase" >&2
    return 1
}

probe_url="${TCPTUN_TEST_PROBE_URL:-https://www.gstatic.com/generate_204}"
probe_proxy() {
    local credential="$1"
    if [[ -n "$credential" ]]; then
        curl --silent --show-error --max-time 15 --socks5-hostname "$device_ip:$port" \
            --proxy-user "$credential" --output /dev/null "$probe_url"
    else
        curl --silent --show-error --max-time 8 --socks5-hostname "$device_ip:$port" \
            --output /dev/null "$probe_url"
    fi
}

adb -s "$serial" logcat -c
instrumentation_output="$output_dir/lan-instrumentation.txt"
adb -s "$serial" shell am instrument -w -r \
    -e class com.tcptun.client.LanAuthValidationTest \
    -e lanAuthValidationEnabled true \
    -e lanAuthValidationPort "$port" \
    -e lanAuthValidationPassword "$password" \
    "$test_package/androidx.test.runner.AndroidJUnitRunner" > "$instrumentation_output" &
instrumentation_pid=$!

wait_for_phase LOOPBACK_ONLY_READY
if probe_proxy "" >/dev/null 2>&1; then
    echo "listenAll=false unexpectedly accepted non-loopback access" >&2
    exit 1
fi
loopback_result="PASS (LAN access refused)"
adb -s "$serial" shell setprop debug.tcptun.lan.lb ready

wait_for_phase AUTH_REQUIRED_READY
if probe_proxy "" >/dev/null 2>&1; then
    echo "listenAll=true unexpectedly accepted anonymous access" >&2
    exit 1
fi
anonymous_result="PASS (authentication refused)"
if probe_proxy "tcptun-validation-wrong:wrong" >/dev/null 2>&1; then
    echo "listenAll=true unexpectedly accepted a wrong password" >&2
    exit 1
fi
wrong_result="PASS (authentication refused)"
probe_proxy ":$password" >/dev/null
correct_result="PASS"
adb -s "$serial" shell setprop debug.tcptun.lan.auth ready

wait_for_phase PERSISTED_RESTART_READY
probe_proxy ":$password" >/dev/null
persistence_result="PASS"
adb -s "$serial" shell setprop debug.tcptun.lan.persist ready

wait "$instrumentation_pid"
instrumentation_pid=""
if ! grep -Eq 'OK \(1 test\)' "$instrumentation_output"; then
    cat "$instrumentation_output"
    echo "LAN instrumentation failed" >&2
    exit 1
fi

fingerprint=$(printf '%s' "$password" | shasum -a 256 | awk '{ print substr($1, 1, 12) }')
report="$output_dir/lan-auth.txt"
{
    printf 'transport=physical non-loopback Wi-Fi IPv4\n'
    printf 'listenAll=false non_loopback=%s\n' "$loopback_result"
    printf 'listenAll=true anonymous=%s\n' "$anonymous_result"
    printf 'listenAll=true wrong_password=%s\n' "$wrong_result"
    printf 'listenAll=true correct_password=%s\n' "$correct_result"
    printf 'password_persistence_stop_start=%s\n' "$persistence_result"
    printf 'password_fingerprint_sha256_prefix=%s\n' "$fingerprint"
    printf 'legacy_migration=NOT_RUN\n'
} > "$report"
cat "$report"
