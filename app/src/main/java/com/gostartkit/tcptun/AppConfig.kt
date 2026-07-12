package com.tcptun.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class AppConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "proxy",
    val serverHost: String = "",
    val serverPort: String = "9443",
    val protocol: String = "native",
    val transport: String = "raw",
    val token: String = "",
    val sni: String = "",
    val path: String = "/proxy",
    val tls: Boolean = false,
    val tlsInsecure: Boolean = false,
    val tunnelSecurity: String = "",
    val flow: String = "",
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    val realityFingerprint: String = "",
    val realitySpiderX: String = "",
    val mux: Boolean = true,
    val udp: Boolean = true,
    val upstreamProtocol: String = "socks5",
    val rawConfigJson: String = "",
) {
    val serverAddr: String
        get() {
            val host = serverHost.trim()
            val port = serverPort.trim()
            val authorityHost = when {
                host.startsWith("[") && host.endsWith("]") -> host
                host.contains(":") -> "[$host]"
                else -> host
            }
            return "$authorityHost:$port"
        }

    fun validate(): String? {
        if (name.isBlank()) return "profile name is required"
        if (rawConfigJson.isNotBlank()) return validateRawConfig(rawConfigJson)
        if (serverHost.isBlank()) return "server address is required"
        val port = serverPort.toIntOrNull() ?: return "server port must be a number"
        if (port !in 1..65535) return "server port must be between 1 and 65535"
        if (protocol !in Protocols) return "unsupported protocol: $protocol"
        if (transport !in Transports) return "unsupported transport: $transport"
        if (upstreamProtocol !in UpstreamProtocols) return "unsupported upstream protocol: $upstreamProtocol"
        if (path.isBlank()) return "path is required"
        return null
    }

    fun toBridgeJson(
        localListenAddr: String,
        verbose: Boolean = false,
        powerSavingMode: Boolean = false,
        socks5Username: String = "",
        socks5Password: String = "",
        routeExternalSources: Boolean = false,
        directFirst: Boolean = false,
        managedRouteRules: List<ManagedRouteRule> = emptyList(),
    ): String {
        if (rawConfigJson.isNotBlank()) {
            return prepareRawConfigForAndroid(
                localListenAddr = localListenAddr,
                udpEnabled = udp,
                socks5Username = socks5Username,
                socks5Password = socks5Password,
                verbose = verbose,
            )
        }
        val (listenHost, listenPort) = splitHostPort(localListenAddr)
        val networks = JSONArray().put("tcp").apply {
            if (udp) put("udp")
        }
        val inbound = JSONObject()
            .put("tag", "local")
            .put("type", upstreamProtocol)
            .put("listen", listenHost)
            .put("port", listenPort)
            .put("network", networks)
            .put("outbound", "proxy")
            .put("username", socks5Username)
            .put("password", socks5Password)

        val proxy = JSONObject()
            .put("tag", "proxy")
            .put("type", protocol)
            .put("server", serverHost.trim().removeSurrounding("[", "]"))
            .put("port", serverPort.trim().toInt())
            .put("flow", flow.trim())
            .put(
                "transport",
                JSONObject()
                    .put("type", transport)
                    .put("path", normalizedPath())
                    .put("tls", tls)
                    .put("server_name", sni.trim())
                    .put("insecure", tlsInsecure),
            )
            .put("mux", JSONObject().put("enabled", mux))
        when (protocol) {
            "vless", "vmess" -> proxy.put("uuid", token.trim())
            "trojan" -> proxy.put("password", token.trim())
            else -> proxy.put("token", token.trim())
        }
        if (tunnelSecurity.equals("reality", ignoreCase = true)) {
            proxy.put(
                "security",
                JSONObject()
                    .put("type", "reality")
                    .put("server_name", sni.trim())
                    .put("fingerprint", realityFingerprint.trim())
                    .put("public_key", realityPublicKey.trim())
                    .put("short_id", realityShortId.trim())
                    .put("spider_x", realitySpiderX.trim()),
            )
        }

        // tcptun-go's direct-first outbound is TCP-only. UDP keeps using the
        // tunnel, matching the previous Android client behavior for public IPs.
        val allowDirectFirst = directFirst &&
            (listenHost in setOf("127.0.0.1", "::1", "localhost") || routeExternalSources)
        val outbounds = JSONArray()
            .put(proxy)
            .put(JSONObject().put("tag", "direct").put("type", "direct"))
        if (allowDirectFirst) {
            outbounds.put(
                JSONObject()
                    .put("tag", "auto")
                    .put("type", "direct-first")
                    .put("primary", "direct")
                    .put("fallback", "proxy")
                    .put("network", JSONArray().put("tcp"))
                    .put("probe_timeout", if (powerSavingMode) "500ms" else "800ms")
                    .put("failure_threshold", 2)
                    .put("positive_ttl", "30m")
                    .put("negative_ttl", if (powerSavingMode) "30m" else "10m"),
            )
        }

        val rules = JSONArray()
        val activeManagedRules = managedRouteRules.map(ManagedRouteRule::normalized)
            .filter { it.enabled && it.isValid() }
        if (allowDirectFirst || activeManagedRules.any { it.outbound == ManagedRouteOutbound.Direct }) {
            rules.put(
                JSONObject()
                    .put("inbound", JSONArray().put("local"))
                    .put("network", JSONArray().put("tcp"))
                    .put(
                        "domains",
                        JSONArray()
                            .put("connectivitycheck.gstatic.com")
                            .put("cp.cloudflare.com"),
                    )
                    .put("outbound", "proxy"),
            )
        }
        activeManagedRules.forEach { rule ->
            rules.put(
                JSONObject()
                    .put("inbound", JSONArray().put("local"))
                    .put(rule.type.jsonKey, JSONArray().put(rule.value))
                    .put("outbound", rule.outbound.tag),
            )
        }
        if (allowDirectFirst) {
            rules.put(
                JSONObject()
                    .put("inbound", JSONArray().put("local"))
                    .put("network", JSONArray().put("tcp"))
                    .put("outbound", "auto"),
            )
        }
        return JSONObject()
            .put("log", JSONObject().put("level", if (verbose) "debug" else "info"))
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", outbounds)
            .put("route", JSONObject().put("default_outbound", "proxy").put("rules", rules))
            .put("dns", JSONObject())
            .put("discovery", JSONObject())
            .toString()
    }

    private fun prepareRawConfigForAndroid(
        localListenAddr: String,
        udpEnabled: Boolean,
        socks5Username: String,
        socks5Password: String,
        verbose: Boolean,
    ): String {
        val root = JSONObject(rawConfigJson)
        val outbounds = root.optJSONArray("outbounds")
            ?: throw IllegalArgumentException("outbounds is required")
        require(outbounds.length() > 0) { "outbounds must not be empty" }

        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val defaultOutbound = route.optString("default_outbound").trim().ifBlank {
            val existingInbounds = root.optJSONArray("inbounds")
            var inferred = ""
            if (existingInbounds != null) {
                for (index in 0 until existingInbounds.length()) {
                    inferred = existingInbounds.optJSONObject(index)?.optString("outbound")?.trim().orEmpty()
                    if (inferred.isNotBlank()) break
                }
            }
            inferred.ifBlank { outbounds.optJSONObject(0)?.optString("tag")?.trim().orEmpty() }
        }
        require(defaultOutbound.isNotBlank()) { "route.default_outbound or a tagged outbound is required" }

        val (listenHost, listenPort) = splitHostPort(localListenAddr)
        val androidInbound = JSONObject()
            .put("tag", AndroidVpnInboundTag)
            .put("type", "socks5")
            .put("listen", listenHost)
            .put("port", listenPort)
            .put("network", JSONArray().put("tcp").apply { if (udpEnabled) put("udp") })
            .put("outbound", defaultOutbound)
            .put("username", socks5Username)
            .put("password", socks5Password)
        val inbounds = JSONArray().put(androidInbound)
        val replacedInboundTags = mutableSetOf(AndroidVpnInboundTag)
        root.optJSONArray("inbounds")?.let { existing ->
            for (index in 0 until existing.length()) {
                val inbound = existing.optJSONObject(index) ?: continue
                val tag = inbound.optString("tag").trim()
                if (tag == AndroidVpnInboundTag || inboundConflictsWithAndroidListener(inbound, listenHost, listenPort)) {
                    if (tag.isNotBlank()) replacedInboundTags += tag
                } else {
                    inbounds.put(inbound)
                }
            }
        }
        root.put("inbounds", inbounds)
        if (!route.has("default_outbound")) route.put("default_outbound", defaultOutbound)
        if (!route.has("rules")) route.put("rules", JSONArray())
        remapInboundRules(route.optJSONArray("rules"), replacedInboundTags)
        if (verbose) {
            val log = root.optJSONObject("log") ?: JSONObject().also { root.put("log", it) }
            log.put("level", "debug")
        }
        return root.toString()
    }

    private fun inboundConflictsWithAndroidListener(inbound: JSONObject, listenHost: String, listenPort: Int): Boolean {
        val address = inbound.optString("address").trim()
        if (address.isNotBlank()) {
            val parsed = runCatching { splitHostPort(address) }.getOrNull()
            return parsed != null && parsed.second == listenPort && listenerHostsOverlap(parsed.first, listenHost)
        }
        if (inbound.optInt("port", -1) != listenPort) return false
        val hosts = buildList {
            inbound.optString("listen").trim().takeIf { it.isNotBlank() }?.let(::add)
            inbound.optJSONArray("listen_addresses")?.let { values ->
                for (index in 0 until values.length()) {
                    values.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }
        return hosts.any { listenerHostsOverlap(it, listenHost) }
    }

    private fun listenerHostsOverlap(first: String, second: String): Boolean {
        fun normalize(host: String): String = host.trim().removeSurrounding("[", "]").lowercase()
        val left = normalize(first)
        val right = normalize(second)
        if (left == right) return true
        if (left in WildcardHosts || right in WildcardHosts) return true
        return left in LoopbackHosts && right in LoopbackHosts
    }

    private fun remapInboundRules(rules: JSONArray?, replacedTags: Set<String>) {
        if (rules == null || replacedTags.isEmpty()) return
        for (ruleIndex in 0 until rules.length()) {
            val rule = rules.optJSONObject(ruleIndex) ?: continue
            val tags = rule.optJSONArray("inbound") ?: continue
            val remapped = linkedSetOf<String>()
            for (tagIndex in 0 until tags.length()) {
                val tag = tags.optString(tagIndex).trim()
                if (tag.isBlank()) continue
                remapped += if (tag in replacedTags) AndroidVpnInboundTag else tag
            }
            rule.put("inbound", JSONArray().apply { remapped.forEach(::put) })
        }
    }

    private fun validateRawConfig(raw: String): String? {
        return runCatching {
            val root = JSONObject(raw)
            if (root.has("mode")) return@runCatching "legacy mode-based configuration is not supported"
            val outbounds = root.optJSONArray("outbounds")
                ?: return@runCatching "outbounds is required"
            if (outbounds.length() == 0) return@runCatching "outbounds must not be empty"
            val hasTaggedOutbound = (0 until outbounds.length()).any { index ->
                outbounds.optJSONObject(index)?.optString("tag")?.isNotBlank() == true
            }
            if (hasTaggedOutbound) null else "at least one tagged outbound is required"
        }.getOrElse { it.message ?: "invalid tcptun JSON" }
    }

    private fun splitHostPort(address: String): Pair<String, Int> {
        val trimmed = address.trim()
        val separator = trimmed.lastIndexOf(':')
        require(separator > 0) { "invalid local listen address: $address" }
        val host = trimmed.substring(0, separator).removeSurrounding("[", "]")
        val port = trimmed.substring(separator + 1).toIntOrNull()
            ?: throw IllegalArgumentException("invalid local listen port: $address")
        require(port in 1..65535) { "invalid local listen port: $address" }
        return host to port
    }

    private fun normalizedPath(): String {
        val trimmed = path.trim()
        return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    }

    companion object {
        val Protocols = listOf("native", "vless", "vmess", "trojan")
        val Transports = listOf("raw", "ws", "h2", "h3")
        val UpstreamProtocols = listOf("socks5", "mixed")
        private const val AndroidVpnInboundTag = "android-vpn"
        private val WildcardHosts = setOf("0.0.0.0", "::", "*")
        private val LoopbackHosts = setOf("127.0.0.1", "::1", "localhost")

        fun load(context: Context): AppConfig {
            return ProfileStore.load(context).profiles.firstOrNull()
                ?: AppConfig()
        }

        fun fromJson(obj: JSONObject): AppConfig {
            return AppConfig(
                id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = obj.optString("name", "proxy").ifBlank { "proxy" },
                serverHost = obj.optString("serverHost"),
                serverPort = obj.optString("serverPort", "9443"),
                protocol = obj.optString("protocol", "native"),
                transport = obj.optString("transport", "raw"),
                token = obj.optString("token"),
                sni = obj.optString("sni"),
                path = obj.optString("path", "/proxy"),
                tls = obj.optBoolean("tls", false),
                tlsInsecure = obj.optBoolean("tlsInsecure", false),
                tunnelSecurity = obj.optString("tunnelSecurity"),
                flow = obj.optString("flow"),
                realityPublicKey = obj.optString("realityPublicKey"),
                realityShortId = obj.optString("realityShortId"),
                realityFingerprint = obj.optString("realityFingerprint"),
                realitySpiderX = obj.optString("realitySpiderX"),
                mux = obj.optBoolean("mux", true),
                udp = obj.optBoolean("udp", true),
                upstreamProtocol = obj.optString("upstreamProtocol", "socks5"),
                rawConfigJson = obj.optString("rawConfigJson"),
            )
        }
    }

    fun save(context: Context) {
        val current = ProfileStore.load(context)
        val profiles = current.profiles.toMutableList()
        val index = profiles.indexOfFirst { it.id == id }
        if (index >= 0) {
            profiles[index] = this
        } else {
            profiles.add(this)
        }
        ProfileStore.save(context, ProfilesState(profiles, id))
    }

    fun label(): String {
        if (rawConfigJson.isNotBlank()) return "TCPTUN / JSON"
        val security = when {
            tunnelSecurity.isNotBlank() -> tunnelSecurity
            sni.isNotBlank() && tls -> "tls"
            sni.isNotBlank() -> "reality"
            tls -> "tls"
            else -> transport
        }
        return if (security.isBlank()) protocol.uppercase() else "${protocol.uppercase()} / $security"
    }

    fun maskedAddress(): String {
        if (rawConfigJson.isNotBlank()) {
            val root = runCatching { JSONObject(rawConfigJson) }.getOrNull()
            val inbounds = root?.optJSONArray("inbounds")?.length() ?: 0
            val outbounds = root?.optJSONArray("outbounds")?.length() ?: 0
            return "$inbounds inbounds · $outbounds outbounds"
        }
        val host = serverHost.trim()
        val masked = when {
            host.length <= 8 -> host
            host.contains(":") -> host.take(9) + ".***"
            else -> host.take(10) + ".***"
        }
        return "$masked : ${serverPort.trim()}"
    }

    fun toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("serverHost", serverHost)
            .put("serverPort", serverPort)
            .put("protocol", protocol)
            .put("transport", transport)
            .put("token", token)
            .put("sni", sni)
            .put("path", path)
            .put("tls", tls)
            .put("tlsInsecure", tlsInsecure)
            .put("tunnelSecurity", tunnelSecurity)
            .put("flow", flow)
            .put("realityPublicKey", realityPublicKey)
            .put("realityShortId", realityShortId)
            .put("realityFingerprint", realityFingerprint)
            .put("realitySpiderX", realitySpiderX)
            .put("mux", mux)
            .put("udp", udp)
            .put("upstreamProtocol", upstreamProtocol)
            .put("rawConfigJson", rawConfigJson)
    }

    fun shareText(): String {
        return ProfileUriCodec.encode(this).orEmpty()
    }
}

data class ProfilesState(
    val profiles: List<AppConfig>,
    val selectedId: String?,
) {
    val selected: AppConfig?
        get() = profiles.firstOrNull { it.id == selectedId } ?: profiles.firstOrNull()
}

object ProfileStore {
    private const val PREFS = "tcptun"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_SELECTED = "selectedProfileId"

    fun load(context: Context): ProfilesState {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PROFILES, null)
        if (!raw.isNullOrBlank()) {
            val arr = JSONArray(raw)
            val profiles = buildList {
                for (i in 0 until arr.length()) {
                    val profile = AppConfig.fromJson(arr.getJSONObject(i))
                    if (profile.serverHost.isNotBlank() || profile.rawConfigJson.isNotBlank()) {
                        add(profile)
                    }
                }
            }
            val state = ProfilesState(profiles, prefs.getString(KEY_SELECTED, profiles.firstOrNull()?.id))
            if (profiles.size != arr.length()) {
                save(context, state)
            }
            return state
        }
        val migrated = migrateSingleProfile(context)
        save(context, migrated)
        return migrated
    }

    fun save(context: Context, state: ProfilesState) {
        val selectedId = state.selected?.id
        val arr = JSONArray()
        state.profiles.forEach { arr.put(it.toJson()) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROFILES, arr.toString())
            .putString(KEY_SELECTED, selectedId)
            .apply()
    }

    private fun migrateSingleProfile(context: Context): ProfilesState {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val oldHost = prefs.getString("serverHost", "") ?: ""
        if (oldHost.isBlank()) {
            return ProfilesState(emptyList(), null)
        }
        val profile = AppConfig(
            name = if (oldHost.isBlank()) "proxy" else "proxy",
            serverHost = oldHost,
            serverPort = prefs.getString("serverPort", "9443") ?: "9443",
            protocol = prefs.getString("protocol", "native") ?: "native",
            transport = prefs.getString("transport", "raw") ?: "raw",
            token = prefs.getString("token", "") ?: "",
            sni = prefs.getString("sni", "") ?: "",
            path = prefs.getString("path", "/proxy") ?: "/proxy",
            tls = prefs.getBoolean("tls", false),
            mux = prefs.getBoolean("mux", true),
            udp = prefs.getBoolean("udp", true),
        )
        return ProfilesState(listOf(profile), profile.id)
    }
}
