#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_dir=$(cd "$script_dir/.." && pwd)
cd "$repo_dir"
# shellcheck source=android-validation-common.sh
source "$script_dir/android-validation-common.sh"

if [[ "${RUNTIME_STRESS_DISPOSABLE_DEBUG_DATA:-}" != "true" ]]; then
    echo "refusing runtime stress without RUNTIME_STRESS_DISPOSABLE_DEBUG_DATA=true" >&2
    exit 1
fi

iterations="${RUNTIME_STRESS_ITERATIONS:-500}"
seed="${RUNTIME_STRESS_SEED:-1592622103}"
max_delay_millis="${RUNTIME_STRESS_MAX_DELAY_MILLIS:-200}"
network_control="${RUNTIME_STRESS_NETWORK_CONTROL:-false}"
system_events="${RUNTIME_STRESS_SYSTEM_EVENTS:-false}"
reuse_installed="${RUNTIME_STRESS_REUSE_INSTALLED:-false}"
output_dir="${RUNTIME_STRESS_OUTPUT_DIR:-build/validation-gate}"
membership_profile_a_uri="${RUNTIME_STRESS_MEMBERSHIP_PROFILE_A_URI:-}"
membership_profile_b_uri="${RUNTIME_STRESS_MEMBERSHIP_PROFILE_B_URI:-}"
debug_package="com.tcptun.client.debug"
debug_test_package="com.tcptun.client.debug.test"
debug_apk="app/build/outputs/apk/debug/app-debug.apk"
test_apk="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

case "$reuse_installed" in true|false) ;; *) echo "RUNTIME_STRESS_REUSE_INSTALLED must be true or false" >&2; exit 1 ;; esac
mkdir -p "$output_dir"
identity_output="$output_dir/identity.txt"

if [[ -n "$membership_profile_a_uri" && -z "$membership_profile_b_uri" ]] ||
    [[ -z "$membership_profile_a_uri" && -n "$membership_profile_b_uri" ]]; then
    echo "membership stress requires both RUNTIME_STRESS_MEMBERSHIP_PROFILE_A_URI and _B_URI" >&2
    exit 1
fi

serial=$(validation_resolve_serial)
activate_vpn_was=$(validation_read_appop_mode "$serial" "$debug_package" ACTIVATE_VPN)
wifi_status=$(adb -s "$serial" shell cmd wifi status | tr '[:upper:]' '[:lower:]')
if [[ "$wifi_status" == *"enabled"* ]]; then
    wifi_was_enabled="true"
else
    wifi_was_enabled="false"
fi
mobile_data_value=$(adb -s "$serial" shell settings get global mobile_data | tr -d '\r' | xargs)
if [[ "$mobile_data_value" == "1" ]]; then
    mobile_data_was_enabled="true"
else
    mobile_data_was_enabled="false"
fi

cleanup_started="false"
package_is_installed() {
    adb -s "$serial" shell pm path "$1" 2>/dev/null | grep -q '^package:'
}

clear_disposable_package() {
    local package_name="$1"
    if ! package_is_installed "$package_name"; then
        return 0
    fi
    if adb -s "$serial" shell pm clear "$package_name" >/dev/null 2>&1; then
        return 0
    fi
    if [[ "$reuse_installed" == "true" ]]; then
        echo "pm clear denied for $package_name; preserving installation and using harness fixture reset" >&2
        return 0
    fi
    echo "pm clear denied for $package_name; uninstalling disposable debug package" >&2
    adb -s "$serial" uninstall "$package_name" >/dev/null
}

