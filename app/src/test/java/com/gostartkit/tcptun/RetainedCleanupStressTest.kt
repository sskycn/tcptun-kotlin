package com.tcptun.client

import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RetainedCleanupStressTest {
    @Test
    fun `delayed native release retains physical and coordinator ownership until exact completion`() {
        var nativeOwned = true
        var tunOwned = true
        var foregroundRetained = true
        var serviceStopped = false
        var tunCloseRequests = 0
        var foregroundRemovals = 0
        var serviceStopRequests = 0
        var stopAttempts = 0
        var ownerCompletions = 0
        var recoverySchedules = 0
        val tasks = mutableListOf<() -> Unit>()
        val stopFailure = IllegalStateException("Stop and WaitStopped timed out; Abort failed")
        val recoveryFailure = IllegalStateException("runtime recovery failed")
        val coordinator = VpnRuntimeCoordinator(Executor(Runnable::run), canExecute = { true })
        val plan = ProfileRunPlan(listOf(AppConfig(id = "A", name = "A")))
        val startToken = coordinator.claimStart(1L, persistent = true)
        assertTrue(coordinator.completeNoOpStart(startToken, plan))
        val recoveryToken = requireNotNull(coordinator.claimRecovery(startToken))
        val recoveryRequest = VpnRuntimeRecoveryRequest(plan, "retained cleanup stress")
        val owner = VpnPlatformCleanupOwner.RecoveryRollback(
            recoveryToken,
            recoveryRequest,
            recoveryFailure,
        )
        val teardownRequest = VpnPlatformTeardownRequest(cleanupOwner = owner)
        val publication = VpnCleanupPublicationPort(
            globalStep = { _, action -> action() },
            localStep = { _, action -> runCatching(action) },
        )
        val adapter = VpnPlatformCleanupAdapter(
            VpnPlatformCleanupActions(
                cancelBridgeRestart = {},
                publishStopping = {},
                stopHealth = {},
                unregisterNetwork = {},
                resetUnderlyingDiagnostics = {},
                clearDesiredConfig = {},
                publishBridgeStopping = {},
                stopBridgeSession = {
                    stopAttempts += 1
                    if (stopAttempts < 3) throw stopFailure
                    nativeOwned = false
                },
                closeTunIfSafe = {
                    tunCloseRequests += 1
                    if (!nativeOwned) tunOwned = false
                },
                clearAppIdentity = {},
                resetHealth = {},
                resourcesOwned = { nativeOwned },
                resetDiagnostics = {},
                publishStopped = {},
                removeForeground = {
                    foregroundRemovals += 1
                    foregroundRetained = false
                },
                requestServiceStop = {
                    serviceStopRequests += 1
                    serviceStopped = true
                },
                honorDeferredStopIfReleased = {},
                publishIncompleteCleanup = {},
                retainCleanupForeground = { foregroundRetained = true },
            ),
        )
        val runtime = VpnPlatformTeardownRuntime(
            retryDelaysMillis = listOf(0L, 0L, 0L),
            performCleanup = { adapter.perform(it, publication).result },
            completeOwner = { completedOwner, result ->
                assertSame(owner, completedOwner)
                ownerCompletions += 1
                if (coordinator.completeRecoveryRollbackCleanup(recoveryToken, result) != null) {
                    recoverySchedules += 1
                }
            },
            resourcesOwned = { nativeOwned },
            scheduleRetry = { _, task ->
                tasks += task
                FutureTask<Unit>({}, Unit)
            },
            dispatchLifecycleRetry = { task -> task(); true },
            isDestroyed = { false },
            log = {},
        )

        lateinit var initial: VpnCleanupAttempt
        coordinator.dispatchRecovery(
            token = recoveryToken,
            request = recoveryRequest,
            onFailure = { throw it },
            recoverRuntime = { _, _, _, _ -> throw recoveryFailure },
            rollbackRecovery = { _, _, _, _ ->
                adapter.perform(teardownRequest, publication).also { initial = it }.result
            },
            onRetryRequired = { _, _, _ -> recoverySchedules += 1 },
        )
        assertSame(stopFailure, initial.bridgeStopFailure)
        assertEquals(VpnPlatformStopResult.RetainedForRetry, initial.result)
        runtime.acceptInitialResult(teardownRequest, initial.result)

        assertTrue(tunOwned)
        assertTrue(foregroundRetained)
        assertFalse(serviceStopped)
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.CleaningUp)
        assertEquals(0, ownerCompletions)
        assertEquals(0, recoverySchedules)
        assertEquals(1, tunCloseRequests)
        assertEquals(0, foregroundRemovals)
        assertEquals(0, serviceStopRequests)

        tasks[0].invoke()
        assertTrue(tunOwned)
        assertTrue(foregroundRetained)
        assertFalse(serviceStopped)
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.CleaningUp)
        assertEquals(0, ownerCompletions)
        assertEquals(2, tunCloseRequests)
        assertEquals(0, foregroundRemovals)
        assertEquals(0, serviceStopRequests)

        tasks[1].invoke()
        assertFalse(tunOwned)
        assertFalse(foregroundRetained)
        assertTrue(serviceStopped)
        assertTrue(coordinator.snapshot.phase is VpnRuntimePhase.Recovering)
        assertEquals(1, ownerCompletions)
        assertEquals(1, recoverySchedules)
        assertEquals(3, tunCloseRequests)
        assertEquals(1, foregroundRemovals)
        assertEquals(1, serviceStopRequests)

        tasks[1].invoke()
        assertEquals(1, ownerCompletions)
        assertEquals(1, recoverySchedules)
    }
}
