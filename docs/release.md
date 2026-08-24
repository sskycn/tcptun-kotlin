# Release process

## Source quality gate

CI and local verification use the same aggregate task:

```bash
./gradlew qualityGate
```

It runs `testDebugUnitTest`, `lintDebug`, `lintRelease`,
`compileDebugAndroidTestKotlin`, and `maintainabilityCheck`. Unit tests include the
offline Java ABI contract for the checked-in `androidbridge.aar`.

Strict Bridge provenance is deliberately separate because a formal release also
requires a clean, pinned sibling `tcptun-go` checkout:

```bash
./scripts/build-androidbridge.sh --verify-release
./scripts/build-androidbridge.sh
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

The formal script has no test-skip mode and rejects `ALLOW_UNPINNED_BRIDGE=1`. Before
changing the version it verifies a clean worktree, required branch, remote alignment,
nonexistent local/remote tag, signing configuration, `bridge.lock`, the clean pinned Go
checkout, a reproducible AAR rebuild, and strict embedded provenance. It then runs
`qualityGate`, strict Bridge verification, and `bundleRelease` before creating the
release commit and annotated tag.

## Signed device-install helper

`release.bash` is a signed APK build/install helper, not the formal version/tag workflow:

```bash
sh release.bash
```

It requires signing and an attached device, and runs `qualityGate`, strict Bridge
verification, and `assembleRelease`. `BUILD_BRIDGE=1` can rebuild from the latest
configured `tcptun-go` branch in a temporary worktree before those gates.

Release builds enable R8/resource shrinking. `bundleRelease` also creates a native
debug-symbol ZIP through `packageReleaseNativeSymbols`.

## Compatibility checklist

- Confirm the AAR ABI set matches Gradle filters.
- Confirm no Bridge method or Profile/URI schema changed.
- Run App Link, persistence, QR, and SavedState security tests on Android.
- Test VPN start/stop and replacement on at least one physical or emulated device.
- Archive the app version, core build ID, AAB, and native symbols together.
