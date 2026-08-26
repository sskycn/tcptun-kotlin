#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_dir=$(cd "$script_dir/.." && pwd)
cd "$repo_dir"
# shellcheck source=android-validation-common.sh
source "$script_dir/android-validation-common.sh"

if [[ "${RUNTIME_STRESS_DISPOSABLE_DEBUG_DATA:-}" != "true" ]]; then
    echo "refusing VPN revoke validation without RUNTIME_STRESS_DISPOSABLE_DEBUG_DATA=true" >&2
    exit 1
fi

mode="${VPN_REVOKE_MODE:-${1:-appops}}"
reuse_installed="${VPN_REVOKE_REUSE_INSTALLED:-false}"
output_dir="${VPN_REVOKE_OUTPUT_DIR:-build/validation-gate/vpn-revoke}"
debug_package="com.tcptun.client.debug"
debug_test_package="com.tcptun.client.debug.test"
debug_apk="app/build/outputs/apk/debug/app-debug.apk"
test_apk="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

case "$mode" in
    appops|system) ;;
    *) echo "VPN_REVOKE_MODE must be appops or system" >&2; exit 1 ;;
esac
case "$reuse_installed" in
    true|false) ;;
    *) echo "VPN_REVOKE_REUSE_INSTALLED must be true or false" >&2; exit 1 ;;
esac

mkdir -p "$output_dir"
serial=$(validation_resolve_serial)
activate_vpn_was=$(validation_read_appop_mode "$serial" "$debug_package" ACTIVATE_VPN)
logcat_pid=""

