#!/usr/bin/env bash
set -euo pipefail

package_name="${1:?package name is required}"
scenario="${2:?scenario name is required}"
requested_serial="${ANDROID_SERIAL:-}"
device_lines=$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')

if [[ ! "$package_name" =~ ^[A-Za-z0-9_.]+$ ]]; then
    echo "invalid package name" >&2
    exit 1
fi
if [[ "$scenario" == *","* || "$scenario" == *$'\n'* ]]; then
    echo "scenario must be a single CSV field" >&2
    exit 1
fi

if [[ -n "$requested_serial" ]]; then
    if ! printf '%s\n' "$device_lines" | awk -v serial="$requested_serial" '$0 == serial { found = 1 } END { exit !found }'; then
        echo "ANDROID_SERIAL does not identify an authorized device" >&2
        exit 1
    fi
    serial="$requested_serial"
else
    device_count=$(printf '%s\n' "$device_lines" | awk 'NF { count += 1 } END { print count + 0 }')
    if (( device_count != 1 )); then
        echo "resource sampling requires exactly one authorized device; found $device_count" >&2
        exit 1
    fi
    serial=$(printf '%s\n' "$device_lines" | awk 'NF { print; exit }')
fi

pid=$(adb -s "$serial" shell pidof "$package_name" | tr -d '\r' | awk '{ print $1 }')
if [[ ! "$pid" =~ ^[0-9]+$ ]]; then
    echo "package process is not running: $package_name" >&2
    exit 1
fi

fd_count=$(adb -s "$serial" shell run-as "$package_name" ls "/proc/$pid/fd" 2>/dev/null | awk 'NF { count += 1 } END { print count + 0 }')
thread_count=$(adb -s "$serial" shell run-as "$package_name" ls "/proc/$pid/task" 2>/dev/null | awk 'NF { count += 1 } END { print count + 0 }')
meminfo=$(adb -s "$serial" shell dumpsys meminfo "$package_name")
java_heap=$(printf '%s\n' "$meminfo" | awk '$1 == "Java" && $2 == "Heap:" { print $3; exit }')
native_heap=$(printf '%s\n' "$meminfo" | awk '$1 == "Native" && $2 == "Heap" { print $3; exit }')
total_pss=$(printf '%s\n' "$meminfo" | awk '$1 == "TOTAL" && $2 == "PSS:" { print $3; exit }')
total_rss=$(printf '%s\n' "$meminfo" | awk '$1 == "TOTAL" && $2 == "PSS:" && $4 == "TOTAL" && $5 == "RSS:" { print $6; exit }')

printf 'scenario,fd,threads,java_heap_kb,native_heap_kb,total_pss_kb,total_rss_kb\n'
printf '%s,%s,%s,%s,%s,%s,%s\n' \
    "$scenario" \
    "$fd_count" \
    "$thread_count" \
    "${java_heap:-NA}" \
    "${native_heap:-NA}" \
    "${total_pss:-NA}" \
    "${total_rss:-NA}"
