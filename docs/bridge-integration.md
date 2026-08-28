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

## Building the AAR

```bash
./scripts/build-androidbridge.sh
TCPTUN_GO_DIR=/path/to/tcptun-go ./scripts/build-androidbridge.sh
```

The wrapper defaults to `armeabi-v7a`, `arm64-v8a`, and `x86_64`, matching Gradle filters.
The generated file is `app/libs/androidbridge.aar` and is ignored by Git.
The wrapper embeds `bridge-version.properties` in the AAR after a successful build. It records
the full tcptun-go commit, `git describe` version, and integer Bridge API version; no wall-clock
timestamp is included so identical inputs remain reproducible.

Verify a locally built artifact with:

```bash
./gradlew :app:verifyAndroidBridge
./gradlew :app:verifyAndroidBridge -PexpectedCoreCommit=<full-tcptun-go-commit>
```

`assembleRelease` and `bundleRelease` depend on strict verification. Debug quality gates use a
warning-only verifier so Android-only CI can test the Kotlin control plane without manufacturing a
fake native artifact. Bridge and managed-device CI build the real AAR in a separate job.

## Compatibility rules

- Do not modify the tcptun-go ABI from this Android project.
- Keep `bridge.lock` pinned to the exact tcptun-go source used to generate the AAR. Proxy-auth
  core upgrades that do not change `mobile/androidbridge` keep the existing Bridge API version.
- Pass local `socks5`/`mixed` accounts as `users[]` (maximum 256); omit the field for no-auth.
  Preserve legacy top-level credentials only when they already belong to a non-reserved raw
  inbound. The core commit is `159053ff8853d278130da25e887c9360b83a2454`; its public mobile
  Bridge API includes the A1 account codec and is version 3.
- Preserve `auth_mode` on raw JSON `socks5`/`mixed` outbounds. Go defaults credentialed outbounds
  to `secure`; explicit `standard` and `auto` policies remain Go-owned and are not reimplemented
  in Kotlin.
- Keep Android's health-probe `Socks5Client` as an RFC 1928/1929 client. Private method `0x80`,
  HKDF/HMAC negotiation, HTTP 407/Basic handling, and header stripping remain Go-owned.
- Do not change profile JSON fields or T2/T3/A1 URI/QR formats here. Kotlin must not implement
  A1 binary or Base45 encoding.
- Keep callback proxies strongly reachable until native cleanup completes.
- Keep reflection error messages actionable: missing AAR, missing Engine method, and
  validation failure are distinct user-visible failure classes.
- Preserve every authenticated inbound `users[]` record in full JSON, including per-user VLESS
  flow. Never add `users` to an outbound or implement credential matching/crypto in Kotlin.
- Increment `bridge.lock`'s Bridge API version whenever the required Java contract changes.

## Contract tests

`AndroidBridgeContractTest` and `AarLifecycleTest` run on Android and validate the real AAR.
`BridgeSessionControllerTest`, `BridgeSessionStopControllerTest`, and
`BridgeLifecycleIntegrationTest` run on the JVM and cover failure injection without loading
the native library.
