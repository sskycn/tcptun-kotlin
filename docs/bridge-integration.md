# Bridge/AAR integration

## Boundary

Production uses:

```text
ReflectionTcptunBridge -> androidbridge.Androidbridge / Engine -> tcptun-go
```

JVM lifecycle tests use `FakeTcptunBridge` through the existing `TcptunBridge` interface.
The fake is under `app/src/test`; it is not packaged into the application.

## Required bridge contract

The wrapper uses Engine operations for Configure, SetPowerSave, SetTun, Start, Stop,
WaitStopped, Abort, Close, callbacks, outbound control, status, and diagnostics. The current
AAR exposes `Abort`, `CoreVersion`, and `CoreBuildID`. `SetPowerSave(bool)` is a session-start-only
integration option: Kotlin calls it before every Start, and settings changes replace the runtime.

`SetTun` receives the Android FD as a native duplicate. Go must never close the original
Android `ParcelFileDescriptor`; Android closes that descriptor only after the stop controller
confirms native release.

The stateless Bridge API also owns `EncodeProfile`, `DecodeProfile`, and profile QR rendering,
plus `EncodeProxyAccount`, `DecodeProxyAccount`, and `EncodeProxyAccountQRCode` for one A1 local
proxy account. The A1 methods use the strict `{username,password}` DTO only at the Bridge boundary;
JSON is not the share wire.

## Bundled AAR and provenance

`app/libs/androidbridge.aar` is a version-controlled release input. A fresh clone uses it directly
for development, CI, and Android releases without a tcptun-go checkout or Go toolchain.

`bridge.lock` pins the expected core commit and Bridge API version. The AAR embeds
`bridge-version.properties` containing the full tcptun-go commit, `git describe` version,
clean-tree flag, and Bridge API version. All important build/check paths run strict verification;
a missing, damaged, dirty, or mismatched artifact is a build error.

Verify the bundled artifact with:

```bash
./gradlew :app:verifyAndroidBridge
./gradlew :app:verifyAndroidBridge -PexpectedCoreCommit=<full-tcptun-go-commit>
```

## Updating the AAR

Changing the Bridge is an explicit maintainer operation, separate from normal app builds and
releases. First select the desired tcptun-go commit and update `bridge.lock`, then use a clean
checkout at exactly that commit:

```bash
./scripts/build-androidbridge.sh
TCPTUN_GO_DIR=/path/to/tcptun-go ./scripts/build-androidbridge.sh
```

The wrapper defaults to `armeabi-v7a`, `arm64-v8a`, and `x86_64`, matching Gradle filters.
The wrapper replaces `app/libs/androidbridge.aar` and embeds its provenance metadata after a
successful build. No wall-clock timestamp is included so identical inputs remain reproducible.
Verify the result, then review and commit `bridge.lock` and the AAR together.

## Compatibility rules

- Do not modify the tcptun-go ABI from this Android project.
- Keep `bridge.lock` pinned to tcptun-go v0.5.0 commit
  `b454f9892a0d978c6ed5d2f6e05ab5989e995c26`. This includes Android TUN confidentiality checks,
  bounded P2P traversal
  retry/prediction, background readiness, aggregate safe diagnostics, and the
  `balanced|aggressive` strategy field. The exported gomobile contract is unchanged
  and remains Bridge API 3.
- Pass local `socks5`/`mixed` accounts as `users[]` (maximum 256); omit the field for no-auth.
  The public mobile Bridge API includes the A1 account codec and is version 3.
- Keep Android's health-probe `Socks5Client` as an RFC 1928/1929 client. Private method `0x80`,
  HKDF/HMAC negotiation, HTTP 407/Basic handling, and header stripping remain Go-owned.
- The JSON passed to `Engine.Configure` is an internal App-to-Core representation generated only
  from structured profiles. It is not a user-importable Full Config surface.
- T2/T3 profile encoders and decoders accept only TLS or REALITY. Kotlin must not implement A1
  binary or Base45 encoding.
- Keep callback proxies strongly reachable until native cleanup completes.
- Keep reflection error messages actionable: missing AAR, missing Engine method, and
  validation failure are distinct user-visible failure classes.
- Structured profile DTOs and runtime JSON are Native-only. Do not emit outbound `uuid`, map a
  removed protocol credential to `token`, or emit REALITY `fingerprint`.
- `ValidateConfig` and the final attached-TUN start boundary reject every Native outbound whose
  security is not TLS or REALITY. Direct outbounds remain valid.
- The profile DTO fields `carrierMode`, `carrierPrefer`, and `carrierUdpMode` map to outbound
  `carrier.mode`, `carrier.prefer`, and `carrier.udp_mode`. Preference is outbound-only and valid
  only with `carrier.mode=auto`; Kotlin configures it but never implements carrier selection.
- Native auto is a Core-owned TCP + QUIC topology for both TLS and REALITY. Both socket families
  continue through the existing `SocketProtector` boundary; Android does not open a QUIC socket or
  schedule QUIC probes.
- Increment `bridge.lock`'s Bridge API version whenever the required Java contract changes.

## Contract tests

`AndroidBridgeContractTest` and `AarLifecycleTest` run on Android and validate the real AAR.
`BridgeSessionControllerTest`, `BridgeSessionStopControllerTest`, and
`BridgeLifecycleIntegrationTest` run on the JVM and cover failure injection without loading
the native library.
