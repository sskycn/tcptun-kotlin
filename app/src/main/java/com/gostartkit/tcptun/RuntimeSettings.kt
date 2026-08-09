package com.tcptun.client

import android.content.Context
import android.content.SharedPreferences

internal object RuntimeSettingsDefaults {
    const val LocalSocksHost = "127.0.0.1"
    const val SocksPort = 1080
    const val VpnMtu = 1400
}

data class RuntimeSettings(
    val mtu: Int = RuntimeSettingsDefaults.VpnMtu,
    val powerSavingMode: Boolean = true,
    val logLevel: String = DefaultLogLevel,
    val socksPort: Int = RuntimeSettingsDefaults.SocksPort,
    val localProxyProtocol: String = DefaultLocalProxyProtocol,
    val socksListenAll: Boolean = false,
    val socksUsername: String = "",
    val socksPassword: String = "",
    /** When true, managed route rules also match mixed/SOCKS local proxy traffic. Default off. */
    val routeLocalProxyTraffic: Boolean = false,
    /** Empty selects the dynamic pool; __direct__ selects direct; any other value is a profile ID. */
    val defaultOutbound: String = DefaultOutboundDynamicPool,
    val flowAnalysisApp: String = "",
)

private val AndroidPackageNamePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
private const val MaxFlowAnalysisAppLength = 255

internal fun normalizeFlowAnalysisApp(value: String): String {
    if (value.length > MaxFlowAnalysisAppLength) return ""
    return value.trim().takeIf(AndroidPackageNamePattern::matches).orEmpty()
}

/** Owns the durable runtime-settings schema independently from the Android service lifecycle. */
object RuntimeSettingsRepository {
    private const val Prefs = "tcptun_runtime"
    private const val KeyMtu = "runtimeMtu"
    private const val KeyPowerSaving = "runtimePowerSaving"
    private const val KeyLogLevel = "runtimeLogLevel"
    private const val KeySocksPort = "runtimeSocksPort"
    private const val KeyLocalProxyProtocol = "runtimeLocalProxyProtocol"
    private const val KeySocksListenAll = "runtimeSocksListenAll"
    private const val KeySocksUsername = "runtimeSocksUsername"
    private const val KeySocksPassword = "runtimeSocksPassword"
    private const val KeyRouteLocalProxyTraffic = "runtimeRouteLocalProxyTraffic"
    private const val KeyDefaultOutbound = "runtimeDefaultOutbound"
    private const val KeyFlowAnalysisApp = "runtimeFlowAnalysisApp"
    private const val MaxRuntimeCredentialLength = 4_096

    fun read(context: Context): RuntimeSettings {
        val appContext = context.applicationContext ?: context
        val prefs = try {
            appContext.getSharedPreferences(Prefs, Context.MODE_PRIVATE)
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            TcptunState.appendLog("runtime settings unavailable: ${failureDescription(error)}")
            return RuntimeSettings()
        }
        val mtu = prefs.readOrDefault(KeyMtu, RuntimeSettingsDefaults.VpnMtu) {
            getInt(KeyMtu, RuntimeSettingsDefaults.VpnMtu)
        }.coerceIn(1280, 1500)
        val powerSavingMode = prefs.readOrDefault(KeyPowerSaving, true) {
            getBoolean(KeyPowerSaving, true)
        }
        val logLevel = normalizeLogLevel(
            prefs.readOrDefault(KeyLogLevel, DefaultLogLevel) {
                getString(KeyLogLevel, DefaultLogLevel).orEmpty()
            },
        )
        val socksPort = prefs.readOrDefault(KeySocksPort, RuntimeSettingsDefaults.SocksPort) {
            getInt(KeySocksPort, RuntimeSettingsDefaults.SocksPort)
        }.coerceIn(1, 65535)
        return RuntimeSettings(
            mtu = mtu,
            powerSavingMode = powerSavingMode,
            logLevel = logLevel,
            socksPort = socksPort,
            localProxyProtocol = normalizeLocalProxyProtocol(
                prefs.readOrDefault(KeyLocalProxyProtocol, DefaultLocalProxyProtocol) {
                    getString(KeyLocalProxyProtocol, DefaultLocalProxyProtocol).orEmpty()
                },
            ),
            socksListenAll = prefs.readOrDefault(KeySocksListenAll, false) {
                getBoolean(KeySocksListenAll, false)
            },
            socksUsername = prefs.readOrDefault(KeySocksUsername, "") {
                getString(KeySocksUsername, "").orEmpty()
            }.take(MaxRuntimeCredentialLength).let(::truncateSocksCredential),
            socksPassword = prefs.readOrDefault(KeySocksPassword, "") {
                getString(KeySocksPassword, "").orEmpty()
            }.take(MaxRuntimeCredentialLength).let(::truncateSocksCredential),
            routeLocalProxyTraffic = prefs.readOrDefault(KeyRouteLocalProxyTraffic, false) {
                getBoolean(KeyRouteLocalProxyTraffic, false)
            },
            defaultOutbound = normalizeDefaultOutboundSelection(
                prefs.readOrDefault(KeyDefaultOutbound, DefaultOutboundDynamicPool) {
                    getString(KeyDefaultOutbound, DefaultOutboundDynamicPool).orEmpty()
                },
            ),
            flowAnalysisApp = normalizeFlowAnalysisApp(
                prefs.readOrDefault(KeyFlowAnalysisApp, "") {
                    getString(KeyFlowAnalysisApp, "").orEmpty()
                },
            ),
        )
    }

