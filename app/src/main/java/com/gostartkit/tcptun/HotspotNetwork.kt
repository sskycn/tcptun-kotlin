package com.tcptun.client

import java.net.Inet4Address
import java.net.NetworkInterface

internal data class InterfaceIpv4Address(
    val interfaceName: String,
    val address: String,
)

internal fun readInterfaceIpv4Addresses(): List<InterfaceIpv4Address> {
    return runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }
        .getOrDefault(emptyList())
        .asSequence()
        .filter { networkInterface ->
            runCatching { networkInterface.isUp && !networkInterface.isLoopback }
                .getOrDefault(false)
        }
        .flatMap { networkInterface ->
            networkInterface.inetAddresses.toList().asSequence()
                .filterIsInstance<Inet4Address>()
                .filterNot { address -> address.isLoopbackAddress || address.isLinkLocalAddress }
                .map { address -> InterfaceIpv4Address(networkInterface.name, address.hostAddress.orEmpty()) }
        }
        .filter { it.address.isNotBlank() }
        .distinct()
        .toList()
}

/**
 * Finds the IPv4 address that tethered Wi-Fi clients use to reach this device.
 *
 * Android 16/API 36 supplies [tetheredInterfaceNames] through TetheringManager. Older
 * releases have no public tether-interface callback, so a conservative interface-name
 * fallback is used after excluding the active upstream and VPN interfaces.
 */
internal fun selectHotspotIpv4Address(
    addresses: List<InterfaceIpv4Address>,
    tetheredInterfaceNames: Set<String>?,
    excludedInterfaceNames: Set<String>,
): InterfaceIpv4Address? {
    val usable = addresses.filter { candidate ->
        candidate.interfaceName !in excludedInterfaceNames && isPrivateIpv4(candidate.address)
    }
    if (tetheredInterfaceNames != null) {
        return usable.firstOrNull { it.interfaceName in tetheredInterfaceNames }
    }
    return usable
        .filter { isLikelyWifiHotspotInterface(it.interfaceName) }
        .maxByOrNull { hotspotInterfaceScore(it.interfaceName) }
}

private fun isLikelyWifiHotspotInterface(name: String): Boolean {
    val value = name.lowercase()
    return value == "wlan0" ||
        value.matches(Regex("wlan\\d+")) ||
        value.startsWith("ap") ||
        value.contains("softap") ||
        value.startsWith("swlan") ||
        value.startsWith("wifi")
}

private fun hotspotInterfaceScore(name: String): Int {
    val value = name.lowercase()
    return when {
        value.contains("softap") -> 50
        value.startsWith("ap") -> 40
        value.startsWith("swlan") -> 30
        value.matches(Regex("wlan\\d+")) -> 20
        value.startsWith("wifi") -> 10
        else -> 0
    }
}

private fun isPrivateIpv4(address: String): Boolean {
    val parts = address.split('.').mapNotNull(String::toIntOrNull)
    if (parts.size != 4 || parts.any { it !in 0..255 }) return false
    return parts[0] == 10 ||
        (parts[0] == 172 && parts[1] in 16..31) ||
        (parts[0] == 192 && parts[1] == 168)
}
