# Release process

## Source quality gate

CI and local verification use the same aggregate task:

```bash
./gradlew qualityGate
```

It runs `testDebugUnitTest`, `lintDebug`, `lintRelease`,
`compileDebugAndroidTestKotlin`, and `maintainabilityCheck`. Unit tests include the
offline Java ABI contract for the checked-in `androidbridge.aar`.

The checked-in AAR is a reviewed release input. Strict provenance verification compares its
embedded `bridge-version.properties` with `bridge.lock` and runs as part of the quality gate as
well as release artifact tasks:

```bash
./gradlew :app:verifyAndroidBridge
```

## Formal versioned release

Configure all four release signing values in `signing.properties` (see
`signing.properties.example`) or with the `TCPTUN_RELEASE_*` environment variables.
The referenced keystore file must exist. Release artifact tasks fail when signing is
missing; an unsigned artifact cannot be committed and tagged by the release script.

Run:

```bash
make publish VERSION=vX.Y.Z
```

For a local commit and tag without pushing:

```bash
./scripts/release.sh vX.Y.Z --no-push
```

The formal script has no test-skip mode. Before changing the version it verifies a clean
worktree, required branch, remote alignment, nonexistent local/remote tag, signing configuration,
and strict bundled-Bridge provenance. It then runs `qualityGate`, updates the app version,
and runs `bundleRelease` before creating the release commit and annotated tag. It never checks out
tcptun-go, invokes Go/gomobile, changes `bridge.lock`, or regenerates the AAR.

Upgrading the Bridge is a separate maintainer workflow: choose a tcptun-go commit, update
`bridge.lock`, run `scripts/build-androidbridge.sh` from a clean checkout at that exact commit,
verify the result, and review/commit the lock and AAR together before starting an app release.

## Signed device-install helper

`release.bash` is a signed APK build/install helper, not the formal version/tag workflow:

```bash
sh release.bash
```

It requires signing and an attached device, and runs `qualityGate`, strict Bridge
verification, and `assembleRelease`. The helper is unchanged and retains its explicit
`BUILD_BRIDGE=1` maintainer option; the formal `make publish` workflow never uses it.

Release builds enable R8/resource shrinking. `bundleRelease` also creates a native
debug-symbol ZIP through `packageReleaseNativeSymbols`.

## Compatibility checklist

- Confirm the AAR ABI set matches Gradle filters.
- Confirm the Bridge method set and Profile/URI schema match the pinned Core contract.
- Run App Link, persistence, QR, and SavedState security tests on Android.
- Test VPN start/stop and replacement on at least one physical or emulated device.
- Archive the app version, core build ID, AAB, and native symbols together.
