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
- Multiple structured profiles in one dynamic, session-affine outbound pool.
- Complete strict tcptun-go JSON profiles without changing their schema.
- Native, VLESS, VMess, and Trojan URI import/export; compact `T2:`/`T3:` QR support.
- Versioned HTTPS App Links at `https://x.tcptun.com/v1#p=...`.
- Optional Android 10+ App routing and single-app traffic analysis.
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
