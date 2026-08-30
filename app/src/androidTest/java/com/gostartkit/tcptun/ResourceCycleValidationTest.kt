package com.tcptun.client

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResourceCycleValidationTest {
    @Test
    fun repeatedStartStopHasBoundedResourceTrend() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "resource-cycle validation is opt-in",
            arguments.getString(EnabledArgument).toBoolean(),
        )
        val cycles = arguments.getString(CyclesArgument)?.toIntOrNull()?.coerceIn(1, 500)
            ?: DefaultCycles

        VpnRuntimeStressHarness().use { harness ->
            harness.stop()
            harness.waitForStopped(timeoutMillis = 30_000)
            waitForSessionThreadsToTerminate(harness)
            settleForSampling()
            printSample(cycle = 0, state = "idle")

            val fdBaselines = mutableListOf<Int>()
            repeat(cycles) { index ->
                harness.start(if (index % 2 == 0) harness.lifecyclePlanA else harness.lifecyclePlanB)
                harness.waitForRunning(timeoutMillis = 30_000)
                val running = harness.activeOwnershipSnapshot()
                assertEquals("Running", running.runtimePhase)
                assertEquals(BridgeResourcePhase.SessionOwned, running.bridgeResourcePhase)
                assertTrue(running.tunOwned)
                assertTrue(running.bridgeEpoch > 0L)
                assertEquals(running.serviceInstanceId, running.leaseOwner)
                assertTrue(running.connectionsReady)

                harness.stop()
                harness.waitForStopped(timeoutMillis = 30_000)
                waitForSessionThreadsToTerminate(harness)
                settleForSampling()
                fdBaselines += printSample(cycle = index + 1, state = "stopped")
            }

            if (fdBaselines.size >= 10) {
                val first = fdBaselines.take(5).sorted()[2]
                val last = fdBaselines.takeLast(5).sorted()[2]
                assertTrue("FD baseline drift: $fdBaselines", last <= first + 8)
            }
            harness.assertNoProcessFailureEvidence()
            println("RESOURCE_CYCLES_COMPLETED=$cycles")
        }
    }

    private fun waitForSessionThreadsToTerminate(harness: VpnRuntimeStressHarness) {
        harness.waitUntil("Actor/lifecycle thread termination", 10_000) {
            val names = liveJavaThreadNames()
            names.none { it == ActorThreadName || it == LifecycleThreadName }
        }
    }

    private fun settleForSampling() {
        Runtime.getRuntime().gc()
        Thread.sleep(SampleSettleMillis)
    }

    private fun printSample(cycle: Int, state: String): Int {
        val memory = Debug.MemoryInfo()
        Debug.getMemoryInfo(memory)
        val javaHeapKb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024L
        val nativeHeapKb = Debug.getNativeHeapAllocatedSize() / 1024L
        val fd = requireNotNull(File("/proc/self/fd").list()) { "cannot sample /proc/self/fd" }.size
        val threads = requireNotNull(File("/proc/self/task").list()) { "cannot sample /proc/self/task" }.size
        val rssKb = File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }
                ?.substringAfter(':')
                ?.trim()
                ?.substringBefore(' ')
                ?.toLongOrNull()
        }
        requireNotNull(rssKb) { "cannot parse VmRSS from /proc/self/status" }
        val names = liveJavaThreadNames()
        val actorThreads = names.count { it == ActorThreadName }
        val lifecycleThreads = names.count { it == LifecycleThreadName }

        println(
            "RESOURCE_CSV,$cycle,${System.currentTimeMillis()},$state,$fd,$threads," +
                "$javaHeapKb,$nativeHeapKb,${memory.totalPss},$rssKb",
        )
        println("RESOURCE_THREADS,$cycle,$actorThreads,$lifecycleThreads")
        return fd
    }

    private fun liveJavaThreadNames(): List<String> =
        Thread.getAllStackTraces().keys.filter(Thread::isAlive).map(Thread::getName)

    private companion object {
        const val EnabledArgument = "resourceCycleEnabled"
        const val CyclesArgument = "resourceCycleCount"
        const val DefaultCycles = 100
        const val SampleSettleMillis = 250L
        const val ActorThreadName = "TcptunRuntimeActor"
        const val LifecycleThreadName = "TcptunLifecycle"
    }
}
