package com.tcptun.client

/** Application metadata safe to show in a support bundle. */
data class TcptunDiagnosticsApplication(
    val version: String,
    val build: String,
)

data class TcptunDiagnosticsCore(
    val version: String?,
    val buildId: String?,
)

data class TcptunDiagnosticsNetwork(
    val type: String,
    val available: Boolean,
)

data class TcptunDiagnosticsTunnel(
    val mtu: Int,
    val tcpState: String,
    val udpState: String,
)

data class TcptunDiagnosticsOutbound(
    val tag: String,
    val health: String,
    val latencyMs: Long?,
    val lastError: String,
)

data class TcptunDiagnosticsError(
    val type: String,
    val timestampMs: Long,
    val userSafeMessage: String,
)

/**
 * Stable, redacted diagnostics boundary. It contains runtime facts only and
 * never carries a profile JSON document, credentials, tokens, or private keys.
 */
data class TcptunDiagnosticsSnapshot(
    val application: TcptunDiagnosticsApplication,
    val core: TcptunDiagnosticsCore,
    val vpnState: String,
    val sessionId: Long?,
    val network: TcptunDiagnosticsNetwork,
    val tunnel: TcptunDiagnosticsTunnel,
    val outbounds: List<TcptunDiagnosticsOutbound>,
    val errors: List<TcptunDiagnosticsError>,
) {
    fun redacted(): TcptunDiagnosticsSnapshot = copy(
        application = application.copy(
            version = diagnosticText(application.version),
            build = diagnosticText(application.build),
        ),
        core = core.copy(
            version = core.version?.let(::diagnosticText),
            buildId = core.buildId?.let(::diagnosticText),
        ),
        vpnState = diagnosticText(vpnState),
        network = network.copy(type = diagnosticText(network.type)),
        tunnel = tunnel.copy(
            tcpState = diagnosticText(tunnel.tcpState),
            udpState = diagnosticText(tunnel.udpState),
        ),
        outbounds = outbounds.map { outbound ->
            outbound.copy(
                tag = diagnosticText(outbound.tag),
                health = diagnosticText(outbound.health),
                lastError = diagnosticText(outbound.lastError),
            )
        },
        errors = errors.map { error ->
            error.copy(
                type = diagnosticText(error.type),
                userSafeMessage = diagnosticText(error.userSafeMessage),
            )
        },
    )

    /** Human-readable support text; deliberately excludes raw profile/config data. */
    fun safeText(): String = redacted().let { safe ->
        buildString {
            appendLine("app=${safe.application.version} build=${safe.application.build}")
            appendLine("core=${safe.core.version.orEmpty()} buildId=${safe.core.buildId.orEmpty()}")
            appendLine("vpn=${safe.vpnState} session=${safe.sessionId ?: "none"}")
            appendLine("network=${safe.network.type} available=${safe.network.available}")
            appendLine("tun.mtu=${safe.tunnel.mtu} tcp=${safe.tunnel.tcpState} udp=${safe.tunnel.udpState}")
            safe.outbounds.forEach { outbound ->
                appendLine(
                    "outbound=${outbound.tag} health=${outbound.health} " +
                        "latencyMs=${outbound.latencyMs ?: "none"} error=${outbound.lastError}",
                )
            }
            safe.errors.forEach { error ->
                appendLine("error.${error.type}=${error.timestampMs}:${error.userSafeMessage}")
            }
        }
    }

    companion object {
        internal fun fromRuntimeState(
            runtimeState: TcptunRuntimeState,
            appVersion: String,
            appBuild: String,
            coreIdentity: TcptunCoreIdentity,
            nowMs: Long = System.currentTimeMillis(),
        ): TcptunDiagnosticsSnapshot {
            val diagnostics = runtimeState.diagnostics
            val errors = buildList {
                runtimeState.lastError.takeIf(String::isNotBlank)?.let { message ->
                    add(TcptunDiagnosticsError("vpn", nowMs, message))
                }
                diagnostics.bridgeLastError.takeIf(String::isNotBlank)?.let { message ->
                    add(TcptunDiagnosticsError("bridge", diagnostics.bridgeTimestampMs, message))
                }
                runtimeState.profileHealth.forEach { (tag, health) ->
                    health.error.takeIf(String::isNotBlank)?.let { message ->
                        add(TcptunDiagnosticsError("outbound:$tag", health.lastCheckedAtMs, message))
                    }
                }
            }
            return TcptunDiagnosticsSnapshot(
                application = TcptunDiagnosticsApplication(appVersion, appBuild),
                core = TcptunDiagnosticsCore(
                    version = coreIdentity.version.takeIf(String::isNotBlank),
                    buildId = coreIdentity.buildId.takeIf(String::isNotBlank),
                ),
                vpnState = runtimeState.status,
                sessionId = diagnostics.bridgeSessionId.takeIf { it > 0L },
                network = TcptunDiagnosticsNetwork(
                    type = diagnostics.underlyingNetwork,
                    available = diagnostics.underlyingNetwork != "None",
                ),
                tunnel = TcptunDiagnosticsTunnel(
                    mtu = diagnostics.mtu,
                    tcpState = diagnostics.bridgeEventState,
                    udpState = diagnostics.bridgeEventState,
                ),
                outbounds = runtimeState.profileHealth.map { (tag, health) ->
                    TcptunDiagnosticsOutbound(
                        tag = tag,
                        health = health.status.name,
                        latencyMs = health.latencyMs,
                        lastError = health.error,
                    )
                },
                errors = errors,
            ).redacted()
        }
    }
}

private fun diagnosticText(value: String): String =
    DiagnosticJsonPayload.replace(redactSensitiveText(value.take(512)), "<redacted>").trim()

private val DiagnosticJsonPayload = Regex(
    """\b(?:profile_json|config_json)\s*=\s*\{.*""",
    RegexOption.IGNORE_CASE,
)
