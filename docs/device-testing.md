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