    fun write(context: Context, settings: RuntimeSettings) {
        val normalizedLogLevel = normalizeLogLevel(settings.logLevel)
        val normalizedSocksPort = settings.socksPort.coerceIn(1, 65535)
        val normalizedLocalProxyProtocol = normalizeLocalProxyProtocol(settings.localProxyProtocol)
        val normalizedDefaultOutbound = normalizeDefaultOutboundSelection(settings.defaultOutbound)
        val normalizedFlowAnalysisApp = normalizeFlowAnalysisApp(settings.flowAnalysisApp)
        require(settings.socksUsername.length <= MaxRuntimeCredentialLength) { "SOCKS username is too long" }
        require(settings.socksPassword.length <= MaxRuntimeCredentialLength) { "SOCKS password is too long" }
        require(hasValidSocksCredentialSize(settings.socksUsername)) {
            "SOCKS username exceeds $MaxSocksCredentialUtf8Bytes UTF-8 bytes"
        }
        require(hasValidSocksCredentialSize(settings.socksPassword)) {
            "SOCKS password exceeds $MaxSocksCredentialUtf8Bytes UTF-8 bytes"
        }
        val normalizedSettings = settings.copy(
            mtu = settings.mtu.coerceIn(1280, 1500),
            logLevel = normalizedLogLevel,
            socksPort = normalizedSocksPort,
            localProxyProtocol = normalizedLocalProxyProtocol,
            defaultOutbound = normalizedDefaultOutbound,
            flowAnalysisApp = normalizedFlowAnalysisApp,
        )
        val appContext = context.applicationContext ?: context
        val saved = appContext.getSharedPreferences(Prefs, Context.MODE_PRIVATE)
            .edit()
            .putInt(KeyMtu, normalizedSettings.mtu)
            .putBoolean(KeyPowerSaving, normalizedSettings.powerSavingMode)
            .putString(KeyLogLevel, normalizedSettings.logLevel)
            .putInt(KeySocksPort, normalizedSettings.socksPort)
            .putString(KeyLocalProxyProtocol, normalizedSettings.localProxyProtocol)
            .putBoolean(KeySocksListenAll, normalizedSettings.socksListenAll)
            .putString(KeySocksUsername, normalizedSettings.socksUsername)
            .putString(KeySocksPassword, normalizedSettings.socksPassword)
            .putBoolean(KeyRouteLocalProxyTraffic, normalizedSettings.routeLocalProxyTraffic)
            .putString(KeyDefaultOutbound, normalizedSettings.defaultOutbound)
            .putString(KeyFlowAnalysisApp, normalizedSettings.flowAnalysisApp)
            .commit()
        check(saved) { "runtime settings could not be persisted" }
        publish(normalizedSettings)
    }

    fun localSocksListenAddress(settings: RuntimeSettings): String {
        val host = if (settings.socksListenAll) "0.0.0.0" else RuntimeSettingsDefaults.LocalSocksHost
        return "$host:${settings.socksPort.coerceIn(1, 65535)}"
    }

    fun localSocksConnectAddress(settings: RuntimeSettings): String =
        "${RuntimeSettingsDefaults.LocalSocksHost}:${settings.socksPort.coerceIn(1, 65535)}"

    fun defaultLocalSocksConnectAddress(): String =
        "${RuntimeSettingsDefaults.LocalSocksHost}:${RuntimeSettingsDefaults.SocksPort}"

    private fun publish(settings: RuntimeSettings) {
        TcptunState.setFlowAnalysisApp(settings.flowAnalysisApp)
        TcptunState.updateDiagnostics {
            it.copy(
                mtu = settings.mtu,
                powerSavingMode = settings.powerSavingMode,
                localProxyAddress = localSocksConnectAddress(settings),
                localProxyPort = settings.socksPort,
            )
        }
        TcptunState.appendLog(
            "runtime settings saved: proxy=${settings.localProxyProtocol}://" +
                "${localSocksListenAddress(settings)} mtu=${settings.mtu} " +
                "log-level=${settings.logLevel} power-saving=${settings.powerSavingMode} " +
                "route-local-proxy=${settings.routeLocalProxyTraffic} " +
                "default-outbound=${settings.defaultOutbound.ifBlank { "profile-pool" }} " +
                "flow-analysis=${settings.flowAnalysisApp.ifBlank { "disabled" }}",
        )
    }
}

internal fun <T> SharedPreferences.readOrDefault(
    key: String,
    defaultValue: T,
    read: SharedPreferences.() -> T,
): T = try {
    read()
} catch (error: Throwable) {
    if (error.isFatalProcessError()) throw error
    runRecoverableCatching { TcptunState.appendLog("runtime setting $key is invalid; using default") }
    defaultValue
}
