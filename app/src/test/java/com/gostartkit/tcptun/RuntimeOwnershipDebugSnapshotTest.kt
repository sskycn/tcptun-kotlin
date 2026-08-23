package com.tcptun.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeOwnershipDebugSnapshotTest {
    @Test
    fun `debug snapshot schema cannot carry known secret material`() {
        val fieldNames = listOf(
            RuntimeOwnershipDebugSnapshot::class.java,
            RuntimeOwnershipDebugCapture::class.java,
        ).flatMap { type -> type.declaredFields.map { it.name.lowercase() } }

        listOf("password", "username", "config", "uri", "profile", "endpoint").forEach { secret ->
            assertFalse("debug snapshot field exposes $secret", fieldNames.any { secret in it })
        }
        assertTrue("serviceInstanceId" in RuntimeOwnershipDebugSnapshot::class.java.declaredFields.map { it.name })
    }

    @Test
    fun `stable snapshot rejects a torn ownership capture`() {
        val torn = capture(tunOwned = true, leaseOwner = 0L)
        val stable = capture(tunOwned = true, leaseOwner = 7L)
        val captures = ArrayDeque(listOf(torn, stable, stable))

        val snapshot = stableRuntimeOwnershipDebugSnapshot(7L) { captures.removeFirst() }

        assertTrue(snapshot.tunOwned)
        assertEquals(7L, snapshot.leaseOwner)
    }

    private fun capture(
        tunOwned: Boolean,
        leaseOwner: Long,
    ) = RuntimeOwnershipDebugCapture(
        lifecycleGeneration = 1,
        persistentGeneration = 1,
        recoveryGeneration = 1L,
        bridgeEpoch = 1L,
        bridgeResourcePhase = BridgeResourcePhase.SessionOwned,
        tunOwned = tunOwned,
        leaseOwner = leaseOwner,
        teardownPending = false,
        runtimePhase = "Running",
        activeServiceOwner = true,
        destroyed = false,
        vpnStatus = VpnStatus.Running,
        connectionsReady = true,
    )
}
