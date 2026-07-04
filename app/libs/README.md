Build the gomobile binding artifact from the neighboring `tcptun-go` checkout:

```bash
./scripts/build-androidbridge.sh
```

The wrapper script writes `app/libs/androidbridge.aar`. The app loads `androidbridge.Androidbridge` by reflection at runtime.
