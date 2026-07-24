package com.tcptun.client

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

private const val MAX_IDENTITY_FLOW_JSON_LENGTH = 64 * 1024
private const val MAX_FLOW_ENDPOINT_LENGTH = 512
private const val MAX_ROUTE_APP_COUNT = 4_096
private const val MAX_APP_LABEL_INPUT_LENGTH = 1_024
private const val MAX_APP_LABEL_LENGTH = 256
private const val MAX_PACKAGE_NAME_LENGTH = 1_024
// tcptun-go accepts at most 256 values for one application identity attribute.
private const val MAX_UID_PACKAGE_CANDIDATES = 256

internal data class InstalledRouteApp(
    val packageName: String,
    val label: String,
) {
    val displayName: String
        get() = if (label == packageName) packageName else "$label · $packageName"
}

internal fun loadInstalledRouteApps(context: Context): List<InstalledRouteApp> {
    val packageManager = runCatching { context.packageManager }.getOrNull() ?: return emptyList()
    val ownPackageName = runCatching { context.packageName }.getOrDefault("")
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    val activities = runCatching { packageManager.queryIntentActivities(launcher, 0) }
        .getOrElse { emptyList() }
    return activities
        .asSequence()
        .mapNotNull { info ->
            runCatching {
                val packageName = info.activityInfo?.packageName?.trim().orEmpty()
                if (
                    packageName.isBlank() ||
                    packageName.length > MAX_PACKAGE_NAME_LENGTH ||
                    packageName == ownPackageName
                ) {
                    return@runCatching null
                }
                val rawLabel = info.loadLabel(packageManager)?.toString().orEmpty()
                val label = rawLabel
                    .takeIf { it.length <= MAX_APP_LABEL_INPUT_LENGTH }
                    ?.trim()
                    ?.take(MAX_APP_LABEL_LENGTH)
                    .orEmpty()
                    .ifBlank { packageName }
                InstalledRouteApp(packageName = packageName, label = label)
            }.getOrNull()
        }
        .distinctBy(InstalledRouteApp::packageName)
        .take(MAX_ROUTE_APP_COUNT)
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, InstalledRouteApp::label).thenBy(InstalledRouteApp::packageName))
        .toList()
}

internal data class ProxyFlowSource(
    val protocol: Int,
    val address: InetAddress,
    val port: Int,
)

internal fun parseProxyFlowSource(flowJson: String): ProxyFlowSource? {
    if (flowJson.length > MAX_IDENTITY_FLOW_JSON_LENGTH) return null
    return runRecoverableCatching {
        requireSafeJsonNesting(flowJson)
        val flow = JSONObject(flowJson)
        parseProxyFlowSource(flow.optString("network"), flow.optString("source"))
    }.getOrNull()
}

internal fun parseProxyFlowSource(network: String, sourceValue: String): ProxyFlowSource? {
    if (network.length > 16 || sourceValue.length > MAX_FLOW_ENDPOINT_LENGTH) return null
    val protocol = when (network.trim().lowercase()) {
        "tcp" -> OsConstants.IPPROTO_TCP
        "udp" -> OsConstants.IPPROTO_UDP
        else -> return null
    }
    val source = sourceValue.trim()
    val separator = source.lastIndexOf(':')
    if (separator <= 0 || separator == source.lastIndex) return null
    val host = source.substring(0, separator).removeSurrounding("[", "]")
    if (host.isBlank()) return null
    val portValue = source.substring(separator + 1)
    if (portValue.isEmpty() || !portValue.all(Char::isDigit)) return null
    val port = portValue.toIntOrNull()?.takeIf { it in 0..65535 } ?: return null
    val address = parseNumericAddress(host) ?: return null
    return ProxyFlowSource(protocol, address, port)
}

