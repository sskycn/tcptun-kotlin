# Release process

## Local quality gates

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
./gradlew :app:lintRelease
./gradlew :app:assembleRelease
```

Release builds enable R8/resource shrinking and produce native debug symbols through the
`packageReleaseNativeSymbols` task when bundling a release.

## Versioning and publish

The Gradle defaults are version name `1.0` and version code `1`; release automation can pass
`-PreleaseVersionName` and `-PreleaseVersionCode`. The project Make target is:

```bash
make publish VERSION=vX.Y.Z
```

For a local release-script dry run without pushing:

```bash
./scripts/release.sh vX.Y.Z --no-push
```

The release script validates the branch/remote, updates Android version metadata, rebuilds
the bridge, runs the quality gates, and performs the requested commit/tag operations. Review
the generated native symbols and the final manifest before distribution.

## Compatibility checklist

- Confirm the AAR ABI set matches Gradle filters.
- Confirm no bridge method or profile/URI schema changed.
- Run App Link, persistence, and QR contract tests.
- Test VPN start/stop and replacement on at least one physical/emulated device.
- Archive the app version, core build ID, and native symbols together.
