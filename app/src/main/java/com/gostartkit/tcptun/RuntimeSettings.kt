package com.tcptun.client

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

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

/** One atomically published view of every setting applied to the current runtime. */
internal data class AppliedRuntimeSettings(
    val mtu: Int = RuntimeSettingsDefaults.VpnMtu,
    val powerSavingMode: Boolean = true,
    val logLevel: String = DefaultLogLevel,
    val socksPort: Int = RuntimeSettingsDefaults.SocksPort,
    val localProxyProtocol: String = DefaultLocalProxyProtocol,
    val socksListenAll: Boolean = false,
    val socksUsername: String = "",
    val socksPassword: String = "",
    val routeLocalProxyTraffic: Boolean = false,
    val defaultOutbound: String = DefaultOutboundDynamicPool,
    val flowAnalysisApp: String = "",
) {
    fun structuralSettings(): RuntimeSettings = RuntimeSettings(
        mtu = mtu,
        powerSavingMode = powerSavingMode,
        logLevel = logLevel,
        socksPort = socksPort,
        localProxyProtocol = localProxyProtocol,
        socksListenAll = socksListenAll,
        socksUsername = socksUsername,
        socksPassword = socksPassword,
        routeLocalProxyTraffic = routeLocalProxyTraffic,
        defaultOutbound = defaultOutbound,
    )

    companion object {
        fun from(settings: RuntimeSettings): AppliedRuntimeSettings = AppliedRuntimeSettings(
            mtu = settings.mtu,
            powerSavingMode = settings.powerSavingMode,
            logLevel = settings.logLevel,
            socksPort = settings.socksPort,
            localProxyProtocol = settings.localProxyProtocol,
            socksListenAll = settings.socksListenAll,
            socksUsername = settings.socksUsername,
            socksPassword = settings.socksPassword,
            routeLocalProxyTraffic = settings.routeLocalProxyTraffic,
            defaultOutbound = settings.defaultOutbound,
            flowAnalysisApp = settings.flowAnalysisApp,
        )
    }
}

private val AndroidPackageNamePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
private const val MaxFlowAnalysisAppLength = 255
internal const val LanProxyPasswordEntropyBytes = 24

internal fun generateLanProxyPassword(secureRandom: SecureRandom = SecureRandom()): String {
    val entropy = ByteArray(LanProxyPasswordEntropyBytes)
    secureRandom.nextBytes(entropy)
    return buildString(LanProxyPasswordEntropyBytes / 3 * 4) {
        for (index in entropy.indices step 3) {
            val value = ((entropy[index].toInt() and 0xff) shl 16) or
                ((entropy[index + 1].toInt() and 0xff) shl 8) or
                (entropy[index + 2].toInt() and 0xff)
            append(Base64UrlAlphabet[(value ushr 18) and 0x3f])
            append(Base64UrlAlphabet[(value ushr 12) and 0x3f])
            append(Base64UrlAlphabet[(value ushr 6) and 0x3f])
            append(Base64UrlAlphabet[value and 0x3f])
        }
    }
}

private const val Base64UrlAlphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

internal fun secureRuntimeSettings(
    settings: RuntimeSettings,
    passwordGenerator: () -> String = ::generateLanProxyPassword,
): RuntimeSettings {
    if (!settings.socksListenAll || settings.socksPassword.isNotEmpty()) return settings
    return settings.copy(socksPassword = passwordGenerator().also { generated ->
        check(generated.isNotEmpty()) { "LAN proxy password generator returned an empty password" }
    })
}

internal fun requireSafeRuntimeSettings(settings: RuntimeSettings): RuntimeSettings = settings.also {
    require(!it.socksListenAll || it.socksPassword.isNotEmpty()) {
        "LAN proxy password is required when listening on all interfaces"
    }
}

internal fun requireSafeAppliedRuntimeSettings(settings: AppliedRuntimeSettings): AppliedRuntimeSettings =
    settings.also {
        require(!it.socksListenAll || it.socksPassword.isNotEmpty()) {
            "LAN proxy password is required when listening on all interfaces"
        }
    }

/** Preserves a restored non-secret draft while hydrating credentials from encrypted storage. */
internal fun hydrateRuntimeSettingsCredentials(
    restoredDraft: RuntimeSettings,
    persisted: RuntimeSettings,
): RuntimeSettings = restoredDraft.copy(
    socksUsername = persisted.socksUsername,
    socksPassword = persisted.socksPassword,
)

