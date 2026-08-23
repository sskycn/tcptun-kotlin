#!/usr/bin/env bash
set -euo pipefail

if [[ "${RUNTIME_STRESS_DISPOSABLE_DEBUG_DATA:-}" != "true" ]]; then
    echo "refusing runtime stress without RUNTIME_STRESS_DISPOSABLE_DEBUG_DATA=true" >&2
    exit 1
fi

iterations="${RUNTIME_STRESS_ITERATIONS:-500}"
seed="${RUNTIME_STRESS_SEED:-1592622103}"
max_delay_millis="${RUNTIME_STRESS_MAX_DELAY_MILLIS:-200}"
network_control="${RUNTIME_STRESS_NETWORK_CONTROL:-false}"
system_events="${RUNTIME_STRESS_SYSTEM_EVENTS:-false}"
membership_profile_a_uri="${RUNTIME_STRESS_MEMBERSHIP_PROFILE_A_URI:-}"
membership_profile_b_uri="${RUNTIME_STRESS_MEMBERSHIP_PROFILE_B_URI:-}"
debug_package="com.tcptun.client.debug"
debug_test_package="com.tcptun.client.debug.test"

if [[ -n "$membership_profile_a_uri" && -z "$membership_profile_b_uri" ]] ||
    [[ -z "$membership_profile_a_uri" && -n "$membership_profile_b_uri" ]]; then
    echo "membership stress requires both RUNTIME_STRESS_MEMBERSHIP_PROFILE_A_URI and _B_URI" >&2
    exit 1
fi

device_lines=$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
device_count=$(printf '%s\n' "$device_lines" | awk 'NF { count += 1 } END { print count + 0 }')
if (( device_count != 1 )); then
    echo "runtime stress requires exactly one authorized device; found $device_count" >&2
    exit 1
fi

serial=$(printf '%s\n' "$device_lines" | awk 'NF { print; exit }')
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
    adb -s "$serial" shell appops reset "$debug_package" >/dev/null 2>&1
    adb -s "$serial" shell appops reset "$debug_test_package" >/dev/null 2>&1
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

if [[ "$network_control" == "true" ]]; then
    echo "network control enabled; use USB ADB because the test disables Wi-Fi"
fi

adb -s "$serial" shell am force-stop "$debug_package" >/dev/null 2>&1 || true
adb -s "$serial" shell appops reset "$debug_package" >/dev/null 2>&1 || true
adb -s "$serial" shell appops reset "$debug_test_package" >/dev/null 2>&1 || true
clear_disposable_package "$debug_package"
clear_disposable_package "$debug_test_package"

gradle_args=(
    :app:connectedDebugAndroidTest
    -Pandroid.testInstrumentationRunnerArguments.class=com.tcptun.client.VpnRuntimeStressTest,com.tcptun.client.VpnNetworkHandoverStressTest
    -Pandroid.testInstrumentationRunnerArguments.runtimeStressEnabled=true
    -Pandroid.testInstrumentationRunnerArguments.runtimeStressIterations="$iterations"
    -Pandroid.testInstrumentationRunnerArguments.runtimeStressSeed="$seed"
    -Pandroid.testInstrumentationRunnerArguments.runtimeStressMaxDelayMillis="$max_delay_millis"
    -Pandroid.testInstrumentationRunnerArguments.runtimeStressNetworkControl="$network_control"
    -Pandroid.testInstrumentationRunnerArguments.runtimeStressSystemEvents="$system_events"
)

if [[ -n "$membership_profile_a_uri" ]]; then
    membership_profile_a_base64=$(printf '%s' "$membership_profile_a_uri" | base64 | tr -d '\r\n')
    membership_profile_b_base64=$(printf '%s' "$membership_profile_b_uri" | base64 | tr -d '\r\n')
    gradle_args+=(
        -Pandroid.testInstrumentationRunnerArguments.runtimeStressMembershipProfileABase64="$membership_profile_a_base64"
        -Pandroid.testInstrumentationRunnerArguments.runtimeStressMembershipProfileBBase64="$membership_profile_b_base64"
    )
fi

ANDROID_SERIAL="$serial" ./gradlew "${gradle_args[@]}"
