package com.tcptun.client

import android.content.Context
import org.json.JSONObject

/** Persists the user's desired VPN state independently from the Service lifecycle. */
internal object DesiredRunningPlanStore {
    const val MaxEncodedLength = 1_000_000

    private const val RuntimePreferences = "tcptun_runtime"
    private const val KeyLegacyConfig = "lastRunningConfig"
    private const val KeyProfilePlan = "lastRunningPlan"
    private const val KeyDesiredRunning = "desiredRunning"
    private const val KeyConfigVersion = "runningConfigVersion"
    private const val CurrentConfigVersion = 3

    fun encode(plan: ProfileRunPlan): String {
        val rawPlan = plan.normalized().toJson().toString()
        require(rawPlan.length <= MaxEncodedLength) { "running profile plan is too large" }
        return rawPlan
    }

    fun publish(context: Context, rawPlan: String) {
        require(rawPlan.length <= MaxEncodedLength) { "running profile plan is too large" }
        val appContext = context.applicationContext ?: context
        val saved = appContext.getSharedPreferences(RuntimePreferences, Context.MODE_PRIVATE)
            .edit()
            .putString(KeyProfilePlan, rawPlan)
            .putInt(KeyConfigVersion, CurrentConfigVersion)
            .putBoolean(KeyDesiredRunning, true)
            .commit()
        check(saved) { "running profile plan could not be persisted" }
    }

    fun read(context: Context): ProfileRunPlan? {
        return try {
            val appContext = context.applicationContext ?: context
            val preferences = appContext.getSharedPreferences(RuntimePreferences, Context.MODE_PRIVATE)
            val desired = preferences.readOrDefault(KeyDesiredRunning, false) {
                getBoolean(KeyDesiredRunning, false)
            }
            if (!desired) return null
            when (preferences.readOrDefault(KeyConfigVersion, 1) { getInt(KeyConfigVersion, 1) }) {
                CurrentConfigVersion, 2 -> preferences.readOrDefault<String?>(KeyProfilePlan, null) {
                    getString(KeyProfilePlan, null)
                }?.takeIf { it.length <= MaxEncodedLength }?.let(::decodePlan)

                1 -> preferences.readOrDefault<String?>(KeyLegacyConfig, null) {
                    getString(KeyLegacyConfig, null)
                }?.takeIf { it.length <= MaxEncodedLength }?.let(::decodeLegacyConfig)

                else -> null
            }
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            TcptunState.appendLog("saved VPN plan is unavailable: ${failureDescription(error)}")
            null
        }
    }

    fun clear(context: Context) {
        val appContext = context.applicationContext ?: context
        val saved = appContext.getSharedPreferences(RuntimePreferences, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KeyDesiredRunning, false)
            .commit()
        check(saved) { "desired VPN state could not be persisted" }
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
