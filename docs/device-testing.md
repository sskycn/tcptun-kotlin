# Real-device validation

This document is the repeatable manual/instrumentation checklist for VPN behavior that JVM
tests cannot prove. Record:

```text
device model:
Android version/API:
ABI:
app version/build:
core version/build ID:
result:
logs/diagnostics:
```

## Install and permissions

1. Build and install `app/build/outputs/apk/debug/app-debug.apk`.
2. Clear the previous app data for a clean-install pass.
3. Grant/approve the VPN prompt.
4. Start a known direct profile and verify the foreground notification.
5. Confirm Diagnostics reports `Running`, a session ID, MTU, network, and core identity.

## Start/stop and recreation

1. Start and stop the same profile three times.
2. Stop while the app is backgrounded.
3. Start, press Home, turn the screen off, wait at least two minutes, and resume.
4. Kill/reopen the Activity while keeping the VPN service alive.
5. Stop during startup and during reconnect/cleanup.
6. Verify no duplicate notification, engine, TUN, or stale error appears.

The existing `VpnServiceLifecycleTest` covers repeated real-AAR start/stop when an emulator is
available. Manual runs are still required for OEM lifecycle behavior.

## Network handover

1. Start on Wi-Fi and record the session ID.
2. Switch to cellular, wait for the new network to become available, and verify recovery.
3. Switch back to Wi-Fi.
4. Briefly disable all networks and re-enable the original network.
5. Confirm a temporary no-network interval does not stop the VPN or cause duplicate recovery.
6. Stop while recovery is scheduled and confirm no later recovery restarts the service.

## Import and persistence

Test each of:

- direct `native://`, `vless://`, `vmess://`, and `trojan://` import;
- QR import/export where the profile is representable;
- `https://x.tcptun.com/v1#p=...` App Link;
- invalid host/path/query/oversized/non-canonical links;
- create, edit, delete, undo, process recreation, and migration.

## Instrumentation commands

With a connected device or emulator:

```bash
adb devices
./gradlew connectedDebugAndroidTest
```

The managed device configuration defines `tcptunCiApi35` (Pixel 2, API 35, AOSP ATD) for CI
or local managed-device runs. If no device is connected, report instrumentation as not run;
do not treat a JVM build as proof of Android VPN behavior.

## Runtime stress harness

The Phase 7 stress harness is manual/device-lab only. It is intentionally not attached to a
GitHub Actions workflow. Run the command-only matrix with one USB-connected device:

```bash
RUNTIME_STRESS_DISPOSABLE_DEBUG_DATA=true \
RUNTIME_STRESS_ITERATIONS=1000 \
RUNTIME_STRESS_SEED=1592622103 \
scripts/run-runtime-stress.sh
```

The defaults are 500 transitions, seed `1592622103`, and a deterministic random delay from
0–200 ms after every command. The storm chooses from Start, Stop, UpdateConnections,
ApplySettings, TCPing, and RefreshClientIps. Values from 200 through 5000 transitions are
accepted. The test clears logcat first and checks process exit history after the run.

The runner refuses to start unless `RUNTIME_STRESS_DISPOSABLE_DEBUG_DATA=true` is explicit.
Set `ANDROID_SERIAL` when more than one authorized device is connected. The runner builds the
debug target and test APKs, installs them with ADB's non-streaming path, and invokes the same
`AndroidJUnitRunner` stress classes directly. This avoids OEM package installers that reject
UTP's streamed/split install session even though normal ADB push installs are authorized.
It only force-stops and clears `com.tcptun.client.debug` and its debug test package; it never
touches release app data in `com.tcptun.client`. Before any mutation, the host records Wi-Fi
and mobile-data state and installs `EXIT`, `INT`, and `TERM` traps. The trap force-stops the
debug target, resets its AppOps, clears both debug packages, and restores both radios even if
instrumentation crashes. If an OEM denies shell `pm clear`, the runner uninstalls that exact
debug package instead; failure of both cleanup paths fails the run. Debug data is therefore
disposable and is not backed up.

For OEMs that intermittently reject USB installation, build and install once, then reuse only
the exact installed artifacts:

```bash
RUNTIME_STRESS_DISPOSABLE_DEBUG_DATA=true \
RUNTIME_STRESS_REUSE_INSTALLED=true \
RUNTIME_STRESS_ITERATIONS=1000 \
RUNTIME_STRESS_SEED=274912837 \
scripts/run-runtime-stress.sh
```

