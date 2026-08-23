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
                    VpnPlatformStopResult.Released
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
                    VpnPlatformStopResult.Released
                },
            )
            releaseStart.countDown()
            assertTrue(stopCompleted.await(2, TimeUnit.SECONDS))
            awaitInFlight(coordinator)

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
                    VpnPlatformStopResult.Released
                },
            )
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
            val second = coordinator.claimStop(ServiceId, "second")
            coordinator.dispatchStop(
                second, "second", VpnRuntimeStopOptions(), { throw AssertionError(it) },
                stopRuntime = { _, _, owner ->
                    assertTrue(owner())
                    secondCompleted.countDown()
                    VpnPlatformStopResult.Released
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
    fun retainedPlatformStopStaysStoppingUntilOwnedRetryReleasesResources() {
        val coordinator = VpnRuntimeCoordinator(ExecutorDirect) { true }
        val running = plan("A")
        val runningToken = coordinator.claimStart(ServiceId, true)
        startImmediately(coordinator, runningToken, running)
        assertFalse(
            coordinator.completePlatformStop(runningToken, VpnPlatformStopResult.Released),
        )
        val stop = coordinator.claimStop(ServiceId, "retry stop")

        coordinator.dispatchStop(
            stop,
            "retry stop",
            VpnRuntimeStopOptions(),
            { throw AssertionError(it) },
            stopRuntime = { _, _, _ -> VpnPlatformStopResult.RetainedForRetry },
        )

        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Stopping)
        assertSame(running, coordinator.snapshot.runningPlan)
        assertTrue(coordinator.completePlatformStop(stop, VpnPlatformStopResult.Released))
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Idle)
        assertNull(coordinator.snapshot.runningPlan)
    }

    @Test
    fun outboundUpdateCommitsOnlyAfterNativeMutationAndPersistence() {
        val coordinator = VpnRuntimeCoordinator(ExecutorDirect) { true }
        val current = membershipPlan("A")
        val next = membershipPlan("B")
        startImmediately(coordinator, coordinator.claimStart(ServiceId, true), current)
        val update = coordinator.claimAuxiliaryCommand(ServiceId, persistent = true)
        val order = mutableListOf<String>()

        coordinator.dispatchOutboundUpdate(
            token = update,
            request = VpnRuntimeOutboundUpdateRequest(next, hasRuntimeResources = true),
            onFailure = { throw AssertionError(it) },
            persistPlan = { plan, owner ->
                assertTrue(owner())
                assertSame(next, plan)
                order += "persist"
                true
            },
            mutateOutbound = { profile, running, owner ->
                assertTrue(owner())
                order += "native-${profile.id}-$running"
            },
            onCommitted = { order += "commit" },
            onRolledBack = { _, error -> throw AssertionError(error) },
            onMutationFailure = { error, _ -> throw AssertionError(error) },
            onReplacementRequired = { _, _, _ -> error("unexpected replacement") },
        )

        assertEquals(listOf("native-A-false", "native-B-true", "persist", "commit"), order)
        assertSame(next, coordinator.snapshot.runningPlan)
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Running)
    }

    @Test
    fun outboundUpdateSupersededByStopDoesNotRollbackOrReplace() {
        val coordinator = VpnRuntimeCoordinator(ExecutorDirect) { true }
        val current = membershipPlan("A")
        val next = membershipPlan("B")
        startImmediately(coordinator, coordinator.claimStart(ServiceId, true), current)
        val update = coordinator.claimAuxiliaryCommand(ServiceId, persistent = true)
        var mutations = 0
        var replacementRequested = false

        coordinator.dispatchOutboundUpdate(
            token = update,
            request = VpnRuntimeOutboundUpdateRequest(next, hasRuntimeResources = true),
            onFailure = { throw AssertionError(it) },
            persistPlan = { _, _ -> error("stale update persisted") },
            mutateOutbound = { _, _, owner ->
                assertTrue(owner())
                mutations += 1
                coordinator.claimStop(ServiceId, "stop during update")
            },
            onCommitted = { error("stale update committed") },
            onRolledBack = { _, _ -> error("stale update rolled back") },
            onMutationFailure = { _, _ -> error("unexpected update failure") },
            onReplacementRequired = { _, _, _ -> replacementRequested = true },
        )

        assertEquals(1, mutations)
        assertFalse(replacementRequested)
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Stopping)
        assertSame(current, coordinator.snapshot.runningPlan)
    }

    @Test
    fun outboundFailureBeforeNativeMutationKeepsCurrentPlan() {
        val coordinator = VpnRuntimeCoordinator(ExecutorDirect) { true }
        val current = membershipPlan("A")
        startImmediately(coordinator, coordinator.claimStart(ServiceId, true), current)
        val update = coordinator.claimAuxiliaryCommand(ServiceId, persistent = true)
        var attempted = true

        coordinator.dispatchOutboundUpdate(
            token = update,
            request = VpnRuntimeOutboundUpdateRequest(current, hasRuntimeResources = true),
            onFailure = { throw AssertionError(it) },
            persistPlan = { _, _ -> error("persistence failure") },
            mutateOutbound = { _, _, _ -> error("native mutation was not expected") },
            onCommitted = { error("failed update committed") },
            onRolledBack = { _, _ -> error("no native mutation needed rollback") },
            onMutationFailure = { _, nativeAttempted -> attempted = nativeAttempted },
            onReplacementRequired = { _, _, _ -> error("unexpected replacement") },
        )

        assertFalse(attempted)
        assertSame(current, coordinator.snapshot.runningPlan)
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Running)
    }

    @Test
    fun successfulOutboundRollbackRestoresCurrentPlanWithoutReplacement() {
        val coordinator = VpnRuntimeCoordinator(ExecutorDirect) { true }
        val current = membershipPlan("A")
        val next = membershipPlan("B")
        startImmediately(coordinator, coordinator.claimStart(ServiceId, true), current)
        val update = coordinator.claimAuxiliaryCommand(ServiceId, persistent = true)
        var mutationCall = 0
        var rolledBack = false

        coordinator.dispatchOutboundUpdate(
            token = update,
            request = VpnRuntimeOutboundUpdateRequest(next, hasRuntimeResources = true),
            onFailure = { throw AssertionError(it) },
            persistPlan = { _, _ -> error("persistence should not run") },
            mutateOutbound = { _, _, _ ->
                mutationCall += 1
                if (mutationCall == 2) error("native failure")
            },
            onCommitted = { error("failed update committed") },
            onRolledBack = { plan, _ ->
                assertSame(current, plan)
                rolledBack = true
            },
            onMutationFailure = { _, attempted -> assertTrue(attempted) },
            onReplacementRequired = { _, _, _ -> error("rollback should avoid replacement") },
        )

        assertEquals(4, mutationCall)
        assertTrue(rolledBack)
        assertSame(current, coordinator.snapshot.runningPlan)
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Running)
    }

    @Test
    fun uncertainOutboundRollbackClaimsReplacementAndClearsPublishedPlan() {
        val coordinator = VpnRuntimeCoordinator(ExecutorDirect) { true }
        val current = membershipPlan("A")
        val next = membershipPlan("B")
        startImmediately(coordinator, coordinator.claimStart(ServiceId, true), current)
        val update = coordinator.claimAuxiliaryCommand(ServiceId, persistent = true)
        var mutationCall = 0
        var replacement: VpnRuntimeCommandToken? = null

        coordinator.dispatchOutboundUpdate(
            token = update,
            request = VpnRuntimeOutboundUpdateRequest(next, hasRuntimeResources = true),
            onFailure = { throw AssertionError(it) },
            persistPlan = { _, _ -> error("persistence should not run") },
            mutateOutbound = { _, _, _ ->
                mutationCall += 1
                if (mutationCall >= 2) error(if (mutationCall == 2) "native failure" else "rollback failure")
            },
            onCommitted = { error("failed update committed") },
            onRolledBack = { _, _ -> error("uncertain rollback reported success") },
            onMutationFailure = { _, attempted -> assertTrue(attempted) },
            onReplacementRequired = { token, plan, failure ->
                replacement = token
                assertSame(current, plan)
                assertTrue(failure != null)
            },
        )

        assertTrue(replacement != null)
        assertTrue(coordinator.isCurrent(requireNotNull(replacement)))
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Starting)
        assertNull(coordinator.snapshot.runningPlan)
    }

    @Test
    fun outboundUpdateSupersededByReplacementCannotPublishOrRollbackOverNewPlan() {
        val coordinator = VpnRuntimeCoordinator(ExecutorDirect) { true }
        val current = membershipPlan("A")
        val requested = membershipPlan("B")
        val finalPlan = plan("replacement")
        startImmediately(coordinator, coordinator.claimStart(ServiceId, true), current)
        val update = coordinator.claimAuxiliaryCommand(ServiceId, persistent = true)
        lateinit var replacement: VpnRuntimeCommandToken
        var mutations = 0

        coordinator.dispatchOutboundUpdate(
            token = update,
            request = VpnRuntimeOutboundUpdateRequest(requested, hasRuntimeResources = true),
            onFailure = { throw AssertionError(it) },
            persistPlan = { _, _ -> error("stale update persisted") },
            mutateOutbound = { _, _, owner ->
                assertTrue(owner())
                mutations += 1
                replacement = coordinator.claimReplacement(ServiceId)
            },
            onCommitted = { error("stale update committed") },
            onRolledBack = { _, _ -> error("stale update rolled back") },
            onMutationFailure = { _, _ -> error("unexpected update failure") },
            onReplacementRequired = { _, _, _ -> error("stale update requested fallback") },
        )
        startImmediately(coordinator, replacement, finalPlan)

        assertEquals(1, mutations)
        assertSame(finalPlan, coordinator.snapshot.runningPlan)
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Running)
    }

    @Test
    fun recoveryTransactionOwnsRecoveringAndRunningTransitions() {
        val coordinator = VpnRuntimeCoordinator(ExecutorDirect) { true }
        val running = plan("A")
        startImmediately(coordinator, coordinator.claimStart(ServiceId, true), running)
        val token = coordinator.currentToken(ServiceId)

        coordinator.dispatchRecovery(
            token = token,
            request = VpnRuntimeRecoveryRequest(running, "health failure"),
            onFailure = { throw AssertionError(it) },
            recoverRuntime = { request, _, owner, commit ->
                assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Recovering)
                assertTrue(coordinator.snapshot.bridgeRestarting)
                assertTrue(owner())
                assertTrue(commit(request.plan))
            },
            rollbackRecovery = { _, _, error, _ -> throw AssertionError(error) },
            onRetryRequired = { _, _, error -> throw AssertionError(error) },
        )

        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Running)
        assertSame(running, coordinator.snapshot.runningPlan)
        assertFalse(coordinator.snapshot.bridgeRestarting)
    }

    @Test
    fun staleRecoveryRetryCannotRunAfterStopClaimsOwnership() {
        val coordinator = VpnRuntimeCoordinator(ExecutorDirect) { true }
        val running = plan("A")
        startImmediately(coordinator, coordinator.claimStart(ServiceId, true), running)
        val recovery = coordinator.currentToken(ServiceId)
        var retryToken: VpnRuntimeCommandToken? = null

        coordinator.dispatchRecovery(
            token = recovery,
            request = VpnRuntimeRecoveryRequest(running, "health failure"),
            onFailure = { throw AssertionError(it) },
            recoverRuntime = { _, _, _, _ -> error("restart failed") },
            rollbackRecovery = { _, _, _, superseded -> assertFalse(superseded) },
            onRetryRequired = { token, _, _ -> retryToken = token },
        )
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Recovering)

        coordinator.claimStop(ServiceId, "stop before retry")
        var staleRecoveryRan = false
        coordinator.dispatchRecovery(
            token = requireNotNull(retryToken),
            request = VpnRuntimeRecoveryRequest(running, "stale retry"),
            onFailure = { throw AssertionError(it) },
            recoverRuntime = { _, _, _, _ -> staleRecoveryRan = true },
            rollbackRecovery = { _, _, _, _ -> error("stale recovery rolled back") },
            onRetryRequired = { _, _, _ -> error("stale recovery scheduled another retry") },
        )

        assertFalse(staleRecoveryRan)
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Stopping)
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

    private fun membershipPlan(activeId: String): ProfileRunPlan {
        val profiles = listOf(AppConfig(id = "A", name = "A"), AppConfig(id = "B", name = "B"))
        return ProfileRunPlan(profiles, linkedSetOf(activeId))
    }

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
