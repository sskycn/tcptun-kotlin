# Android architecture

## Responsibilities

The Android project is a VPN client, not the protocol implementation. It owns:

- `TcptunVpnService` and foreground notification state.
- Android TUN establishment and the original `ParcelFileDescriptor`.
- The local SOCKS5/mixed listener configuration.
- Profile persistence, import validation, and Compose UI.
- Runtime state in `TcptunState` and diagnostics presentation.

The Go core owns TUN forwarding, protocol handshakes, outbound selection, DNS/fake-IP
processing, mux sessions, and native runtime cleanup. Android calls it through the existing
`androidbridge.aar` reflection wrapper.

## Runtime data path

```text
App traffic -> Android VpnService TUN -> native tcptun inbound
                                         -> route rules/default outbound
Local client -> Android mixed/SOCKS inbound -> same outbound set
Bridge callbacks -> TcptunState StateFlow -> Material 3 Compose UI
```

The service installs IPv4/IPv6 default routes. The Go core receives a duplicate of the
Android TUN FD; the Android-owned descriptor remains in an exclusive owner slot until native
ownership is confirmed released.

## Concurrency boundaries

- Service lifecycle work is serialized by the lifecycle executor and service-owner lock.
- Native calls are serialized by `bridgeLock` where required by the engine contract.
- UI-originated durable mutations are owned by `TcptunApplication` process scopes.
- Status, flow, and network callbacks use epochs/generations to reject stale work.
- The process-wide runtime lease prevents old/new `VpnService` instances from overlapping.

## State and persistence

`TcptunState` is an in-memory immutable `StateFlow`. Profiles and runtime settings use the
existing local persistence layer. Flow events and diagnostics are not persisted as profile
data. No protocol, profile, or URI schema is defined in this Android architecture document;
see the focused documents below.

Application lifecycle state is represented by `VpnStatus`; bridge wire states are converted
from strings only at the bridge boundary. UI code accesses profile persistence through
`ProfileRepository`, while `RuntimeSettingsRepository` owns the durable runtime-settings
schema. `TcptunVpnService` retains compatibility forwarding methods for device tests and old
call sites, but it no longer owns either persistence implementation.

Compose feature pages are split by responsibility (`SettingsPage`, `FlowAnalysisPage`,
`RouteManagementPage`, and `EditProfilePage`). `MainActivity` owns Android activity lifecycle;
`TcptunScreen` is the root screen coordinator. Mutually exclusive sub-pages use the typed
`MainDestination` state rather than independent booleans. All UI continues to use Material 3
components.

Foreground notification construction and channel management belong to
`VpnNotificationController`. Incoming service actions are converted to `VpnServiceCommand`
before lifecycle dispatch so policy code does not branch on arbitrary action strings.
Desired-running-plan persistence, SOCKS5 probe handshakes, underlying-network callback
ownership, and delayed member-health scheduling are isolated in focused collaborators. The
Gradle `check` lifecycle enforces hotspot line-count baselines so these responsibilities do
not drift back into `MainActivity` or `TcptunVpnService`. It also caps individual Service
lifecycle functions at 180 lines so startup, connection mutation, rollback, and teardown stay
as named transaction stages instead of growing back into monolithic methods.

`VpnServiceIntents` owns the validated command payload and extras schema while the Service
companion keeps source-compatible forwarding APIs. `VpnHealthCheckRequests` owns process-wide
refresh flags and hands requests to only the currently installed Service callbacks.
`BridgeStatusJson` is the single bounded parser for callback events, reconciled snapshots,
client-IP refreshes, and per-outbound health records; it preserves field-presence semantics
when a partial snapshot must not erase newer diagnostics.

## Testing boundaries

The production seam is the existing `TcptunBridge` interface:

```text
ReflectionTcptunBridge -> androidbridge.aar
FakeTcptunBridge       -> JVM lifecycle tests
```

The fake is intentionally test-only and fault-injecting. It does not mirror the entire Go
runtime or introduce another production interface hierarchy.