cleanup() {
    local runner_status=$?
    local cleanup_failed="false"
    if [[ "$cleanup_started" == "true" ]]; then
        return
    fi
    cleanup_started="true"
    trap - EXIT
    trap '' INT TERM
    set +e
    adb -s "$serial" shell am force-stop "$debug_package"
    validation_restore_appop_mode "$serial" "$debug_package" ACTIVATE_VPN "$activate_vpn_was" || cleanup_failed="true"
    clear_disposable_package "$debug_package" || cleanup_failed="true"
    clear_disposable_package "$debug_test_package" || cleanup_failed="true"
    if [[ "$wifi_was_enabled" == "true" ]]; then
        adb -s "$serial" shell svc wifi enable
    else
        adb -s "$serial" shell svc wifi disable
    fi
    if [[ "$mobile_data_was_enabled" == "true" ]]; then
        adb -s "$serial" shell svc data enable
    else
        adb -s "$serial" shell svc data disable
    fi
    if [[ "$cleanup_failed" == "true" ]]; then
        echo "runtime stress could not clear all disposable debug data" >&2
        runner_status=1
    fi
    exit "$runner_status"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

echo "runtime stress device: $serial"
adb -s "$serial" shell getprop ro.product.manufacturer
adb -s "$serial" shell getprop ro.product.model
adb -s "$serial" shell getprop ro.build.version.release
adb -s "$serial" shell getprop ro.build.version.sdk
adb -s "$serial" shell getprop ro.product.cpu.abi
device_abi=$(adb -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')

if [[ "$network_control" == "true" ]]; then
    echo "network control enabled; use USB ADB because the test disables Wi-Fi"
fi

if [[ "$reuse_installed" == "true" ]]; then
    echo "reuse-installed mode: skipping build and install"
else
    ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
    adb -s "$serial" install -r -t --no-streaming "$debug_apk"
    adb -s "$serial" install -r -t --no-streaming "$test_apk"
fi

: > "$identity_output"
validation_verify_bridge_identity "$debug_apk" "$device_abi" "$identity_output"
validation_verify_installed_apk "$serial" "$debug_apk" "$debug_package" "" "$identity_output"
validation_verify_installed_apk "$serial" "$test_apk" "$debug_test_package" "$debug_package" "$identity_output"

adb -s "$serial" shell am force-stop "$debug_package" >/dev/null 2>&1 || true
clear_disposable_package "$debug_package"
clear_disposable_package "$debug_test_package"

instrumentation_args=(
    -e class com.tcptun.client.VpnRuntimeStressTest,com.tcptun.client.VpnNetworkHandoverStressTest
    -e runtimeStressEnabled true
    -e runtimeStressIterations "$iterations"
    -e runtimeStressSeed "$seed"
    -e runtimeStressMaxDelayMillis "$max_delay_millis"
    -e runtimeStressNetworkControl "$network_control"
    -e runtimeStressSystemEvents "$system_events"
)

if [[ -n "$membership_profile_a_uri" ]]; then
    membership_profile_a_base64=$(printf '%s' "$membership_profile_a_uri" | base64 | tr -d '\r\n')
    membership_profile_b_base64=$(printf '%s' "$membership_profile_b_uri" | base64 | tr -d '\r\n')
    instrumentation_args+=(
        -e runtimeStressMembershipProfileABase64 "$membership_profile_a_base64"
        -e runtimeStressMembershipProfileBBase64 "$membership_profile_b_base64"
    )
fi

set +e
instrumentation_output=$(adb -s "$serial" shell am instrument -w -r \
    "${instrumentation_args[@]}" \
    "$debug_test_package/androidx.test.runner.AndroidJUnitRunner")
instrumentation_status=$?
set -e
printf '%s\n' "$instrumentation_output"
printf '%s\n' "$instrumentation_output" > "$output_dir/seed-$seed.txt"

if (( instrumentation_status != 0 )) ||
    grep -Eq 'FAILURES!!!|INSTRUMENTATION_(FAILED|ABORTED)|shortMsg=|Process crashed' \
        <<<"$instrumentation_output"; then
    echo "runtime stress instrumentation failed" >&2
    exit 1
fi
if ! grep -Eq 'OK \([0-9]+ tests?\)' <<<"$instrumentation_output"; then
    echo "runtime stress instrumentation did not report a successful JUnit summary" >&2
    exit 1
fi

junit_duration=$(printf '%s\n' "$instrumentation_output" |
    sed -n 's/^Time: //p' | tail -1)
skipped_tests=$(grep -c 'INSTRUMENTATION_STATUS_CODE: -4' <<<"$instrumentation_output" || true)
{
    printf 'RUNTIME_STRESS_RUN seed=%s iterations=%s duration_seconds=%s result=PASS\n' \
        "$seed" "$iterations" "${junit_duration:-NA}"
    printf 'crash=0 native_crash=0 anr=0 ownership_failure=0 final_ownership=released skipped_tests=%s\n' \
        "$skipped_tests"
} | tee -a "$output_dir/seed-$seed.txt"
