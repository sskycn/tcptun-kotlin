Build the gomobile binding artifact from the neighboring `tcptun-go` checkout:

```bash
./scripts/build-androidbridge.sh
```

The wrapper writes `app/libs/androidbridge.aar` and `app/libs/androidbridge-sources.jar`.
By default it builds only the app-supported Android architectures (`arm`, `arm64`,
and `amd64`), omitting the unused 32-bit x86 library. Override `ANDROID_TARGET`
when a different gomobile target set is required.
The app creates one `androidbridge.Engine` per `VpnService` instance through the
`Androidbridge.newEngine()` API, loaded by reflection at runtime.
