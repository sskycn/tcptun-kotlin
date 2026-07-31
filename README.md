# tcptun Android client

This repository contains the Android VPN client for `sskycn/tcptun`.

The Android app owns the VPN side:

```text
Android apps -> VpnService TUN -> tcptun gomobile native TUN inbound
                                  -> selected tcptun outbound
Local clients -> SOCKS5/mixed listener -> selected tcptun outbound
```

Android itself connects to the local proxy through `127.0.0.1:1080`. The listener protocol is
configurable as `socks5` (the default) or `mixed`; mixed accepts both SOCKS5 and HTTP proxy clients.
The listener binds to `127.0.0.1` by default. Enable listening on all interfaces to bind
`0.0.0.0`, allowing other devices on the same reachable network to use the phone IP and port `1080`.

The Go protocol implementation and gomobile wrapper live in the neighboring `tcptun-go` checkout. This Android project only consumes the generated `app/libs/androidbridge.aar`; `./scripts/build-androidbridge.sh` delegates to `../tcptun-go/scripts/build-androidbridge.sh`.
The release workflow checks out the Go core at the revision recorded in
`.github/workflows/release.yml` and rebuilds the AAR before Gradle runs, so a
clean release runner does not depend on an untracked local binary.

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
func CoreVersion() string
func CoreBuildID() string
func (e *Engine) SetLogCallback(cb LogCallback)
func (e *Engine) SetStatusCallback(cb StatusCallback)
func (e *Engine) RegisterEvent(event string) error
func (e *Engine) UnregisterEvent(event string) error
func (e *Engine) SetSocketProtector(p SocketProtector)
func (e *Engine) SetAppIdentityProvider(provider AppIdentityProvider)
func (e *Engine) Configure(configJson string) error
func (e *Engine) SetTun(fd int64, mtu int64) error
func (e *Engine) StartConfiguredSessionWithDisabledOutbounds(disabledTagsJson string) (int64, error)
func (e *Engine) StartOutbound(tag string) error
func (e *Engine) StopOutbound(tag string, force bool, timeoutMillis int64) error
func (e *Engine) ProbeOutbound(tag string, host string, port int, timeoutMillis int64) (int64, error)
func (e *Engine) ProbeOutboundHealth(tag string, host string, port int, timeoutMillis int64) (int64, error)
func (e *Engine) OutboundsStatusJSON() string
func (e *Engine) Stop() error
func (e *Engine) Close() error
func (e *Engine) SessionID() int64
func (e *Engine) WaitStopped(sessionID int64, timeoutMillis int64) error
func (e *Engine) Status() string
func (e *Engine) StatusJSON() string
```

The diagnostics page shows `CoreVersion` and `CoreBuildID`, so installed builds
can be matched to the exact tcptun-go revision. CI and release builds currently
pin `7d0ef7f95af9e268d98c268a4bfe8c5f0895c3b4`
(`tcptun-go` v0.2.4-6-g7d0ef7f).

Optional telemetry must be opted into with `RegisterEvent`. The app registers:

- `REMOTE_ENDPOINTS_CHANGED` — live managed-tunnel remotes for diagnostics
- `RUNTIME_RECONNECTING` / `RUNTIME_CONNECTION_ISSUE` — health-check wakes

Each `TcptunVpnService` instance owns one `Engine`; runtime control is available
only through that instance.

`Start` receives the current strict `tcptun-go` file configuration. The Android
app builds a local mixed/SOCKS5 inbound, every configured structured profile as
an equal tagged tunnel outbound, a dynamic `balance` pool, and a direct outbound.
Ordered route rules are evaluated
first and may select a specific configured profile by its stable tag; unmatched sessions enter the
pool, whose effective weights follow active load, observed connection latency,
and failures while destination affinity keeps related sessions on one link.
Event-driven checks call `ProbeOutboundHealth` for every active structured pool
member when forced (VPN start, network change, core degraded/reconnect, pool
membership change, TCPing failures, UI refresh). Failed checks increase only
that member's balance penalty; a successful check clears the penalty so a
recovered member can immediately re-enter selection. There is no background
member-health sweep. `OutboundsStatusJSON` reports `health`, `failures`,
`latency_ms`, `last_observed_at_ms`, and `last_succeeded_at_ms` without exposing
credentials. The client targets extreme hang efficiency while keeping the VPN
tunnel and the local mixed/SOCKS proxy fully usable. App traffic keeps the data
path alive; control-plane work stays near zero while the UI is closed.

There is no routine timer-based health polling. The bridge monitor
sleeps until an event wakes it: network change callbacks, core status callbacks
(including registered `REMOTE_ENDPOINTS_CHANGED` / `RUNTIME_RECONNECTING` /
`RUNTIME_CONNECTION_ISSUE`), pull-to-refresh, or opening the app. A failed check
schedules one bounded confirmation check before recovery. Routine wakes prefer
StatusCallback state already folded into `TcptunState`; full `StatusJSON`
reconciliation, loopback proxy probes, and the aggregate SOCKS/HTTP upstream
probe run only on UI-driven refreshes. Unlock wakes and background logcat I/O
are skipped.
Generated profiles enable mux with no warm spare, so the Go runtime can retire
idle physical connections. Carrier selection is configured independently through
`carrier.mode`; `mux` only contains logical-stream pooling and resume options.
When flow analysis is disabled and no app route is configured, the
bridge also skips Android UID ownership lookups entirely.
Native raw profiles using automatic TCP/QUIC REALITY can enable resumable mux
streams. The structured editor persists and emits `mux.resume`,
`mux.resume_timeout`, and `mux.resume_buffer_size`; both client and server must
use matching resumable settings. Standard URI shares preserve these fields,
while compact `T3:` QR payloads cannot represent them and are therefore not
offered for such profiles. Complete JSON profiles can additionally override the
global `resources.resumable_buffer_budget`.
The service installs `SocketProtector` and `AppIdentityProvider`, calls `Configure`,
passes a duplicate of the `VpnService` TUN to `SetTun`, and then starts the
configured session with every inactive profile tag disabled from its
first state. Later profile row taps call `StartOutbound` or `StopOutbound` without recreating the Android
VPN interface or local listener. A rule bound to a stopped profile remains
authoritative and becomes usable again as soon as that profile is started.
Custom routing is stored in the strict JSON `route.rules`:

```json
{
  "log": {"level": "info"},
  "inbounds": [
    {"tag": "local", "type": "socks5", "address": ["127.0.0.1:1080"],
     "network": ["tcp", "udp"]}
  ],
  "outbounds": [
    {"tag": "profile-a", "type": "native", "address": ["203.0.113.10:9443"],
     "token": "secret", "transport": {"type": "raw"}, "mux": {"enabled": true}},
    {"tag": "profile-b", "type": "native", "address": ["203.0.113.20:9443"],
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
  "dns": {
    "servers": ["1.1.1.1", "[2606:4700:4700::1111]:53"],
    "strategy": "prefer_ipv4",
    "fake_ip": {
      "enabled": true,
      "ipv4_range": "198.18.0.0/15",
      "ipv6_range": "fc00::/18",
      "capacity": 65536,
      "ttl": "10m"
    }
  }
}
```

`StatusCallback.OnStatus(eventJson)` includes `session_id`, `sequence`, `state`,
`reason`, `phase`, `listen`, `remote`, `outbound_tag`, `active_connections`, `mux_sources`,
`mux_sessions`, `mux_streams`, `recoverable`, `last_error`, and `timestamp_ms`. The app drops events from an older engine/session
or with a non-increasing sequence and folds accepted events into one immutable
`StateFlow` snapshot consumed by Compose.

VPN startup is transactional: the service starts the Engine, waits for
`state=core_ready` after establishing the Android TUN and passing it to the Go
Engine. Only then is `Running` published. The Engine owns only its duplicated fd;
the service retains the original `ParcelFileDescriptor`. Stop first calls the
bounded `Engine.Stop()` and, if that call times out, uses `WaitStopped(sessionID)`
to confirm that the exact Go session released its runtime and duplicated TUN.
Only then are bridge callbacks unregistered; an unsettled session retains both
its callbacks and its stop obligation for the destroy retry. The original
descriptor is closed after the stop attempt so Android can tear down the VPN.
Every VPN restart creates a new TUN and calls `SetTun` again. `onDestroy()`
finally calls `Engine.Close()`; failed closes retain their Java callback proxies
because tcptun-go may still invoke them while completing shutdown.

Bridge resource ownership is tracked separately from the Go status strings with
an explicit state machine:

```text
Idle -> Preparing -> TunTransferPending -> StartPending -> SessionOwned
                                                      -> Stopping
Stopping -> CallbacksOwned -> Idle
                         \-> Closed (successful Engine.Close)
```

The state claims callback/TUN cleanup before crossing JNI, so a partially
successful `SetTun` or `Start` cannot leak ownership. `STOP_TIMEOUT` remains in
`Stopping` and is retryable; a terminal stopped/error session advances only after
the Go-owned TUN duplicate is confirmed released. The service's original
`ParcelFileDescriptor` is held by a separate exclusive-owner slot: replacement
cannot overwrite it, and close first atomically detaches it from the service.
A process-wide runtime lease also serializes old and newly-created
`VpnService` instances. Because native teardown continues off the Android main
thread after `onDestroy()`, a replacement service waits a bounded interval for
the old instance to release both its Go resources and original TUN before it may
establish another VPN or start another Engine.

Underlying-network handovers are settled briefly before rebuilding the bridge,
and the temporary no-network selection is not treated as a replacement network.
If a rebuild still fails during the handover, the service keeps the desired
profile state and foreground ownership, then retries with exponential backoff
capped at 30 seconds. A successful start resets the backoff; an explicit stop,
a newer start command, or service destruction cancels the pending recovery.
Removing the app from Recents does not stop the VPN service; the service
reasserts its existing foreground notification without rebuilding the tunnel.

`SetAppIdentityProvider` uses the source and destination tuple reported directly
by the native TUN inbound on Android 10 and newer. The provider resolves that
TCP tuple with `ConnectivityManager.getConnectionOwnerUid`, maps the UID to
installed package names, and returns a local-only app identity to tcptun-go.
Managed app rules use the multi-valued `attributes.packages` matcher so shared
UID packages are handled conservatively. On Android 9 and older, app identity is
unavailable and app rules simply do not match; ordinary routing continues.
Android per-app VPN filtering is not used: the VPN still captures IPv4 and IPv6
default routes and lets tcptun-go select an outbound per flow.

Traffic analysis can select one installed app on Android 10 or newer. The
service installs the app identity provider before startup, then configures
`SetFlowAnalysisApp` and `FlowCallback`. Changing or clearing the selected app
uses a dedicated service action and updates the running Engine without
restarting the VPN. The provider cache follows the selected package for shared
UID handling, while Android still defensively rejects callbacks whose `app.id`
belongs to the previous selection. Successful TCP connections and first
successful UDP sends are folded into a session/sequence-ordered, 256-item in-memory list.
The Material 3 traffic analysis page shows restored domains or literal IPs,
ports, outbound tags, route reasons, and bridge drop counts. Events are never
persisted and are cleared when the selected app changes or the process exits.

Profiles can also store a complete strict tcptun-go JSON document. The app
preserves all supported `log`, `inbounds`, `outbounds`, `route`, and `dns`
fields, removes the retired top-level `discovery` field from older saved
configs. VPN rules use the platform inbound tag `tun`; a separate `android-vpn`
SOCKS5/mixed inbound remains available for local or LAN proxy clients.
Managed route rules match `tun` by default. Enable **Route local proxy traffic**
in Settings to also match the local mixed/SOCKS inbound (`local` for structured
profiles and `android-vpn` for complete JSON profiles).

Build the AAR through this Kotlin project wrapper:

```bash
./scripts/build-androidbridge.sh
```

If `tcptun-go` is not a sibling checkout, point the wrapper at it:

```bash
TCPTUN_GO_DIR=/path/to/tcptun-go ./scripts/build-androidbridge.sh
```

The wrapper builds only `armeabi-v7a`, `arm64-v8a`, and `x86_64` by default,
matching the Gradle ABI filters and avoiding an unused x86 native library in the
intermediate AAR. Set `ANDROID_TARGET` to override the gomobile target list.

If `gomobile` is missing:

```bash
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init
```

The generated AAR is copied to `app/libs/androidbridge.aar`. The file is ignored
by Git and is rebuilt by the release workflow from the pinned tcptun-go revision
listed above.

## Build the Android app

```bash
./gradlew :app:assembleDebug
```

Run the same local quality gates enforced for pull requests and releases:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
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

Then publish from a clean, up-to-date `main` branch:

```bash
make publish VERSION=v0.2.4
```

The publish command validates the semantic version and Android version code,
updates the defaults in `gradle.properties`, rebuilds the Android Bridge, runs
the local Gradle quality gates, creates a release commit and annotated tag, and
pushes both to `origin`. Use `RELEASE_BRANCH` or `RELEASE_REMOTE` to override the
required branch or remote. For local release-script testing without a push, run
`./scripts/release.sh v0.2.4 --no-push` (this still creates a local commit and
tag).

The resulting GitHub Release contains a universal APK plus smaller
`arm64-v8a`, `armeabi-v7a`, and `x86_64` APKs, each with a SHA-256 checksum.
Pre-release tags such as `v1.2.3-rc.1` are marked as pre-releases.

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
4. Tap `⇩` to import a URI share link, or enter profile name, server address, port, protocol, transport, UUID/password/token, SNI, path, TLS, REALITY, QUIC REALITY, mux, upstream, and UDP settings manually.
5. Tap a profile row to start or stop only that connection. Every running structured profile joins the same dynamic pool; while the VPN is running, the row action hot-starts or hot-stops only that outbound.
6. Tap the share icon to export a URI link for the profile protocol.
7. Approve the Android VPN prompt when starting the first connection.
8. Open a browser and visit a site.
9. Tap the bottom status line to view recent logs.

To let another device use the running Android client, enable listening on all interfaces and set
that device's SOCKS proxy to `PHONE_LAN_IP:1080`. Select the local `mixed` listener protocol before
using an HTTP proxy client such as the iOS Wi-Fi proxy setting. The phone and the other device must
be on a network that permits inbound connections to the phone.

Each row has a share action. Swipe a row to the left to delete it; the snackbar action can undo the deletion. Profiles are saved locally in `SharedPreferences`.

HTTPS App Link imports use this envelope:

```text
https://x.tcptun.com/v1#p=<BASE64URL(profile-uri)>
```

`v1` is the envelope version. `p` is the UTF-8 profile URI encoded as unpadded
Base64URL. The fragment keeps the profile payload out of ordinary HTTP requests
and server access logs. The current version accepts these inner profile URI
schemes:

- `native://`
- `vless://`
- `vmess://` with Base64 JSON
- `trojan://`

The app still accepts the inner schemes directly for compatibility. Opening a
validated `https://x.tcptun.com/v1` Android App Link launches TcpTun and shows a
Material 3 confirmation before saving it. Existing equivalent profiles are
reused instead of duplicated; imported links never start the VPN
automatically. The domain must publish a matching release-signing association at
`https://x.tcptun.com/.well-known/assetlinks.json` for verified App Link routing.

Every system-link, clipboard, and QR import must pass URI decoding, Android
profile validation, and the Go core's non-listening runtime construction before
it is persisted. A rejected link leaves the connection list unchanged.

Example VLESS/REALITY format:

```text
vless://00000000-0000-4000-8000-000000000000@203.0.113.10:443?security=reality&encryption=none&pbk=PUBLIC_KEY_PLACEHOLDER&headerType=none&fp=chrome&spx=%2F&type=tcp&flow=xtls-rprx-vision&sni=example.com#example
```

Example native TCP REALITY format:

```text
native://TOKEN@203.0.113.10:443?v=1&type=raw&security=reality&sni=example.com&fp=chrome&pbk=PUBLIC_KEY_PLACEHOLDER&sid=SHORT_ID&spx=%2F&mux=true&carrier_mode=tcp#example
```

Example native/QUIC REALITY format:

```text
native://TOKEN@203.0.113.10:443?v=1&type=raw&security=reality&sni=example.com&fp=chrome&pbk=PUBLIC_KEY_PLACEHOLDER&sid=SHORT_ID&mux=true&carrier_mode=quic&carrier_udp_mode=auto#example
```

## Supported

- Kotlin + Jetpack Compose Android app.
- `VpnService` with foreground service notification.
- Config persistence with `SharedPreferences`.
- Independently started local profiles with add, edit, delete, and share actions; active structured profiles form one dynamically weighted, session-affine pool.
- URI sharing and compact `T3:` QR import/export for native, VLESS, VMess, and Trojan profiles. REALITY uses `security=reality`; `carrier_mode=tcp|auto|quic` selects the physical carrier independently. T3 preserves carrier selection, QUIC UDP mode, and receive-window overrides; import remains compatible with legacy `T2:` payloads and the removed `reality-tcp` / `reality-quic` forms. Versioned payloads use tcptun-go's `EncodeProfile` / `DecodeProfile` bridge API and a dedicated profile DTO; Android only renders and scans the QR image.
- Protocol and transport selection UI.
- Optional token, SNI, path, TLS, TLS insecure, REALITY short ID, ECH ClientHello protection, carrier mode, mux limits, and upstream protocol UI. Native REALITY supports independent `tcp`, `auto`, and `quic` carrier selection; QUIC and automatic selection require mux. ECH uses native + raw + security none + TCP carrier and requires a matching server private key. Because tcptun-go's URI and T2/T3 formats deliberately cannot represent ECH, ECH profiles run and persist locally but do not expose URI/QR sharing.
- IPv4/IPv6 default routes send all VPN traffic into tcptun-go; explicit rules run first and unmatched traffic uses the balanced active-profile pool.
- Status display: `Stopped`, `Starting`, `Running`, `Error`.
- Recent log display.
- Native tcptun-go TUN forwarding.
- Runtime reflection bridge to gomobile AAR.
- In-app diagnostics for VPN, underlying network, bridge state, local proxy reachability, MTU, TCP/UDP mode, and socket protect.
- Runtime MTU settings.
- Strict tcptun-go topology config and ordered managed routing.
- Native TUN TCP/UDP forwarding with in-tunnel DNS interception and fake-IP restoration.
- Android 10+ per-app outbound routing for TCP and UDP flows.
- Android 10+ single-app successful destination analysis through `SetFlowAnalysisApp` and `FlowCallback`.
- Import, edit, persist, share, and run complete strict tcptun-go JSON profiles.
- Full JSON profiles preserve the new server-only native TLS passthrough fallback fields without exposing them in the client endpoint editor.

## Not yet supported

- Building the Go AAR from Gradle automatically.
