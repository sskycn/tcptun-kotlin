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

internal data class InstalledRouteApp(
    val packageName: String,
    val label: String,
) {
    val displayName: String
        get() = if (label == packageName) packageName else "$label · $packageName"
}

internal fun loadInstalledRouteApps(context: Context): List<InstalledRouteApp> {
    val packageManager = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    val activities = packageManager.queryIntentActivities(launcher, 0)
    return activities
        .mapNotNull { info ->
            val packageName = info.activityInfo?.packageName?.trim().orEmpty()
            if (packageName.isBlank() || packageName == context.packageName) return@mapNotNull null
            InstalledRouteApp(
                packageName = packageName,
                label = info.loadLabel(packageManager)?.toString()?.trim().orEmpty().ifBlank { packageName },
            )
        }
        .distinctBy(InstalledRouteApp::packageName)
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, InstalledRouteApp::label).thenBy(InstalledRouteApp::packageName))
}

internal data class ProxyFlowSource(
    val protocol: Int,
    val address: InetAddress,
    val port: Int,
)

internal fun parseProxyFlowSource(flowJson: String): ProxyFlowSource? {
    val flow = runCatching { JSONObject(flowJson) }.getOrNull() ?: return null
    return parseProxyFlowSource(flow.optString("network"), flow.optString("source"))
}

internal fun parseProxyFlowSource(network: String, sourceValue: String): ProxyFlowSource? {
    val protocol = when (network.trim().lowercase()) {
        "tcp" -> OsConstants.IPPROTO_TCP
        "udp" -> OsConstants.IPPROTO_UDP
        else -> return null
    }
    val source = sourceValue.trim()
    val separator = source.lastIndexOf(':')
    if (separator <= 0 || separator == source.lastIndex) return null
    val host = source.substring(0, separator).removeSurrounding("[", "]")
    val port = source.substring(separator + 1).toIntOrNull()?.takeIf { it in 0..65535 } ?: return null
    val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
    return ProxyFlowSource(protocol, address, port)
}

private data class OriginalFlow(
    val protocol: Int,
    val local: InetSocketAddress,
    val remote: InetSocketAddress,
)

internal class AndroidAppIdentityProvider(
    context: Context,
    private val connectivity: ConnectivityManager,
) {
    private val packageManager = context.applicationContext.packageManager
    private val identities = ConcurrentHashMap<Int, String?>()

    fun identify(flowJson: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val proxy = parseProxyFlowSource(flowJson) ?: return null
        val original = resolveOriginalFlow(proxy) ?: return null
        val uid = runCatching {
            connectivity.getConnectionOwnerUid(original.protocol, original.local, original.remote)
        }.getOrDefault(Process.INVALID_UID)
        if (uid == Process.INVALID_UID) return null
        return identities.computeIfAbsent(uid, ::identityForUid)
    }

    fun clear() {
        identities.clear()
    }

    private fun resolveOriginalFlow(proxy: ProxyFlowSource): OriginalFlow? {
        val raw = runCatching {
            HevSocks5Tunnel.resolveOriginalFlow(proxy.protocol, proxy.address.address, proxy.port)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val protocol = json.optInt("protocol")
        if (protocol != OsConstants.IPPROTO_TCP && protocol != OsConstants.IPPROTO_UDP) return null
        val local = socketAddress(json, "local") ?: return null
        val remote = socketAddress(json, "remote") ?: return null
        return OriginalFlow(protocol, local, remote)
    }

    private fun socketAddress(json: JSONObject, prefix: String): InetSocketAddress? {
        val address = json.optString("${prefix}_address").takeIf(String::isNotBlank) ?: return null
        val port = json.optInt("${prefix}_port", -1).takeIf { it in 0..65535 } ?: return null
        return runCatching { InetSocketAddress(InetAddress.getByName(address), port) }.getOrNull()
    }

    private fun identityForUid(uid: Int): String? {
        val packages = packageManager.getPackagesForUid(uid)
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.distinct()
            ?.sorted()
            .orEmpty()
        if (packages.isEmpty()) return null
        return JSONObject()
            .apply {
                if (packages.size == 1) put("id", packages.single())
                put("platform", "android")
                put(
                    "attributes",
                    JSONObject()
                        .put("uid", JSONArray().put(uid.toString()))
                        .put("packages", JSONArray().apply { packages.forEach(::put) }),
                )
            }
            .toString()
    }
}
