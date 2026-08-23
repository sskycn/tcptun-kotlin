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
RUNTIME_STRESS_ITERATIONS=1000 \
RUNTIME_STRESS_SEED=1592622103 \
scripts/run-runtime-stress.sh
```

The defaults are 500 transitions, seed `1592622103`, and a deterministic random delay from
0–200 ms after every command. The storm chooses from Start, Stop, UpdateConnections,
ApplySettings, TCPing, and RefreshClientIps. Values from 200 through 5000 transitions are
accepted. The test clears logcat first and checks process exit history after the run.

Network and system-event controls are separate opt-ins because they change device-wide state:

```bash
RUNTIME_STRESS_NETWORK_CONTROL=true \
RUNTIME_STRESS_SYSTEM_EVENTS=true \
scripts/run-runtime-stress.sh
```

Use USB ADB for network-control runs. The test records and restores Wi-Fi/mobile-data state,
but a lab interruption can still leave radios changed. The device needs a working Wi-Fi
network and an active cellular subscription for the full handover matrix.

### Harness architecture

- `VpnRuntimeStressHarness` owns test setup/restore, command emission, bounded waits, logcat,
  and process-exit inspection.
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
- `connectionsReady` implies an active Running coordinator, a positive current bridge epoch,
  `SessionOwned`, an Android TUN, and the matching runtime lease;
- no target-process fatal exception, foreground-start deadline exception, crash/native crash,
  or ANR is recorded during the run.

### Scenario coverage

| # | Scenario | Execution |
|---:|---|---|
| 1 | rapid Start → Stop | default stress test |
| 2 | Start → Stop → Start | default stress test |
| 3 | Start A → Start B | default stress test |
| 4 | repeated UpdateConnections | default stress test and storm |
| 5 | UpdateConnections → Stop | default stress test and storm |
| 6 | Recovery → Stop | network-control opt-in |
| 7 | Recovery → Start replacement | network-control opt-in |
| 8 | ApplyRuntimeSettings during Recovery gap | network-control opt-in |
| 9 | FlowAnalysis update during Recovery gap | network-control opt-in |
| 10 | TCPing while auxiliary ownership changes | storm and network-control opt-in |
| 11 | Wi-Fi → cellular | network-control opt-in |
| 12 | cellular → Wi-Fi | network-control opt-in |
| 13 | underlying callback during Stop | network-control opt-in |
| 14 | recreation while old native cleanup runs | default stress test; retained JNI fault remains a lab gap |
| 15 | app task removed while Running | system-event opt-in |
| 16 | VPN permission revoke while Running | system-event opt-in |
| 17 | VPN permission revoke during Recovery | network + system-event opt-ins |

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
