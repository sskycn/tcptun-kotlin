package com.tcptun.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsSnapshotTest {
    @Test
    fun snapshotContainsRequiredRuntimeSections() {
        val state = TcptunRuntimeState(
            status = "Running",
            diagnostics = TcptunDiagnostics(
                underlyingNetwork = "WIFI",
                mtu = 1400,
                bridgeEventState = "running",
                bridgeSessionId = 17L,
            ),
            profileHealth = mapOf(
                "profile-a" to ProfileHealth(
                    status = ProfileHealthStatus.Healthy,
                    latencyMs = 42L,
                ),
            ),
        )

        val snapshot = TcptunDiagnosticsSnapshot.fromRuntimeState(
            runtimeState = state,
            appVersion = "1.0",
            appBuild = "7",
            coreIdentity = TcptunCoreIdentity("0.2.5", "build-7"),
            nowMs = 100L,
        )

        assertTrue(snapshot.sessionId == 17L)
        assertTrue(snapshot.network.available)
        assertTrue(snapshot.tunnel.mtu == 1400)
        assertTrue(snapshot.outbounds.single().latencyMs == 42L)
    }

    @Test
    fun supportTextRedactsSecretsAndDoesNotContainProfileJson() {
        val snapshot = TcptunDiagnosticsSnapshot(
            application = TcptunDiagnosticsApplication("1.0", "7"),
            core = TcptunDiagnosticsCore("0.2.5", "build-7"),
            vpnState = "Error",
            sessionId = 17L,
            network = TcptunDiagnosticsNetwork("WIFI", true),
            tunnel = TcptunDiagnosticsTunnel(1400, "running", "running"),
            outbounds = listOf(
                TcptunDiagnosticsOutbound(
                    tag = "profile-a",
                    health = "Degraded",
                    latencyMs = null,
                    lastError = "token=secret-token",
                ),
            ),
            errors = listOf(
                TcptunDiagnosticsError(
                    type = "bridge",
                    timestampMs = 100L,
                    userSafeMessage = "private_key=secret-key; profile_json={\"token\":\"secret-token\"}",
                ),
            ),
        )

        val safeText = snapshot.safeText()

        assertFalse(safeText.contains("secret-token"))
        assertFalse(safeText.contains("secret-key"))
        assertFalse(safeText.contains("profile_json={"))
        assertTrue(safeText.contains("<redacted>"))
    }
}