internal fun normalizeFlowAnalysisApp(value: String): String {
    if (value.length > MaxFlowAnalysisAppLength) return ""
    return value.trim().takeIf(AndroidPackageNamePattern::matches).orEmpty()
}

internal const val RuntimeSettingsUnavailableSafeDescription =
    "Settings could not be loaded securely. Retry before making changes."

internal enum class RuntimeSettingsSource { CleanInstall, Stored }

internal sealed interface RuntimeSettingsRevision {
    data object Absent : RuntimeSettingsRevision
    data class Stored(private val opaqueId: String) : RuntimeSettingsRevision
}

internal sealed interface RuntimeSettingsRead {
    data class Success(
        val settings: RuntimeSettings,
        val source: RuntimeSettingsSource,
        internal val revision: RuntimeSettingsRevision,
    ) : RuntimeSettingsRead

    class Unavailable(
        internal val failure: Throwable,
        val safeDescription: String = RuntimeSettingsUnavailableSafeDescription,
    ) : RuntimeSettingsRead
}

internal fun RuntimeSettingsRead.requireAuthoritativeSettings(): RuntimeSettings =
    (this as? RuntimeSettingsRead.Success)?.settings
        ?: throw IllegalStateException(RuntimeSettingsUnavailableSafeDescription)

internal fun RuntimeSettingsRead.uiFallbackSettings(): RuntimeSettings =
    (this as? RuntimeSettingsRead.Success)?.settings ?: RuntimeSettings()

internal fun RuntimeSettingsRead.allowsMutation(): Boolean = this is RuntimeSettingsRead.Success

internal object RuntimeSettingsStorageKeys {
    const val Prefs = "tcptun_runtime"
    const val Mtu = "runtimeMtu"
    const val PowerSaving = "runtimePowerSaving"
    const val LogLevel = "runtimeLogLevel"
    const val SocksPort = "runtimeSocksPort"
    const val LocalProxyProtocol = "runtimeLocalProxyProtocol"
    const val SocksListenAll = "runtimeSocksListenAll"
    const val SocksUsername = "runtimeSocksUsername"
    const val SocksPassword = "runtimeSocksPassword"
    const val RouteLocalProxyTraffic = "runtimeRouteLocalProxyTraffic"
    const val DefaultOutbound = "runtimeDefaultOutbound"
    const val FlowAnalysisApp = "runtimeFlowAnalysisApp"
    const val StorageVersion = "runtimeStorageVersion"
    const val SecretsId = "runtimeSecretsId"
    const val EncryptedSecretsVersion = 2

    val all = setOf(
        Mtu,
        PowerSaving,
        LogLevel,
        SocksPort,
        LocalProxyProtocol,
        SocksListenAll,
        SocksUsername,
        SocksPassword,
        RouteLocalProxyTraffic,
        DefaultOutbound,
        FlowAnalysisApp,
        StorageVersion,
        SecretsId,
    )
}

internal interface RuntimeSettingsPreferences {
    fun contains(key: String): Boolean
    fun getInt(key: String, defaultValue: Int): Int
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun getString(key: String, defaultValue: String?): String?
    fun publish(settings: RuntimeSettings, secretsId: String): Boolean
}

internal interface RuntimeSettingsCredentialCodec {
    fun encode(username: String, password: String): String
    fun decode(raw: String): Pair<String, String>
}

private object JsonRuntimeSettingsCredentialCodec : RuntimeSettingsCredentialCodec {
    override fun encode(username: String, password: String): String = JSONObject()
        .put("username", username)
        .put("password", password)
        .toString()

    override fun decode(raw: String): Pair<String, String> {
        val json = JSONObject(raw)
        val username = json.opt("username")
        val password = json.opt("password")
        require(username is String && password is String) {
            "encrypted runtime settings credentials are incomplete"
        }
        return username to password
    }
}

