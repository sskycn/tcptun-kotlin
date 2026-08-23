package com.tcptun.client

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboundTcpingRuntimeTest {
    @Test
    fun invalidRequestIsRejectedWithoutAProbe() {
        val harness = Harness()
        harness.state.currentRequestId = 7L

        assertFalse(harness.runtime.request(request(id = 7L, host = " ")))

        assertEquals(listOf(7L to "invalid TCPing request"), harness.state.failures)
        assertEquals(0, harness.probeCalls)
    }

    @Test
    fun requestWithoutRunningRuntimeFailsBeforeScheduling() {
        val harness = Harness()
        harness.state.currentRequestId = 1L
        harness.activeOwnership = null

        assertFalse(harness.runtime.request(request()))

        assertEquals("connections are still starting", harness.state.failures.single().second)
        assertTrue(harness.executor.tasks.isEmpty())
    }

    @Test
    fun ownershipChangedBeforeFirstProbeFailsTheCapturedSession() {
        val harness = Harness()
        harness.start(request())
        harness.activeOwnership = null

        harness.executor.runNext()

        assertEquals(0, harness.probeCalls)
        assertEquals("VPN session changed", harness.state.failures.single().second)
        assertTrue(harness.state.results.isEmpty())
    }

    @Test
    fun ownershipChangedDuringNativeProbeCannotPublishItsResult() {
        val harness = Harness()
        harness.onProbe = { harness.activeOwnership = ownership(serviceInstance = 2L) }
        harness.start(request())

        harness.executor.runNext()

        assertEquals(1, harness.probeCalls)
        assertTrue(harness.state.results.isEmpty())
        assertEquals("VPN session changed", harness.state.failures.single().second)
    }

    @Test
    fun ownershipChangedBetweenProfilesStopsBeforeTheNextProbe() {
        val harness = Harness(profileNames = listOf("A", "B"))
        harness.state.afterComplete = { harness.activeOwnership = ownership(epoch = 20L) }
        harness.start(request())

        harness.executor.runNext()

        assertEquals(1, harness.probeCalls)
        assertEquals(listOf("A"), harness.state.results.map(TcpingLinkResult::profileName))
        assertEquals("VPN session changed", harness.state.failures.single().second)
    }

    @Test
    fun samePhysicalEpochAuxiliaryTokenChangeInvalidatesTheRequest() {
        val harness = Harness()
        harness.start(request())
        harness.activeOwnership = ownership(generation = 2, epoch = 10L)

        harness.executor.runNext()

        assertEquals(0, harness.probeCalls)
        assertEquals("VPN session changed", harness.state.failures.single().second)
    }

    @Test
    fun requestBSupersedesQueuedRequestA() {
        val harness = Harness()
        harness.start(request(id = 1L))
        harness.start(request(id = 2L))

        harness.executor.runAll()

        assertEquals(1, harness.probeCalls)
        assertEquals(listOf(2L), harness.state.finished)
        assertEquals(listOf(2L), harness.state.resultRequestIds)
    }

    @Test
    fun totalBatchDeadlineProducesAResultWithoutStartingAnotherProbe() {
        val clock = MutableClock()
        val harness = Harness(
            profileNames = listOf("A", "B"),
            clock = clock,
            policy = OutboundTcpingPolicy(
                attemptTimeoutMillis = 10L,
                profileTimeoutMillis = 20L,
                batchTimeoutMillis = 10L,
            ),
        )
        harness.onProbe = { clock.now = 11L }
        harness.start(request())

        harness.executor.runNext()

        assertEquals(1, harness.probeCalls)
        assertEquals(2, harness.state.results.size)
        assertTrue(harness.state.results[1].error.contains("overall TCPing deadline elapsed"))
        assertEquals(listOf("TCPing failed on 1 connection(s)"), harness.memberProbeReasons)
    }

    @Test
    fun profileFailureRequestsMemberHealthProbeAfterPublishingTheBatch() {
        val harness = Harness()
        harness.probeFailure = IllegalStateException("dial failed")
        harness.start(request())

        harness.executor.runNext()

        assertEquals("dial failed", harness.state.results.single().error)
        assertEquals(listOf(1L), harness.state.finished)
        assertEquals(listOf("TCPing failed on 1 connection(s)"), harness.memberProbeReasons)
    }

    @Test
    fun staleRequestCannotFinishTheNewerRequest() {
        val harness = Harness()
        harness.start(request(id = 1L))
        harness.start(request(id = 2L))

        harness.executor.runNext()

        assertTrue(harness.state.finished.isEmpty())
        assertTrue(harness.state.results.isEmpty())

        harness.executor.runNext()

        assertEquals(listOf(2L), harness.state.finished)
        assertTrue(harness.state.failures.isEmpty())
    }

    private class Harness(
        profileNames: List<String> = listOf("A"),
        private val clock: MutableClock = MutableClock(),
        policy: OutboundTcpingPolicy = OutboundTcpingPolicy(),
    ) {
        val executor = ManualExecutorService()
        val state = FakeStatePort()
        var activeOwnership: VpnRuntimeOwnership? = ownership()
        var probeCalls = 0
        var probeFailure: Throwable? = null
        var onProbe: () -> Unit = {}
        val memberProbeReasons = mutableListOf<String>()
        private val plan = ProfileRunPlan(
            profileNames.mapIndexed { index, name ->
                AppConfig(id = "profile-$index", name = name, serverHost = "127.0.0.1")
            },
        )
        val runtime = OutboundTcpingRuntime(
            bridgePort = object : TcpingBridgePort {
                override fun probeOutbound(
                    ownership: VpnRuntimeOwnership,
                    tag: String,
                    host: String,
                    port: Int,
                    timeoutMillis: Long,
                ): Long {
                    probeCalls += 1
                    onProbe()
                    probeFailure?.let { throw it }
                    return 12L
                }
            },
            currentOwnership = { activeOwnership },
            isOwnershipCurrent = { it == activeOwnership },
            currentPlan = { plan },
            connectionsReady = { true },
            publishIfOwned = { captured, publication ->
                if (captured != activeOwnership) false else {
                    publication()
                    true
                }
            },
            publishSessionChanged = { captured, requestId ->
                if (captured != activeOwnership && state.isCurrent(requestId)) {
                    state.fail(requestId, "VPN session changed")
                }
            },
            state = state,
            onMemberProbeRequested = { reason, _ -> memberProbeReasons += reason },
            executor = executor,
            policy = policy,
            nowMillis = { clock.now },
            pause = { clock.now += it },
        )

        fun start(request: OutboundTcpingRequest) {
            state.currentRequestId = request.requestId
            assertTrue(runtime.request(request))
        }
    }

    private class FakeStatePort : OutboundTcpingStatePort {
        var currentRequestId = 0L
        val results = mutableListOf<TcpingLinkResult>()
        val resultRequestIds = mutableListOf<Long>()
        val finished = mutableListOf<Long>()
        val failures = mutableListOf<Pair<Long, String>>()
        var afterComplete: () -> Unit = {}

        override fun isCurrent(requestId: Long): Boolean =
            currentRequestId == requestId && finished.lastOrNull() != requestId &&
                failures.none { it.first == requestId }

        override fun isLatest(requestId: Long): Boolean = currentRequestId == requestId

        override fun beginStep(requestId: Long, index: Int, total: Int, profileName: String) = Unit

        override fun completeStep(requestId: Long, result: TcpingLinkResult) {
            resultRequestIds += requestId
            results += result
            afterComplete()
        }

        override fun finish(requestId: Long) {
            finished += requestId
        }

        override fun fail(requestId: Long, error: String) {
            failures += requestId to error
        }

        override fun log(message: String) = Unit
    }

    private class MutableClock(var now: Long = 0L)

    private class ManualExecutorService : AbstractExecutorService() {
        val tasks = ArrayDeque<Runnable>()
        private var shutdown = false

        override fun execute(command: Runnable) {
            check(!shutdown) { "executor is shut down" }
            tasks.addLast(command)
        }

        override fun shutdown() {
            shutdown = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shutdown = true
            return tasks.toMutableList().also { tasks.clear() }
        }

        override fun isShutdown(): Boolean = shutdown
        override fun isTerminated(): Boolean = shutdown && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated

        fun runNext() {
            tasks.removeFirst().run()
        }

        fun runAll() {
            while (tasks.isNotEmpty()) runNext()
        }
    }

    private companion object {
        fun request(id: Long = 1L, host: String = "example.com") = OutboundTcpingRequest(
            requestId = id,
            targetLabel = "Target",
            host = host,
            port = 443,
        )

        fun ownership(
            serviceInstance: Long = 1L,
            generation: Int = 1,
            epoch: Long = 10L,
        ) = VpnRuntimeOwnership(
            runtimeToken = VpnRuntimeCommandToken(serviceInstance, generation, generation),
            bridgeEpoch = epoch,
        )
    }
}
