#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_dir=$(cd "$script_dir/.." && pwd)
cd "$repo_dir"
# shellcheck source=android-validation-common.sh
source "$script_dir/android-validation-common.sh"

scenario="${1:-}"
duration_seconds="${2:-${POWER_VALIDATION_DURATION_SECONDS:-600}}"
package_name="${POWER_VALIDATION_PACKAGE:-com.tcptun.client.debug}"
apk_path="${POWER_VALIDATION_APK:-app/build/outputs/apk/debug/app-debug.apk}"

case "$scenario" in
    stable-foreground-idle|stable-background-screen-off|offline-background|network-recovery|flow-disabled-background|flow-enabled-sustained|sparse-after-idle|quic-idle-burst-idle) ;;
    *)
        echo "usage: $0 SCENARIO [duration-seconds]" >&2
        echo "scenarios: stable-foreground-idle, stable-background-screen-off, offline-background," >&2
        echo "  network-recovery, flow-disabled-background, flow-enabled-sustained," >&2
        echo "  sparse-after-idle, quic-idle-burst-idle" >&2
        exit 2
        ;;
esac
[[ "$duration_seconds" =~ ^[1-9][0-9]*$ ]] || { echo "duration must be a positive integer" >&2; exit 2; }
[[ "$package_name" =~ ^[A-Za-z0-9_.]+$ ]] || { echo "invalid package name" >&2; exit 2; }

serial=$(validation_resolve_serial)
timestamp=$(date -u +%Y%m%dT%H%M%SZ)
kotlin_commit=$(git rev-parse --short=12 HEAD)
core_commit=$(unzip -p app/libs/androidbridge.aar bridge-version.properties 2>/dev/null |
    sed -n 's/^coreCommit=//p' | head -1)
core_commit="${core_commit:-unknown-core}"
output_root="${POWER_VALIDATION_OUTPUT_DIR:-build/power-validation}"
output_dir="$output_root/${scenario}-${timestamp}-app-${kotlin_commit}-core-${core_commit:0:12}"
mkdir -p "$output_dir"

pid=$(adb -s "$serial" shell pidof "$package_name" | tr -d '\r' | awk '{ print $1 }')
[[ "$pid" =~ ^[0-9]+$ ]] || { echo "$package_name is not running" >&2; exit 1; }

apk_sha="missing"
if [[ -f "$apk_path" ]]; then
    apk_sha=$(validation_sha256 "$apk_path")
fi
{
    printf 'scenario=%s\n' "$scenario"
    printf 'startedUtc=%s\n' "$timestamp"
    printf 'durationSeconds=%s\n' "$duration_seconds"
    printf 'deviceSerial=%s\n' "$serial"
    printf 'package=%s\n' "$package_name"
    printf 'pidAtStart=%s\n' "$pid"
    printf 'appCommit=%s\n' "$kotlin_commit"
    printf 'coreCommit=%s\n' "$core_commit"
    printf 'apkPath=%s\n' "$apk_path"
    printf 'apkSha256=%s\n' "$apk_sha"
} > "$output_dir/identity.txt"

capture_process() {
    local label="$1" current_pid
    current_pid=$(adb -s "$serial" shell pidof "$package_name" | tr -d '\r' | awk '{ print $1 }')
    adb -s "$serial" shell top -b -n 1 -H -p "${current_pid:-$pid}" > "$output_dir/top-${label}.txt" 2>&1 || true
    adb -s "$serial" shell dumpsys cpuinfo > "$output_dir/cpuinfo-${label}.txt" 2>&1 || true
    adb -s "$serial" shell dumpsys meminfo "$package_name" > "$output_dir/meminfo-${label}.txt" 2>&1 || true
    if [[ "$current_pid" =~ ^[0-9]+$ ]]; then
        adb -s "$serial" shell run-as "$package_name" cat "/proc/$current_pid/stat" > "$output_dir/proc-stat-${label}.txt" 2>&1 || true
        adb -s "$serial" shell run-as "$package_name" cat "/proc/$current_pid/status" > "$output_dir/proc-status-${label}.txt" 2>&1 || true
        adb -s "$serial" shell run-as "$package_name" cat "/proc/$current_pid/sched" > "$output_dir/proc-sched-${label}.txt" 2>&1 || true
        adb -s "$serial" shell run-as "$package_name" sh -c "for task in /proc/$current_pid/task/*; do cat \"\$task/stat\"; done" > "$output_dir/thread-stats-${label}.txt" 2>&1 || true
    fi
}

adb -s "$serial" shell dumpsys batterystats --reset > "$output_dir/batterystats-reset.txt" 2>&1
adb -s "$serial" shell log -t TCPTUN_POWER "START $scenario $timestamp" || true
capture_process start
adb -s "$serial" shell dumpsys power > "$output_dir/power-start.txt"
adb -s "$serial" shell dumpsys netstats detail > "$output_dir/netstats-start.txt" 2>&1 || true

perfetto_pid=""
perfetto_remote="/data/local/tmp/tcptun-power-${timestamp}.perfetto-trace"
if [[ "${POWER_VALIDATION_PERFETTO:-false}" == "true" ]]; then
    adb -s "$serial" shell perfetto --txt -c - --time "${duration_seconds}s" -o "$perfetto_remote" \
        < "$script_dir/power-validation.perfetto.pbtxt" > "$output_dir/perfetto-command.txt" 2>&1 &
    perfetto_pid=$!
fi

echo "Collecting '$scenario' for ${duration_seconds}s into $output_dir"
echo "Prepare the named foreground/background/network/traffic state now; the script does not mutate radios."
sleep "$duration_seconds"

if [[ -n "$perfetto_pid" ]]; then
    wait "$perfetto_pid" || true
    adb -s "$serial" pull "$perfetto_remote" "$output_dir/trace.perfetto-trace" > "$output_dir/perfetto-pull.txt" 2>&1 || true
    adb -s "$serial" shell rm -f "$perfetto_remote" || true
fi

ended_timestamp=$(date -u +%Y%m%dT%H%M%SZ)
adb -s "$serial" shell log -t TCPTUN_POWER "END $scenario $ended_timestamp" || true
capture_process end
adb -s "$serial" shell dumpsys batterystats --charged > "$output_dir/batterystats.txt"
adb -s "$serial" shell dumpsys power > "$output_dir/power-end.txt"
adb -s "$serial" shell dumpsys alarm > "$output_dir/alarms.txt" 2>&1 || true
adb -s "$serial" shell dumpsys jobscheduler > "$output_dir/jobscheduler.txt" 2>&1 || true
adb -s "$serial" shell dumpsys netstats detail > "$output_dir/netstats-end.txt" 2>&1 || true
adb -s "$serial" shell cat /sys/kernel/debug/wakeup_sources > "$output_dir/wakeup-sources.txt" 2>&1 || true
adb -s "$serial" shell cat /proc/net/xt_qtaguid/stats > "$output_dir/xt-qtaguid-stats.txt" 2>&1 || true
adb -s "$serial" logcat -d -v threadtime -s TCPTUN_POWER TcptunVpnService > "$output_dir/logcat.txt" 2>&1 || true
printf 'endedUtc=%s\n' "$ended_timestamp" >> "$output_dir/identity.txt"

echo "Power validation capture complete: $output_dir"