private class SharedPreferencesRuntimeSettingsPreferences(
    private val preferences: SharedPreferences,
) : RuntimeSettingsPreferences {
    override fun contains(key: String): Boolean = preferences.contains(key)
    override fun getInt(key: String, defaultValue: Int): Int = preferences.getInt(key, defaultValue)
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        preferences.getBoolean(key, defaultValue)
    override fun getString(key: String, defaultValue: String?): String? =
        preferences.getString(key, defaultValue)

    override fun publish(settings: RuntimeSettings, secretsId: String): Boolean = preferences.edit()
        .putInt(RuntimeSettingsStorageKeys.StorageVersion, RuntimeSettingsStorageKeys.EncryptedSecretsVersion)
        .putInt(RuntimeSettingsStorageKeys.Mtu, settings.mtu)
        .putBoolean(RuntimeSettingsStorageKeys.PowerSaving, settings.powerSavingMode)
        .putString(RuntimeSettingsStorageKeys.LogLevel, settings.logLevel)
        .putInt(RuntimeSettingsStorageKeys.SocksPort, settings.socksPort)
        .putString(RuntimeSettingsStorageKeys.LocalProxyProtocol, settings.localProxyProtocol)
        .putBoolean(RuntimeSettingsStorageKeys.SocksListenAll, settings.socksListenAll)
        .putString(RuntimeSettingsStorageKeys.SecretsId, secretsId)
        .remove(RuntimeSettingsStorageKeys.SocksUsername)
        .remove(RuntimeSettingsStorageKeys.SocksPassword)
        .putBoolean(RuntimeSettingsStorageKeys.RouteLocalProxyTraffic, settings.routeLocalProxyTraffic)
        .putString(RuntimeSettingsStorageKeys.DefaultOutbound, settings.defaultOutbound)
        .putString(RuntimeSettingsStorageKeys.FlowAnalysisApp, settings.flowAnalysisApp)
        .commit()
}

