package com.tcptun.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal sealed interface VpnCommandPayload

internal data class StartVpnCommandPayload(
    val configJson: String,
    val plan: ProfileRunPlan,
    val runtimeSettings: RuntimeSettings,
) : VpnCommandPayload

internal data class UpdateOutboundsCommandPayload(
    val plan: ProfileRunPlan,
) : VpnCommandPayload

internal interface VpnCommandStore {
    fun publish(command: VpnCommandPayload): String
    fun consume(commandId: String): VpnCommandPayload?
    fun remove(commandId: String)
    fun cleanupExpired()
}

internal interface VpnCommandMetadataStorage {
    fun put(commandId: String, createdAtMillis: Long): Boolean
    fun read(commandId: String): Long?
    fun entries(): Map<String, Long>
    fun remove(commandId: String): Boolean
}

internal class EncryptedVpnCommandStore(
    private val secretStorage: SecretStorage,
    private val metadataStorage: VpnCommandMetadataStorage,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val commandIdGenerator: () -> String = { UUID.randomUUID().toString() },
) : VpnCommandStore {
    override fun publish(command: VpnCommandPayload): String = synchronized(CommandLock) {
        cleanupExpiredLocked()
        val commandId = commandIdGenerator()
        require(commandId.matches(CommandIdPattern)) { "invalid VPN command ID" }
        val encoded = encode(command)
        require(encoded.length <= MaxVpnCommandPayloadLength) { "VPN command payload is too large" }
        val secretId = secretId(commandId)
        secretStorage.writeVerified(secretId, encoded)
        if (!metadataStorage.put(commandId, nowMillis())) {
            runRecoverableCatching { secretStorage.remove(secretId) }
            error("VPN command metadata could not be persisted")
        }
        commandId
    }

    override fun consume(commandId: String): VpnCommandPayload? = synchronized(CommandLock) {
        if (!commandId.matches(CommandIdPattern)) return@synchronized null
        val createdAt = metadataStorage.read(commandId) ?: return@synchronized null
        if (isExpired(createdAt)) {
            removeLocked(commandId)
            return@synchronized null
        }
        val secretId = secretId(commandId)
        try {
            val encoded = secretStorage.read(secretId) ?: run {
                removeLocked(commandId)
                return@synchronized null
            }
            require(encoded.length <= MaxVpnCommandPayloadLength) { "VPN command payload is too large" }
            decode(encoded).also { removeLocked(commandId) }
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            removeLocked(commandId)
            throw error
        }
    }

    override fun remove(commandId: String) = synchronized(CommandLock) {
        if (commandId.matches(CommandIdPattern)) removeLocked(commandId)
    }

    override fun cleanupExpired() = synchronized(CommandLock) {
        cleanupExpiredLocked()
    }

    private fun cleanupExpiredLocked() {
        metadataStorage.entries().forEach { (commandId, createdAt) ->
            if (!commandId.matches(CommandIdPattern) || isExpired(createdAt)) removeLocked(commandId)
        }
    }

    private fun isExpired(createdAt: Long): Boolean {
        val age = nowMillis() - createdAt
        return createdAt <= 0L || age < 0L || age > CommandTtlMillis
    }

    private fun removeLocked(commandId: String) {
        val metadataRemoved = metadataStorage.remove(commandId)
        runRecoverableCatching { secretStorage.remove(secretId(commandId)) }
            .onFailure { if (metadataRemoved) throw it }
    }

    private fun encode(command: VpnCommandPayload): String = when (command) {
        is StartVpnCommandPayload -> JSONObject()
            .put("version", PayloadVersion)
            .put("type", TypeStart)
            .put("config", command.configJson)
            .put("plan", command.plan.normalized().toJson())
            .put("settings", encodeRuntimeSettings(command.runtimeSettings))
            .toString()
        is UpdateOutboundsCommandPayload -> JSONObject()
            .put("version", PayloadVersion)
            .put("type", TypeUpdateOutbounds)
            .put("plan", command.plan.normalized().toJson())
            .toString()
    }

    private fun decode(encoded: String): VpnCommandPayload {
        requireSafeJsonNesting(encoded)
        val json = JSONObject(encoded)
        require(json.getInt("version") == PayloadVersion) { "unsupported VPN command payload version" }
        return when (json.getString("type")) {
            TypeStart -> StartVpnCommandPayload(
                configJson = json.getString("config"),
                plan = ProfileRunPlan.fromJson(json.getJSONObject("plan")),
                runtimeSettings = decodeRuntimeSettings(json.getJSONObject("settings")),
            )
            TypeUpdateOutbounds -> UpdateOutboundsCommandPayload(
                plan = ProfileRunPlan.fromJson(json.getJSONObject("plan")),
            )
            else -> error("unsupported VPN command payload type")
        }
    }

    private fun encodeRuntimeSettings(settings: RuntimeSettings): JSONObject = JSONObject()
        .put("mtu", settings.mtu)
        .put("powerSavingMode", settings.powerSavingMode)
        .put("logLevel", settings.logLevel)
        .put("socksPort", settings.socksPort)
        .put("localProxyProtocol", settings.localProxyProtocol)
        .put("socksListenAll", settings.socksListenAll)
        .put("localProxyUsers", JSONArray().apply {
            settings.localProxyUsers.forEach { user ->
                put(JSONObject().put("username", user.username).put("password", user.password))
            }
        })
        .put("routeLocalProxyTraffic", settings.routeLocalProxyTraffic)
        .put("defaultOutbound", settings.defaultOutbound)
        .put("flowAnalysisApp", settings.flowAnalysisApp)

    private fun decodeRuntimeSettings(json: JSONObject): RuntimeSettings = requireSafeRuntimeSettings(
        RuntimeSettings(
            mtu = json.optInt("mtu", RuntimeSettingsDefaults.VpnMtu).coerceIn(1280, 1500),
            powerSavingMode = json.optBoolean("powerSavingMode", true),
            logLevel = normalizeLogLevel(json.optString("logLevel")),
            socksPort = json.optInt("socksPort", RuntimeSettingsDefaults.SocksPort).coerceIn(1, 65535),
            localProxyProtocol = normalizeLocalProxyProtocol(json.optString("localProxyProtocol")),
            socksListenAll = json.optBoolean("socksListenAll", false),
            localProxyUsers = json.optJSONArray("localProxyUsers")?.let { users ->
                require(users.length() <= MaxLocalProxyUsers) { "too many local proxy accounts" }
                buildList {
                    for (index in 0 until users.length()) {
                        val user = users.getJSONObject(index)
                        add(LocalProxyUser(user.getString("username"), user.getString("password")))
                    }
                }
            } ?: emptyList(),
            routeLocalProxyTraffic = json.optBoolean("routeLocalProxyTraffic", false),
            defaultOutbound = normalizeDefaultOutboundSelection(json.optString("defaultOutbound")),
            flowAnalysisApp = normalizeFlowAnalysisApp(json.optString("flowAnalysisApp")),
        ),
    )

    private fun secretId(commandId: String) = "vpn-command.$commandId"

    internal companion object {
        const val CommandTtlMillis = 5 * 60 * 1_000L
        private const val PayloadVersion = 1
        private const val TypeStart = "start"
        private const val TypeUpdateOutbounds = "update_outbounds"
        private val CommandIdPattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        private val CommandLock = Any()
    }
}

internal class SharedPreferencesVpnCommandMetadataStorage(context: Context) : VpnCommandMetadataStorage {
    private val preferences = (context.applicationContext ?: context)
        .getSharedPreferences(Preferences, Context.MODE_PRIVATE)

    override fun put(commandId: String, createdAtMillis: Long): Boolean =
        preferences.edit().putLong(commandId, createdAtMillis).commit()

    override fun read(commandId: String): Long? =
        if (preferences.contains(commandId)) preferences.getLong(commandId, 0L) else null

    override fun entries(): Map<String, Long> = preferences.all.mapNotNull { (id, value) ->
        (value as? Long)?.let { id to it }
    }.toMap()

    override fun remove(commandId: String): Boolean = preferences.edit().remove(commandId).commit()

    private companion object {
        const val Preferences = "tcptun_vpn_commands"
    }
}

internal fun vpnCommandStore(context: Context): VpnCommandStore {
    val appContext = context.applicationContext ?: context
    return EncryptedVpnCommandStore(
        secretStorage = EncryptedSecretStore(appContext),
        metadataStorage = SharedPreferencesVpnCommandMetadataStorage(appContext),
    )
}
