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
