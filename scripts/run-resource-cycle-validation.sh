#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_dir=$(cd "$script_dir/.." && pwd)
cd "$repo_dir"
# shellcheck source=android-validation-common.sh
source "$script_dir/android-validation-common.sh"

if [[ "${RUNTIME_STRESS_DISPOSABLE_DEBUG_DATA:-}" != "true" ]]; then
    echo "refusing resource cycles without RUNTIME_STRESS_DISPOSABLE_DEBUG_DATA=true" >&2
    exit 1
fi

cycles="${CYCLES:-100}"
reuse_installed="${REUSE_INSTALLED:-${RUNTIME_STRESS_REUSE_INSTALLED:-false}}"
output_dir="${OUTPUT_DIR:-build/validation-gate}"
debug_package="com.tcptun.client.debug"
test_package="com.tcptun.client.debug.test"
debug_apk="app/build/outputs/apk/debug/app-debug.apk"
test_apk="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

[[ "$cycles" =~ ^[0-9]+$ ]] && (( cycles >= 1 && cycles <= 500 )) || {
    echo "CYCLES must be an integer from 1 through 500" >&2
    exit 1
}
case "$reuse_installed" in true|false) ;; *) echo "REUSE_INSTALLED must be true or false" >&2; exit 1 ;; esac