/** Testable authoritative reader/writer; Android Context creation stays in the facade below. */
internal class RuntimeSettingsRepositoryEngine(
    private val preferences: RuntimeSettingsPreferences,
    private val secretStore: SecretStorage,
    private val nextSecretId: () -> String = { "runtime.${UUID.randomUUID()}" },
    private val logUnavailable: () -> Unit = {},
    private val credentialCodec: RuntimeSettingsCredentialCodec = JsonRuntimeSettingsCredentialCodec,
) {
    fun read(): RuntimeSettingsRead = try {
        readUnsafe()
    } catch (error: Throwable) {
        if (error.isFatalProcessError()) throw error
        logUnavailable()
        RuntimeSettingsRead.Unavailable(error)
    }

    fun writeIfCurrent(expected: RuntimeSettingsRead, settings: RuntimeSettings): RuntimeSettingsRead.Success {
        val expectedSuccess = expected as? RuntimeSettingsRead.Success
            ?: throw IllegalStateException(RuntimeSettingsUnavailableSafeDescription)
        val current = read() as? RuntimeSettingsRead.Success
            ?: throw IllegalStateException(RuntimeSettingsUnavailableSafeDescription)
        check(current.revision == expectedSuccess.revision) {
            "runtime settings changed while being edited"
        }
        return write(settings)
    }

    fun write(settings: RuntimeSettings): RuntimeSettingsRead.Success {
        val normalized = normalizeForStorage(settings)
        val previousSecretsId = preferences.getString(RuntimeSettingsStorageKeys.SecretsId, null).orEmpty()
        val secretsId = nextSecretId()
        require(secretsId.isNotBlank()) { "runtime settings secret reference must not be blank" }
        val encodedSecrets = credentialCodec.encode(
            normalized.socksUsername,
            normalized.socksPassword,
        )
        val saved = replaceWithVerifiedSecret(
            secretStore = secretStore,
            newSecretId = secretsId,
            plaintext = encodedSecrets,
            commitPointer = { preferences.publish(normalized, secretsId) },
        )
        check(saved) { "runtime settings could not be persisted" }
        if (previousSecretsId.isNotBlank() && previousSecretsId != secretsId) {
            runRecoverableCatching { secretStore.remove(previousSecretsId) }
        }
        return RuntimeSettingsRead.Success(
            normalized,
            RuntimeSettingsSource.Stored,
            RuntimeSettingsRevision.Stored(secretsId),
        )
    }

    private fun readUnsafe(): RuntimeSettingsRead.Success {
        if (RuntimeSettingsStorageKeys.all.none(preferences::contains)) {
            return RuntimeSettingsRead.Success(
                RuntimeSettings(),
                RuntimeSettingsSource.CleanInstall,
                RuntimeSettingsRevision.Absent,
            )
        }
        val version = preferences.getInt(RuntimeSettingsStorageKeys.StorageVersion, 0)
        require(version in 0..RuntimeSettingsStorageKeys.EncryptedSecretsVersion) {
            "unsupported runtime settings storage version"
        }
        val encrypted = version == RuntimeSettingsStorageKeys.EncryptedSecretsVersion
        if (!encrypted) {
            require(!preferences.contains(RuntimeSettingsStorageKeys.SecretsId)) {
                "encrypted runtime settings reference exists without its storage version"
            }
        } else {
            val requiredEncryptedKeys = RuntimeSettingsStorageKeys.all -
                RuntimeSettingsStorageKeys.SocksUsername - RuntimeSettingsStorageKeys.SocksPassword
            require(requiredEncryptedKeys.all(preferences::contains)) {
                "encrypted runtime settings public record is incomplete"
            }
        }
        val secretsId = if (encrypted) {
            preferences.getString(RuntimeSettingsStorageKeys.SecretsId, null)
                ?.takeIf(String::isNotBlank)
                ?: throw IllegalStateException("encrypted runtime settings reference is missing")
        } else {
            null
        }
        val credentials = if (encrypted) {
            parseCredentials(
                secretStore.read(requireNotNull(secretsId))
                    ?: throw IllegalStateException("encrypted runtime settings are missing"),
            )
        } else {
            preferences.getString(RuntimeSettingsStorageKeys.SocksUsername, "").orEmpty() to
                preferences.getString(RuntimeSettingsStorageKeys.SocksPassword, "").orEmpty()
        }
        val stored = RuntimeSettings(
            mtu = preferences.getInt(RuntimeSettingsStorageKeys.Mtu, RuntimeSettingsDefaults.VpnMtu)
                .coerceIn(1280, 1500),
            powerSavingMode = preferences.getBoolean(RuntimeSettingsStorageKeys.PowerSaving, true),
            logLevel = normalizeLogLevel(
                preferences.getString(RuntimeSettingsStorageKeys.LogLevel, DefaultLogLevel).orEmpty(),
            ),
            socksPort = preferences.getInt(RuntimeSettingsStorageKeys.SocksPort, RuntimeSettingsDefaults.SocksPort)
                .coerceIn(1, 65535),
            localProxyProtocol = normalizeLocalProxyProtocol(
                preferences.getString(
                    RuntimeSettingsStorageKeys.LocalProxyProtocol,
                    DefaultLocalProxyProtocol,
                ).orEmpty(),
            ),
            socksListenAll = preferences.getBoolean(RuntimeSettingsStorageKeys.SocksListenAll, false),
            socksUsername = credentials.first.let(::validatedCredential),
            socksPassword = credentials.second.let(::validatedCredential),
            routeLocalProxyTraffic = preferences.getBoolean(
                RuntimeSettingsStorageKeys.RouteLocalProxyTraffic,
                false,
            ),
            defaultOutbound = normalizeDefaultOutboundSelection(
                preferences.getString(
                    RuntimeSettingsStorageKeys.DefaultOutbound,
                    DefaultOutboundDynamicPool,
                ).orEmpty(),
            ),
            flowAnalysisApp = normalizeFlowAnalysisApp(
                preferences.getString(RuntimeSettingsStorageKeys.FlowAnalysisApp, "").orEmpty(),
            ),
        )
        if (encrypted) {
            requireSafeRuntimeSettings(stored)
            return RuntimeSettingsRead.Success(
                stored,
                RuntimeSettingsSource.Stored,
                RuntimeSettingsRevision.Stored(requireNotNull(secretsId)),
            )
        }
        // Legacy data becomes authoritative only after encrypted migration and pointer commit.
        return write(secureRuntimeSettings(stored))
    }

    private fun parseCredentials(raw: String): Pair<String, String> {
        require(raw.length <= MaxRuntimeCredentialLength * 3) {
            "encrypted runtime settings are too large"
        }
        return credentialCodec.decode(raw)
    }

    private fun validatedCredential(value: String): String {
        require(value.length <= MaxRuntimeCredentialLength) { "runtime credential is too long" }
        require(hasValidSocksCredentialSize(value)) { "runtime credential exceeds UTF-8 limit" }
        return truncateSocksCredential(value)
    }

    private fun normalizeForStorage(settings: RuntimeSettings): RuntimeSettings {
        requireSafeRuntimeSettings(settings)
        require(settings.socksUsername.length <= MaxRuntimeCredentialLength) { "SOCKS username is too long" }
        require(settings.socksPassword.length <= MaxRuntimeCredentialLength) { "SOCKS password is too long" }
        require(hasValidSocksCredentialSize(settings.socksUsername)) {
            "SOCKS username exceeds $MaxSocksCredentialUtf8Bytes UTF-8 bytes"
        }
        require(hasValidSocksCredentialSize(settings.socksPassword)) {
            "SOCKS password exceeds $MaxSocksCredentialUtf8Bytes UTF-8 bytes"
        }
        return settings.copy(
            mtu = settings.mtu.coerceIn(1280, 1500),
            logLevel = normalizeLogLevel(settings.logLevel),
            socksPort = settings.socksPort.coerceIn(1, 65535),
            localProxyProtocol = normalizeLocalProxyProtocol(settings.localProxyProtocol),
            defaultOutbound = normalizeDefaultOutboundSelection(settings.defaultOutbound),
            flowAnalysisApp = normalizeFlowAnalysisApp(settings.flowAnalysisApp),
        )
    }

    private companion object {
        const val MaxRuntimeCredentialLength = 4_096
    }
}

