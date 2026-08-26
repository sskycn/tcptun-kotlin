package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeMutationOwnershipTest {
    @Test
    fun networkCallbackFromRunningAIsRejectedAfterStartB() {
        val callbackA = ownership(generation = 1, epoch = 10)

        assertFalse(
            callbackA.isCurrent(
                runtimeTokenCurrent = false,
                activeBridgeEpoch = 10,
                activeServiceInstance = true,
            ),
        )
    }

    @Test
    fun networkCallbackFromRunningAIsRejectedAfterStop() {
        val callbackA = ownership(generation = 1, epoch = 10)

        assertFalse(
            callbackA.isCurrent(false, activeBridgeEpoch = 0, activeServiceInstance = true),
        )
    }

    @Test
    fun networkCallbackFromRecoveryEpochOneIsRejectedByEpochTwo() {
        val epochOne = ownership(generation = 1, epoch = 10)

        assertFalse(epochOne.isCurrent(true, activeBridgeEpoch = 11, activeServiceInstance = true))
    }

    @Test
    fun destroyedServiceRejectsCurrentNetworkCallback() {
        val callback = ownership(generation = 1, epoch = 10)

        assertFalse(callback.isCurrent(true, activeBridgeEpoch = 10, activeServiceInstance = false))
    }

    @Test
    fun rapidSettingsRequestsKeepLatestAuthoritativeOwnershipAndForce() {
        val state = RuntimeSettingsRuntimeState()
        val runtimeA = ownership(generation = 1, epoch = 10)
        val runtimeB = ownership(generation = 2, epoch = 11)
        val first = state.requestDesired(forceRestart = true)
        val second = state.requestDesired(forceRestart = false)

        assertFalse(state.isLatest(RuntimeSettingsApplyClaim(first, runtimeA)))
        val claim = state.bindLatest(runtimeB)
        assertEquals(runtimeB, claim?.ownership)
        assertTrue(claim?.mutation?.forceRestart == true)
        assertEquals(second.sequence, claim?.mutation?.sequence)
    }

    @Test
    fun settingsApplyIsRejectedAfterStopOrStart() {
        val applyA = ownership(generation = 1, epoch = 10)

        assertFalse(applyA.isCurrent(false, activeBridgeEpoch = 0, activeServiceInstance = true))
        assertFalse(applyA.isCurrent(false, activeBridgeEpoch = 11, activeServiceInstance = true))
    }

    @Test
    fun structuralSettingRequiresOneReplacementWhileHotSettingDoesNot() {
        val current = RuntimeSettings(mtu = 1400, logLevel = "info", powerSavingMode = true)
        val structural = current.copy(mtu = 1280)
        val powerPolicy = current.copy(powerSavingMode = false)
        val hot = current.copy(logLevel = "debug")

        assertTrue(BridgeHealthPolicy.requiresRuntimeRestart(false, current, structural))
        assertTrue(BridgeHealthPolicy.requiresRuntimeRestart(false, current, powerPolicy))
        assertFalse(BridgeHealthPolicy.requiresRuntimeRestart(false, current, hot))
    }

    @Test
    fun rapidStructuralSettingsCoalesceToOneReplacement() {
        val state = RuntimeSettingsRuntimeState()
        val runtime = ownership(generation = 1, epoch = 10)
        val first = state.requestDesired(forceRestart = false)
        val second = state.requestDesired(forceRestart = false)
        val latest = state.requestDesired(forceRestart = false)
        var replacements = 0

        listOf(first, second, latest).forEach { request ->
            val claim = RuntimeSettingsApplyClaim(request, runtime)
            if (!state.isLatest(claim)) return@forEach
            if (BridgeHealthPolicy.requiresRuntimeRestart(
                    forceRestart = claim.mutation.forceRestart,
                    previous = RuntimeSettings(mtu = 1400),
                    next = RuntimeSettings(mtu = 1280),
                )
            ) {
                replacements += 1
            }
        }

        assertEquals(1, replacements)
    }

    @Test
    fun appliedSettingsSnapshotNeverPublishesPartialConfiguration() {
        val old = AppliedRuntimeSettings.from(RuntimeSettings(mtu = 1400, socksPort = 1080))
        val next = AppliedRuntimeSettings.from(RuntimeSettings(mtu = 1280, socksPort = 2080))
        var published = old
        val observed = mutableListOf<AppliedRuntimeSettings>()

        observed += published
        published = next
        observed += published

        assertEquals(listOf(1400 to 1080, 1280 to 2080), observed.map { it.mtu to it.socksPort })
    }

    private fun ownership(generation: Int, epoch: Long) = VpnRuntimeOwnership(
        runtimeToken = VpnRuntimeCommandToken(
            serviceInstanceId = 1,
            lifecycleGeneration = generation,
            persistentGeneration = generation,
        ),
        bridgeEpoch = epoch,
    )
}
