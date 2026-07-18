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
        val flow = runCatching { JSONObject(flowJson) }.getOrNull() ?: return null
        val network = flow.optString("network")
        val source = parseProxyFlowSource(network, flow.optString("source")) ?: return null
        val destination = parseProxyFlowSource(network, flow.optString("destination")) ?: return null
        val original = OriginalFlow(
            protocol = source.protocol,
            local = InetSocketAddress(source.address, source.port),
            remote = InetSocketAddress(destination.address, destination.port),
        )
        val uid = runCatching {
            connectivity.getConnectionOwnerUid(original.protocol, original.local, original.remote)
        }.getOrDefault(Process.INVALID_UID)
        if (uid == Process.INVALID_UID) return null
        return identities.computeIfAbsent(uid, ::identityForUid)
    }

    fun clear() {
        identities.clear()
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
