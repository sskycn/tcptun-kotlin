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
                    VpnPlatformStopResult.Released
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
    fun startFailureAfterTunWithReleasedRollbackTransitionsToIdle() {
        val coordinator = VpnRuntimeCoordinator(ExecutorDirect) { true }
        val token = coordinator.claimStart(ServiceId, persistent = true)

        coordinator.dispatchStart(
            token = token,
            request = request(plan("A")),
            onFailure = {},
            hasRuntimeResources = { false },
            stopExisting = { _, _ -> error("unexpected stop") },
            startRuntime = { _, _, _, _ -> error("failed after TUN") },
            rollbackStart = { _, _, _, superseded ->
                assertFalse(superseded)
                assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.CleaningUp)
                assertTrue(coordinator.snapshot.stopping)
                VpnPlatformStopResult.Released
            },
        )

        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Idle)
        assertFalse(coordinator.snapshot.stopping)
        assertNull(coordinator.snapshot.runningPlan)
    }

    @Test
    fun retainedStartRollbackRemainsCleanupOwnedUntilRetryReleases() {
        val coordinator = VpnRuntimeCoordinator(ExecutorDirect) { true }
        val previous = plan("previous")
        startImmediately(coordinator, coordinator.claimStart(ServiceId, true), previous)
        val replacement = coordinator.claimReplacement(ServiceId)

        coordinator.dispatchStart(
            token = replacement,
            request = request(plan("replacement")),
            onFailure = {},
            hasRuntimeResources = { true },
            stopExisting = { _, owner -> assertTrue(owner()) },
            startRuntime = { _, _, _, _ -> error("failed after Bridge start") },
            rollbackStart = { _, _, _, _ -> VpnPlatformStopResult.RetainedForRetry },
        )

        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.CleaningUp)
        assertTrue(coordinator.snapshot.stopping)
        assertNull(coordinator.snapshot.runningPlan)
        assertTrue(
            coordinator.completeStartRollbackCleanup(
                replacement,
                VpnPlatformStopResult.Released,
            ),
        )
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Idle)
        assertFalse(coordinator.snapshot.stopping)
    }

    @Test
    fun retainedStartRollbackRetryCannotTransitionNewerStart() {
        val coordinator = VpnRuntimeCoordinator(ExecutorDirect) { true }
        val failed = coordinator.claimStart(ServiceId, persistent = true)
        failStartWithRetainedRollback(coordinator, failed)

        val newer = coordinator.claimStart(ServiceId, persistent = true)
        assertFalse(
            coordinator.completeStartRollbackCleanup(failed, VpnPlatformStopResult.Released),
        )
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Starting)
        assertTrue(coordinator.isCurrent(newer))

        startImmediately(coordinator, newer, plan("newer"))
        assertEquals("newer", coordinator.snapshot.runningPlan?.profiles?.single()?.id)
    }

    @Test
    fun retainedStartRollbackRetryCannotOverwriteStop() {
        val coordinator = VpnRuntimeCoordinator(ExecutorDirect) { true }
        val failed = coordinator.claimStart(ServiceId, persistent = true)
        failStartWithRetainedRollback(coordinator, failed)

        val stop = coordinator.claimStop(ServiceId, "stop cleanup-owned start")
        assertFalse(
            coordinator.completeStartRollbackCleanup(failed, VpnPlatformStopResult.Released),
        )
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Stopping)
        assertTrue(coordinator.isCurrent(stop))
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
                { _, _, _, _ -> VpnPlatformStopResult.Released },
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
            awaitInFlight(coordinator)

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
                { _, _, _, _ -> VpnPlatformStopResult.Released },
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
                { _, _, _, _ -> VpnPlatformStopResult.Released },
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
                { _, _, _, _ -> VpnPlatformStopResult.Released },
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
        val token = requireNotNull(coordinator.claimRecovery(coordinator.currentToken(ServiceId)))

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
    fun releasedRecoveryRollbackIssuesFreshRetryGeneration() {
        val coordinator = VpnRuntimeCoordinator(ExecutorDirect) { true }
        val running = plan("A")
        startImmediately(coordinator, coordinator.claimStart(ServiceId, true), running)
        val failed = requireNotNull(coordinator.claimRecovery(coordinator.currentToken(ServiceId)))
        var retry: VpnRuntimeRecoveryToken? = null

        coordinator.dispatchRecovery(
            token = failed,
            request = VpnRuntimeRecoveryRequest(running, "restart failure"),
            onFailure = { throw AssertionError(it) },
            recoverRuntime = { _, _, _, _ -> error("restart failed") },
            rollbackRecovery = { _, _, _, superseded ->
                assertFalse(superseded)
                VpnPlatformStopResult.Released
            },
            onRetryRequired = { token, _, _ -> retry = token },
        )

        val retryToken = requireNotNull(retry)
        assertTrue(retryToken.recoveryGeneration > failed.recoveryGeneration)
        assertTrue(coordinator.isCurrent(retryToken))
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Recovering)
        assertFalse(coordinator.snapshot.stopping)
        assertFalse(coordinator.snapshot.bridgeRestarting)
    }

    @Test
    fun retainedRecoveryRollbackOwnsCleanupAndDoesNotScheduleRetry() {
        val coordinator = VpnRuntimeCoordinator(ExecutorDirect) { true }
        val running = plan("A")
        startImmediately(coordinator, coordinator.claimStart(ServiceId, true), running)
        val failed = requireNotNull(coordinator.claimRecovery(coordinator.currentToken(ServiceId)))
        var retries = 0

        coordinator.dispatchRecovery(
            token = failed,
            request = VpnRuntimeRecoveryRequest(running, "restart failure"),
            onFailure = { throw AssertionError(it) },
            recoverRuntime = { _, _, _, _ -> error("restart failed") },
            rollbackRecovery = { _, token, _, superseded ->
                assertFalse(superseded)
                val cleanup = coordinator.snapshot.phase as VpnRuntimePhase.CleaningUp
                assertEquals(VpnRuntimeCleanupOwner.RecoveryRollback(token), cleanup.owner)
                VpnPlatformStopResult.RetainedForRetry
            },
            onRetryRequired = { _, _, _ -> retries += 1 },
        )

        assertEquals(0, retries)
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.CleaningUp)
        assertTrue(coordinator.snapshot.stopping)
        assertFalse(coordinator.snapshot.bridgeRestarting)
        assertNull(coordinator.snapshot.runningPlan)
    }

    @Test
    fun retainedRecoveryCleanupReleaseProducesRunnableFreshGeneration() {
        val coordinator = VpnRuntimeCoordinator(ExecutorDirect) { true }
        val running = plan("A")
        startImmediately(coordinator, coordinator.claimStart(ServiceId, true), running)
        val failed = requireNotNull(coordinator.claimRecovery(coordinator.currentToken(ServiceId)))
        dispatchRetainedRecovery(coordinator, failed, running)

        val retry = requireNotNull(
            coordinator.completeRecoveryRollbackCleanup(
                failed,
                VpnPlatformStopResult.Released,
            ),
        )

        assertTrue(retry.recoveryGeneration > failed.recoveryGeneration)
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Recovering)
        var retryRan = false
        dispatchSuccessfulRecovery(coordinator, retry, running) { retryRan = true }
        assertTrue(retryRan)
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Running)
    }

    @Test
    fun retainedRecoveryCleanupCannotCompleteAfterNewStop() {
        val coordinator = VpnRuntimeCoordinator(ExecutorDirect) { true }
        val running = plan("A")
        startImmediately(coordinator, coordinator.claimStart(ServiceId, true), running)
        val failed = requireNotNull(coordinator.claimRecovery(coordinator.currentToken(ServiceId)))
        dispatchRetainedRecovery(coordinator, failed, running)

        val stop = coordinator.claimStop(ServiceId, "stop retained recovery cleanup")

        assertNull(
            coordinator.completeRecoveryRollbackCleanup(failed, VpnPlatformStopResult.Released),
        )
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Stopping)
        assertTrue(coordinator.isCurrent(stop))
    }

    @Test
    fun retainedRecoveryCleanupCannotCompleteAfterNewStart() {
        val coordinator = VpnRuntimeCoordinator(ExecutorDirect) { true }
        val running = plan("A")
        startImmediately(coordinator, coordinator.claimStart(ServiceId, true), running)
        val failed = requireNotNull(coordinator.claimRecovery(coordinator.currentToken(ServiceId)))
        dispatchRetainedRecovery(coordinator, failed, running)

        val start = coordinator.claimStart(ServiceId, persistent = true)

        assertNull(
            coordinator.completeRecoveryRollbackCleanup(failed, VpnPlatformStopResult.Released),
        )
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Starting)
        assertTrue(coordinator.isCurrent(start))
    }

    @Test
    fun failedRecoveryCompletionCannotTransitionNewerRecoveryGeneration() {
        val coordinator = VpnRuntimeCoordinator(ExecutorDirect) { true }
        val running = plan("A")
        startImmediately(coordinator, coordinator.claimStart(ServiceId, true), running)
        val recoveryA = requireNotNull(
            coordinator.claimRecovery(coordinator.currentToken(ServiceId)),
        )
        var recoveryB: VpnRuntimeRecoveryToken? = null

        coordinator.dispatchRecovery(
            token = recoveryA,
            request = VpnRuntimeRecoveryRequest(running, "Recovery A"),
            onFailure = { throw AssertionError(it) },
            recoverRuntime = { _, activeToken, _, _ ->
                recoveryB = requireNotNull(
                    coordinator.claimRecovery(activeToken.lifecycleToken),
                )
                error("Recovery A failed after Recovery B was claimed")
            },
            rollbackRecovery = { _, _, _, superseded ->
                assertTrue(superseded)
                VpnPlatformStopResult.RetainedForRetry
            },
            onRetryRequired = { _, _, _ -> error("stale Recovery A scheduled retry") },
        )

        val current = requireNotNull(recoveryB)
        assertNull(
            coordinator.completeRecoveryRollbackCleanup(
                recoveryA,
                VpnPlatformStopResult.Released,
            ),
        )
        assertTrue(coordinator.isCurrent(current))
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Recovering)
    }

    @Test
    fun duplicateRecoveryTeardownCompletionOnlyIssuesOneRetryGeneration() {
        val coordinator = VpnRuntimeCoordinator(ExecutorDirect) { true }
        val running = plan("A")
        startImmediately(coordinator, coordinator.claimStart(ServiceId, true), running)
        val failed = requireNotNull(coordinator.claimRecovery(coordinator.currentToken(ServiceId)))
        dispatchRetainedRecovery(coordinator, failed, running)

        val first = coordinator.completeRecoveryRollbackCleanup(
            failed,
            VpnPlatformStopResult.Released,
        )
        val duplicate = coordinator.completeRecoveryRollbackCleanup(
            failed,
            VpnPlatformStopResult.Released,
        )

        assertTrue(first != null)
        assertNull(duplicate)
        assertTrue(coordinator.isCurrent(requireNotNull(first)))
    }

    @Test
    fun retainedRecoveryCleanupNeverInvokesRetryBeforeRelease() {
        val coordinator = VpnRuntimeCoordinator(ExecutorDirect) { true }
        val running = plan("A")
        startImmediately(coordinator, coordinator.claimStart(ServiceId, true), running)
        val failed = requireNotNull(coordinator.claimRecovery(coordinator.currentToken(ServiceId)))
        var retries = 0

        coordinator.dispatchRecovery(
            token = failed,
            request = VpnRuntimeRecoveryRequest(running, "restart failure"),
            onFailure = { throw AssertionError(it) },
            recoverRuntime = { _, _, _, _ -> error("restart failed") },
            rollbackRecovery = { _, _, _, _ -> VpnPlatformStopResult.RetainedForRetry },
            onRetryRequired = { _, _, _ -> retries += 1 },
        )
        fun completeTeardown(result: VpnPlatformStopResult) {
            coordinator.completeRecoveryRollbackCleanup(failed, result)?.let { retries += 1 }
        }

        completeTeardown(VpnPlatformStopResult.RetainedForRetry)
        assertEquals(0, retries)

        completeTeardown(VpnPlatformStopResult.Released)
        assertEquals(1, retries)
    }

    @Test
    fun restartFailureWithRetainedResourcesHasSingleCleanupOwnerAndNoEarlyRetry() {
        val coordinator = VpnRuntimeCoordinator(ExecutorDirect) { true }
        val running = plan("A")
        startImmediately(coordinator, coordinator.claimStart(ServiceId, true), running)
        val failed = requireNotNull(coordinator.claimRecovery(coordinator.currentToken(ServiceId)))
        var rollbackCalls = 0
        var retries = 0

        coordinator.dispatchRecovery(
            token = failed,
            request = VpnRuntimeRecoveryRequest(running, "Bridge restart"),
            onFailure = { throw AssertionError(it) },
            recoverRuntime = { _, _, _, _ -> error("restartBridge failed") },
            rollbackRecovery = { _, _, _, _ ->
                rollbackCalls += 1
                VpnPlatformStopResult.RetainedForRetry
            },
            onRetryRequired = { _, _, _ -> retries += 1 },
        )

        assertEquals(1, rollbackCalls)
        assertEquals(0, retries)
        val cleanup = coordinator.snapshot.phase as VpnRuntimePhase.CleaningUp
        assertEquals(VpnRuntimeCleanupOwner.RecoveryRollback(failed), cleanup.owner)
    }

    @Test
    fun newerRecoveryGenerationRejectsQueuedOlderRecovery() {
        val executor = QueuedExecutor()
        val coordinator = VpnRuntimeCoordinator(executor) { true }
        val running = plan("A")
        startQueued(coordinator, executor, running)
        val lifecycle = coordinator.currentToken(ServiceId)
        val recoveryA = requireNotNull(coordinator.claimRecovery(lifecycle))
        val ran = mutableListOf<String>()
        dispatchSuccessfulRecovery(coordinator, recoveryA, running) { ran += "A" }
        val recoveryB = requireNotNull(coordinator.claimRecovery(lifecycle))
        assertEquals(recoveryA.lifecycleToken, recoveryB.lifecycleToken)
        assertTrue(recoveryB.recoveryGeneration > recoveryA.recoveryGeneration)
        dispatchSuccessfulRecovery(coordinator, recoveryB, running) { ran += "B" }

        executor.runAll()

        assertEquals(listOf("B"), ran)
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Running)
    }

    @Test
    fun inProgressRecoveryCannotCommitAfterNewGenerationIsClaimed() {
        val coordinator = VpnRuntimeCoordinator(ExecutorDirect) { true }
        val running = plan("A")
        startImmediately(coordinator, coordinator.claimStart(ServiceId, true), running)
        val recoveryA = requireNotNull(
            coordinator.claimRecovery(coordinator.currentToken(ServiceId)),
        )
        var recoveryB: VpnRuntimeRecoveryToken? = null
        var staleCommitAccepted = true

        coordinator.dispatchRecovery(
            token = recoveryA,
            request = VpnRuntimeRecoveryRequest(running, "Recovery A"),
            onFailure = { throw AssertionError(it) },
            recoverRuntime = { request, activeToken, owner, commit ->
                assertTrue(owner())
                recoveryB = requireNotNull(
                    coordinator.claimRecovery(activeToken.lifecycleToken),
                )
                assertFalse(owner())
                staleCommitAccepted = commit(request.plan)
            },
            rollbackRecovery = { _, _, error, _ -> throw AssertionError(error) },
            onRetryRequired = { _, _, error -> throw AssertionError(error) },
        )

        assertFalse(staleCommitAccepted)
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Recovering)
        var recoveryBRan = false
        dispatchSuccessfulRecovery(coordinator, requireNotNull(recoveryB), running) {
            recoveryBRan = true
        }
        assertTrue(recoveryBRan)
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Running)
    }

    @Test
    fun startedRetryCallbackIsRejectedWhenNewRecoveryIsRequested() {
        val executor = QueuedExecutor()
        val coordinator = VpnRuntimeCoordinator(executor) { true }
        val running = plan("A")
        startQueued(coordinator, executor, running)
        val recoveryA = requireNotNull(coordinator.claimRecovery(coordinator.currentToken(ServiceId)))
        var retryA: VpnRuntimeRecoveryToken? = null
        dispatchFailedRecovery(coordinator, recoveryA, running) { retryA = it }
        executor.runNext()

        var staleRetryRan = false
        dispatchSuccessfulRecovery(coordinator, requireNotNull(retryA), running) {
            staleRetryRan = true
        }
        val recoveryB = requireNotNull(
            coordinator.claimRecovery(coordinator.currentToken(ServiceId)),
        )
        var recoveryBRan = false
        dispatchSuccessfulRecovery(coordinator, recoveryB, running) { recoveryBRan = true }
        executor.runAll()

        assertFalse(staleRetryRan)
        assertTrue(recoveryBRan)
    }

    @Test
    fun recoveryTokenIsRejectedAfterNewStartClaimsLifecycle() {
        val executor = QueuedExecutor()
        val coordinator = VpnRuntimeCoordinator(executor) { true }
        val running = plan("A")
        startQueued(coordinator, executor, running)
        val recovery = requireNotNull(coordinator.claimRecovery(coordinator.currentToken(ServiceId)))
        var recoveryRan = false
        dispatchSuccessfulRecovery(coordinator, recovery, running) { recoveryRan = true }

        val replacement = coordinator.claimReplacement(ServiceId)
        executor.runAll()

        assertFalse(recoveryRan)
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Starting)
        assertTrue(coordinator.isCurrent(replacement))
    }

    @Test
    fun duplicateRetryCallbackCannotActAfterNextGenerationIsIssued() {
        val executor = QueuedExecutor()
        val coordinator = VpnRuntimeCoordinator(executor) { true }
        val running = plan("A")
        startQueued(coordinator, executor, running)
        val initial = requireNotNull(coordinator.claimRecovery(coordinator.currentToken(ServiceId)))
        var retryOne: VpnRuntimeRecoveryToken? = null
        dispatchFailedRecovery(coordinator, initial, running) { retryOne = it }
        executor.runNext()

        var retryOneRuns = 0
        var retryTwo: VpnRuntimeRecoveryToken? = null
        dispatchFailedRecovery(coordinator, requireNotNull(retryOne), running) {
            retryOneRuns += 1
            retryTwo = it
        }
        dispatchSuccessfulRecovery(coordinator, requireNotNull(retryOne), running) {
            retryOneRuns += 1
        }
        executor.runAll()
        var retryTwoRuns = 0
        dispatchSuccessfulRecovery(coordinator, requireNotNull(retryTwo), running) {
            retryTwoRuns += 1
        }
        executor.runAll()

        assertEquals(1, retryOneRuns)
        assertEquals(1, retryTwoRuns)
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Running)
    }

    @Test
    fun staleRecoveryRetryCannotRunAfterStopClaimsOwnership() {
        val coordinator = VpnRuntimeCoordinator(ExecutorDirect) { true }
        val running = plan("A")
        startImmediately(coordinator, coordinator.claimStart(ServiceId, true), running)
        val recovery = requireNotNull(coordinator.claimRecovery(coordinator.currentToken(ServiceId)))
        var retryToken: VpnRuntimeRecoveryToken? = null

        coordinator.dispatchRecovery(
            token = recovery,
            request = VpnRuntimeRecoveryRequest(running, "health failure"),
            onFailure = { throw AssertionError(it) },
            recoverRuntime = { _, _, _, _ -> error("restart failed") },
            rollbackRecovery = { _, _, _, superseded ->
                assertFalse(superseded)
                VpnPlatformStopResult.Released
            },
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
    fun retryAdmissionCompletesStopWhenResourcesReleasedBeforeFutureCreation() {
        val coordinator = VpnRuntimeCoordinator(ExecutorDirect) { true }
        val token = coordinator.claimStop(ServiceId, "released before retry")
        coordinator.completePlatformStop(token, VpnPlatformStopResult.RetainedForRetry)

        assertTrue(completeReleasedBeforeRetry(resourcesOwned = false) {
            coordinator.completePlatformStop(token, VpnPlatformStopResult.Released)
        })
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Idle)
    }

    @Test
    fun retryAdmissionCompletesStartRollbackWhenResourcesReleasedBeforeFutureCreation() {
        val coordinator = VpnRuntimeCoordinator(ExecutorDirect) { true }
        val token = coordinator.claimStart(ServiceId, persistent = true)
        failStartWithRetainedRollback(coordinator, token)

        assertTrue(completeReleasedBeforeRetry(resourcesOwned = false) {
            coordinator.completeStartRollbackCleanup(token, VpnPlatformStopResult.Released)
        })
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Idle)
    }

    @Test
    fun retryAdmissionCompletesRecoveryRollbackBeforeSchedulingFreshRecovery() {
        val coordinator = VpnRuntimeCoordinator(ExecutorDirect) { true }
        val running = plan("A")
        startImmediately(coordinator, coordinator.claimStart(ServiceId, true), running)
        val failed = requireNotNull(coordinator.claimRecovery(coordinator.currentToken(ServiceId)))
        dispatchRetainedRecovery(coordinator, failed, running)
        var scheduled: VpnRuntimeRecoveryToken? = null

        assertTrue(completeReleasedBeforeRetry(resourcesOwned = false) {
            scheduled = coordinator.completeRecoveryRollbackCleanup(
                failed,
                VpnPlatformStopResult.Released,
            )
        })

        val retry = requireNotNull(scheduled)
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Recovering)
        assertTrue(coordinator.isCurrent(retry))
    }

    @Test
    fun retryAdmissionCannotCompleteStaleCleanupOwner() {
        val coordinator = VpnRuntimeCoordinator(ExecutorDirect) { true }
        val stale = coordinator.claimStop(ServiceId, "old stop")
        coordinator.completePlatformStop(stale, VpnPlatformStopResult.RetainedForRetry)
        val current = coordinator.claimStart(ServiceId, persistent = true)
        var accepted = true

        completeReleasedBeforeRetry(resourcesOwned = false) {
            accepted = coordinator.completePlatformStop(stale, VpnPlatformStopResult.Released)
        }

        assertFalse(accepted)
        assertTrue(coordinator.isCurrent(current))
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Starting)
    }

    @Test
    fun delayedRecoveryContinuationIsRejectedAfterStop() {
        val harness = preparedRecovery()
        harness.coordinator.claimStop(ServiceId, "stop during delay")

        assertFalse(harness.continueRecovery())
        assertTrue(harness.coordinator.snapshot.phase is VpnRuntimePhase.Stopping)
    }

    @Test
    fun delayedRecoveryContinuationIsRejectedAfterStart() {
        val harness = preparedRecovery()
        val start = harness.coordinator.claimStart(ServiceId, persistent = true)

        assertFalse(harness.continueRecovery())
        assertTrue(harness.coordinator.isCurrent(start))
    }

    @Test
    fun delayedRecoveryContinuationIsRejectedAfterNewRecoveryGeneration() {
        val harness = preparedRecovery()
        val next = requireNotNull(
            harness.coordinator.claimRecovery(harness.token.lifecycleToken),
        )

        assertFalse(harness.continueRecovery())
        assertTrue(harness.coordinator.isCurrent(next))
    }

    @Test
    fun onlyCurrentDelayedRecoveryContinuationExecutes() {
        val harness = preparedRecovery()

        assertTrue(harness.continueRecovery())
        assertEquals(1, harness.continuationRuns)
        assertTrue(harness.coordinator.snapshot.phase is VpnRuntimePhase.Running)
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

    private fun preparedRecovery(): PreparedRecoveryHarness {
        val coordinator = VpnRuntimeCoordinator(ExecutorDirect) { true }
        val running = plan("A")
        startImmediately(coordinator, coordinator.claimStart(ServiceId, true), running)
        val token = requireNotNull(coordinator.claimRecovery(coordinator.currentToken(ServiceId)))
        coordinator.dispatchRecoveryPreparation(
            token = token,
            request = VpnRuntimeRecoveryRequest(running, "delayed restart"),
            onFailure = { throw AssertionError(it) },
            prepareRuntime = { _, _, owner -> assertTrue(owner()) },
            rollbackRecovery = { _, _, error, _ -> throw AssertionError(error) },
            onRetryRequired = { _, _, error -> throw AssertionError(error) },
        )
        return PreparedRecoveryHarness(coordinator, token, running)
    }

    private class PreparedRecoveryHarness(
        val coordinator: VpnRuntimeCoordinator,
        val token: VpnRuntimeRecoveryToken,
        private val running: ProfileRunPlan,
    ) {
        var continuationRuns = 0
            private set

        fun continueRecovery(): Boolean {
            var ran = false
            coordinator.dispatchRecoveryContinuation(
                token = token,
                request = VpnRuntimeRecoveryRequest(running, "delayed restart"),
                onFailure = { throw AssertionError(it) },
                recoverRuntime = { request, _, owner, commit ->
                    ran = true
                    continuationRuns += 1
                    assertTrue(owner())
                    assertTrue(commit(request.plan))
                },
                rollbackRecovery = { _, _, error, _ -> throw AssertionError(error) },
                onRetryRequired = { _, _, error -> throw AssertionError(error) },
            )
            return ran
        }
    }

    private fun failStartWithRetainedRollback(
        coordinator: VpnRuntimeCoordinator,
        token: VpnRuntimeCommandToken,
    ) {
        coordinator.dispatchStart(
            token = token,
            request = request(plan("failed")),
            onFailure = {},
            hasRuntimeResources = { false },
            stopExisting = { _, _ -> error("unexpected stop") },
            startRuntime = { _, _, _, _ -> error("start failure") },
            rollbackStart = { _, _, _, _ -> VpnPlatformStopResult.RetainedForRetry },
        )
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.CleaningUp)
    }

    private fun startQueued(
        coordinator: VpnRuntimeCoordinator,
        executor: QueuedExecutor,
        running: ProfileRunPlan,
    ) {
        val token = coordinator.claimStart(ServiceId, persistent = true)
        coordinator.dispatchStart(
            token,
            request(running),
            { throw AssertionError(it) },
            { false },
            { _, _ -> error("unexpected stop") },
            { _, _, owner, commit ->
                assertTrue(owner())
                assertTrue(commit(running))
            },
            { _, _, error, _ -> throw AssertionError(error) },
        )
        executor.runNext()
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Running)
    }

    private fun dispatchSuccessfulRecovery(
        coordinator: VpnRuntimeCoordinator,
        token: VpnRuntimeRecoveryToken,
        running: ProfileRunPlan,
        onRun: () -> Unit,
    ) {
        coordinator.dispatchRecovery(
            token = token,
            request = VpnRuntimeRecoveryRequest(running, "test recovery"),
            onFailure = { throw AssertionError(it) },
            recoverRuntime = { request, _, owner, commit ->
                onRun()
                assertTrue(owner())
                assertTrue(commit(request.plan))
            },
            rollbackRecovery = { _, _, error, _ -> throw AssertionError(error) },
            onRetryRequired = { _, _, error -> throw AssertionError(error) },
        )
    }

    private fun dispatchFailedRecovery(
        coordinator: VpnRuntimeCoordinator,
        token: VpnRuntimeRecoveryToken,
        running: ProfileRunPlan,
        onRetry: (VpnRuntimeRecoveryToken) -> Unit,
    ) {
        coordinator.dispatchRecovery(
            token = token,
            request = VpnRuntimeRecoveryRequest(running, "failed recovery"),
            onFailure = { throw AssertionError(it) },
            recoverRuntime = { _, _, _, _ -> error("recovery failure") },
            rollbackRecovery = { _, _, _, superseded ->
                assertFalse(superseded)
                VpnPlatformStopResult.Released
            },
            onRetryRequired = { retryToken, _, _ -> onRetry(retryToken) },
        )
    }

    private fun dispatchRetainedRecovery(
        coordinator: VpnRuntimeCoordinator,
        token: VpnRuntimeRecoveryToken,
        running: ProfileRunPlan,
    ) {
        coordinator.dispatchRecovery(
            token = token,
            request = VpnRuntimeRecoveryRequest(running, "retained cleanup"),
            onFailure = { throw AssertionError(it) },
            recoverRuntime = { _, _, _, _ -> error("recovery failure") },
            rollbackRecovery = { _, _, _, superseded ->
                assertFalse(superseded)
                VpnPlatformStopResult.RetainedForRetry
            },
            onRetryRequired = { _, _, _ -> error("retained cleanup scheduled retry") },
        )
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.CleaningUp)
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

    private class QueuedExecutor : java.util.concurrent.Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runNext() {
            tasks.removeFirst().run()
        }

        fun runAll() {
            while (tasks.isNotEmpty()) runNext()
        }
    }
}
