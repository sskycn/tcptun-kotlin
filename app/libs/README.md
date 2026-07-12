Build the gomobile binding artifact from the neighboring `tcptun-go` checkout:

```bash
./scripts/build-androidbridge.sh
```

The wrapper writes `app/libs/androidbridge.aar` and `app/libs/androidbridge-sources.jar`.
The app creates one `androidbridge.Engine` per `VpnService` instance through the
`Androidbridge.newEngine()` API, loaded by reflection at runtime.
