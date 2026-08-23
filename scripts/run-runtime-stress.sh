#!/usr/bin/env bash
set -euo pipefail

iterations="${RUNTIME_STRESS_ITERATIONS:-500}"
seed="${RUNTIME_STRESS_SEED:-1592622103}"
max_delay_millis="${RUNTIME_STRESS_MAX_DELAY_MILLIS:-200}"
network_control="${RUNTIME_STRESS_NETWORK_CONTROL:-false}"
system_events="${RUNTIME_STRESS_SYSTEM_EVENTS:-false}"

device_lines=$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
device_count=$(printf '%s\n' "$device_lines" | awk 'NF { count += 1 } END { print count + 0 }')
if (( device_count != 1 )); then
    echo "runtime stress requires exactly one authorized device; found $device_count" >&2
    exit 1
fi

serial=$(printf '%s\n' "$device_lines" | awk 'NF { print; exit }')
echo "runtime stress device: $serial"
adb -s "$serial" shell getprop ro.product.manufacturer
adb -s "$serial" shell getprop ro.product.model
adb -s "$serial" shell getprop ro.build.version.release
adb -s "$serial" shell getprop ro.build.version.sdk
adb -s "$serial" shell getprop ro.product.cpu.abi

if [[ "$network_control" == "true" ]]; then
    echo "network control enabled; use USB ADB because the test disables Wi-Fi"
fi

ANDROID_SERIAL="$serial" ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.tcptun.client.VpnRuntimeStressTest,com.tcptun.client.VpnNetworkHandoverStressTest \
    -Pandroid.testInstrumentationRunnerArguments.runtimeStressEnabled=true \
    -Pandroid.testInstrumentationRunnerArguments.runtimeStressIterations="$iterations" \
    -Pandroid.testInstrumentationRunnerArguments.runtimeStressSeed="$seed" \
    -Pandroid.testInstrumentationRunnerArguments.runtimeStressMaxDelayMillis="$max_delay_millis" \
    -Pandroid.testInstrumentationRunnerArguments.runtimeStressNetworkControl="$network_control" \
    -Pandroid.testInstrumentationRunnerArguments.runtimeStressSystemEvents="$system_events"
