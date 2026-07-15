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

type AppIdentityProvider interface {
	IdentifyApp(flowJSON string) (identityJSON string, err error)
}

type Engine struct { /* gomobile-owned runtime */ }

func NewEngine() *Engine
func ValidateConfig(configJson string) error
func (e *Engine) SetLogCallback(cb LogCallback)
func (e *Engine) SetStatusCallback(cb StatusCallback)
func (e *Engine) SetSocketProtector(p SocketProtector)
func (e *Engine) SetAppIdentityProvider(provider AppIdentityProvider)
func (e *Engine) Configure(configJson string) error
func (e *Engine) StartConfiguredSessionWithDisabledOutbounds(disabledTagsJson string) (int64, error)
func (e *Engine) StartOutbound(tag string) error
func (e *Engine) StopOutbound(tag string, force bool, timeoutMillis int64) error
func (e *Engine) OutboundsStatusJSON() string
func (e *Engine) Stop() error
func (e *Engine) Close() error
func (e *Engine) SessionID() int64
func (e *Engine) WaitStopped(sessionID int64, timeoutMillis int64) error
func (e *Engine) Status() string
func (e *Engine) StatusJSON() string
```

Each `TcptunVpnService` instance owns one `Engine`; runtime control is available
only through that instance.

`Start` receives the current strict `tcptun-go` file configuration. The Android
app builds a local mixed/SOCKS5 inbound, every configured structured profile as
an equal tagged tunnel outbound, a dynamic `balance` pool, a direct outbound, and
an optional TCP-only `direct-first` outbound. Ordered route rules are evaluated
first and may select a specific configured profile by its stable tag; unmatched sessions enter the
pool, whose effective weights follow active load, observed connection latency,
and failures while destination affinity keeps related sessions on one link.
The service first calls `Configure`, which has no runtime side effects, and then
starts the configured session with every inactive profile tag disabled from its
first state. Later profile row taps call `StartOutbound` or `StopOutbound` without recreating the Android
VPN interface or local listener. A rule bound to a stopped profile remains
authoritative and becomes usable again as soon as that profile is started.
Custom routing is stored in the strict JSON `route.rules`:

```json
{
  "log": {"level": "info"},
  "inbounds": [
    {"tag": "local", "type": "socks5", "listen": "127.0.0.1", "port": 1080,
     "network": ["tcp", "udp"], "outbound": "profile-pool"}
  ],
  "outbounds": [
    {"tag": "profile-a", "type": "native", "server": "203.0.113.10", "port": 9443,
     "token": "secret", "transport": {"type": "raw"}, "mux": {"enabled": true}},
    {"tag": "profile-b", "type": "native", "server": "203.0.113.20", "port": 9443,
     "token": "secret", "transport": {"type": "raw"}, "mux": {"enabled": true}},
    {"tag": "direct", "type": "direct"},
    {"tag": "profile-pool", "type": "balance", "affinity_ttl": "10m",
     "members": [
       {"outbound": "profile-a", "weight": 100},
       {"outbound": "profile-b", "weight": 100}
     ]}
  ],
  "route": {
    "default_outbound": "profile-pool",
    "rules": [{"domain_suffixes": ["example.com"], "outbound": "profile-a"}]
  },
  "dns": {}
}
```

`StatusCallback.OnStatus(eventJson)` includes `session_id`, `sequence`, `state`,
`reason`, `phase`, `listen`, `remote`, `outbound_tag`, `active_connections`, `mux_sources`,
`mux_sessions`, `mux_streams`, `recoverable`, `last_error`, and `timestamp_ms`. The app drops events from an older engine/session
or with a non-increasing sequence and folds accepted events into one immutable
`StateFlow` snapshot consumed by Compose.

VPN startup is transactional: the service starts the Engine, waits for
`state=core_ready`, establishes the Android TUN, then starts tun2socks. Only after
all three steps succeed is `Running` published. Failure rolls back tun2socks, the
TUN descriptor, and the Engine in reverse order. Stop and `onRevoke()` use the same
idempotent teardown; `onDestroy()` finally calls `Engine.Close()`.

`SetAppIdentityProvider` is currently left unset. The existing tun2socks hop
does not carry the originating Android UID to the local Go inbound, so resolving
the accepted loopback socket would incorrectly identify the client process.
Android per-app filtering is intentionally not used: the VPN installs IPv4 and
IPv6 default routes and sends all captured traffic to tcptun-go.

Profiles can also store a complete strict tcptun-go JSON document. The app
preserves all supported `log`, `inbounds`, `outbounds`, `route`, and `dns`
fields, removes the retired top-level `discovery` field from older saved
configs, then injects/replaces one `android-vpn` SOCKS5 inbound at runtime so
the TUN adapter uses the configured local port, UDP mode, and auth.

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

Debug builds use `com.tcptun.client.debug`, so they can coexist with a signed
release installation.

## Publish a GitHub release

The `Release Android APK` GitHub Actions workflow builds and publishes a signed
APK whenever a semantic-version tag beginning with `v` is pushed, for example
`v1.2.3` or `v1.2.3-rc.1`. The tag is used as the app version name, and release
notes are generated automatically by GitHub.

Configure these repository Actions secrets before pushing the first release tag:

- `TCPTUN_RELEASE_KEYSTORE_BASE64`: the release keystore encoded as Base64
- `TCPTUN_RELEASE_STORE_PASSWORD`: keystore password
- `TCPTUN_RELEASE_KEY_ALIAS`: signing key alias
- `TCPTUN_RELEASE_KEY_PASSWORD`: signing key password

Create the Base64 value without line breaks:

```bash
base64 < /path/to/tcptun-release.jks | tr -d '\n'
```

Then publish a release:

```bash
git tag v1.2.3
git push origin v1.2.3
```

The resulting GitHub Release contains `tcptun-v1.2.3.apk` and its SHA-256
checksum. Pre-release tags such as `v1.2.3-rc.1` are marked as pre-releases.

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
5. Tap a profile row to start or stop only that connection. Every running structured profile joins the same dynamic pool; while the VPN is running, the row action hot-starts or hot-stops only that outbound.
6. Tap the share icon to export a URI link for the profile protocol.
7. Approve the Android VPN prompt when starting the first connection.
8. Open a browser and visit a site.
9. Tap the bottom status line to view recent logs.

To let another device use the running Android client, set that device's HTTP or SOCKS proxy to
`PHONE_LAN_IP:1080`. The phone and the other device must be on a network that permits inbound
connections to the phone.

Each row has a share action. Swipe a row to the left to delete it; the snackbar action can undo the deletion. Profiles are saved locally in `SharedPreferences`.

HTTPS App Link imports use this envelope:

```text
https://tcptun.com/x/v1#p=<BASE64URL(profile-uri)>
```

`v1` is the envelope version. `p` is the UTF-8 profile URI encoded as unpadded
Base64URL. The fragment keeps the profile payload out of ordinary HTTP requests
and server access logs. The current version accepts these inner profile URI
schemes:

- `native://`
- `tcptun://` (legacy alias for `native://`)
- `vless://`
- `vmess://` with Base64 JSON
- `trojan://`

