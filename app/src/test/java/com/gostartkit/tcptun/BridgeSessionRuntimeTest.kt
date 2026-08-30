package com.tcptun.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeSessionRuntimeTest {
    @Test
    fun stopReleasesAStartWaitingForCoreReadyExceptionally() {
        val nativeStarted = CountDownLatch(1)
        val bridge = FakeTcptunBridge(onStart = nativeStarted::countDown)
        val harness = Harness(bridge)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val start = executor.submit<Unit> { harness.start() }
            assertTrue(nativeStarted.await(2, TimeUnit.SECONDS))

            harness.runtime.stopSession(10L, harness.callbacks())

            assertThrows(Exception::class.java) { start.get(2, TimeUnit.SECONDS) }
            assertEquals(BridgeResourcePhase.Idle, harness.resources.snapshot.phase)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun supersededStartIsRejectedBeforeNativeStart() {
        val bridge = FakeTcptunBridge()
        val harness = Harness(bridge)
        harness.onEpochBegan = { harness.owner.set(false) }

        assertThrows(IllegalStateException::class.java) { harness.start() }

        assertFalse(bridge.calls.contains("start"))
        assertEquals(BridgeResourcePhase.Preparing, harness.resources.snapshot.phase)
    }

    @Test
    fun staleTokenAfterNativeStartCannotPublishSessionReadySuccess() {
        lateinit var harness: Harness
        val bridge = FakeTcptunBridge(onStart = { harness.owner.set(false) })
        harness = Harness(bridge)

        assertThrows(IllegalStateException::class.java) { harness.start() }

        assertTrue(bridge.calls.contains("start"))
        assertEquals(0, harness.verifiedStatuses)
        assertEquals(BridgeResourcePhase.SessionOwned, harness.resources.snapshot.phase)
    }

    @Test
    fun oldEpochReadyCannotReleaseReplacementWaiterOrMutateDiagnostics() {
        val bridge = FakeTcptunBridge()
        val harness = Harness(bridge)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<Unit> { harness.start() }
            awaitCall(bridge, "start")
            bridge.emitStatus(statusJson(sessionId = 1L, state = "core_ready"))
            first.get(2, TimeUnit.SECONDS)
            harness.runtime.stopSession(10L, harness.callbacks())

            val mutationsBeforeOldEvent = harness.statusMutations
            val second = executor.submit<Unit> { harness.start() }
            awaitCallCount(bridge, "start", 2)
            bridge.emitStatusFromInstallation(
                0,
                statusJson(sessionId = 1L, sequence = 2L, state = "core_ready"),
            )

            assertFalse(second.isDone)
            assertEquals(mutationsBeforeOldEvent, harness.statusMutations)
            bridge.emitStatus(statusJson(sessionId = 2L, state = "core_ready"))
            second.get(2, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun partiallyOwnedNativeStartRetainsCleanupUntilStopSettles() {
        val bridge = FakeTcptunBridge(
            failures = FakeTcptunBridge.Failures(setTun = IllegalStateException("partial SetTun")),
        )
        val harness = Harness(bridge)
        val androidTun = ExclusiveResourceOwner<Int>().apply { acquire(77) }

        assertThrows(IllegalStateException::class.java) { harness.start() }
        assertEquals(BridgeResourcePhase.TunTransferPending, harness.resources.snapshot.phase)
        assertTrue(harness.resources.snapshot.nativeStopRequired)
        assertEquals(77, androidTun.resource)

        harness.runtime.stopSession(10L, harness.callbacks())
        assertEquals(BridgeResourcePhase.Idle, harness.resources.snapshot.phase)
        assertEquals(77, androidTun.resource)
    }

    @Test
    fun stopDuringPartiallyOwnedNativeStartPreservesCleanupObligation() {
        val enteredStart = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val bridge = FakeTcptunBridge(onStart = {
            enteredStart.countDown()
            assertTrue(releaseStart.await(2, TimeUnit.SECONDS))
        })
        val harness = Harness(bridge)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val start = executor.submit<Unit> { harness.start() }
            assertTrue(enteredStart.await(2, TimeUnit.SECONDS))
            val stop = executor.submit<Unit> {
                harness.runtime.stopSession(10L, harness.callbacks())
            }
            awaitPhase(harness.resources, BridgeResourcePhase.Stopping)

            assertTrue(harness.resources.snapshot.nativeStopRequired)
            releaseStart.countDown()
            assertThrows(Exception::class.java) { start.get(2, TimeUnit.SECONDS) }
            stop.get(2, TimeUnit.SECONDS)
            assertEquals(BridgeResourcePhase.Idle, harness.resources.snapshot.phase)
        } finally {
            releaseStart.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun failedStopAndAbortRetainTheSameObligationAcrossRepeatedStops() {
        val bridge = FakeTcptunBridge(
            failures = FakeTcptunBridge.Failures(
                stop = IllegalStateException("stop failed"),
                waitStopped = IllegalStateException("wait failed"),
                abort = IllegalStateException("abort failed"),
            ),
            currentStatus = "Error",
            currentStatusReason = "STOP_TIMEOUT",
        )
        val harness = Harness(bridge)
        harness.prepareStartedSession()

        assertThrows(IllegalStateException::class.java) {
            harness.runtime.stopSession(10L, harness.callbacks())
        }
        val first = harness.resources.snapshot
        assertThrows(IllegalStateException::class.java) {
            harness.runtime.stopSession(10L, harness.callbacks())
        }

        assertEquals(BridgeResourcePhase.Stopping, harness.resources.snapshot.phase)
        assertTrue(harness.resources.snapshot.nativeStopRequired)
        assertEquals(first, harness.resources.snapshot)
        assertFalse(bridge.callbacksCleared)
    }

    @Test
    fun stoppingIdleSessionIsSafeAndRuntimeNeverClosesAndroidTun() {
        val bridge = FakeTcptunBridge()
        val harness = Harness(bridge, bridgeInitialized = false)
        val androidTun = ExclusiveResourceOwner<Int>().apply { acquire(99) }

        harness.runtime.stopSession(10L, harness.callbacks())

        assertEquals(BridgeResourcePhase.Idle, harness.resources.snapshot.phase)
        assertEquals(99, androidTun.resource)
        assertFalse(bridge.calls.contains("stop"))
    }

    @Test
    fun sessionHealthAndTcpingPortsShareTheExactBridgeLock() {
        val lock = Any()
        val bridge = FakeTcptunBridge()
        val resources = BridgeResourceStateMachine()
        val runtime = BridgeSessionRuntime({ bridge }, { true }, lock, resources)
        val health = LockedHealthBridgePort(lock, { bridge }, { true }, { true })
        val tcping = LockedTcpingBridgePort(lock, { bridge }, { true })

        assertTrue(runtime.sharesBridgeLock(lock))
        assertTrue(health.sharesBridgeLock(lock))
        assertTrue(tcping.sharesBridgeLock(lock))
        assertFalse(runtime.sharesBridgeLock(Any()))
    }

    @Test
    fun controlDiagnosticsDistinguishWaitingForLockFromEnteredNativeCall() {
        val lock = Any()
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        val waiting = java.util.concurrent.CountDownLatch(1)
        val owner = VpnRuntimeOwnership(VpnRuntimeCommandToken(3, 4, 5), 6)
        val bridge = FakeTcptunBridge()
        val port = LockedHealthBridgePort(lock, { bridge }, { true }, { true }) { line ->
            lines += line
            if ("phase=waiting_lock" in line) waiting.countDown()
        }
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val result = synchronized(lock) {
                val future = executor.submit<String> { port.statusJson(owner) }
                assertTrue(waiting.await(1, java.util.concurrent.TimeUnit.SECONDS))
                assertFalse(future.isDone)
                assertTrue(lines.none { "phase=entered" in it })
                future
            }
            result.get(1, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(lines.any { "phase=entered" in it })
            assertTrue(lines.any { "phase=returned" in it })
            assertTrue(lines.all { "service_id=3" in it && "bridge_epoch=6" in it })
        } finally {
            executor.shutdownNow()
        }
    }

    private class Harness(
        private val bridge: FakeTcptunBridge,
        bridgeInitialized: Boolean = true,
    ) {
        val resources = BridgeResourceStateMachine()
        val owner = AtomicBoolean(true)
        private val epochSequence = AtomicLong()
        private val activeEpoch = AtomicLong()
        var verifiedStatuses = 0
            private set
        var statusMutations = 0
            private set
        var onEpochBegan: () -> Unit = {}
        val runtime = BridgeSessionRuntime(
            bridge = { bridge },
            bridgeInitialized = { bridgeInitialized },
            bridgeLock = Any(),
            resources = resources,
        )

        fun start() = runtime.startSession(request(), callbacks())

        fun callbacks() = BridgeSessionRuntimeCallbacks(
            commandOwner = owner::get,
            beginBridgeEpoch = {
                check(owner.get())
                epochSequence.incrementAndGet().also {
                    activeEpoch.set(it)
                    resources.beginPreparation(it)
                    onEpochBegan()
                }
            },
            endBridgeEpoch = { epoch ->
                if (epoch > 0L) activeEpoch.compareAndSet(epoch, 0L)
            },
            onLog = {},
            onStatusEvent = { epoch, json ->
                if (epoch != activeEpoch.get()) {
                    null
                } else {
                    statusMutations += 1
                    event(
                        when {
                            json.contains("core_ready") -> "core_ready"
                            json.contains("stopped") -> "stopped"
                            else -> "error"
                        },
                    )
                }
            },
            protectSocket = { true },
            configureFlowAnalysis = { _, _, _ -> },
            onInitialStatus = { _, _ -> },
            onSessionStarted = { epoch, _ ->
                check(owner.get() && epoch == activeEpoch.get())
            },
            onVerifiedStatus = { epoch, _ ->
                if (owner.get() && epoch == activeEpoch.get()) {
                    verifiedStatuses += 1
                    true
                } else {
                    false
                }
            },
            onOptionalEventRegistrationFailure = { _, _ -> },
            onNativeStillStopping = {},
            onNativeStoppedWithError = {},
            onCleanupFailure = { _, _ -> },
        )

        fun prepareStartedSession() {
            val epoch = epochSequence.incrementAndGet()
            activeEpoch.set(epoch)
            resources.beginPreparation(epoch)
            resources.beginTunTransfer()
            resources.beginStart()
            resources.sessionStarted(41L, "config-json")
        }

        private fun request() = BridgeSessionRuntimeStartRequest(
            configJson = "config-json",
            disabledOutboundTags = emptyList(),
            tunFd = 77,
            mtu = 1400,
            settings = AppliedRuntimeSettings(),
            readyTimeoutMillis = 5_000L,
        )
    }

    private companion object {
        fun event(state: String) = BridgeStatusEvent(
            sessionId = 1L,
            sequence = 1L,
            state = state,
            reason = "",
            phase = "",
            listen = "",
            remote = "",
            outboundTag = "",
            activeConnections = 0,
            clientIps = emptyList(),
            muxSources = 0,
            muxSessions = 0,
            muxStreams = 0,
            recoverable = false,
            lastError = "",
            timestampMs = 0L,
        )

        fun statusJson(
            sessionId: Long,
            sequence: Long = 1L,
            state: String,
        ) = """{"session_id":$sessionId,"sequence":$sequence,"state":"$state"}"""

        fun awaitCall(bridge: FakeTcptunBridge, call: String) = awaitCallCount(bridge, call, 1)

        fun awaitCallCount(bridge: FakeTcptunBridge, call: String, count: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (bridge.calls.count { it == call } < count && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertTrue("timed out waiting for $call", bridge.calls.count { it == call } >= count)
        }

        fun awaitPhase(resources: BridgeResourceStateMachine, phase: BridgeResourcePhase) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (resources.snapshot.phase != phase && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals(phase, resources.snapshot.phase)
        }
    }
}