private fun parseNumericAddress(value: String): InetAddress? {
    if (':' !in value) {
        val octets = value.split('.', limit = 5)
        if (octets.size != 4 || octets.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
        val bytes = ByteArray(4)
        octets.forEachIndexed { index, octet ->
            val number = octet.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            bytes[index] = number.toByte()
        }
        return runCatching { InetAddress.getByAddress(bytes) }.getOrNull()
    }

    val zoneSeparator = value.indexOf('%')
    if (zoneSeparator >= 0) {
        if (zoneSeparator != value.lastIndexOf('%')) return null
        val zone = value.substring(zoneSeparator + 1)
        if (zone.isEmpty() || zone.length > 64 || zone.any { !it.isLetterOrDigit() && it !in "_.-" }) return null
    }
    val literal = if (zoneSeparator >= 0) value.substring(0, zoneSeparator) else value
    if (literal.isEmpty() || literal.any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' && it != ':' && it != '.' }) {
        return null
    }
    return runCatching { InetAddress.getByName(value) }.getOrNull()
}

internal fun androidAppIdentityJson(uid: Int, packages: List<String>, flowAnalysisApp: String): String? {
    val normalizedPackages = runCatching {
        packages.asSequence()
            .take(MAX_UID_PACKAGE_CANDIDATES)
            .filter { it.length <= MAX_PACKAGE_NAME_LENGTH }
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .take(MAX_UID_PACKAGE_CANDIDATES)
            .sorted()
            .toList()
    }.getOrDefault(emptyList())
    if (normalizedPackages.isEmpty()) return null
    val analysisApp = flowAnalysisApp
        .takeIf { it.length <= MAX_PACKAGE_NAME_LENGTH }
        ?.trim()
        ?.takeIf(normalizedPackages::contains)
    return runCatching {
        JSONObject()
            .apply {
                if (analysisApp != null) {
                    put("id", analysisApp)
                } else if (normalizedPackages.size == 1) {
                    put("id", normalizedPackages.single())
                }
                put("platform", "android")
                put(
                    "attributes",
                    JSONObject()
                        .put("uid", JSONArray().put(uid.toString()))
                        .put("packages", JSONArray().apply { normalizedPackages.forEach(::put) }),
                )
            }
            .toString()
    }.getOrNull()
}

private data class OriginalFlow(
    val protocol: Int,
    val local: InetSocketAddress,
    val remote: InetSocketAddress,
)

private data class IdentityCacheKey(
    val uid: Int,
    val flowAnalysisApp: String,
)

internal class AndroidAppIdentityProvider(
    context: Context,
    private val connectivity: ConnectivityManager,
) {
    private val packageManager = runCatching { (context.applicationContext ?: context).packageManager }.getOrNull()
    private val identities = ConcurrentHashMap<IdentityCacheKey, String>()
    @Volatile private var flowAnalysisApp: String = ""

    fun setFlowAnalysisApp(packageName: String) {
        val normalized = packageName
            .takeIf { it.length <= MAX_PACKAGE_NAME_LENGTH }
            ?.trim()
            .orEmpty()
        if (normalized == flowAnalysisApp) return
        flowAnalysisApp = normalized
        identities.clear()
    }

    fun identify(flowJson: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (flowJson.length > MAX_IDENTITY_FLOW_JSON_LENGTH) return null
        val original = runRecoverableCatching {
            requireSafeJsonNesting(flowJson)
            val flow = JSONObject(flowJson)
            val network = flow.optString("network")
            val source = parseProxyFlowSource(network, flow.optString("source"))
                ?: return@runRecoverableCatching null
            val destination = parseProxyFlowSource(network, flow.optString("destination"))
                ?: return@runRecoverableCatching null
            OriginalFlow(
                protocol = source.protocol,
                local = InetSocketAddress(source.address, source.port),
                remote = InetSocketAddress(destination.address, destination.port),
            )
        }.getOrNull() ?: return null
        val uid = runCatching {
            connectivity.getConnectionOwnerUid(original.protocol, original.local, original.remote)
        }.getOrDefault(Process.INVALID_UID)
        if (uid == Process.INVALID_UID) return null
        val key = IdentityCacheKey(uid, flowAnalysisApp)
        identities[key]?.let { return it }
        val identity = identityForUid(uid, key.flowAnalysisApp) ?: return null
        return identities.putIfAbsent(key, identity) ?: identity
    }

    fun clear() {
        identities.clear()
    }

    private fun identityForUid(uid: Int, analysisApp: String): String? {
        val manager = packageManager ?: return null
        val packages = runCatching {
            buildList<String> {
                manager.getPackagesForUid(uid)?.forEach { packageName -> packageName?.let(::add) }
            }
        }
            .getOrDefault(emptyList())
        return androidAppIdentityJson(uid, packages, analysisApp)
    }
}