The app still accepts the inner schemes directly for compatibility. Opening a
validated `https://tcptun.com/x/v1` Android App Link launches TcpTun and shows a
Material 3 confirmation before saving it. Existing equivalent profiles are
reused instead of duplicated; imported links never start the VPN
automatically. The domain must publish a matching release-signing association at
`https://tcptun.com/.well-known/assetlinks.json` for verified App Link routing.

Every system-link, clipboard, and QR import must pass URI decoding, Android
profile validation, and the Go core's non-listening runtime construction before
it is persisted. A rejected link leaves the connection list unchanged.

Example VLESS/REALITY format:

```text
vless://00000000-0000-4000-8000-000000000000@203.0.113.10:443?security=reality&encryption=none&pbk=PUBLIC_KEY_PLACEHOLDER&headerType=none&fp=chrome&spx=%2F&type=tcp&flow=xtls-rprx-vision&sni=example.com#example
```

## Supported

- Kotlin + Jetpack Compose Android app.
- `VpnService` with foreground service notification.
- Config persistence with `SharedPreferences`.
- Independently started local profiles with add, edit, delete, and share actions; active structured profiles form one dynamically weighted, session-affine pool.
- URI import/export for native, VLESS, VMess, and Trojan profiles, including REALITY `pbk`, `sid`, `fp`, `spx`, `flow`, and `sni`.
- Protocol and transport selection UI.
- Optional token, SNI, path, TLS, TLS insecure, REALITY short ID, mux, upstream protocol, and UDP UI.
- IPv4/IPv6 default routes send all VPN traffic into tcptun-go; explicit rules run first and unmatched traffic uses the balanced active-profile pool.
- Status display: `Stopped`, `Starting`, `Running`, `Error`.
- Recent log display.
- Native `hev-socks5-tunnel` forwarding from TUN to local SOCKS5/mixed proxy.
- Runtime reflection bridge to gomobile AAR.
- In-app diagnostics for VPN, underlying network, bridge state, local proxy reachability, MTU, UDP, and socket protect.
- Runtime MTU and UDP test-mode settings.
- Strict tcptun-go topology config and cached TCP direct-first routing.
- Import, edit, persist, share, and run complete strict tcptun-go JSON profiles.

## Not yet supported

- Building the Go AAR from Gradle automatically.
