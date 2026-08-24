#!/usr/bin/env bash

# Shared, fail-closed helpers for real-device validation runners.

validation_resolve_serial() {
    local requested_serial="${ANDROID_SERIAL:-}"
    local device_lines
    device_lines=$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
    if [[ -n "$requested_serial" ]]; then
        if ! printf '%s\n' "$device_lines" |
            awk -v serial="$requested_serial" '$0 == serial { found = 1 } END { exit !found }'; then
            echo "ANDROID_SERIAL does not identify an authorized device" >&2
            return 1
        fi
        printf '%s\n' "$requested_serial"
        return
    fi

    local device_count
    device_count=$(printf '%s\n' "$device_lines" | awk 'NF { count += 1 } END { print count + 0 }')
    if (( device_count != 1 )); then
        echo "validation requires exactly one authorized device; found $device_count" >&2
        return 1
    fi
    printf '%s\n' "$device_lines" | awk 'NF { print; exit }'
}

validation_find_sdk_tool() {
    local tool_name="$1"
    local candidate
    if command -v "$tool_name" >/dev/null 2>&1; then
        command -v "$tool_name"
        return
    fi
    for candidate in \
        "${ANDROID_HOME:-}/cmdline-tools/latest/bin/$tool_name" \
        "${ANDROID_SDK_ROOT:-}/cmdline-tools/latest/bin/$tool_name" \
        "$HOME/Library/Android/sdk/cmdline-tools/latest/bin/$tool_name"; do
        if [[ -x "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return
        fi
    done
    for candidate in \
        "${ANDROID_HOME:-}"/build-tools/*/"$tool_name" \
        "${ANDROID_SDK_ROOT:-}"/build-tools/*/"$tool_name" \
        "$HOME"/Library/Android/sdk/build-tools/*/"$tool_name"; do
        if [[ -x "$candidate" ]]; then
            printf '%s\n' "$candidate"
        fi
    done | sort -V | tail -1
}

validation_sha256() {
    shasum -a 256 "$1" | awk '{ print $1 }'
}

validation_read_appop_mode() {
    local serial="$1" package_name="$2" operation="$3"
    local mode
    mode=$(adb -s "$serial" shell appops get "$package_name" "$operation" 2>/dev/null |
        tr -d '\r' | sed -n "s/^$operation: \([a-z]*\).*/\1/p" | head -1)
    case "$mode" in
        allow|deny|ignore|default|foreground) printf '%s\n' "$mode" ;;
        *) printf 'default\n' ;;
    esac
}

validation_restore_appop_mode() {
    local serial="$1" package_name="$2" operation="$3" mode="$4"
    case "$mode" in
        allow|deny|ignore|default|foreground) ;;
        *) echo "invalid saved AppOps mode for $operation" >&2; return 1 ;;
    esac
    adb -s "$serial" shell appops set "$package_name" "$operation" "$mode" >/dev/null
}

validation_manifest_value() {
    local apkanalyzer="$1"
    local operation="$2"
    local apk="$3"
    JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null || true)}" \
        "$apkanalyzer" manifest "$operation" "$apk" 2>/dev/null | tr -d '\r' | tail -1
}

