# tcptun Android client

This repository contains the Android VPN client for `sskycn/tcptun`.

The Android app owns the VPN side:

```text
Android apps -> VpnService TUN -> hev-socks5-tunnel
             -> 0.0.0.0:1080 mixed proxy -> tcptun gomobile bridge
             -> remote tcptun server
```

Android itself connects to the mixed proxy through `127.0.0.1:1080`. The Go bridge listens on
`0.0.0.0:1080`, so other devices on the same reachable network can use the phone IP plus port
`1080` as a mixed HTTP/SOCKS proxy while the VPN client is running.

The Go protocol implementation and gomobile wrapper live in the neighboring `tcptun-go` checkout. This Android project only consumes the generated `app/libs/androidbridge.aar`; `./scripts/build-androidbridge.sh` delegates to `../tcptun-go/scripts/build-androidbridge.sh`.

## Expected Go mobile bridge

Build or provide an AAR whose Java package is `androidbridge` and whose class exposes these gomobile methods:

```go
package androidbridge

type LogCallback interface {
	OnLog(line string)
}

type StatusCallback interface {
	OnStatus(eventJson string)
}

type SocketProtector interface {
	Protect(fd int64) bool
}

func SetLogCallback(cb LogCallback)
func SetStatusCallback(cb StatusCallback)
func SetSocketProtector(p SocketProtector)
func Start(configJson string) error
func Stop() error
func Status() string
```

`Start` should start the existing tcptun client mixed proxy on the `local_listen_addr` from `configJson`. The Android app currently sends:

```json
{
  "mode": "client",
  "listen_addrs": ["0.0.0.0:1080"],
  "local_listen_addr": "0.0.0.0:1080",
  "server_addr": "203.0.113.10:9443",
  "token": "uuid-or-password",
  "tunnel_protocol": "native",
  "tunnel_transport": "raw",
  "tunnel_path": "/proxy",
  "tunnel_tls": false,
  "tunnel_tls_server_name": "",
  "tunnel_tls_insecure": false,
  "tunnel_security": "",
  "tunnel_flow": "",
  "reality_server_name": "",
  "reality_public_key": "",
  "reality_short_id": "",
  "reality_fingerprint": "",
  "reality_spider_x": "",
  "tunnel_mux": true,
  "upstream_protocol": "socks5",
  "enable_udp": true,
  "config_path": "",
  "route_config_path": "",
  "verbose": true
}
```

`SetStatusCallback` is optional for backward compatibility. Newer AARs call
`StatusCallback.OnStatus(eventJson)` with JSON fields such as `state`, `phase`,
`listen`, `remote`, `active_connections`, `last_error`, and `timestamp_ms`. The
Android diagnostics screen shows the latest event, while `Status()` remains the
fallback simple bridge status.

Build the AAR through this Kotlin project wrapper:

```bash
./scripts/build-androidbridge.sh
```

If `tcptun-go` is not a sibling checkout, point the wrapper at it:

```bash
TCPTUN_GO_DIR=/path/to/tcptun-go ./scripts/build-androidbridge.sh
```

If `gomobile` is missing:

```bash
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init
```

The generated AAR is copied to `app/libs/androidbridge.aar`.

## Build the Android app

```bash
./gradlew :app:assembleDebug
```

Install the debug APK:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Server example

On a reachable server running the Go project:

```bash
make build
./bin/tcptun server \
  --listen 0.0.0.0:9443 \
  --tunnel-protocol native \
  --transport raw \
  --token "change-me"
```

For VLESS/VMess/Trojan, use the same protocol, transport, token/UUID/password, TLS/SNI, and path values in the app.

## App usage

1. Run `./scripts/build-androidbridge.sh` to create `app/libs/androidbridge.aar`, then install the app.
2. Open the app. The first screen is the profile list.
3. Tap `+` to add a profile, or tap the pen icon to edit an existing profile.
4. Tap `⇩` to import a URI share link, or enter profile name, server address, port, protocol, transport, UUID/password/token, SNI, path, TLS, REALITY, mux, upstream, and UDP settings manually.
5. Tap a profile row to select it. The selected profile has a black bar on the left.
6. Tap the share icon to export a URI link for the profile protocol.
7. Tap the orange floating button and approve the Android VPN prompt.
8. Open a browser and visit a site.
9. Tap the bottom status line to view recent logs.

To let another device use the running Android client, set that device's HTTP or SOCKS proxy to
`PHONE_LAN_IP:1080`. The phone and the other device must be on a network that permits inbound
connections to the phone.

Each row also has share, edit, and delete actions. Profiles are saved locally in `SharedPreferences`.

Supported import/export URI schemes:

- `native://`
- `vless://`
- `vmess://` with Base64 JSON
- `trojan://`

Example VLESS/REALITY format:

```text
vless://00000000-0000-4000-8000-000000000000@203.0.113.10:443?security=reality&encryption=none&pbk=PUBLIC_KEY_PLACEHOLDER&headerType=none&fp=chrome&spx=%2F&type=tcp&flow=xtls-rprx-vision&sni=example.com#example
```

## Supported

- Kotlin + Jetpack Compose Android app.
- `VpnService` with foreground service notification.
- Config persistence with `SharedPreferences`.
- Multiple local profiles with select, add, edit, delete, and share actions.
- URI import/export for native, VLESS, VMess, and Trojan profiles, including REALITY `pbk`, `sid`, `fp`, `spx`, `flow`, and `sni`.
- Protocol and transport selection UI.
- Optional token, SNI, path, TLS, TLS insecure, REALITY short ID, mux, upstream protocol, and UDP UI.
- App filter modes for Android VPN routing: all apps proxied by default, or no apps proxied by default.
- Status display: `Stopped`, `Starting`, `Running`, `Error`.
- Recent log display.
- Native `hev-socks5-tunnel` forwarding from TUN to local SOCKS5/mixed proxy.
- Runtime reflection bridge to gomobile AAR.
- In-app diagnostics for VPN, underlying network, bridge state, local proxy reachability, MTU, UDP, and socket protect.
- Runtime MTU and UDP test-mode settings.

## Not yet supported

- Building the Go AAR from Gradle automatically.