Reuse mode never builds, installs, or falls back from `pm clear` to uninstall. Before clearing
debug data it pulls each installed monolithic APK and requires an exact SHA-256 match with the
local app/test artifact, matching package/version/signing certificate, a debuggable target, and
a test APK targeting `com.tcptun.client.debug`. It also verifies `bridge.lock`, the AAR core/API
metadata, and the device-ABI Bridge binary packaged in the exact APK. Any mismatch refuses to
run. Split installs are refused because this project produces monolithic validation APKs.
If the OEM also denies `pm clear`, reuse mode records the skipped clear and continues with
force-stop, AppOps reset, and the instrumentation harness's scoped settings/profile fixture
reset; it still never uninstalls either package.

Run the stability trend on the same verified installation:

```bash
RUNTIME_STRESS_DISPOSABLE_DEBUG_DATA=true \
REUSE_INSTALLED=true \
CYCLES=100 \
scripts/run-resource-cycle-validation.sh
```

Each cycle condition-waits for `Running + connectionsReady`, asserts the TUN/Bridge/lease
ownership tuple, condition-waits for fully released `Stopped`, and waits for the old
`TcptunRuntimeActor` and `TcptunLifecycle` threads to terminate. The short post-condition delay
is only a resource sampling settle period. Results are written under `build/validation-gate/`.

For a real non-loopback LAN authentication check, set the secret only in the environment:

```bash
RUNTIME_STRESS_DISPOSABLE_DEBUG_DATA=true \
TCPTUN_TEST_PROXY_ALICE_PASSWORD='use-a-protected-random-value-a' \
TCPTUN_TEST_PROXY_BOB_PASSWORD='use-a-protected-random-value-b' \
TCPTUN_GO_DIR=/path/to/clean-locked-tcptun-go \
scripts/run-lan-auth-validation.sh
```

The host acts as the second LAN client. It verifies that loopback-only mode refuses the device's
Wi-Fi address, then that listen-all refuses anonymous, wrong-password, and unknown-user SOCKS
requests while accepting both `alice` and `bob`. It exercises TCP and UDP through RFC 1929 and
uses the clean locked tcptun-go client against the Android Bridge to verify Secure Auth V2 for
both identities. It switches the same listener to mixed mode and covers
SOCKS5 compatibility, ordinary HTTP proxy authentication (`407` for missing/wrong Basic
credentials), and HTTPS CONNECT authentication followed by an opaque TLS tunnel. Stop/start
persistence probes cover authenticated SOCKS5, HTTP, and HTTPS CONNECT in mixed mode.
Passwords are never echoed or retained; the report contains only 12-hex SHA-256 prefixes.
Host/instrumentation phase acknowledgements use short `debug.tcptun.lan.*` properties and are
reset to `none` on every exit path. This avoids OEM SELinux differences around shell-created
files in `/data/local/tmp`; the properties never contain credentials or endpoint data.

The default lifecycle matrix uses self-contained single-profile raw direct configs. It does
not claim real endpoint connectivity. Supply one protected structured profile URI to exercise
the lifecycle, handover, and Recovery matrix against a real endpoint without enabling the
two-profile membership assertion:

```bash
RUNTIME_STRESS_DISPOSABLE_DEBUG_DATA=true \
RUNTIME_STRESS_NETWORK_CONTROL=true \
RUNTIME_STRESS_LIFECYCLE_PROFILE_URI='native://protected-lab-endpoint.example:9443' \
scripts/run-runtime-stress.sh
```

In-place membership is a separate device-lab opt-in requiring two reachable, independently
configured structured profile URIs (no full JSON profiles):

```bash
RUNTIME_STRESS_DISPOSABLE_DEBUG_DATA=true \
RUNTIME_STRESS_MEMBERSHIP_PROFILE_A_URI='native://first-lab-endpoint.example:9443' \
RUNTIME_STRESS_MEMBERSHIP_PROFILE_B_URI='native://second-lab-endpoint.example:9443' \
scripts/run-runtime-stress.sh
```

Both profiles are configured in every membership plan; only `activeIds` changes from A, to
A+B, to B. The test waits for each committed membership and `connectionsReady`, then requires
the bridge epoch to remain unchanged across both updates. Supply URI values through a
protected lab environment because they may contain credentials.