validation_verify_installed_apk() {
    local serial="$1"
    local expected_apk="$2"
    local expected_package="$3"
    local expected_target_package="${4:-}"
    local identity_output="$5"
    local apkanalyzer apksigner temp_dir package_paths installed_apk package_dump

    [[ -f "$expected_apk" ]] || {
        echo "REFUSE TO RUN: expected APK is missing: $expected_apk" >&2
        return 1
    }
    apkanalyzer=$(validation_find_sdk_tool apkanalyzer)
    apksigner=$(validation_find_sdk_tool apksigner)
    [[ -x "$apkanalyzer" && -x "$apksigner" ]] || {
        echo "REFUSE TO RUN: Android SDK apkanalyzer/apksigner is unavailable" >&2
        return 1
    }

    package_paths=$(adb -s "$serial" shell pm path "$expected_package" 2>/dev/null |
        tr -d '\r' | sed -n 's/^package://p')
    if [[ -z "$package_paths" ]]; then
        echo "REFUSE TO RUN: $expected_package is not installed" >&2
        return 1
    fi
    if (( $(printf '%s\n' "$package_paths" | awk 'NF { count += 1 } END { print count + 0 }') != 1 )); then
        echo "REFUSE TO RUN: $expected_package uses unexpected split APKs; exact monolithic identity cannot be proven" >&2
        return 1
    fi

    temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/tcptun-installed-apk.XXXXXX")
    installed_apk="$temp_dir/installed.apk"
    if ! adb -s "$serial" pull "$package_paths" "$installed_apk" >/dev/null; then
        rm -rf "$temp_dir"
        echo "REFUSE TO RUN: could not pull installed APK for $expected_package" >&2
        return 1
    fi

    local expected_id expected_version_code expected_version_name installed_id
    local installed_version_code installed_version_name expected_sha installed_sha
    expected_id=$(validation_manifest_value "$apkanalyzer" application-id "$expected_apk")
    expected_version_code=$(validation_manifest_value "$apkanalyzer" version-code "$expected_apk")
    expected_version_name=$(validation_manifest_value "$apkanalyzer" version-name "$expected_apk")
    installed_id=$(validation_manifest_value "$apkanalyzer" application-id "$installed_apk")
    installed_version_code=$(validation_manifest_value "$apkanalyzer" version-code "$installed_apk")
    installed_version_name=$(validation_manifest_value "$apkanalyzer" version-name "$installed_apk")
    expected_sha=$(validation_sha256 "$expected_apk")
    installed_sha=$(validation_sha256 "$installed_apk")

    local expected_cert installed_cert
    expected_cert=$("$apksigner" verify --print-certs "$expected_apk" 2>/dev/null |
        sed -n 's/^.*certificate SHA-256 digest: //p' | head -1)
    installed_cert=$("$apksigner" verify --print-certs "$installed_apk" 2>/dev/null |
        sed -n 's/^.*certificate SHA-256 digest: //p' | head -1)

    package_dump=$(adb -s "$serial" shell dumpsys package "$expected_package" | tr -d '\r')
    local device_version_code device_version_name
    device_version_code=$(printf '%s\n' "$package_dump" |
        sed -n 's/^[[:space:]]*versionCode=\([0-9][0-9]*\).*/\1/p' | head -1)
    device_version_name=$(printf '%s\n' "$package_dump" |
        sed -n 's/^[[:space:]]*versionName=//p' | head -1)

    local refusal=""
    [[ "$expected_id" == "$expected_package" ]] || refusal="local package is $expected_id"
    [[ "$installed_id" == "$expected_package" ]] || refusal="installed package is $installed_id"
    [[ "$installed_version_code" == "$expected_version_code" ]] || refusal="installed APK versionCode mismatch"
    [[ "$installed_version_name" == "$expected_version_name" ]] || refusal="installed APK versionName mismatch"
    if [[ "$expected_version_code" == "UNKNOWN" ]]; then
        [[ "$device_version_code" == "0" ]] || refusal="PackageManager test versionCode mismatch"
    else
        [[ "$device_version_code" == "$expected_version_code" ]] || refusal="PackageManager versionCode mismatch"
    fi
    if [[ "$expected_version_name" == "UNKNOWN" ]]; then
        [[ "$device_version_name" == "null" ]] || refusal="PackageManager test versionName mismatch"
    else
        [[ "$device_version_name" == "$expected_version_name" ]] || refusal="PackageManager versionName mismatch"
    fi
    [[ "$installed_sha" == "$expected_sha" ]] || refusal="installed APK SHA-256 mismatch"
    [[ -n "$expected_cert" && "$installed_cert" == "$expected_cert" ]] || refusal="signing certificate mismatch"
    if ! grep -Eq '(pkgFlags|flags)=\[[^]]*DEBUGGABLE' <<<"$package_dump"; then
        refusal="installed package is not debuggable"
    fi
    if [[ -n "$expected_target_package" ]]; then
        local actual_target
        actual_target=$(JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null || true)}" \
            "$apkanalyzer" manifest print "$installed_apk" 2>/dev/null |
            sed -n 's/.*android:targetPackage="\([^"]*\)".*/\1/p' | head -1)
        [[ "$actual_target" == "$expected_target_package" ]] || refusal="test APK target package mismatch"
    fi

    if [[ -n "$refusal" ]]; then
        rm -rf "$temp_dir"
        echo "REFUSE TO RUN: $expected_package identity check failed: $refusal" >&2
        return 1
    fi

    {
        printf 'package=%s\n' "$expected_package"
        printf 'versionCode=%s\n' "$expected_version_code"
        printf 'versionName=%s\n' "$expected_version_name"
        printf 'debuggable=true\n'
        printf 'apkSha256=%s\n' "$expected_sha"
        printf 'signingCertificateSha256=%s\n' "$expected_cert"
        [[ -z "$expected_target_package" ]] || printf 'targetPackage=%s\n' "$expected_target_package"
        printf 'identity=PASS\n'
    } | tee -a "$identity_output"
    rm -rf "$temp_dir"
}

validation_verify_bridge_identity() {
    local expected_apk="$1"
    local device_abi="$2"
    local identity_output="$3"
    local aar="app/libs/androidbridge.aar"
    local lock="bridge.lock"
    local metadata core_commit core_version bridge_api lock_commit lock_api apk_lib_sha aar_lib_sha

    metadata=$(unzip -p "$aar" bridge-version.properties 2>/dev/null) || {
        echo "REFUSE TO RUN: bridge metadata is unavailable" >&2
        return 1
    }
    core_commit=$(printf '%s\n' "$metadata" | sed -n 's/^coreCommit=//p')
    core_version=$(printf '%s\n' "$metadata" | sed -n 's/^coreVersion=//p')
    bridge_api=$(printf '%s\n' "$metadata" | sed -n 's/^bridgeApiVersion=//p')
    lock_commit=$(sed -n 's/^coreCommit=//p' "$lock")
    lock_api=$(sed -n 's/^bridgeApiVersion=//p' "$lock")
    [[ -n "$core_commit" && "$core_commit" == "$lock_commit" ]] || {
        echo "REFUSE TO RUN: Bridge core build ID does not match bridge.lock" >&2
        return 1
    }
    [[ -n "$bridge_api" && "$bridge_api" == "$lock_api" ]] || {
        echo "REFUSE TO RUN: Bridge API does not match bridge.lock" >&2
        return 1
    }
    apk_lib_sha=$(unzip -p "$expected_apk" "lib/$device_abi/libgojni.so" | shasum -a 256 | awk '{ print $1 }')
    aar_lib_sha=$(unzip -p "$aar" "jni/$device_abi/libgojni.so" | shasum -a 256 | awk '{ print $1 }')
    [[ -n "$apk_lib_sha" && "$apk_lib_sha" == "$aar_lib_sha" ]] || {
        echo "REFUSE TO RUN: packaged Bridge binary does not match verified AAR for $device_abi" >&2
        return 1
    }
    {
        printf 'bridgeCoreCommit=%s\n' "$core_commit"
        printf 'bridgeCoreVersion=%s\n' "$core_version"
        printf 'bridgeApiVersion=%s\n' "$bridge_api"
        printf 'bridgeBinarySha256=%s\n' "$apk_lib_sha"
        printf 'bridgeIdentity=PASS\n'
    } | tee -a "$identity_output"
}
