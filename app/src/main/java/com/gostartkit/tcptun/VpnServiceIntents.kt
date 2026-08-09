package com.tcptun.client

import android.content.Context
import android.content.Intent
import org.json.JSONObject

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

    const val ExtraConfig = "config"
    const val ExtraProfilePlan = "profilePlan"
    const val ExtraTcpingRequestId = "tcpingRequestId"
    const val ExtraTcpingTargetLabel = "tcpingTargetLabel"
    const val ExtraTcpingHost = "tcpingHost"
    const val ExtraTcpingPort = "tcpingPort"
    const val ExtraForceRuntimeRestart = "forceRuntimeRestart"

    private const val ExtraRuntimeSettingsVersion = "runtimeSettingsVersion"
    private const val ExtraRuntimeMtu = "runtimeMtu"
    private const val ExtraRuntimePowerSaving = "runtimePowerSaving"
    private const val ExtraRuntimeLogLevel = "runtimeLogLevel"
    private const val ExtraRuntimeSocksPort = "runtimeSocksPort"
    private const val ExtraRuntimeLocalProxyProtocol = "runtimeLocalProxyProtocol"
    private const val ExtraRuntimeSocksListenAll = "runtimeSocksListenAll"
    private const val ExtraRuntimeSocksUsername = "runtimeSocksUsername"
    private const val ExtraRuntimeSocksPassword = "runtimeSocksPassword"
    private const val ExtraRuntimeRouteLocalProxyTraffic = "runtimeRouteLocalProxyTraffic"
    private const val ExtraRuntimeDefaultOutbound = "runtimeDefaultOutbound"
    private const val ExtraRuntimeFlowAnalysisApp = "runtimeFlowAnalysisApp"
    private const val RuntimeSettingsIntentVersion = 1
    private const val MaxRuntimeCredentialLength = 4_096

    fun start(context: Context, config: AppConfig): Intent =
        start(context, ProfileRunPlan(listOf(config)))

    fun start(context: Context, sourcePlan: ProfileRunPlan): Intent {
        val payload = buildCommandPayload(
            context = context,
            sourcePlan = sourcePlan,
            managedRouteRules = RouteRuleStore.loadAuthoritative(context).getOrThrow(),
        )
        return serviceIntent(context, ActionStart)
            .putExtra(ExtraConfig, payload.configJson)
            .putExtra(ExtraProfilePlan, payload.planJson)
            .putRuntimeSettingsSnapshot(payload.runtimeSettings)
    }

    fun preflightStart(
        context: Context,
        sourcePlan: ProfileRunPlan,
        managedRouteRules: List<ManagedRouteRule>,
    ) {
        buildCommandPayload(context, sourcePlan, managedRouteRules)
    }

    fun stop(context: Context): Intent = serviceIntent(context, ActionStop)

    fun updateOutbounds(context: Context, plan: ProfileRunPlan): Intent =
        start(context, plan).setAction(ActionUpdateOutbounds)

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

    fun runtimeSettingsSnapshot(intent: Intent): RuntimeSettings? {
        if (intent.getIntExtra(ExtraRuntimeSettingsVersion, 0) != RuntimeSettingsIntentVersion) {
            return null
        }
        val username = intent.getStringExtra(ExtraRuntimeSocksUsername).orEmpty()
        val password = intent.getStringExtra(ExtraRuntimeSocksPassword).orEmpty()
        require(username.length <= MaxRuntimeCredentialLength) { "SOCKS username is too long" }
        require(password.length <= MaxRuntimeCredentialLength) { "SOCKS password is too long" }
        return RuntimeSettings(
            mtu = intent.getIntExtra(ExtraRuntimeMtu, RuntimeSettingsDefaults.VpnMtu).coerceIn(1280, 1500),
            powerSavingMode = intent.getBooleanExtra(ExtraRuntimePowerSaving, true),
            logLevel = normalizeLogLevel(intent.getStringExtra(ExtraRuntimeLogLevel).orEmpty()),
            socksPort = intent.getIntExtra(ExtraRuntimeSocksPort, RuntimeSettingsDefaults.SocksPort)
                .coerceIn(1, 65535),
            localProxyProtocol = normalizeLocalProxyProtocol(
                intent.getStringExtra(ExtraRuntimeLocalProxyProtocol).orEmpty(),
            ),
            socksListenAll = intent.getBooleanExtra(ExtraRuntimeSocksListenAll, false),
            socksUsername = username,
            socksPassword = password,
            routeLocalProxyTraffic = intent.getBooleanExtra(ExtraRuntimeRouteLocalProxyTraffic, false),
            defaultOutbound = normalizeDefaultOutboundSelection(
                intent.getStringExtra(ExtraRuntimeDefaultOutbound).orEmpty(),
            ),
            flowAnalysisApp = normalizeFlowAnalysisApp(
                intent.getStringExtra(ExtraRuntimeFlowAnalysisApp).orEmpty(),
            ),
        )
    }

    fun parseStartCommand(context: Context, intent: Intent): VpnStartCommand {
        val configJson = intent.getStringExtra(ExtraConfig)
            ?.takeIf { it.length <= MaxVpnCommandPayloadLength }
            ?: error("missing VPN config")
        val rawPlan = intent.getStringExtra(ExtraProfilePlan)
            ?.takeIf { it.length <= DesiredRunningPlanStore.MaxEncodedLength }
            ?: error("missing or invalid VPN profile plan")
        require(
            isVpnCommandPayloadWithinLimit(
                configLength = configJson.length,
                planLength = rawPlan.length,
                settingsPayloadLength = 0,
            ),
        ) {
            "VPN command payload is too large"
        }
        val plan = runRecoverableCatching {
            requireSafeJsonNesting(rawPlan)
            ProfileRunPlan.fromJson(JSONObject(rawPlan))
        }.getOrNull() ?: error("missing or invalid VPN profile plan")
        return VpnStartCommand(
            configJson = configJson,
            plan = plan,
            runtimeSettings = runtimeSettingsSnapshot(intent) ?: RuntimeSettingsRepository.read(context),
            desiredPlanJson = DesiredRunningPlanStore.encode(plan),
        )
    }

    private data class CommandPayload(
        val configJson: String,
        val planJson: String,
        val runtimeSettings: RuntimeSettings,
    )

    private fun buildCommandPayload(
        context: Context,
        sourcePlan: ProfileRunPlan,
        managedRouteRules: List<ManagedRouteRule>,
    ): CommandPayload {
        val runtimeSettings = RuntimeSettingsRepository.read(context)
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
        val planJson = plan.toJson().toString()
        val settingsPayloadLength = runtimeSettings.socksUsername.length +
            runtimeSettings.socksPassword.length +
            runtimeSettings.localProxyProtocol.length +
            runtimeSettings.logLevel.length +
            runtimeSettings.defaultOutbound.length +
            runtimeSettings.flowAnalysisApp.length
        require(isVpnCommandPayloadWithinLimit(configJson.length, planJson.length, settingsPayloadLength)) {
            "VPN configuration is too large to send to the service"
        }
        return CommandPayload(configJson, planJson, runtimeSettings)
    }

    private fun serviceIntent(context: Context, action: String): Intent =
        Intent(context, TcptunVpnService::class.java).setAction(action)

    private fun Intent.putRuntimeSettingsSnapshot(settings: RuntimeSettings): Intent = apply {
        putExtra(ExtraRuntimeSettingsVersion, RuntimeSettingsIntentVersion)
        putExtra(ExtraRuntimeMtu, settings.mtu)
        putExtra(ExtraRuntimePowerSaving, settings.powerSavingMode)
        putExtra(ExtraRuntimeLogLevel, settings.logLevel)
        putExtra(ExtraRuntimeSocksPort, settings.socksPort)
        putExtra(ExtraRuntimeLocalProxyProtocol, settings.localProxyProtocol)
        putExtra(ExtraRuntimeSocksListenAll, settings.socksListenAll)
        putExtra(ExtraRuntimeSocksUsername, settings.socksUsername)
        putExtra(ExtraRuntimeSocksPassword, settings.socksPassword)
        putExtra(ExtraRuntimeRouteLocalProxyTraffic, settings.routeLocalProxyTraffic)
        putExtra(ExtraRuntimeDefaultOutbound, settings.defaultOutbound)
        putExtra(ExtraRuntimeFlowAnalysisApp, settings.flowAnalysisApp)
    }
}