Network and system-event controls are separate opt-ins because they change device-wide state:

```bash
RUNTIME_STRESS_DISPOSABLE_DEBUG_DATA=true \
RUNTIME_STRESS_NETWORK_CONTROL=true \
RUNTIME_STRESS_SYSTEM_EVENTS=true \
scripts/run-runtime-stress.sh
```

Use USB ADB for network-control runs. Both the instrumentation helper and the host trap restore
Wi-Fi/mobile-data state; the host restoration remains available after a target-process crash.
Dual-SIM devices are considered data-enabled when any `mobile_data` or `mobile_dataN` setting is
enabled, avoiding restoration from an inactive generic key on OEM builds. The device needs a
working Wi-Fi network and an active cellular subscription for the full handover matrix.

The host also captures the exact pre-run `ACTIVATE_VPN` AppOps mode and restores that mode on
every exit path. It does not use a package-wide AppOps reset, which can silently change unrelated
permissions or map the VPN authorization to a different OEM default.

### VPN permission revoke semantics

Changing `ACTIVATE_VPN` with `appops set` changes pre-consent. It is not, by itself, the Android
framework operation that replaces the prepared VPN owner, so it must not be reported as proof that
`VpnService.onRevoke()` was delivered. Run the AppOps diagnostic separately:

Keep these system events distinct in every device report:

| Method | What it validates | Immediate `onRevoke()` required? |
| --- | --- | --- |
| `ACTIVATE_VPN` AppOps `ignore`/`deny` | Future VPN pre-consent mode | No contract claimed |
| System VPN authorization revoke | Framework ownership revoke | Yes |
| Disconnect in system VPN Settings | Active tunnel disconnect behavior | Observe separately; do not relabel as authorization revoke |
| Package force-stop | Process/service termination and OS resource cleanup | No callback contract claimed |
| App task removal | Activity task lifecycle while foreground VPN remains owned | No; current policy keeps the VPN running |

The Android framework contract says the prepared owner is notified when another owner replaces it,
and the system-server revoke path explicitly disconnects the interface before sending the
`VpnService` callback. Changing the AppOps value only changes the authorization check; it does not
execute that prepared-owner replacement path by itself.

```bash
RUNTIME_STRESS_DISPOSABLE_DEBUG_DATA=true \
VPN_REVOKE_MODE=appops \
scripts/run-vpn-revoke-validation.sh
```

This records the AppOps value, framework VPN/connectivity state, safe runtime ownership, and
whether a callback happened, but its success is diagnostic only. It never requires immediate
teardown.

For the real user/system revoke path, use a physical device and the interactive mode:

```bash
RUNTIME_STRESS_DISPOSABLE_DEBUG_DATA=true \
VPN_REVOKE_MODE=system \
scripts/run-vpn-revoke-validation.sh
```

Wait for `VPN_REVOKE_ACTION_REQUIRED`, then revoke VPN authorization in system Settings. Do not
substitute Disconnect, force-stop, task removal, or an AppOps shell command. The runner waits up
to two minutes, requires released TUN/Bridge ownership, captures debug-only lifecycle markers,
and restores the exact prior AppOps mode on exit. Run it on Xiaomi and at least one non-Xiaomi
physical reference device. Reports are written below `build/validation-gate/vpn-revoke/` and do
not contain profiles, credentials, URIs, or raw configuration. If no `onRevoke` marker is seen,
the interactive run is reported as `INCOMPLETE` with `ACTION_NOT_OBSERVED`; it is not labeled as
a product cleanup failure. A failure after an observed callback remains a cleanup-contract failure.

### Harness architecture

- `VpnRuntimeStressHarness` owns test setup/restore, command emission, bounded waits, logcat,
  and process-exit inspection.
- Membership fixture normalization is the first harness operation. VPN permission is verified
  before RuntimeSettings or ProfileStore mutation, and any setup failure performs best-effort
  target-side rollback before the host trap clears disposable debug data.
- `RuntimeOwnershipDebugSnapshot` exposes only ownership scalars and safe status names. It never
  contains a username, password, profile, endpoint, URI, or config JSON.
- The debug registry retains providers for old Service instances until their native destroy
  cleanup completes, so a replacement and an old retained owner are visible together.
- `VpnRuntimeStressTest` runs rapid lifecycle sequences, service recreation, and the seeded
  command storm.
