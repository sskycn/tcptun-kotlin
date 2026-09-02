# tcptun Android client

Android VPN client for [sskycn/tcptun](https://github.com/sskycn/tcptun). The app owns the
Android `VpnService`, TUN lifecycle, local SOCKS5/mixed listener, profile persistence, and
Compose UI. The protocol core is provided by the generated `androidbridge.aar` from the
neighboring `tcptun-go` checkout.

```text
Android apps -> VpnService TUN -> tcptun-go native TUN inbound -> selected outbound
Local clients -> SOCKS5/mixed listener ------------------------> selected outbound
```

## Features

- Foreground `VpnService` with IPv4/IPv6 routes and event-driven recovery.
- Backward-compatible dual-stack Full Tunnel and explicit IPv4/IPv6 home-network Split Tunnel.
- Reverse Subnet Remote support through encrypted Full JSON, including Core-owned relay and optional P2P direct QUIC.
- Multiple structured profiles in one dynamic, session-affine outbound pool.
- Complete strict tcptun-go JSON profiles without changing their schema.
- Native URI import/export and compact `T2:`/`T3:` profile QR support.
- Per-account `A1:` QR, copy, share, preview, and conflict-safe import for local proxy credentials.
- Versioned HTTPS App Links at `https://x.tcptun.com/v1#p=...`.
- Optional Android 10+ App routing and single-app traffic analysis.
- Up to 256 accounts on one authenticated local SOCKS5 or mixed SOCKS5/HTTP/HTTPS CONNECT/UDP listener.
- Latest tcptun-go SOCKS5 secure authentication v2, with HKDF-derived proofs and explicit
  `secure`, `standard`, or `auto` outbound authentication policies in full JSON profiles.
- Material 3 UI, runtime diagnostics, redacted in-app logs, and TCPing tools.

## Build

Build the bridge when the neighboring Go checkout is available:

```bash
./scripts/build-androidbridge.sh
# or: TCPTUN_GO_DIR=/path/to/tcptun-go ./scripts/build-androidbridge.sh
./gradlew :app:assembleDebug
```

The default Android ABIs are `armeabi-v7a`, `arm64-v8a`, and `x86_64`. Debug builds use
`com.tcptun.client.debug` and can coexist with a release installation.

## Quick start

1. Build/install the bridge and debug APK.
2. Add or import a profile.
3. Tap a profile and approve the Android VPN prompt.
4. Open the diagnostics page to inspect runtime state and recent logs.

See [docs/device-testing.md](docs/device-testing.md) for repeatable device validation.

## Documentation

- [Android architecture](docs/android-architecture.md)
- [VPN lifecycle and ownership](docs/vpn-lifecycle.md)
- [Bridge/AAR integration](docs/bridge-integration.md)
- [Profiles and persistence](docs/profiles.md)
- [Reverse Subnet on Android](docs/reverse-subnet-android.md)
- [Reverse Subnet device lab](docs/reverse-subnet-lab.md)
- [URI and QR import](docs/uri-qr-import.md)
- [App routing](docs/app-routing.md)
- [Traffic analysis](docs/traffic-analysis.md)
- [Performance baselines](docs/performance.md)
- [Diagnostics and redaction](docs/diagnostics.md)
- [Troubleshooting](docs/troubleshooting.md)
- [Release process](docs/release.md)

## Links

- Go core: `../tcptun-go` locally, or the project repository configured by the release build.
- Bridge wrapper: `scripts/build-androidbridge.sh`.
- Release entry point: `make publish VERSION=vX.Y.Z`.

## tcptun-go authentication

The Android bridge is generated from tcptun-go v0.4.2 commit
`907130cb53b71a4317da057c9ca33fd128c29ffd` (Bridge API 3). Secure authentication uses the
tcptun-go v2 protocol: HKDF-SHA256 derives the authentication key and the Go core performs all
challenge/response processing. It is intended for high-entropy shared secrets and is not
transport encryption.

Credentialed `socks5` and `mixed` outbounds default to `auth_mode: "secure"`, which offers only
private method `0x80` and prevents downgrade to RFC1929. `standard` explicitly selects RFC1929;
`auto` offers secure auth and RFC1929 for compatibility and is intentionally downgradeable.
Android preserves these fields in full JSON profiles; it does not implement the authentication
protocol itself.

Android-created local proxy inbounds use `users[]`; all configured accounts protect the same
listener and mixed-protocol surfaces. Existing encrypted single-account settings migrate
automatically. Full JSON profiles preserve multi-user `mixed`, `socks5`, and `native` inbounds,
including per-user Native flow. Outbounds remain one client identity. Passwords remain in
encrypted secret storage and listen-all cannot run without an account.

tcptun-go v0.4.0 is Native-only for tunnel endpoints. Stored VLESS, VMess, and Trojan structured
profiles remain readable but are marked unsupported and cannot start or export; they are never
silently converted to Native. Legacy REALITY fingerprint values are ignored on read and are not
written to storage, profile payloads, or runtime JSON. Incompatible full JSON remains fail-closed.

Native `raw` profiles with mux enabled can use `carrier.mode=auto` with either TLS or REALITY.
The Core maintains TCP and QUIC carriers and owns health/load selection, fallback, backoff, QUIC
DATAGRAM requirements, and path probes. Structured outbound profiles expose
`carrier.prefer=adaptive|quic|tcp`: adaptive is the dynamic policy, while QUIC/TCP preferences
fall back to the other carrier when the preferred path is unhealthy. They are not single-carrier
modes. Existing auto profiles omit the preference and therefore retain adaptive behavior.

Each local proxy account can be shared independently as `A1:<Base45>`. One A1 payload always
contains exactly one username/password pair; it never contains listener settings or a tunnel
profile. A1 uses the same binary-to-Base45 QR-alphanumeric outer strategy as T3, but its wire
schema and scanner/import path are separate. A1 is not encrypted and is handled as a bearer
secret: the app marks clipboard content sensitive, shows a trust warning, and does not persist
the payload, QR image, or password in SavedState or navigation arguments.
