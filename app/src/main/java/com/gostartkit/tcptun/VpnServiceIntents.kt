package com.tcptun.client

import android.content.Context
import android.content.Intent

internal data class VpnStartCommand(
    val configJson: String,
    val plan: ProfileRunPlan,
    val runtimeSettings: RuntimeSettings,
    val desiredPlanJson: String,
)

/** Defines and validates the Intent protocol accepted by [TcptunVpnService]. */
internal object VpnServiceIntents {
    const val ActionStart = "com.tcptun.client.START"
    const val ActionStop = "com.tcptun.client.STOP"
    const val ActionUpdateOutbounds = "com.tcptun.client.UPDATE_OUTBOUNDS"
    const val ActionTcpingOutbounds = "com.tcptun.client.TCPING_OUTBOUNDS"
    const val ActionApplyRuntimeSettings = "com.tcptun.client.APPLY_RUNTIME_SETTINGS"
    const val ActionUpdateFlowAnalysis = "com.tcptun.client.UPDATE_FLOW_ANALYSIS"
    const val ActionRefreshClientIps = "com.tcptun.client.REFRESH_CLIENT_IPS"

    const val ExtraCommandId = "commandId"
    const val ExtraCommandVersion = "commandVersion"
    const val ExtraTcpingRequestId = "tcpingRequestId"
    const val ExtraTcpingTargetLabel = "tcpingTargetLabel"
    const val ExtraTcpingHost = "tcpingHost"
    const val ExtraTcpingPort = "tcpingPort"
    const val ExtraForceRuntimeRestart = "forceRuntimeRestart"
    const val CommandVersion = 1

    fun start(context: Context, config: AppConfig): Intent =
        start(context, ProfileRunPlan(listOf(config)))

    fun start(context: Context, sourcePlan: ProfileRunPlan): Intent {
        val payload = buildStartPayload(
            context = context,
            sourcePlan = sourcePlan,
            managedRouteRules = RouteRuleStore.loadAuthoritative(context).getOrThrow(),
        )
        val commandId = vpnCommandStore(context).publish(payload)
        return commandIntent(context, ActionStart, commandId)
    }

    fun preflightStart(
        context: Context,
        sourcePlan: ProfileRunPlan,
        managedRouteRules: List<ManagedRouteRule>,
    ) {
        buildStartPayload(context, sourcePlan, managedRouteRules)
    }

    fun stop(context: Context): Intent = serviceIntent(context, ActionStop)

    fun updateOutbounds(context: Context, plan: ProfileRunPlan): Intent {
        val normalized = plan.normalized()
        val encodedLength = normalized.toJson().toString().length
        require(encodedLength <= DesiredRunningPlanStore.MaxEncodedLength) {
            "VPN profile plan is too large"
        }
        val commandId = vpnCommandStore(context).publish(UpdateOutboundsCommandPayload(normalized))
        return commandIntent(context, ActionUpdateOutbounds, commandId)
    }

    fun applyRuntimeSettings(context: Context, forceRestart: Boolean): Intent =
        serviceIntent(context, ActionApplyRuntimeSettings)
            .putExtra(ExtraForceRuntimeRestart, forceRestart)

    fun updateFlowAnalysis(context: Context): Intent = serviceIntent(context, ActionUpdateFlowAnalysis)

    fun refreshClientIps(context: Context): Intent = serviceIntent(context, ActionRefreshClientIps)

    fun tcpingOutbounds(
        context: Context,
        requestId: Long,
        targetLabel: String,
        host: String,
        port: Int,
    ): Intent = serviceIntent(context, ActionTcpingOutbounds)
        .putExtra(ExtraTcpingRequestId, requestId)
        .putExtra(ExtraTcpingTargetLabel, targetLabel)
        .putExtra(ExtraTcpingHost, host)
        .putExtra(ExtraTcpingPort, port)

    fun parseStartCommand(context: Context, intent: Intent): VpnStartCommand {
        val payload = consumePayload(context, intent) as? StartVpnCommandPayload
            ?: error("missing or invalid VPN start command")
        val configJson = payload.configJson.takeIf { it.length <= MaxVpnCommandPayloadLength }
            ?: error("missing or invalid VPN config")
        val plan = payload.plan.normalized()
        val planJson = plan.toJson().toString()
        require(
            isVpnCommandPayloadWithinLimit(
                configLength = configJson.length,
                planLength = planJson.length,
                settingsPayloadLength = 0,
            ),
        ) { "VPN command payload is too large" }
        val runtimeSettings = requireSafeRuntimeSettings(payload.runtimeSettings)
        return VpnStartCommand(
            configJson = configJson,
            plan = plan,
            runtimeSettings = runtimeSettings,
            desiredPlanJson = DesiredRunningPlanStore.encode(plan),
        )
    }

    fun parseOutboundsUpdate(context: Context, intent: Intent): ProfileRunPlan {
        val payload = consumePayload(context, intent) as? UpdateOutboundsCommandPayload
            ?: error("missing or invalid VPN outbound update command")
        return payload.plan.normalized()
    }

    private fun consumePayload(context: Context, intent: Intent): VpnCommandPayload {
        require(intent.getIntExtra(ExtraCommandVersion, 0) == CommandVersion) {
            "unsupported VPN command version"
        }
        val commandId = intent.getStringExtra(ExtraCommandId)
            ?.takeIf { it.length == 36 }
            ?: error("missing VPN command ID")
        return vpnCommandStore(context).consume(commandId) ?: error("VPN command is missing or expired")
    }

    private fun buildStartPayload(
        context: Context,
        sourcePlan: ProfileRunPlan,
        managedRouteRules: List<ManagedRouteRule>,
    ): StartVpnCommandPayload {
        val runtimeSettings = requireSafeRuntimeSettings(RuntimeSettingsRepository.read(context))
        val plan = sourcePlan.normalized()
        val configJson = plan.toBridgeJson(
            localListenAddr = RuntimeSettingsRepository.localSocksListenAddress(runtimeSettings),
            localProxyProtocol = runtimeSettings.localProxyProtocol,
            logLevel = runtimeSettings.logLevel,
            socks5Username = runtimeSettings.socksUsername,
            socks5Password = runtimeSettings.socksPassword,
            managedRouteRules = managedRouteRules,
            routeLocalProxyTraffic = runtimeSettings.routeLocalProxyTraffic,
            defaultOutbound = runtimeSettings.defaultOutbound,
        )
        val planLength = plan.toJson().toString().length
        val settingsPayloadLength = runtimeSettings.socksUsername.length +
            runtimeSettings.socksPassword.length +
            runtimeSettings.localProxyProtocol.length +
            runtimeSettings.logLevel.length +
            runtimeSettings.defaultOutbound.length +
            runtimeSettings.flowAnalysisApp.length
        require(isVpnCommandPayloadWithinLimit(configJson.length, planLength, settingsPayloadLength)) {
            "VPN configuration is too large"
        }
        return StartVpnCommandPayload(configJson, plan, runtimeSettings)
    }

    private fun commandIntent(context: Context, action: String, commandId: String): Intent =
        serviceIntent(context, action)
            .putExtra(ExtraCommandId, commandId)
            .putExtra(ExtraCommandVersion, CommandVersion)

    private fun serviceIntent(context: Context, action: String): Intent =
        Intent(context, TcptunVpnService::class.java).setAction(action)
}
