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

## Testing boundaries

The production seam is the existing `TcptunBridge` interface:

```text
ReflectionTcptunBridge -> androidbridge.aar
FakeTcptunBridge       -> JVM lifecycle tests
```

The fake is intentionally test-only and fault-injecting. It does not mirror the entire Go
runtime or introduce another production interface hierarchy.
