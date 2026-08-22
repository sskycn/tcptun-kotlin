# Bridge/AAR integration

## Boundary

Production uses:

```text
ReflectionTcptunBridge -> androidbridge.Androidbridge / Engine -> tcptun-go
```

JVM lifecycle tests use `FakeTcptunBridge` through the existing `TcptunBridge` interface.
The fake is under `app/src/test`; it is not packaged into the application.

## Required bridge contract

The wrapper uses the existing Engine operations for Configure, SetTun, Start, Stop,
WaitStopped, Abort, Close, callbacks, outbound control, status, and diagnostics. The current
AAR already exposes `Abort`, `CoreVersion`, and `CoreBuildID`. This project does not add or
rename gomobile methods.

`SetTun` receives the Android FD as a native duplicate. Go must never close the original
Android `ParcelFileDescriptor`; Android closes that descriptor only after the stop controller
confirms native release.

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
- Do not change profile JSON fields or URI/QR formats here.
- Keep callback proxies strongly reachable until native cleanup completes.
- Keep reflection error messages actionable: missing AAR, missing Engine method, and
  validation failure are distinct user-visible failure classes.
- Increment the Android expected Bridge API version and the build script's `BRIDGE_API_VERSION`
  together whenever the required Java contract changes incompatibly.

## Contract tests

`AndroidBridgeContractTest` and `AarLifecycleTest` run on Android and validate the real AAR.
`BridgeSessionControllerTest`, `BridgeSessionStopControllerTest`, and
`BridgeLifecycleIntegrationTest` run on the JVM and cover failure injection without loading
the native library.
