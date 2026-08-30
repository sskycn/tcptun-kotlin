package com.tcptun.client

import java.net.InetSocketAddress
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

internal data class LocalListenerProbeResult(
    val address: String,
    val layer: String,
    val error: String = "",
    val elapsedMs: Long = 0,
) {
    val healthy: Boolean get() = layer == "ready"
    fun summary(): String = "target=$address layer=$layer error=${error.ifEmpty { "none" }} elapsed_ms=$elapsedMs"
}

/** Device-side interface checks cannot prove reachability from a remote LAN client. */
internal fun localProxyInterfaceAddresses(): List<String> =
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }
        .mapNotNull { it.hostAddress }
        .distinct()
        .sorted()
        .take(8)

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

    fun listener(
        localPort: Int,
        proxyUser: LocalProxyUser? = null,
        host: String = localHost,
    ): LocalListenerProbeResult {
        val started = System.nanoTime()
        var layer = "A_listener"
        val result = runRecoverableCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, localPort), localConnectTimeoutMs)
                layer = "B_handshake"
                socket.soTimeout = localConnectTimeoutMs
                Socks5Client.negotiate(
                    socket, proxyUser?.username.orEmpty(), proxyUser?.password.orEmpty(), proxyUser != null,
                )
                Socks5Client.finishListenerProbe(socket)
            }
        }
        return LocalListenerProbeResult(
            address = "$host:$localPort",
            layer = if (result.isSuccess) "ready" else layer,
            // Classify without persisting server-supplied text or authentication material.
            error = result.exceptionOrNull()?.javaClass?.simpleName.orEmpty(),
            elapsedMs = (System.nanoTime() - started) / 1_000_000,
        )
    }

    fun connectAddress(localPort: Int): String = "$localHost:$localPort"

    fun orderedTargets(): List<UpstreamProbeTarget> {
        val start = nextTargetIndex.getAndUpdate { current -> (current + 1).mod(targets.size) }
            .mod(targets.size)
        return targets.indices.map { offset -> targets[(start + offset) % targets.size] }
    }

    fun upstreamFailure(
        orderedTargets: List<UpstreamProbeTarget>,
        localPort: Int,
        proxyUser: LocalProxyUser?,
        onSuccess: (UpstreamProbeTarget) -> Unit,
    ): String? {
        val failures = mutableListOf<String>()
        for (target in orderedTargets) {
            val failure = probeUpstream(target, localPort, proxyUser)
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
        proxyUser: LocalProxyUser?,
    ): String? {
        var layer = "A_listener"
        return runRecoverableCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(localHost, localPort), upstreamTimeoutMs)
                socket.soTimeout = upstreamTimeoutMs
                layer = "B_handshake"
                Socks5Client.negotiate(
                    socket, proxyUser?.username.orEmpty(), proxyUser?.password.orEmpty(), proxyUser != null,
                )
                layer = "C_outbound"
                Socks5Client.connectDestination(socket, target.host, target.port)
                layer = "D_transfer"
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
            onFailure = { error -> "$layer ${error.javaClass.simpleName}" },
        )
    }

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
