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
alice_password="${TCPTUN_TEST_PROXY_ALICE_PASSWORD:-${TCPTUN_TEST_PROXY_PASSWORD:-}}"
bob_password="${TCPTUN_TEST_PROXY_BOB_PASSWORD:-}"
[[ -n "$alice_password" ]] || { echo "TCPTUN_TEST_PROXY_ALICE_PASSWORD is required" >&2; exit 1; }
[[ -n "$bob_password" ]] || { echo "TCPTUN_TEST_PROXY_BOB_PASSWORD is required" >&2; exit 1; }
(( ${#alice_password} <= 200 && ${#bob_password} <= 200 )) || {
    echo "LAN validation password is too long" >&2
    exit 1
}

serial=$(validation_resolve_serial)
output_dir="${OUTPUT_DIR:-build/validation-gate}"
port="${TCPTUN_TEST_PROXY_PORT:-19080}"
debug_package="com.tcptun.client.debug"
test_package="com.tcptun.client.debug.test"
debug_apk="app/build/outputs/apk/debug/app-debug.apk"
test_apk="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
mkdir -p "$output_dir"
command -v python3 >/dev/null || { echo "python3 is required for SOCKS5 UDP validation" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq is required for Secure Auth V2 validation" >&2; exit 1; }
validation_go_dir="${TCPTUN_GO_DIR:-$repo_dir/../tcptun-go}"
locked_core=$(sed -n 's/^coreCommit=//p' bridge.lock)
[[ "$(git -C "$validation_go_dir" rev-parse HEAD)" == "$locked_core" ]] || {
    echo "TCPTUN_GO_DIR must be checked out at bridge.lock for Secure Auth V2 validation" >&2
    exit 1
}
[[ -z "$(git -C "$validation_go_dir" status --porcelain --untracked-files=normal)" ]] || {
    echo "TCPTUN_GO_DIR must be clean for Secure Auth V2 validation" >&2
    exit 1
}
make -C "$validation_go_dir" build >/dev/null
tcptun_go_bin="$validation_go_dir/bin/tcptun"
[[ -x "$tcptun_go_bin" ]] || { echo "locked tcptun-go client binary is unavailable" >&2; exit 1; }

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
secure_client_pid=""
secure_config_dir=""
cleanup() {
    local status=$?
    trap - EXIT
    trap '' INT TERM
    set +e
    if [[ -n "$instrumentation_pid" ]]; then
        kill "$instrumentation_pid" >/dev/null 2>&1
        wait "$instrumentation_pid" >/dev/null 2>&1
    fi
    if [[ -n "$secure_client_pid" ]]; then kill "$secure_client_pid" >/dev/null 2>&1; fi
    if [[ -n "$secure_config_dir" ]]; then rm -rf "$secure_config_dir"; fi
    adb -s "$serial" shell setprop debug.tcptun.lan.lb none
    adb -s "$serial" shell setprop debug.tcptun.lan.auth none
    adb -s "$serial" shell setprop debug.tcptun.lan.persist none
    adb -s "$serial" shell setprop debug.tcptun.lan.mixed none
    adb -s "$serial" shell setprop debug.tcptun.lan.mixpersist none
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

https_probe_url="${TCPTUN_TEST_HTTPS_PROBE_URL:-${TCPTUN_TEST_PROBE_URL:-https://www.gstatic.com/generate_204}}"
http_probe_url="${TCPTUN_TEST_HTTP_PROBE_URL:-http://connectivitycheck.gstatic.com/generate_204}"
proxy_url="http://$device_ip:$port"
probe_socks() {
    local credential="$1"
    if [[ -n "$credential" ]]; then
        curl --silent --show-error --noproxy "" --max-time 15 --socks5-hostname "$device_ip:$port" \
            --proxy-user "$credential" --output /dev/null "$https_probe_url"
    else
        curl --silent --show-error --noproxy "" --max-time 8 --socks5-hostname "$device_ip:$port" \
            --output /dev/null "$https_probe_url"
    fi
}

proxy_http_code() {
    local credential="$1"
    if [[ -n "$credential" ]]; then
        curl --silent --show-error --noproxy "" --max-time 15 --proxy "$proxy_url" \
            --proxy-user "$credential" --output /dev/null --write-out '%{http_code}' "$http_probe_url" || true
    else
        curl --silent --show-error --noproxy "" --max-time 8 --proxy "$proxy_url" \
            --output /dev/null --write-out '%{http_code}' "$http_probe_url" || true
    fi
}

probe_http() {
    curl --silent --show-error --fail --noproxy "" --max-time 15 --proxy "$proxy_url" \
        --proxy-user "$1" --output /dev/null "$http_probe_url"
}

proxy_connect_code() {
    local credential="$1"
    if [[ -n "$credential" ]]; then
        curl --silent --show-error --noproxy "" --max-time 20 --proxy "$proxy_url" \
            --proxy-user "$credential" --output /dev/null --write-out '%{http_connect}' "$https_probe_url" || true
    else
        curl --silent --show-error --noproxy "" --max-time 10 --proxy "$proxy_url" \
            --output /dev/null --write-out '%{http_connect}' "$https_probe_url" || true
    fi
}

probe_https_connect() {
    curl --silent --show-error --fail --noproxy "" --max-time 20 --proxy "$proxy_url" \
        --proxy-user "$1" --output /dev/null "$https_probe_url"
}

probe_udp() {
    python3 "$script_dir/probe-socks5-udp.py" "$device_ip" "$port" "$1" "$2"
}

probe_secure_v2() {
    local username="$1" password_value="$2" local_port="$3"
    secure_config_dir=$(mktemp -d "${TMPDIR:-/tmp}/tcptun-secure-v2.XXXXXX")
    jq -n \
        --arg local_address "127.0.0.1:$local_port" \
        --arg remote_address "$device_ip:$port" \
        --arg username "$username" \
        --arg password "$password_value" \
        '{inbounds:[{tag:"local",type:"socks5",address:[$local_address],network:["tcp","udp"]}],outbounds:[{tag:"proxy",type:"socks5",address:[$remote_address],network:["tcp","udp"],username:$username,password:$password,auth_mode:"secure"}],route:{default_outbound:"proxy",rules:[]}}' \
        > "$secure_config_dir/client.json"
    "$tcptun_go_bin" -c "$secure_config_dir/client.json" > "$secure_config_dir/client.log" 2>&1 &
    secure_client_pid=$!
    local deadline=$((SECONDS + 15))
    until curl --silent --show-error --noproxy "" --max-time 2 --socks5-hostname "127.0.0.1:$local_port" --output /dev/null "$https_probe_url"; do
        (( SECONDS < deadline )) || { cat "$secure_config_dir/client.log" >&2; return 1; }
        sleep 1
    done
    python3 "$script_dir/probe-socks5-udp.py" 127.0.0.1 "$local_port"
    kill "$secure_client_pid" >/dev/null 2>&1 || true
    wait "$secure_client_pid" >/dev/null 2>&1 || true
    secure_client_pid=""
    rm -rf "$secure_config_dir"
    secure_config_dir=""
}

reject_secure_v2() {
    local username="$1" password_value="$2" local_port="$3"
    secure_config_dir=$(mktemp -d "${TMPDIR:-/tmp}/tcptun-secure-v2-reject.XXXXXX")
    jq -n \
        --arg local_address "127.0.0.1:$local_port" \
        --arg remote_address "$device_ip:$port" \
        --arg username "$username" \
        --arg password "$password_value" \
        '{inbounds:[{tag:"local",type:"socks5",address:[$local_address],network:["tcp"]}],outbounds:[{tag:"proxy",type:"socks5",address:[$remote_address],network:["tcp"],username:$username,password:$password,auth_mode:"secure"}],route:{default_outbound:"proxy",rules:[]}}' \
        > "$secure_config_dir/client.json"
    "$tcptun_go_bin" -c "$secure_config_dir/client.json" > "$secure_config_dir/client.log" 2>&1 &
    secure_client_pid=$!
    sleep 1
    local accepted=false
    if curl --silent --show-error --noproxy "" --max-time 5 --socks5-hostname "127.0.0.1:$local_port" \
        --output /dev/null "$https_probe_url"; then
        accepted=true
    fi
    kill "$secure_client_pid" >/dev/null 2>&1 || true
    wait "$secure_client_pid" >/dev/null 2>&1 || true
    secure_client_pid=""
    rm -rf "$secure_config_dir"
    secure_config_dir=""
    [[ "$accepted" == false ]]
}

adb -s "$serial" logcat -c
instrumentation_output="$output_dir/lan-instrumentation.txt"
adb -s "$serial" shell am instrument -w -r \
    -e class com.tcptun.client.LanAuthValidationTest \
    -e lanAuthValidationEnabled true \
    -e lanAuthValidationPort "$port" \
    -e lanAuthValidationAlicePassword "$alice_password" \
    -e lanAuthValidationBobPassword "$bob_password" \
    "$test_package/androidx.test.runner.AndroidJUnitRunner" > "$instrumentation_output" &
instrumentation_pid=$!

wait_for_phase LOOPBACK_ONLY_READY
if probe_socks "" >/dev/null 2>&1; then
    echo "listenAll=false unexpectedly accepted non-loopback access" >&2
    exit 1
fi
loopback_result="PASS (LAN access refused)"
adb -s "$serial" shell setprop debug.tcptun.lan.lb ready

wait_for_phase AUTH_REQUIRED_READY
if probe_socks "" >/dev/null 2>&1; then
    echo "listenAll=true unexpectedly accepted anonymous access" >&2
    exit 1
fi
anonymous_result="PASS (authentication refused)"
if probe_socks "alice:wrong" >/dev/null 2>&1; then
    echo "listenAll=true unexpectedly accepted a wrong password" >&2
    exit 1
fi
wrong_result="PASS (authentication refused)"
if probe_socks "unknown:$alice_password" >/dev/null 2>&1; then
    echo "listenAll=true unexpectedly accepted an unknown username" >&2
    exit 1
fi
unknown_result="PASS (authentication refused)"
if probe_udp alice wrong >/dev/null 2>&1; then
    echo "listenAll=true UDP unexpectedly accepted a wrong password" >&2
    exit 1
fi
if probe_udp unknown "$alice_password" >/dev/null 2>&1; then
    echo "listenAll=true UDP unexpectedly accepted an unknown username" >&2
    exit 1
fi
probe_socks "alice:$alice_password" >/dev/null
probe_socks "bob:$bob_password" >/dev/null
probe_udp alice "$alice_password"
probe_udp bob "$bob_password"
probe_secure_v2 alice "$alice_password" "${TCPTUN_TEST_SECURE_PORT_ALICE:-29080}"
probe_secure_v2 bob "$bob_password" "${TCPTUN_TEST_SECURE_PORT_BOB:-29081}"
reject_secure_v2 alice wrong "${TCPTUN_TEST_SECURE_PORT_REJECT:-29082}"
reject_secure_v2 unknown "$alice_password" "${TCPTUN_TEST_SECURE_PORT_REJECT:-29082}"
alice_correct_result="PASS"
bob_correct_result="PASS"
udp_result="PASS (alice and bob)"
secure_v2_result="PASS (alice and bob TCP/UDP)"
secure_v2_rejection_result="PASS (wrong secret and unknown username)"
adb -s "$serial" shell setprop debug.tcptun.lan.auth ready

wait_for_phase PERSISTED_RESTART_READY
probe_socks "alice:$alice_password" >/dev/null
probe_socks "bob:$bob_password" >/dev/null
persistence_result="PASS"
adb -s "$serial" shell setprop debug.tcptun.lan.persist ready

wait_for_phase MIXED_AUTH_REQUIRED_READY
if probe_socks "" >/dev/null 2>&1; then
    echo "mixed SOCKS unexpectedly accepted anonymous access" >&2
    exit 1
fi
mixed_socks_anonymous_result="PASS (authentication refused)"
if probe_socks "alice:wrong" >/dev/null 2>&1; then
    echo "mixed SOCKS unexpectedly accepted wrong credentials" >&2
    exit 1
fi
mixed_socks_wrong_result="PASS (authentication refused)"
if probe_socks "unknown:$alice_password" >/dev/null 2>&1; then
    echo "mixed SOCKS unexpectedly accepted an unknown username" >&2
    exit 1
fi
mixed_socks_unknown_result="PASS (authentication refused)"
if probe_udp alice wrong >/dev/null 2>&1; then
    echo "mixed SOCKS UDP unexpectedly accepted a wrong password" >&2
    exit 1
fi
if probe_udp unknown "$alice_password" >/dev/null 2>&1; then
    echo "mixed SOCKS UDP unexpectedly accepted an unknown username" >&2
    exit 1
fi
probe_socks "alice:$alice_password" >/dev/null
probe_socks "bob:$bob_password" >/dev/null
probe_udp alice "$alice_password"
probe_udp bob "$bob_password"
probe_secure_v2 alice "$alice_password" "${TCPTUN_TEST_SECURE_PORT_ALICE:-29080}"
probe_secure_v2 bob "$bob_password" "${TCPTUN_TEST_SECURE_PORT_BOB:-29081}"
reject_secure_v2 alice wrong "${TCPTUN_TEST_SECURE_PORT_REJECT:-29082}"
reject_secure_v2 unknown "$alice_password" "${TCPTUN_TEST_SECURE_PORT_REJECT:-29082}"
mixed_socks_alice_result="PASS"
mixed_socks_bob_result="PASS"
mixed_udp_result="PASS (alice and bob)"
mixed_secure_v2_result="PASS (alice and bob TCP/UDP)"
mixed_secure_v2_rejection_result="PASS (wrong secret and unknown username)"

[[ "$(proxy_http_code "")" == "407" ]] || {
    echo "mixed HTTP proxy did not return 407 for anonymous access" >&2
    exit 1
}
mixed_http_anonymous_result="PASS (HTTP 407)"
[[ "$(proxy_http_code "alice:wrong")" == "407" ]] || {
    echo "mixed HTTP proxy did not return 407 for wrong credentials" >&2
    exit 1
}
mixed_http_wrong_result="PASS (HTTP 407)"
[[ "$(proxy_http_code "unknown:$alice_password")" == "407" ]] || {
    echo "mixed HTTP proxy did not return 407 for unknown username" >&2
    exit 1
}
mixed_http_unknown_result="PASS (HTTP 407)"
probe_http "alice:$alice_password"
probe_http "bob:$bob_password"
mixed_http_alice_result="PASS"
mixed_http_bob_result="PASS"

[[ "$(proxy_connect_code "")" == "407" ]] || {
    echo "mixed HTTPS CONNECT did not return 407 for anonymous access" >&2
    exit 1
}
mixed_connect_anonymous_result="PASS (HTTP 407)"
[[ "$(proxy_connect_code "alice:wrong")" == "407" ]] || {
    echo "mixed HTTPS CONNECT did not return 407 for wrong credentials" >&2
    exit 1
}
mixed_connect_wrong_result="PASS (HTTP 407)"
[[ "$(proxy_connect_code "unknown:$alice_password")" == "407" ]] || {
    echo "mixed HTTPS CONNECT did not return 407 for unknown username" >&2
    exit 1
}
mixed_connect_unknown_result="PASS (HTTP 407)"
probe_https_connect "alice:$alice_password"
probe_https_connect "bob:$bob_password"
mixed_connect_alice_result="PASS"
mixed_connect_bob_result="PASS"
adb -s "$serial" shell setprop debug.tcptun.lan.mixed ready

wait_for_phase MIXED_PERSISTED_RESTART_READY
probe_socks "alice:$alice_password" >/dev/null
probe_socks "bob:$bob_password" >/dev/null
mixed_persistence_socks_result="PASS"
probe_http "alice:$alice_password"
probe_http "bob:$bob_password"
mixed_persistence_http_result="PASS"
probe_https_connect "alice:$alice_password"
probe_https_connect "bob:$bob_password"
mixed_persistence_connect_result="PASS"
adb -s "$serial" shell setprop debug.tcptun.lan.mixpersist ready

wait "$instrumentation_pid"
instrumentation_pid=""
if ! grep -Eq 'OK \(1 test\)' "$instrumentation_output"; then
    cat "$instrumentation_output"
    echo "LAN instrumentation failed" >&2
    exit 1
fi

alice_fingerprint=$(printf '%s' "$alice_password" | shasum -a 256 | awk '{ print substr($1, 1, 12) }')
bob_fingerprint=$(printf '%s' "$bob_password" | shasum -a 256 | awk '{ print substr($1, 1, 12) }')
report="$output_dir/lan-auth.txt"
{
    printf 'transport=physical non-loopback Wi-Fi IPv4\n'
    printf 'listenAll=false non_loopback=%s\n' "$loopback_result"
    printf 'listenAll=true anonymous=%s\n' "$anonymous_result"
    printf 'listenAll=true wrong_password=%s\n' "$wrong_result"
    printf 'listenAll=true unknown_username=%s\n' "$unknown_result"
    printf 'listenAll=true alice_tcp=%s\n' "$alice_correct_result"
    printf 'listenAll=true bob_tcp=%s\n' "$bob_correct_result"
    printf 'listenAll=true socks5_udp=%s\n' "$udp_result"
    printf 'listenAll=true secure_auth_v2=%s\n' "$secure_v2_result"
    printf 'listenAll=true secure_auth_v2_rejection=%s\n' "$secure_v2_rejection_result"
    printf 'password_persistence_stop_start=%s\n' "$persistence_result"
    printf 'mixed_socks_anonymous=%s\n' "$mixed_socks_anonymous_result"
    printf 'mixed_socks_wrong_password=%s\n' "$mixed_socks_wrong_result"
    printf 'mixed_socks_unknown_username=%s\n' "$mixed_socks_unknown_result"
    printf 'mixed_socks_alice=%s\n' "$mixed_socks_alice_result"
    printf 'mixed_socks_bob=%s\n' "$mixed_socks_bob_result"
    printf 'mixed_socks_udp=%s\n' "$mixed_udp_result"
    printf 'mixed_secure_auth_v2=%s\n' "$mixed_secure_v2_result"
    printf 'mixed_secure_auth_v2_rejection=%s\n' "$mixed_secure_v2_rejection_result"
    printf 'mixed_http_anonymous=%s\n' "$mixed_http_anonymous_result"
    printf 'mixed_http_wrong_password=%s\n' "$mixed_http_wrong_result"
    printf 'mixed_http_unknown_username=%s\n' "$mixed_http_unknown_result"
    printf 'mixed_http_alice=%s\n' "$mixed_http_alice_result"
    printf 'mixed_http_bob=%s\n' "$mixed_http_bob_result"
    printf 'mixed_https_connect_anonymous=%s\n' "$mixed_connect_anonymous_result"
    printf 'mixed_https_connect_wrong_password=%s\n' "$mixed_connect_wrong_result"
    printf 'mixed_https_connect_unknown_username=%s\n' "$mixed_connect_unknown_result"
    printf 'mixed_https_connect_alice=%s\n' "$mixed_connect_alice_result"
    printf 'mixed_https_connect_bob=%s\n' "$mixed_connect_bob_result"
    printf 'mixed_persistence_socks=%s\n' "$mixed_persistence_socks_result"
    printf 'mixed_persistence_http=%s\n' "$mixed_persistence_http_result"
    printf 'mixed_persistence_https_connect=%s\n' "$mixed_persistence_connect_result"
    printf 'alice_password_fingerprint_sha256_prefix=%s\n' "$alice_fingerprint"
    printf 'bob_password_fingerprint_sha256_prefix=%s\n' "$bob_fingerprint"
    printf 'legacy_migration=NOT_RUN\n'
} > "$report"
cat "$report"
