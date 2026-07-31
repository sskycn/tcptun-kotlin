package com.tcptun.client

import org.json.JSONObject

/**
 * Saved-state strings are written into Activity state and therefore share the Binder transaction
 * budget with framework state. A run plan can be larger than a single profile, so keep separate
 * conservative limits instead of accepting the much larger on-disk profile limits.
 */
internal const val MaxPendingRunPlanJsonLength = 256 * 1024
internal const val MaxPendingProfileJsonLength = 128 * 1024

internal fun <T> encodeBoundedSavedState(
    value: T?,
    maxLength: Int,
    encode: (T) -> String,
): String? {
    if (value == null || maxLength <= 0) return null
    return runRecoverableCatching { encode(value) }
        .getOrNull()
        ?.takeIf { it.isNotBlank() && it.length <= maxLength }
}

internal fun <T> decodeBoundedSavedState(
    encoded: String?,
    maxLength: Int,
    decode: (String) -> T,
): T? {
    if (encoded.isNullOrBlank() || maxLength <= 0 || encoded.length > maxLength) return null
    return runRecoverableCatching { decode(encoded) }.getOrNull()
}

internal fun encodePendingRunPlan(plan: ProfileRunPlan?): String? = encodeBoundedSavedState(
    value = plan,
    maxLength = MaxPendingRunPlanJsonLength,
) { value -> value.normalized().toJson().toString() }

internal fun decodePendingRunPlan(encoded: String?): ProfileRunPlan? = decodeBoundedSavedState(
    encoded = encoded,
    maxLength = MaxPendingRunPlanJsonLength,
) { value ->
    requireSafeJsonNesting(value)
    ProfileRunPlan.fromJson(JSONObject(value))
}

internal fun encodePendingProfile(profile: AppConfig?): String? = encodeBoundedSavedState(
    value = profile,
    maxLength = MaxPendingProfileJsonLength,
) { value ->
    require(value.hasSafeStorageSize()) { "pending profile data is too large" }
    value.toJson().toString()
}

internal fun decodePendingProfile(encoded: String?): AppConfig? = decodeBoundedSavedState(
    encoded = encoded,
    maxLength = MaxPendingProfileJsonLength,
) { value ->
    requireSafeJsonNesting(value)
    AppConfig.fromJson(JSONObject(value)).also { profile ->
        require(profile.id.isNotBlank() && profile.id.length <= MaxProfileIdLength) {
            "invalid pending profile ID"
        }
        require(profile.hasSafeStorageSize()) { "pending profile data is too large" }
        profile.validate()?.let { error -> throw IllegalArgumentException(error) }
    }
}
