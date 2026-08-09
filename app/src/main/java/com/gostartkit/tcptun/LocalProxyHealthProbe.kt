package com.tcptun.client

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/** Performs bounded local-listener and upstream checks through the active SOCKS5 proxy. */
internal class LocalProxyHealthProbe(
    private val localHost: String = RuntimeSettingsDefaults.LocalSocksHost,
    private val targets: List<UpstreamProbeTarget> = DefaultTargets,
    private val localConnectTimeoutMs: Int = 1_000,
    private val upstreamTimeoutMs: Int = 5_000,
) {
    private val nextTargetIndex = AtomicInteger()

    init {
        require(targets.isNotEmpty()) { "at least one upstream probe target is required" }
    }

    fun canConnect(localPort: Int): Boolean = runRecoverableCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(localHost, localPort), localConnectTimeoutMs)
        }
    }.isSuccess

    fun connectAddress(localPort: Int): String = "$localHost:$localPort"

    fun orderedTargets(): List<UpstreamProbeTarget> {
        val start = nextTargetIndex.getAndUpdate { current -> (current + 1).mod(targets.size) }
            .mod(targets.size)
        return targets.indices.map { offset -> targets[(start + offset) % targets.size] }
    }

    fun upstreamFailure(
        orderedTargets: List<UpstreamProbeTarget>,
        localPort: Int,
        username: String,
        password: String,
        onSuccess: (UpstreamProbeTarget) -> Unit,
    ): String? {
        val failures = mutableListOf<String>()
        for (target in orderedTargets) {
            val failure = probeUpstream(target, localPort, username, password)
            if (failure == null) {
                onSuccess(target)
                return null
            }
            failures += "${target.label}: $failure"
        }
        return "all upstream probes failed: ${failures.joinToString("; ")}"
    }

    private fun probeUpstream(
        target: UpstreamProbeTarget,
        localPort: Int,
        username: String,
        password: String,
    ): String? = runRecoverableCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(localHost, localPort), upstreamTimeoutMs)
            socket.soTimeout = upstreamTimeoutMs
            Socks5Client.connect(socket, target.host, target.port, username, password)
            val expectedStatus = target.expectedStatus
            if (expectedStatus == null) {
                completeTlsHandshake(socket, target.host, target.port, upstreamTimeoutMs)
            } else {
                val status = fetchHttpsStatus(socket, target.host, target.port, target.path, upstreamTimeoutMs)
                require(status == expectedStatus) {
                    "HTTP ${target.label} returned $status, expected $expectedStatus"
                }
            }
        }
    }.fold(
        onSuccess = { null },
        onFailure = { error -> error.message ?: error.javaClass.simpleName },
    )

    private companion object {
        val DefaultTargets = listOf(
            UpstreamProbeTarget(
                label = "Google 204",
                host = "connectivitycheck.gstatic.com",
                path = "/generate_204",
                expectedStatus = 204,
            ),
            UpstreamProbeTarget(
                label = "Cloudflare 204",
                host = "cp.cloudflare.com",
                path = "/generate_204",
                expectedStatus = 204,
            ),
        )
    }
}
