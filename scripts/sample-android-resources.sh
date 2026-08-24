#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=android-validation-common.sh
source "$script_dir/android-validation-common.sh"

package_name="${1:?package name is required}"
scenario="${2:?scenario name is required}"

if [[ ! "$package_name" =~ ^[A-Za-z0-9_.]+$ ]]; then
    echo "invalid package name" >&2
    exit 1
fi
if [[ "$scenario" == *","* || "$scenario" == *$'\n'* ]]; then
    echo "scenario must be a single CSV field" >&2
    exit 1
fi

serial=$(validation_resolve_serial)

pid=$(adb -s "$serial" shell pidof "$package_name" | tr -d '\r' | awk '{ print $1 }')
if [[ ! "$pid" =~ ^[0-9]+$ ]]; then
    echo "package process is not running: $package_name" >&2
    exit 1
fi

parse_failed="false"
if fd_entries=$(adb -s "$serial" shell run-as "$package_name" ls -1 "/proc/$pid/fd" 2>/dev/null); then
    fd_count=$(printf '%s\n' "$fd_entries" | awk 'NF { count += 1 } END { print count + 0 }')
else
    fd_count="NA"
    parse_failed="true"
fi
if thread_entries=$(adb -s "$serial" shell run-as "$package_name" ls -1 "/proc/$pid/task" 2>/dev/null); then
    thread_count=$(printf '%s\n' "$thread_entries" | awk 'NF { count += 1 } END { print count + 0 }')
else
    thread_count="NA"
    parse_failed="true"
fi
meminfo=$(adb -s "$serial" shell dumpsys meminfo "$package_name")
java_heap=$(printf '%s\n' "$meminfo" | awk '$1 == "Java" && $2 == "Heap:" && $3 ~ /^[0-9]+$/ { print $3; exit }')
native_heap=$(printf '%s\n' "$meminfo" | awk '$1 == "Native" && $2 == "Heap:" && $3 ~ /^[0-9]+$/ { print $3; exit }')
total_pss=$(printf '%s\n' "$meminfo" | awk '$1 == "TOTAL" && $2 == "PSS:" && $3 ~ /^[0-9]+$/ { print $3; exit }')
total_rss=$(printf '%s\n' "$meminfo" | awk '$1 == "TOTAL" && $2 == "PSS:" { for (i = 4; i <= NF; i++) if ($i == "RSS:" && $(i + 1) ~ /^[0-9]+$/) { print $(i + 1); exit } }')

for value in java_heap native_heap total_pss total_rss; do
    if [[ ! "${!value:-}" =~ ^[0-9]+$ ]]; then
        printf -v "$value" '%s' "NA"
        parse_failed="true"
    fi
done

printf 'scenario,fd,threads,java_heap_kb,native_heap_kb,total_pss_kb,total_rss_kb\n'
printf '%s,%s,%s,%s,%s,%s,%s\n' \
    "$scenario" \
    "$fd_count" \
    "$thread_count" \
    "${java_heap:-NA}" \
    "${native_heap:-NA}" \
    "${total_pss:-NA}" \
    "${total_rss:-NA}"

if [[ "$parse_failed" == "true" ]]; then
    echo "resource sample contains unavailable fields; NA is not accepted as valid data" >&2
    exit 2
fi
