package com.tcptun.client

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.UUID

/** Persists the user's desired VPN state independently from the Service lifecycle. */
internal object DesiredRunningPlanStore {
    const val MaxEncodedLength = 1_000_000

    internal const val RuntimePreferences = "tcptun_runtime"
    internal const val KeyLegacyConfig = "lastRunningConfig"
    internal const val KeyProfilePlan = "lastRunningPlan"
    internal const val KeyDesiredRunning = "desiredRunning"
    internal const val KeyConfigVersion = "runningConfigVersion"
    internal const val KeySecretsId = "runningPlanSecretsId"
    internal const val CurrentConfigVersion = 4

    fun encode(plan: ProfileRunPlan): String {
        val rawPlan = plan.normalized().toJson().toString()
        require(rawPlan.length <= MaxEncodedLength) { "running profile plan is too large" }
        return rawPlan
    }

    @Synchronized
    fun publish(context: Context, rawPlan: String) {
        require(rawPlan.length <= MaxEncodedLength) { "running profile plan is too large" }
        requireNotNull(decodePlan(rawPlan)) { "running profile plan is invalid" }
        val appContext = context.applicationContext ?: context
        val preferences = appContext.getSharedPreferences(RuntimePreferences, Context.MODE_PRIVATE)
        publishEncrypted(appContext, preferences, rawPlan)
    }

    @Synchronized
    fun read(context: Context): ProfileRunPlan? {
        return try {
            val appContext = context.applicationContext ?: context
            val preferences = appContext.getSharedPreferences(RuntimePreferences, Context.MODE_PRIVATE)
            val desired = preferences.readOrDefault(KeyDesiredRunning, false) {
                getBoolean(KeyDesiredRunning, false)
            }
            if (!desired) return null
            when (val version = preferences.readOrDefault(KeyConfigVersion, 1) {
                getInt(KeyConfigVersion, 1)
            }) {
                CurrentConfigVersion -> readEncrypted(appContext, preferences)
                2, 3 -> readLegacyPlan(preferences)?.also { plan ->
                    migrateLegacy(appContext, preferences, encode(plan), version)
                }
                1 -> readLegacyConfig(preferences)?.also { plan ->
                    migrateLegacy(appContext, preferences, encode(plan), version)
                }
                else -> null
            }
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            TcptunState.appendLog("saved VPN plan is unavailable: ${failureDescription(error)}")
            null
        }
    }

    @Synchronized
    fun clear(context: Context) {
        val appContext = context.applicationContext ?: context
        val preferences = appContext.getSharedPreferences(RuntimePreferences, Context.MODE_PRIVATE)
        val previousSecretsId = preferences.getString(KeySecretsId, null).orEmpty()
        val saved = preferences.edit()
            .putBoolean(KeyDesiredRunning, false)
            .putInt(KeyConfigVersion, CurrentConfigVersion)
            .remove(KeySecretsId)
            .remove(KeyProfilePlan)
            .remove(KeyLegacyConfig)
            .commit()
        check(saved) { "desired VPN state could not be persisted" }
        if (previousSecretsId.isNotBlank()) {
            runRecoverableCatching { EncryptedSecretStore(appContext).remove(previousSecretsId) }
        }
    }

    private fun publishEncrypted(
        context: Context,
        preferences: SharedPreferences,
        rawPlan: String,
    ) {
        val previousSecretsId = preferences.getString(KeySecretsId, null).orEmpty()
        val secretsId = "running-plan.${UUID.randomUUID()}"
        val secretStore = EncryptedSecretStore(context)
        val committed = replaceWithVerifiedSecret(
            secretStore = secretStore,
            newSecretId = secretsId,
            plaintext = rawPlan,
            commitPointer = {
                preferences.edit()
                    .putInt(KeyConfigVersion, CurrentConfigVersion)
                    .putBoolean(KeyDesiredRunning, true)
                    .putString(KeySecretsId, secretsId)
                    .remove(KeyProfilePlan)
                    .remove(KeyLegacyConfig)
                    .commit()
            },
        )
        check(committed) { "running profile plan could not be persisted" }
        if (previousSecretsId.isNotBlank() && previousSecretsId != secretsId) {
            runRecoverableCatching { secretStore.remove(previousSecretsId) }
        }
    }

    private fun readEncrypted(context: Context, preferences: SharedPreferences): ProfileRunPlan? {
        val secretsId = preferences.getString(KeySecretsId, null)
            ?: throw IllegalStateException("encrypted running profile plan reference is missing")
        val rawPlan = EncryptedSecretStore(context).read(secretsId)
            ?: throw IllegalStateException("encrypted running profile plan is missing")
        require(rawPlan.length <= MaxEncodedLength) { "encrypted running profile plan is too large" }
        return decodePlan(rawPlan)
            ?: throw IllegalStateException("encrypted running profile plan is invalid")
    }

    private fun readLegacyPlan(preferences: SharedPreferences): ProfileRunPlan? =
        preferences.readOrDefault<String?>(KeyProfilePlan, null) { getString(KeyProfilePlan, null) }
            ?.takeIf { it.length <= MaxEncodedLength }
            ?.let(::decodePlan)

    private fun readLegacyConfig(preferences: SharedPreferences): ProfileRunPlan? =
        preferences.readOrDefault<String?>(KeyLegacyConfig, null) { getString(KeyLegacyConfig, null) }
            ?.takeIf { it.length <= MaxEncodedLength }
            ?.let(::decodeLegacyConfig)

    private fun migrateLegacy(
        context: Context,
        preferences: SharedPreferences,
        rawPlan: String,
        version: Int,
    ) {
        runRecoverableCatching { publishEncrypted(context, preferences, rawPlan) }
            .onFailure { error ->
                TcptunState.appendLog(
                    "saved VPN plan v$version migration deferred: ${failureDescription(error)}",
                )
            }
    }

    private fun decodePlan(raw: String): ProfileRunPlan? = runRecoverableCatching {
        requireSafeJsonNesting(raw)
        ProfileRunPlan.fromJson(JSONObject(raw))
    }.getOrNull()

    private fun decodeLegacyConfig(raw: String): ProfileRunPlan? = runRecoverableCatching {
        requireSafeJsonNesting(raw)
        ProfileRunPlan(listOf(AppConfig.fromJson(JSONObject(raw)))).normalized()
    }.getOrNull()
}
