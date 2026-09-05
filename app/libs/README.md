`androidbridge.aar` is an intentional vendored binary dependency tracked by Git. Ordinary
development, CI, and Android app releases consume this file directly and do not need a
`tcptun-go` checkout, Go, or gomobile.

`bridge.lock` pins the tcptun-go core commit and Bridge API version. The AAR contains
`bridge-version.properties` with the actual core commit/version, clean-tree flag, and Bridge API
version. Gradle strictly verifies those values before build, test, lint, and release tasks.

Maintainers update the artifact explicitly from a clean tcptun-go checkout at the commit selected
in `bridge.lock`:

```bash
./scripts/build-androidbridge.sh
# or: TCPTUN_GO_DIR=/path/to/tcptun-go ./scripts/build-androidbridge.sh
./gradlew :app:verifyAndroidBridge --no-daemon
```

The wrapper writes `app/libs/androidbridge.aar` and `app/libs/androidbridge-sources.jar`.
Commit `bridge.lock` and `app/libs/androidbridge.aar` together in the same reviewed change;
the sources JAR remains an optional, ignored build byproduct.
By default it builds only the app-supported Android architectures (`arm`, `arm64`,
and `amd64`), omitting the unused 32-bit x86 library. Override `ANDROID_TARGET`
when a different gomobile target set is required.
The app creates one `androidbridge.Engine` per `VpnService` instance through the
`Androidbridge.newEngine()` API, loaded by reflection at runtime.
