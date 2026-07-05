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
) {
    val serverAddr: String
        get() = "${serverHost.trim()}:${serverPort.trim()}"

    fun validate(): String? {
        if (name.isBlank()) return "profile name is required"
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
        routeConfigPath: String = "",
        verbose: Boolean = false,
        powerSavingMode: Boolean = false,
        socks5Username: String = "",
        socks5Password: String = "",
    ): String {
        val heartbeatInterval = if (powerSavingMode) "120s" else "30s"
        val connectionIdleTimeout = if (powerSavingMode) "5m" else "2m"
        val udpSessionTimeout = if (powerSavingMode) "2m" else "45s"
        val retryMaxInterval = if (powerSavingMode) "15s" else "5s"
        return JSONObject()
            .put("mode", "client")
            .put("listen_addrs", JSONArray().put(localListenAddr))
            .put("local_listen_addr", localListenAddr)
            .put("server_addr", serverAddr)
            .put("token", token.trim())
            .put("tunnel_protocol", protocol)
            .put("tunnel_transport", transport)
            .put("tunnel_path", normalizedPath())
            .put("tunnel_tls", tls)
            .put("tunnel_tls_server_name", sni.trim())
            .put("tunnel_tls_insecure", tlsInsecure)
            .put("tunnel_security", tunnelSecurity.trim())
            .put("tunnel_flow", flow.trim())
            .put("reality_server_name", sni.trim())
            .put("reality_public_key", realityPublicKey.trim())
            .put("reality_short_id", realityShortId.trim())
            .put("reality_fingerprint", realityFingerprint.trim())
            .put("reality_spider_x", realitySpiderX.trim())
            .put("tunnel_mux", mux)
            .put("upstream_protocol", upstreamProtocol)
            .put("socks5_username", socks5Username)
            .put("socks5_password", socks5Password)
            .put("enable_udp", udp)
            .put("config_path", "")
            .put("route_config_path", routeConfigPath)
            .put("heartbeat_interval", heartbeatInterval)
            .put("connection_idle_timeout", connectionIdleTimeout)
            .put("udp_session_timeout", udpSessionTimeout)
            .put("retry_max_interval", retryMaxInterval)
            .put("verbose", verbose)
            .toString()
    }

    private fun normalizedPath(): String {
        val trimmed = path.trim()
        return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    }

    companion object {
        val Protocols = listOf("native", "vless", "vmess", "trojan")
        val Transports = listOf("raw", "ws", "h2", "h3")
        val UpstreamProtocols = listOf("socks5", "mixed")

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
    }

    fun shareText(): String {
        return ProfileUriCodec.encode(this) ?: toJson().toString(2)
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
                    if (profile.serverHost.isNotBlank()) {
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