mkdir -p "$output_dir"
serial=$(validation_resolve_serial)
identity_output="$output_dir/identity.txt"
device_abi=$(adb -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')
activate_vpn_was=$(validation_read_appop_mode "$serial" "$debug_package" ACTIVATE_VPN)

cleanup_started="false"
logcat_pid=""
clear_reused_package_data() {
    local package_name="$1"
    if adb -s "$serial" shell pm clear "$package_name" >/dev/null 2>&1; then
        return
    fi
    if [[ "$reuse_installed" == "true" ]]; then
        echo "pm clear denied for $package_name; preserving installation and using harness fixture reset" >&2
        return
    fi
    echo "pm clear denied for $package_name" >&2
    return 1
}

cleanup() {
    local runner_status=$?
    [[ "$cleanup_started" == "false" ]] || return
    cleanup_started="true"
    trap - EXIT
    trap '' INT TERM
    set +e
    if [[ -n "$logcat_pid" ]]; then
        kill "$logcat_pid" >/dev/null 2>&1
        wait "$logcat_pid" >/dev/null 2>&1
    fi
    adb -s "$serial" shell am force-stop "$debug_package" >/dev/null 2>&1
    validation_restore_appop_mode "$serial" "$debug_package" ACTIVATE_VPN "$activate_vpn_was" || runner_status=1
    clear_reused_package_data "$debug_package" || runner_status=1
    clear_reused_package_data "$test_package" || runner_status=1
    exit "$runner_status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

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
validation_verify_installed_apk "$serial" "$test_apk" "$test_package" "$debug_package" "$identity_output"

adb -s "$serial" shell am force-stop "$debug_package" >/dev/null 2>&1 || true
clear_reused_package_data "$debug_package"
clear_reused_package_data "$test_package"

logcat_capture="$output_dir/resource-logcat.txt"
adb -s "$serial" logcat -c
adb -s "$serial" logcat -v brief -s System.out:I '*:S' > "$logcat_capture" &
logcat_pid=$!

set +e
instrumentation_output=$(adb -s "$serial" shell am instrument -w -r \
    -e class com.tcptun.client.ResourceCycleValidationTest \
    -e resourceCycleEnabled true \
    -e resourceCycleCount "$cycles" \
    "$test_package/androidx.test.runner.AndroidJUnitRunner")
instrumentation_status=$?
set -e
kill "$logcat_pid" >/dev/null 2>&1 || true
wait "$logcat_pid" >/dev/null 2>&1 || true
logcat_pid=""
resource_logcat=$(<"$logcat_capture")
combined_output=$(printf '%s\n%s\n' "$instrumentation_output" "$resource_logcat")
printf '%s\n' "$combined_output" > "$output_dir/resource-instrumentation.txt"

if (( instrumentation_status != 0 )) ||
    grep -Eq 'FAILURES!!!|INSTRUMENTATION_(FAILED|ABORTED)|shortMsg=|Process crashed' \
        <<<"$instrumentation_output"; then
    printf '%s\n' "$instrumentation_output"
    echo "resource-cycle instrumentation failed" >&2
    exit 1
fi

csv="$output_dir/resource-cycles.csv"
threads_csv="$output_dir/resource-threads.csv"
printf 'cycle,timestamp,state,fd,threads,java_heap_kb,native_heap_kb,pss_kb,rss_kb\n' > "$csv"
printf '%s\n' "$combined_output" |
    sed -n 's/.*RESOURCE_CSV,//p' >> "$csv"
printf 'cycle,actor_threads,lifecycle_threads\n' > "$threads_csv"
printf '%s\n' "$combined_output" |
    sed -n 's/.*RESOURCE_THREADS,//p' >> "$threads_csv"

if ! grep -q "RESOURCE_CYCLES_COMPLETED=$cycles" <<<"$combined_output"; then
    echo "resource-cycle completion marker is missing" >&2
    exit 1
fi

expected_rows=$((cycles + 1))
actual_rows=$(awk 'END { print NR - 1 }' "$csv")
thread_rows=$(awk 'END { print NR - 1 }' "$threads_csv")
if (( actual_rows != expected_rows || thread_rows != expected_rows )) || grep -q ',NA\($\|,\)' "$csv"; then
    echo "resource-cycle output is incomplete: samples=$actual_rows thread_samples=$thread_rows expected=$expected_rows" >&2
    exit 1
fi

summary="$output_dir/resource-summary.txt"
summarize_metric() {
    local name="$1"
    local column="$2"
    local temp_values="$output_dir/.resource-$column-values"
    tail -n +2 "$csv" | cut -d, -f"$column" | sort -n > "$temp_values"
    local count baseline minimum maximum final median p90 delta slope rises assessment
    count=$(awk 'END { print NR }' "$temp_values")
    baseline=$(awk -F, 'NR == 2 { print $'"$column"' }' "$csv")
    minimum=$(awk 'NR == 1 { print }' "$temp_values")
    maximum=$(awk 'END { print }' "$temp_values")
    median=$(awk -v row="$(( (count + 1) / 2 ))" 'NR == row { print }' "$temp_values")
    p90=$(awk -v row="$(( (count * 90 + 99) / 100 ))" 'NR == row { print }' "$temp_values")
    final=$(awk -F, 'END { print $'"$column"' }' "$csv")
    delta=$((final - baseline))
    slope=$(awk -F, -v column="$column" 'NR > 1 { x = $1; y = $column; n++; sx += x; sy += y; sxy += x*y; sx2 += x*x } END { denominator = n*sx2-sx*sx; if (denominator == 0) print "0.000"; else printf "%.3f", (n*sxy-sx*sy)/denominator }' "$csv")
    rises=$(awk -F, -v column="$column" 'NR == 2 { previous = $column; next } NR > 2 { if ($column > previous) rises++; previous = $column } END { print rises + 0 }' "$csv")
    assessment="stable"
    if awk -v rises="$rises" -v count="$count" -v delta="$delta" -v baseline="$baseline" 'BEGIN { exit !((rises >= (count-1)*0.9) && delta > baseline*0.25) }'; then
        assessment="leak likely"
    elif awk -v rises="$rises" -v count="$count" -v delta="$delta" -v baseline="$baseline" 'BEGIN { exit !((rises >= (count-1)*0.75) && delta > baseline*0.20) }'; then
        assessment="suspicious"
    elif (( delta != 0 )); then
        assessment="bounded drift"
    fi
    printf '%s baseline=%s min=%s median=%s p90=%s max=%s final=%s delta=%s slope_per_cycle=%s assessment=%s\n' \
        "$name" "$baseline" "$minimum" "$median" "$p90" "$maximum" "$final" "$delta" "$slope" "$assessment" >> "$summary"
    rm -f "$temp_values"
}

: > "$summary"
summarize_metric fd 4
summarize_metric threads 5
summarize_metric java_heap_kb 6
summarize_metric native_heap_kb 7
summarize_metric pss_kb 8
summarize_metric rss_kb 9
actor_max=$(tail -n +2 "$threads_csv" | cut -d, -f2 | sort -n | tail -1)
lifecycle_max=$(tail -n +2 "$threads_csv" | cut -d, -f3 | sort -n | tail -1)
actor_final=$(awk -F, 'END { print $2 }' "$threads_csv")
lifecycle_final=$(awk -F, 'END { print $3 }' "$threads_csv")
{
    printf 'actor_threads max=%s final=%s assessment=%s\n' "$actor_max" "$actor_final" "$([[ "$actor_max" == 0 ]] && echo stable || echo suspicious)"
    printf 'lifecycle_threads max=%s final=%s assessment=%s\n' "$lifecycle_max" "$lifecycle_final" "$([[ "$lifecycle_max" == 0 ]] && echo stable || echo suspicious)"
    printf 'native_thread_proxy=total /proc task count; Go runtime threads are process-shared and not required to reach zero\n'
} >> "$summary"

cat "$summary"
