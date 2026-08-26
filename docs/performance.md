# Performance baselines

The `benchmark` module owns Macrobenchmark and Baseline Profile generation. Its target is the
non-debuggable `benchmark` app build, which includes a fixture activity that is not present in
debug or release manifests.

Covered journeys:

- cold process start until the profile list is usable;
- selecting a local direct profile until VPN status becomes Running;
- injecting 1,000 flow events and rendering the bounded 256-event view;
- scrolling a list seeded with 1,000 encrypted profiles.

Run on the configured API 35 Gradle-managed device:

```bash
./gradlew :benchmark:tcptunBenchmarkApi35BenchmarkAndroidTest
./gradlew :benchmark:tcptunBenchmarkApi35BenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.tcptun.client.benchmark.BaselineProfileGenerator
```

The generated HRF output can be reviewed against the conservative checked-in
`app/src/main/baseline-prof.txt`. The project uses this manual profile path because the current
Baseline Profile Gradle plugin rejects AGP 9.2 application modules during configuration.

Benchmark results should be compared on the same host/device image. Record frame timing, memory,
and GC regressions alongside the commit and embedded Bridge metadata. The VPN journey grants the
test package VPN app-op and uses a direct local core configuration, so it does not depend on a
remote proxy.

## Power-saving baseline

Power work must be measured separately from UI benchmark throughput. Compare the same release APK,
profile, network, screen state, and device charge state before and after a change. Reset battery
accounting immediately before each run and capture both battery attribution and a Perfetto trace:

```bash
adb shell dumpsys batterystats --reset
# Run the selected scenario for a fixed duration.
adb shell dumpsys batterystats --charged > batterystats.txt
adb shell dumpsys power > power-state.txt
```

Use at least these scenarios:

- **stable foreground** — VPN Running with the app visible and no user interaction;
- **stable background** — VPN Running, app backgrounded, screen off;
- **offline background** — VPN Running/desired while the eligible underlying network is absent;
- **flow-analysis background** — flow analysis enabled with sustained matching traffic while the
  app is backgrounded.

Record process CPU time, wakeups/scheduled work visible in the trace, network/radio activity, and
whether tunnel recovery still succeeds after network return. Do not infer a battery percentage
improvement from a short run; compare repeatable CPU/wakeup deltas instead.

The intended power-saving invariants are:

- healthy bridge monitoring is event-driven; there is no routine safety polling timer;
- losing the eligible underlying network does not enqueue a member-health probe that cannot
  succeed;
- overlapping member-health settle requests share an existing delayed wake when that wake already
  covers the new request;
- with power saving enabled and the UI hidden, the actual native Flow Analysis callback is removed
  from the Go bridge. Events already collected before hiding remain in the bounded UI history, but
  new hidden-period Flow Analysis events are intentionally not collected; foreground visibility
  restores native observation immediately;
- removing the Flow Analysis callback also lets a Flow-Analysis-only runtime skip app-identity
  lookup, Flow event JSON encoding, callback queue work, and gomobile/JNI delivery. Application
  identity remains active when route rules require it, so app-routing correctness is not gated by
  UI visibility;
- with power saving enabled and the UI hidden, log history is buffered in memory instead of
  publishing a new `TcptunRuntimeState` for each log line; foreground visibility flushes it once;
- tunnel lifecycle, status/error state, network handover, app-routing decisions, and restart
  decisions are never gated by UI visibility.