cleanup() {
    local runner_status=$?
    trap - EXIT INT TERM
    set +e
    if [[ -n "$logcat_pid" ]]; then
        kill "$logcat_pid" >/dev/null 2>&1
        wait "$logcat_pid" >/dev/null 2>&1
    fi
    adb -s "$serial" shell am force-stop "$debug_package" >/dev/null 2>&1
    validation_restore_appop_mode \
        "$serial" "$debug_package" ACTIVATE_VPN "$activate_vpn_was" >/dev/null 2>&1 || runner_status=1
    exit "$runner_status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

device_abi=$(adb -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')
{
    printf 'serial=%s\n' "$serial"
    printf 'manufacturer=%s\n' "$(adb -s "$serial" shell getprop ro.product.manufacturer | tr -d '\r')"
    printf 'model=%s\n' "$(adb -s "$serial" shell getprop ro.product.model | tr -d '\r')"
    printf 'android=%s\n' "$(adb -s "$serial" shell getprop ro.build.version.release | tr -d '\r')"
    printf 'api=%s\n' "$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
    printf 'abi=%s\n' "$device_abi"
    printf 'mode=%s\n' "$mode"
} | tee "$output_dir/device.txt"

if [[ "$reuse_installed" == "true" ]]; then
    echo "reuse-installed mode: skipping build and install"
else
    ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
    adb -s "$serial" install -r -t --no-streaming "$debug_apk"
    adb -s "$serial" install -r -t --no-streaming "$test_apk"
fi

: > "$output_dir/identity.txt"
validation_verify_bridge_identity "$debug_apk" "$device_abi" "$output_dir/identity.txt"
validation_verify_installed_apk \
    "$serial" "$debug_apk" "$debug_package" "" "$output_dir/identity.txt"
validation_verify_installed_apk \
    "$serial" "$test_apk" "$debug_test_package" "$debug_package" "$output_dir/identity.txt"

capture_framework_state() {
    local phase="$1"
    local state_dir="$output_dir/$phase"
    mkdir -p "$state_dir"
    date -u '+timestamp_utc=%Y-%m-%dT%H:%M:%SZ' > "$state_dir/timestamp.txt"
    adb -s "$serial" shell appops get "$debug_package" ACTIVATE_VPN \
        > "$state_dir/appops.txt" 2>&1 || true
    adb -s "$serial" shell dumpsys activity services "$debug_package" \
        > "$state_dir/activity-services.txt" 2>&1 || true
    adb -s "$serial" shell dumpsys connectivity \
        > "$state_dir/connectivity.txt" 2>&1 || true
    adb -s "$serial" shell dumpsys vpn \
        > "$state_dir/vpn.txt" 2>&1 || true
}

capture_framework_state before
adb -s "$serial" logcat -c
adb -s "$serial" logcat -v threadtime \
    TcptunVpnLifecycle:I Vpn:I VpnManagerService:I ConnectivityService:I AndroidRuntime:E '*:S' \
    > "$output_dir/lifecycle-logcat.txt" 2>&1 &
logcat_pid=$!

if [[ "$mode" == "system" ]]; then
    echo "When VPN_REVOKE_ACTION_REQUIRED appears, use system Settings to revoke VPN authorization."
    echo "Do not substitute Disconnect, force-stop, task removal, or an AppOps shell command."
    test_method="permissionRevokeDuringRunningReleasesOwnership"
else
    test_method="appOpsAuthorizationChangeRecordsBehaviorWithoutAssumingFrameworkRevoke"
fi

set +e
adb -s "$serial" shell am instrument -w -r \
    -e class "com.tcptun.client.VpnNetworkHandoverStressTest#$test_method" \
    -e runtimeStressSystemEvents true \
    -e runtimeStressRevokeMode "$mode" \
    "$debug_test_package/androidx.test.runner.AndroidJUnitRunner" \
    | tee "$output_dir/instrumentation.txt"
instrumentation_status=${PIPESTATUS[0]}
set -e

capture_framework_state after
if [[ -n "$logcat_pid" ]]; then
    kill "$logcat_pid" >/dev/null 2>&1 || true
    wait "$logcat_pid" >/dev/null 2>&1 || true
    logcat_pid=""
fi

callback_observed=false
if grep -Fq 'onRevoke observed' "$output_dir/lifecycle-logcat.txt"; then
    callback_observed=true
fi

instrumentation_failed=false
if (( instrumentation_status != 0 )) ||
    grep -Eq 'FAILURES!!!|INSTRUMENTATION_(FAILED|ABORTED)|shortMsg=|Process crashed' \
        "$output_dir/instrumentation.txt"; then
    instrumentation_failed=true
fi
instrumentation_complete=false
if grep -Eq 'OK \([0-9]+ tests?\)' "$output_dir/instrumentation.txt"; then
    instrumentation_complete=true
fi

if [[ "$mode" == "system" && "$callback_observed" != "true" ]]; then
    {
        printf 'mode=system\n'
        printf 'result=INCOMPLETE\n'
        printf 'action_observed=false\n'
        printf 'framework_callback=not_observed\n'
        printf 'classification=ACTION_NOT_OBSERVED\n'
    } | tee "$output_dir/summary.txt"
    exit 2
fi
if [[ "$instrumentation_failed" == "true" ]]; then
    {
        printf 'mode=%s\n' "$mode"
        printf 'result=FAIL\n'
        printf 'framework_callback=%s\n' "$callback_observed"
    } | tee "$output_dir/summary.txt"
    exit 1
fi
if [[ "$instrumentation_complete" != "true" ]]; then
    {
        printf 'mode=%s\n' "$mode"
        printf 'result=INCOMPLETE\n'
        printf 'framework_callback=%s\n' "$callback_observed"
    } | tee "$output_dir/summary.txt"
    exit 2
fi

if [[ "$mode" == "appops" ]]; then
    observation=$(sed -n 's/^.*VPN_APPOPS_OBSERVATION/VPN_APPOPS_OBSERVATION/p' \
        "$output_dir/instrumentation.txt" | tail -1)
    {
        printf 'mode=appops\n'
        printf 'result=PASS_DIAGNOSTIC_ONLY\n'
        printf 'framework_revoke_contract=NOT_CLAIMED\n'
        printf 'framework_callback=%s\n' "$callback_observed"
        printf '%s\n' "${observation:-observation marker unavailable}"
    } | tee "$output_dir/summary.txt"
else
    {
        printf 'mode=system\n'
        printf 'result=PASS\n'
        printf 'action_observed=true\n'
        printf 'framework_callback=observed\n'
        printf 'final_ownership=released\n'
    } | tee "$output_dir/summary.txt"
fi