- `VpnNetworkHandoverStressTest` contains optional radio, Recovery-gap, task-removal, and VPN
  permission-revoke scenarios.

Every command transition asserts:

- at most one Android TUN owner;
- at most one native bridge-resource owner;
- one process-wide runtime lease owner, matching any TUN/native owner;
- at most one active Service instance;
- `connectionsReady` implies VPN status and coordinator phase are Running, the active Service
  is live and not tearing down, the bridge epoch is positive and `SessionOwned`, the Android
  TUN is owned, and the runtime lease matches that Service;
- final Stopped requires no TUN, no bridge resources, no pending teardown, and lease owner zero;
- no target-process fatal exception, foreground-start deadline exception, crash/native crash,
  or ANR is recorded during the run.

### Scenario coverage

| # | Scenario | Execution |
|---:|---|---|
| 1 | rapid Start → Stop | default stress test |
| 2 | Start → Stop → Start | default stress test |
| 3 | Start A → Start B | default stress test |
| 4 | repeated valid single-profile UpdateConnections/replacement | default stress test and storm |
| 5 | A → A+B → B in-place membership; bridge epoch unchanged | structured-endpoint opt-in |
| 6 | UpdateConnections → Stop | default stress test and storm |
| 7 | Recovery → Stop; restored network cannot restart the old runtime | independent network-control test |
| 8 | Recovery → Start replacement; B is authoritative after restore | independent network-control test |
| 9 | ApplyRuntimeSettings during Recovery gap | network-control opt-in |
| 10 | FlowAnalysis update during Recovery gap | network-control opt-in |
| 11 | TCPing while auxiliary ownership changes | storm and network-control opt-in |
| 12 | Wi-Fi → cellular | network-control opt-in |
| 13 | cellular → Wi-Fi | network-control opt-in |
| 14 | underlying callback during Stop | network-control opt-in |
| 15 | recreation while old native cleanup runs | default stress test; retained JNI fault remains a lab gap |
| 16 | app task removed while Running | system-event opt-in |
| 17 | AppOps authorization-mode diagnostic (not revoke proof) | system-event opt-in; default appops mode |
| 18 | Real VPN permission revoke while Running | system-event opt-in + `RUNTIME_STRESS_REVOKE_MODE=system`; manual Settings action |
| 19 | Real VPN permission revoke during Recovery | network + system-event opt-ins + system revoke mode; manual Settings action |

Recovery tests require the real device to enter the coordinator's Recovering phase after all
underlying networks are disabled. A device/core combination that does not enter that phase is
reported as skipped rather than simulated with a production test hook.

## Retained cleanup fault model

The production AAR has no device-test API for forcing JNI Stop, WaitStopped, or Abort failures.
Do not add such a backdoor. JVM tests use the existing `TcptunBridge` fake seam:

| Fault | Assertion |
|---|---|
| Stop timeout + WaitStopped timeout + Abort failure | native and callback ownership remain retained |
| repeated retained attempts | Android TUN remains owned and foreground/Service are retained |
| delayed native release | TUN closes only after release; foreground and Service stop only afterward |
| stale/duplicate retry callback | cleanup owner completes exactly once |
| recovery continuation | downstream recovery is admitted only after Released |

Real-device delayed-release and abort-failure injection remains a coverage gap until the native
bridge supplies a debug-only, non-production fault interface.

## Release smoke matrix

Record the device model, exact build/API, ABI, notification permission, VPN permission state,
network types, and result for every row.

| Android/API | Required smoke coverage |
|---|---|
| Android 7 / API 24–25 | legacy foreground start, VPN permission, start/stop, Doze |
| Android 8 / API 26–27 | notification channel, foreground-service deadline, background stop |
| Android 10 / API 29 | typed foreground start, task removal, handover |
| Android 12 / API 31–32 | background-start restrictions, Doze, recreation |
| Android 13 / API 33 | POST_NOTIFICATIONS denied/granted, ongoing VPN notification |
| Android 14 / API 34 | special-use foreground-service type, revoke, handover |
| Android 15+ / API 35+ | latest background/FGS behavior, command storm, process recreation |
| configured target / API 36 | full matrix on a matching API 36 device or managed image |

For each version run clean install, permission denial and grant, foreground/background start,
screen-off/Doze, Wi-Fi/cellular handover where supported, task removal, revoke, rapid command
matrix, and a final stop confirming no TUN/native/foreground ownership remains.
