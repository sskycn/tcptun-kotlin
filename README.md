# tcptun Android client

This repository contains the Android VPN client for `sskycn/tcptun`.

The Android app owns the VPN side:

```text
Android apps -> VpnService TUN -> Kotlin TUN-to-SOCKS forwarder
             -> 0.0.0.0:1080 mixed proxy -> tcptun gomobile bridge
             -> remote tcptun server
```

Android itself connects to the mixed proxy through `127.0.0.1:1080`. The Go bridge listens on
`0.0.0.0:1080`, so other devices on the same reachable network can use the phone IP plus port
`1080` as a mixed HTTP/SOCKS proxy while the VPN client is running.

The Go protocol implementation is not copied into the Android app. This repository includes a small gomobile wrapper in `mobile/androidbridge`, which references the neighboring `tcptun-go` checkout through a Go `replace` directive and builds `app/libs/androidbridge.aar`.

## Expected Go mobile bridge

Build or provide an AAR whose Java package is `androidbridge` and whose class exposes these gomobile methods:

```go
package androidbridge

type LogCallback interface {
	OnLog(line string)
}

func SetLogCallback(cb LogCallback)
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
  "tunnel_mux": true,
  "enable_udp": true,
  "config_path": "",
  "route_config_path": "",
  "verbose": true
}
```

Build the AAR from this Kotlin project:

```bash
./scripts/build-androidbridge.sh
```

If `gomobile` is missing:

```bash
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init
```

This Kotlin project references `tcptun-go`, but does not modify it.

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
4. Tap `⇩` to import a URI share link, or enter profile name, server address, port, protocol, transport, UUID/password/token, SNI, path, TLS, mux, and UDP settings manually.
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
- URI import/export for native, VLESS, VMess, and Trojan profiles, including REALITY `pbk`, `fp`, `spx`, `flow`, and `sni`.
- Protocol and transport selection UI.
- Optional token, SNI, path, TLS, mux, and UDP UI.
- Status display: `Stopped`, `Starting`, `Running`, `Error`.
- Recent log display.
- IPv4 TCP forwarding from TUN to local SOCKS5 CONNECT.
- IPv4 UDP forwarding from TUN to local SOCKS5 UDP ASSOCIATE.
- Runtime reflection bridge to gomobile AAR.

## Not yet supported

- IPv6 TUN forwarding.
- TCP retransmission/window scaling/SACK handling in the userspace TCP shim.
- Per-app routing UI.
- In-app route rule editing.
- Building the Go AAR from Gradle automatically.
