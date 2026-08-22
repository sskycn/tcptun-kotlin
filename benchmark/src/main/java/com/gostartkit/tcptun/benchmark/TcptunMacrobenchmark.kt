package com.tcptun.client.benchmark

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class TcptunMacrobenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartToUsableProfileList() = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        check(device.wait(Until.hasObject(By.text("Profiles")), UI_TIMEOUT_MS))
    }

    @Test
    fun vpnStartupToRunning() = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.WARM,
        iterations = 3,
        setupBlock = {
            startFixture("vpn", 1)
            check(device.wait(Until.hasObject(By.text("Benchmark VPN")), UI_TIMEOUT_MS))
            device.executeShellCommand("appops set $PACKAGE_NAME ACTIVATE_VPN allow")
            killProcess()
        },
    ) {
        startActivityAndWait()
        val profile = device.wait(Until.findObject(By.text("Benchmark VPN")), UI_TIMEOUT_MS)
            ?: error("benchmark VPN profile did not become usable")
        profile.click()
        check(device.wait(Until.hasObject(By.text("Running")), VPN_TIMEOUT_MS))
    }

    @Test
    fun flowAnalysisThousandEventUpdate() = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Last)),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.WARM,
        iterations = 3,
        setupBlock = { killProcess() },
    ) {
        startFixture("flow", 1_000)
        check(device.wait(Until.hasObject(By.textContains("Flow")), UI_TIMEOUT_MS))
        device.swipe(
            device.displayWidth / 2,
            device.displayHeight * 3 / 4,
            device.displayWidth / 2,
            device.displayHeight / 4,
            20,
        )
    }

    @Test
    fun thousandProfileListScroll() = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Last)),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.WARM,
        iterations = 3,
        setupBlock = {
            startFixture("profiles", 1_000)
            check(device.wait(Until.hasObject(By.text("Benchmark 0000")), UI_TIMEOUT_MS))
            killProcess()
        },
    ) {
        startActivityAndWait()
        check(device.wait(Until.hasObject(By.text("Benchmark 0000")), UI_TIMEOUT_MS))
        repeat(6) {
            device.swipe(
                device.displayWidth / 2,
                device.displayHeight * 3 / 4,
                device.displayWidth / 2,
                device.displayHeight / 4,
                12,
            )
        }
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.startFixture(mode: String, count: Int) {
        startActivityAndWait(
            Intent(Intent.ACTION_MAIN)
                .setComponent(ComponentName(PACKAGE_NAME, "$PACKAGE_NAME.BenchmarkFixtureActivity"))
                .putExtra("benchmarkMode", mode)
                .putExtra("benchmarkCount", count),
        )
    }

    private companion object {
        const val PACKAGE_NAME = "com.tcptun.client"
        const val UI_TIMEOUT_MS = 15_000L
        const val VPN_TIMEOUT_MS = 30_000L
    }
}
