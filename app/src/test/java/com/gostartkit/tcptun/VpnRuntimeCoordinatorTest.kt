package com.tcptun.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnRuntimeCoordinatorTest {
    @Test
    fun idleStartCommitsRunningSnapshot() {
        val coordinator = VpnRuntimeCoordinator(ExecutorDirect) { true }
        val plan = plan("A")
        val token = coordinator.claimStart(ServiceId, persistent = true)

        assertTrue(
            coordinator.dispatchStart(
                token = token,
                request = request(plan),
                onFailure = { throw AssertionError(it) },
                hasRuntimeResources = { false },
                stopExisting = { _, _ -> throw AssertionError("idle start stopped a runtime") },
                startRuntime = { _, _, owner, commit ->
                    assertTrue(owner())
                    assertTrue(commit(plan))
                },
                rollbackStart = { _, _, error, _ -> throw AssertionError(error) },
            ),
        )

        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Running)
        assertSame(plan, coordinator.snapshot.runningPlan)
        assertEquals(token.lifecycleGeneration, coordinator.snapshot.lifecycleGeneration)
        assertEquals(token.persistentGeneration, coordinator.snapshot.persistentCommandGeneration)
    }

    @Test
    fun runningStartStopsOldRuntimeBeforeStartingReplacement() {
        val coordinator = VpnRuntimeCoordinator(ExecutorDirect) { true }
        val first = plan("A")
        val second = plan("B")
        var resourcesOwned = false
        val order = mutableListOf<String>()

        startImmediately(coordinator, coordinator.claimStart(ServiceId, true), first) {
            resourcesOwned = true
        }
        val replacement = coordinator.claimReplacement(ServiceId)
        coordinator.dispatchStart(
            token = replacement,
            request = request(second),
            onFailure = { throw AssertionError(it) },
            hasRuntimeResources = { resourcesOwned },
            stopExisting = { _, owner ->
                assertTrue(owner())
                order += "stop-A"
                resourcesOwned = false
            },
            startRuntime = { _, _, owner, commit ->
                assertTrue(owner())
                assertFalse(resourcesOwned)
                order += "start-B"
                resourcesOwned = true
                assertTrue(commit(second))
            },
            rollbackStart = { _, _, error, _ -> throw AssertionError(error) },
        )

        assertEquals(listOf("stop-A", "start-B"), order)
        assertSame(second, coordinator.snapshot.runningPlan)
    }

    @Test
    fun failureAtEveryStartStageRollsBackLeaseTunAndBridgeOwnership() {
        StartFailureStage.entries.forEach { failureStage ->
            val coordinator = VpnRuntimeCoordinator(ExecutorDirect) { true }
            val token = coordinator.claimStart(ServiceId, persistent = true)
            var leaseOwned = false
            var tunOwned = false
            var bridgeOwned = false
            var observedFailure: Throwable? = null

            coordinator.dispatchStart(
                token = token,
                request = request(plan("failure-$failureStage")),
                onFailure = { observedFailure = it },
                hasRuntimeResources = { false },
                stopExisting = { _, _ -> error("unexpected stop") },
                startRuntime = { startRequest, _, owner, commit ->
                    if (failureStage == StartFailureStage.BeforeTun) error("before TUN")
                    leaseOwned = true
                    if (failureStage == StartFailureStage.AfterLease) error("after lease")
                    tunOwned = true
                    if (failureStage == StartFailureStage.AfterTun) error("after TUN")
                    bridgeOwned = true
                    if (failureStage == StartFailureStage.AfterBridgeConfigure) error("after configure")
                    if (failureStage == StartFailureStage.AfterBridgeStart) error("after start")
                    assertTrue(owner())
                    assertTrue(commit(startRequest.command.plan))
                },
                rollbackStart = { _, _, _, _ ->
                    bridgeOwned = false
                    tunOwned = false
                    leaseOwned = false
                },
            )

            assertTrue("$failureStage must report failure", observedFailure != null)
            assertFalse("$failureStage leaked lease", leaseOwned)
            assertFalse("$failureStage leaked TUN", tunOwned)
            assertFalse("$failureStage leaked Bridge", bridgeOwned)
            assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Idle)
            assertNull(coordinator.snapshot.runningPlan)
        }
    }

    @Test
    fun restartSupersededByStopCannotPublishRunning() {
        val executor = newLifecycleScheduledExecutor("restart-stop-test")
        val coordinator = VpnRuntimeCoordinator(executor) { true }
        val first = plan("A")
        val replacement = plan("B")
        val stoppingOld = CountDownLatch(1)
        val releaseOldStop = CountDownLatch(1)
        val finished = CountDownLatch(2)
        var replacementStarted = false
        try {
            startAndAwait(coordinator, coordinator.claimStart(ServiceId, true), first)
            val replacementToken = coordinator.claimReplacement(ServiceId)
            coordinator.dispatchStart(
                token = replacementToken,
                request = request(replacement),
                onFailure = { throw AssertionError(it) },
                hasRuntimeResources = { true },
                stopExisting = { _, _ ->
                    stoppingOld.countDown()
                    assertTrue(releaseOldStop.await(2, TimeUnit.SECONDS))
                },
                startRuntime = { _, _, _, _ -> replacementStarted = true },
                rollbackStart = { _, _, error, _ -> throw AssertionError(error) },
            )
            assertTrue(stoppingOld.await(2, TimeUnit.SECONDS))
            val stopToken = coordinator.claimStop(ServiceId, "user stop")
            coordinator.dispatchStop(
                token = stopToken,
                reason = "user stop",
                options = VpnRuntimeStopOptions(),
                onFailure = { throw AssertionError(it) },
                stopRuntime = { _, _, owner ->
                    assertTrue(owner())
                    finished.countDown()
                    true
                },
            )
            releaseOldStop.countDown()
            finished.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            awaitInFlight(coordinator)

            assertFalse(replacementStarted)
            assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Idle)
            assertNull(coordinator.snapshot.runningPlan)
            assertTrue(coordinator.snapshot.explicitStopRequested)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun stopWhileStartingInvalidatesLateRunningCommit() {
        val executor = newLifecycleScheduledExecutor("start-stop-test")
        val coordinator = VpnRuntimeCoordinator(executor) { true }
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val stopCompleted = CountDownLatch(1)
        var lateCommitAccepted = true
        try {
            val startToken = coordinator.claimStart(ServiceId, persistent = true)
            coordinator.dispatchStart(
                startToken, request(plan("A")), { throw AssertionError(it) }, { false }, { _, _ -> },
                { startRequest, _, owner, commit ->
                    startEntered.countDown()
                    assertTrue(releaseStart.await(2, TimeUnit.SECONDS))
                    lateCommitAccepted = owner() && commit(startRequest.command.plan)
                },
                { _, _, _, _ -> },
            )
            assertTrue(startEntered.await(2, TimeUnit.SECONDS))
            val stopToken = coordinator.claimStop(ServiceId, "stop while starting")
            coordinator.dispatchStop(
                stopToken,
                "stop while starting",
                VpnRuntimeStopOptions(),
                { throw AssertionError(it) },
                stopRuntime = { _, _, owner ->
                    assertTrue(owner())
                    stopCompleted.countDown()
                    true
                },
            )
            releaseStart.countDown()
            assertTrue(stopCompleted.await(2, TimeUnit.SECONDS))

            assertFalse(lateCommitAccepted)
            assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Idle)
            assertNull(coordinator.snapshot.runningPlan)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun newerDuplicateStopSupersedesOlderStop() {
        val executor = newLifecycleScheduledExecutor("duplicate-stop-test")
        val coordinator = VpnRuntimeCoordinator(executor) { true }
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondCompleted = CountDownLatch(1)
        var firstPublished = false
        try {
            val first = coordinator.claimStop(ServiceId, "first")
            coordinator.dispatchStop(
                first, "first", VpnRuntimeStopOptions(), { throw AssertionError(it) },
                stopRuntime = { _, _, owner ->
                    firstEntered.countDown()
                    assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
                    firstPublished = owner()
                    true
                },
            )
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
            val second = coordinator.claimStop(ServiceId, "second")
            coordinator.dispatchStop(
                second, "second", VpnRuntimeStopOptions(), { throw AssertionError(it) },
                stopRuntime = { _, _, owner ->
                    assertTrue(owner())
                    secondCompleted.countDown()
                    true
                },
            )
            releaseFirst.countDown()
            assertTrue(secondCompleted.await(2, TimeUnit.SECONDS))

            assertFalse(firstPublished)
            assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Idle)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun tokenFromAnotherServiceInstanceCannotOwnCallbacks() {
        val coordinator = VpnRuntimeCoordinator(ExecutorDirect) { true }
        val token = coordinator.claimStart(ServiceId, persistent = true)
        val staleServiceToken = token.copy(serviceInstanceId = ServiceId + 1)

        assertTrue(coordinator.isCurrent(token))
        assertFalse(coordinator.isCurrent(staleServiceToken))
    }

    @Test
    fun newerStartSupersedesQueuedReplacementAndOwnsFinalRuntime() {
        val executor = newLifecycleScheduledExecutor("replacement-supersession-test")
        val coordinator = VpnRuntimeCoordinator(executor) { true }
        val firstBlocked = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        var secondStarted = false
        var thirdStarted = false
        try {
            val tokenA = coordinator.claimStart(ServiceId, true)
            coordinator.dispatchStart(
                tokenA,
                request(plan("A")),
                { throw AssertionError(it) },
                { false },
                { _, _ -> error("unexpected stop") },
                { _, _, owner, commit ->
                    firstBlocked.countDown()
                    assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
                    if (owner()) commit(plan("A"))
                },
                { _, _, _, _ -> },
            )
            assertTrue(firstBlocked.await(2, TimeUnit.SECONDS))
            val tokenB = coordinator.claimReplacement(ServiceId)
            coordinator.dispatchStart(
                tokenB, request(plan("B")), { throw AssertionError(it) }, { false },
                { _, _ -> },
                { startRequest, _, _, commit ->
                    secondStarted = true
                    commit(startRequest.command.plan)
                },
                { _, _, _, _ -> },
            )
            val tokenC = coordinator.claimReplacement(ServiceId)
            coordinator.dispatchStart(
                tokenC, request(plan("C")), { throw AssertionError(it) }, { false },
                { _, _ -> },
                { startRequest, _, owner, commit ->
                    thirdStarted = true
                    assertTrue(owner())
                    assertTrue(commit(startRequest.command.plan))
                },
                { _, _, _, _ -> },
            )
            releaseFirst.countDown()
            awaitInFlight(coordinator)

            assertFalse(secondStarted)
            assertTrue(thirdStarted)
            assertEquals("C", coordinator.snapshot.runningPlan?.profiles?.single()?.id)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun destroyInvalidatesStartingAndStoppingCommands() {
        val coordinator = VpnRuntimeCoordinator(ExecutorDirect) { true }
        val start = coordinator.claimStart(ServiceId, persistent = true)
        coordinator.destroy(ServiceId)
        assertFalse(coordinator.isCurrent(start))
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Destroyed)
        assertTrue(coordinator.snapshot.stopping)

        val second = VpnRuntimeCoordinator(ExecutorDirect) { true }
        val stop = second.claimStop(ServiceId, "destroy during stop")
        second.destroy(ServiceId)
        assertFalse(second.isCurrent(stop))
        assertTrue(second.snapshot.phase is VpnRuntimePhase.Destroyed)
    }

    @Test
    fun runtimeMutationsExecuteSeriallyInAdmissionOrder() {
        val executor = newLifecycleScheduledExecutor("coordinator-test")
        val coordinator = VpnRuntimeCoordinator(executor) { true }
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val order = mutableListOf<Int>()
        try {
            assertTrue(coordinator.dispatch(VpnRuntimeCommand.Start, { throw AssertionError(it) }) {
                firstStarted.countDown()
                assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
                order += 1
                completed.countDown()
            })
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
            assertTrue(coordinator.dispatch(VpnRuntimeCommand.Stop, { throw AssertionError(it) }) {
                order += 2
                completed.countDown()
            })
            assertEquals(2, coordinator.inFlight)

            releaseFirst.countDown()
            assertTrue(completed.await(2, TimeUnit.SECONDS))

            assertEquals(listOf(1, 2), order)
            assertEquals(0, coordinator.inFlight)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun inactiveCoordinatorRejectsMutation() {
        val executor = newLifecycleScheduledExecutor("coordinator-rejection-test")
        try {
            val coordinator = VpnRuntimeCoordinator(executor) { false }
            assertFalse(coordinator.dispatch(VpnRuntimeCommand.Start, { throw AssertionError(it) }) {})
            assertEquals(0, coordinator.inFlight)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun startImmediately(
        coordinator: VpnRuntimeCoordinator,
        token: VpnRuntimeCommandToken,
        plan: ProfileRunPlan,
        beforeCommit: () -> Unit = {},
    ) {
        coordinator.dispatchStart(
            token = token,
            request = request(plan),
            onFailure = { throw AssertionError(it) },
            hasRuntimeResources = { false },
            stopExisting = { _, _ -> error("unexpected stop") },
            startRuntime = { _, _, owner, commit ->
                beforeCommit()
                assertTrue(owner())
                assertTrue(commit(plan))
            },
            rollbackStart = { _, _, error, _ -> throw AssertionError(error) },
        )
    }

    private fun startAndAwait(
        coordinator: VpnRuntimeCoordinator,
        token: VpnRuntimeCommandToken,
        plan: ProfileRunPlan,
    ) {
        val completed = CountDownLatch(1)
        coordinator.dispatchStart(
            token, request(plan), { throw AssertionError(it) }, { false }, { _, _ -> },
            { _, _, owner, commit ->
                assertTrue(owner())
                assertTrue(commit(plan))
                completed.countDown()
            },
            { _, _, error, _ -> throw AssertionError(error) },
        )
        assertTrue(completed.await(2, TimeUnit.SECONDS))
    }

    private fun awaitInFlight(coordinator: VpnRuntimeCoordinator) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (coordinator.inFlight != 0 && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertEquals(0, coordinator.inFlight)
    }

    private fun request(plan: ProfileRunPlan) = VpnRuntimeStartRequest(
        command = VpnStartCommand(
            configJson = "{}",
            plan = plan,
            runtimeSettings = RuntimeSettings(),
            desiredPlanJson = "{}",
        ),
        expectedProfileMutationRevision = 1,
    )

    private fun plan(id: String) = ProfileRunPlan(listOf(AppConfig(id = id, name = id)))

    private enum class StartFailureStage {
        BeforeTun,
        AfterLease,
        AfterTun,
        AfterBridgeConfigure,
        AfterBridgeStart,
    }

    private companion object {
        const val ServiceId = 7L
        val ExecutorDirect = java.util.concurrent.Executor { command -> command.run() }
    }
}