/** Owns the durable runtime-settings schema independently from the Android service lifecycle. */
object RuntimeSettingsRepository {
    internal fun read(context: Context): RuntimeSettingsRead {
        val appContext = context.applicationContext ?: context
        return try {
            engine(appContext).read()
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            runRecoverableCatching { TcptunState.appendLog("runtime settings unavailable") }
            RuntimeSettingsRead.Unavailable(error)
        }
    }

    internal fun writeIfCurrent(
        context: Context,
        expected: RuntimeSettingsRead,
        settings: RuntimeSettings,
    ): RuntimeSettingsRead.Success {
        val appContext = context.applicationContext ?: context
        return engine(appContext).writeIfCurrent(expected, settings).also { publish(it.settings) }
    }

    internal fun write(context: Context, settings: RuntimeSettings): RuntimeSettingsRead.Success {
        val appContext = context.applicationContext ?: context
        return engine(appContext).write(settings).also { publish(it.settings) }
    }

    private fun engine(context: Context): RuntimeSettingsRepositoryEngine {
        val preferences = context.getSharedPreferences(RuntimeSettingsStorageKeys.Prefs, Context.MODE_PRIVATE)
        return RuntimeSettingsRepositoryEngine(
            SharedPreferencesRuntimeSettingsPreferences(preferences),
            EncryptedSecretStore(context),
            logUnavailable = {
                runRecoverableCatching { TcptunState.appendLog("runtime settings unavailable") }
            },
        )
    }

    fun localSocksListenAddress(settings: RuntimeSettings): String {
        val host = if (settings.socksListenAll) "0.0.0.0" else RuntimeSettingsDefaults.LocalSocksHost
        return "$host:${settings.socksPort.coerceIn(1, 65535)}"
    }

    fun localSocksConnectAddress(settings: RuntimeSettings): String =
        "${RuntimeSettingsDefaults.LocalSocksHost}:${settings.socksPort.coerceIn(1, 65535)}"

    fun defaultLocalSocksConnectAddress(): String =
        "${RuntimeSettingsDefaults.LocalSocksHost}:${RuntimeSettingsDefaults.SocksPort}"

    fun publishHotApplied(settings: RuntimeSettings) {
        publishDiagnostics(settings)
        TcptunState.appendLog(
            "runtime settings applied without VPN restart: " +
                "log-level=${settings.logLevel} power-saving=${settings.powerSavingMode}",
        )
    }

    private fun publish(settings: RuntimeSettings) {
        TcptunState.setFlowAnalysisApp(settings.flowAnalysisApp)
        publishDiagnostics(settings)
        TcptunState.appendLog(
            "runtime settings saved: proxy=${settings.localProxyProtocol}://" +
                "${localSocksListenAddress(settings)} mtu=${settings.mtu} " +
                "log-level=${settings.logLevel} power-saving=${settings.powerSavingMode} " +
                "route-local-proxy=${settings.routeLocalProxyTraffic} " +
                "default-outbound=${settings.defaultOutbound.ifBlank { "profile-pool" }} " +
                "flow-analysis=${settings.flowAnalysisApp.ifBlank { "disabled" }}",
        )
    }

    private fun publishDiagnostics(settings: RuntimeSettings) {
        TcptunState.updateDiagnostics {
            it.copy(
                mtu = settings.mtu,
                powerSavingMode = settings.powerSavingMode,
                localProxyAddress = localSocksConnectAddress(settings),
                localProxyPort = settings.socksPort,
            )
        }
    }
}

/** Legacy helper retained for non-RuntimeSettings stores that intentionally define a fallback. */
internal fun <T> SharedPreferences.readOrDefault(
    key: String,
    defaultValue: T,
    read: SharedPreferences.() -> T,
): T = try {
    read()
} catch (error: Throwable) {
    if (error.isFatalProcessError()) throw error
    runRecoverableCatching { TcptunState.appendLog("stored value $key is invalid; using fallback") }
    defaultValue
}
