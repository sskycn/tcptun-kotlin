package com.tcptun.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class ProfilesState(
    val profiles: List<AppConfig>,
    val activeIds: Set<String> = emptySet(),
) {
    val activeProfiles: List<AppConfig>
        get() = profiles.filter { it.id in activeIds }

    fun runPlan(): ProfileRunPlan = ProfileRunPlan(profiles, activeIds).normalized()
}

internal data class ProfileStoreSnapshot(
    val state: ProfilesState,
    val mutationRevision: Long,
    val readFailure: Throwable? = null,
) {
    val isAuthoritative: Boolean
        get() = readFailure == null

    internal fun requireAuthoritativeState(): ProfilesState {
        val failure = readFailure ?: return state
        throw IllegalStateException("profile storage is unavailable; retry the operation", failure)
    }
}

internal data class RecoveringProfileStoreRead(
    val state: ProfilesState,
    val readFailure: Throwable? = null,
) {
    val isAuthoritative: Boolean
        get() = readFailure == null

    internal fun requireAuthoritativeState(): ProfilesState {
        val failure = readFailure ?: return state
        throw IllegalStateException("profile storage is unavailable; retry the operation", failure)
    }
}

internal fun recoverProfileStoreRead(
    fallback: ProfilesState,
    read: () -> ProfilesState,
): RecoveringProfileStoreRead = try {
    RecoveringProfileStoreRead(read())
} catch (error: Throwable) {
    if (error.isFatalProcessError()) throw error
    RecoveringProfileStoreRead(fallback, error)
}

internal fun profileStoreCasMatches(
    expected: ProfileStoreSnapshot,
    currentMutationRevision: Long,
    current: RecoveringProfileStoreRead,
): Boolean {
    val expectedState = expected.requireAuthoritativeState()
    if (currentMutationRevision != expected.mutationRevision) return false
    return current.requireAuthoritativeState() == expectedState
}

object ProfileStore {
    private data class EncodedState(
        val profiles: String,
        val activeIds: String,
        val secrets: String,
    )

    private const val PREFS = "tcptun"
    private const val KEY_STATE_VERSION = "profileStateVersion"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_SELECTED = "selectedProfileId"
    private const val KEY_ENABLED = "enabledProfileIds"
    private const val KEY_ACTIVE = "activeProfileIds"
    private const val KEY_SECRETS_ID = "profileSecretsId"
    private const val STATE_VERSION_INDEPENDENT_OUTBOUNDS = 2
    private const val STATE_VERSION_ENCRYPTED_SECRETS = 3
    private val mutationRevision = AtomicLong()

    internal fun currentMutationRevision(): Long = mutationRevision.get()

    @Synchronized
    internal fun runIfRevisionCurrent(
        expectedMutationRevision: Long,
        commitLock: Any,
        canCommit: () -> Boolean,
        action: () -> Unit,
    ): Boolean = synchronized(commitLock) {
        if (mutationRevision.get() != expectedMutationRevision || !canCommit()) {
            false
        } else {
            action()
            true
        }
    }

    @Synchronized
    internal fun snapshot(context: Context): ProfileStoreSnapshot {
        val read = readRecoveringInternal(context.applicationContext ?: context)
        return ProfileStoreSnapshot(read.state, mutationRevision.get(), read.readFailure)
    }

    @Synchronized
    fun load(context: Context): ProfilesState = loadRecoveringInternal(context.applicationContext ?: context)

    private fun loadRecoveringInternal(context: Context): ProfilesState = readRecoveringInternal(context).state

    private fun loadAuthoritativeInternal(context: Context): ProfilesState =
        readRecoveringInternal(context).requireAuthoritativeState()

    private fun readRecoveringInternal(context: Context): RecoveringProfileStoreRead {
        val read = recoverProfileStoreRead(ProfilesState(emptyList())) { loadInternal(context) }
        read.readFailure?.let { error ->
            // Read-only callers may render the fallback, but mutation paths must
            // call requireAuthoritativeState() and fail closed.
            runRecoverableCatching {
                TcptunState.appendLog("profile storage unavailable: ${failureDescription(error)}")
            }
        }
        return read
    }

    private fun loadInternal(context: Context): ProfilesState {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PROFILES, null)
        if (raw != null) {
            require(raw.isNotBlank()) { "stored profile data is empty" }
            require(raw.length <= MaxStoredProfilesLength) { "stored profile data is too large" }
            requireSafeJsonNesting(raw)
            val arr = JSONArray(raw)
            require(arr.length() <= MaxStoredProfileCount) { "too many stored profiles" }
            val stateVersion = prefs.getInt(KEY_STATE_VERSION, 0)
            val secretsById = if (stateVersion >= STATE_VERSION_ENCRYPTED_SECRETS) {
                val secretsId = prefs.getString(KEY_SECRETS_ID, null)
                    ?: throw IllegalStateException("encrypted profile data reference is missing")
                val encrypted = EncryptedSecretStore(context).read(secretsId)
                    ?: throw IllegalStateException("encrypted profile data is missing")
                require(encrypted.length <= MaxStoredProfilesLength) { "encrypted profile data is too large" }
                requireSafeJsonNesting(encrypted)
                val secrets = JSONArray(encrypted)
                require(secrets.length() <= MaxStoredProfileCount) { "too many encrypted profiles" }
                require(secrets.length() == arr.length()) { "encrypted profile count does not match public data" }
                buildMap {
                    for (index in 0 until secrets.length()) {
                        val value = secrets.optJSONObject(index)
                            ?: throw IllegalStateException("encrypted profile entry is malformed")
                        val id = value.optString("id").takeIf(String::isNotBlank)
                            ?: throw IllegalStateException("encrypted profile ID is missing")
                        require(put(id, value) == null) { "encrypted profile IDs are duplicated" }
                    }
                }
            } else {
                emptyMap()
            }
            var repaired = false
            val seenIds = mutableSetOf<String>()
            val profiles = buildList {
                for (i in 0 until arr.length()) {
                    val json = arr.optJSONObject(i)
                    if (json == null) {
                        repaired = true
                        continue
                    }
                    val secrets = if (stateVersion >= STATE_VERSION_ENCRYPTED_SECRETS) {
                        secretsById[json.optString("id")]
                            ?: throw IllegalStateException("encrypted profile entry is missing")
                    } else {
                        null
                    }
                    if (isUnsupportedLegacyAndroidProfile(json, secrets)) {
                        repaired = true
                        continue
                    }
                    val decoded = runRecoverableCatching {
                        AppConfig.fromJson(json).withStorageSecrets(secrets)
                    }.getOrNull()
                    if (decoded == null || !decoded.hasSafeStorageSize() || decoded.serverHost.isBlank()) {
                        repaired = true
                        continue
                    }
                    val storedId = decoded.id.trim()
                    val normalizedId = storedId
                        .takeIf { it.isNotBlank() && it.length <= MaxProfileIdLength && seenIds.add(it) }
                        ?: generateUniqueProfileId(seenIds).also { repaired = true }
                    if (normalizedId != decoded.id) repaired = true
                    add(decoded.copy(id = normalizedId))
                }
            }
            val storedActive = if (stateVersion >= STATE_VERSION_INDEPENDENT_OUTBOUNDS) {
                val encoded = prefs.getString(KEY_ACTIVE, null)
                    ?: throw IllegalStateException("active profile data is missing")
                require(encoded.length <= MaxStoredProfilesLength) { "active profile data is too large" }
                requireSafeJsonNesting(encoded)
                val active = JSONArray(encoded)
                require(active.length() <= MaxStoredProfileCount) { "too many active profiles" }
                buildSet {
                    for (index in 0 until active.length()) {
                        val value = active.opt(index)
                        if (value !is String) {
                            repaired = true
                            continue
                        }
                        val normalized = value.trim()
                        if (
                            normalized.isBlank() ||
                            normalized.length > MaxProfileIdLength ||
                            !add(normalized)
                        ) {
                            repaired = true
                        }
                    }
                }
            } else {
                emptySet()
            }
            val knownIds = profiles.mapTo(mutableSetOf(), AppConfig::id)
            val activeIds = storedActive.orEmpty().filterTo(linkedSetOf()) { it in knownIds }
            if (activeIds.size != storedActive.size) repaired = true
            val state = ProfilesState(profiles, activeIds)
            if (
                repaired ||
                profiles.size != arr.length() ||
                stateVersion < STATE_VERSION_ENCRYPTED_SECRETS ||
                !prefs.contains(KEY_ACTIVE)
            ) {
                save(context, state).getOrThrow()
            }
            return state
        }
        val migrated = migrateSingleProfile(context)
        save(context, migrated).getOrThrow()
        return migrated
    }

    @Synchronized
    fun save(context: Context, state: ProfilesState): Result<Unit> = runRecoverableCatching {
        writeState(context.applicationContext ?: context, state)
    }

    private fun encodeState(state: ProfilesState): EncodedState {
        require(state.profiles.size <= MaxStoredProfileCount) { "too many profiles" }
        require(state.profiles.all(AppConfig::hasSafeStorageSize)) { "profile data is too large" }
        val seenIds = mutableSetOf<String>()
        val normalizedProfiles = state.profiles.map { profile ->
            val storedId = profile.id.trim()
            val normalizedId = storedId
                .takeIf { it.isNotBlank() && it.length <= MaxProfileIdLength && seenIds.add(it) }
                ?: generateUniqueProfileId(seenIds)
            if (profile.id == normalizedId) profile else profile.copy(id = normalizedId)
        }
        val knownIds = normalizedProfiles.mapTo(mutableSetOf(), AppConfig::id)
        val normalizedActiveIds = state.activeIds.filterTo(linkedSetOf()) { it in knownIds }
        val arr = JSONArray()
        val secrets = JSONArray()
        normalizedProfiles.forEach {
            arr.put(it.toPublicStorageJson())
            secrets.put(it.toSecretStorageJson())
        }
        val active = JSONArray()
        normalizedProfiles.filter { it.id in normalizedActiveIds }.forEach { active.put(it.id) }
        val encodedProfiles = arr.toString()
        require(encodedProfiles.length <= MaxStoredProfilesLength) { "stored profile data is too large" }
        val encodedSecrets = secrets.toString()
        require(encodedSecrets.length <= MaxStoredProfilesLength) { "encrypted profile data is too large" }
        return EncodedState(encodedProfiles, active.toString(), encodedSecrets)
    }

    private fun writeState(context: Context, state: ProfilesState) {
        writeEncodedState(context, encodeState(state))
    }

    private fun writeEncodedState(context: Context, encoded: EncodedState) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previousSecretsId = prefs.getString(KEY_SECRETS_ID, null).orEmpty()
        val secretsId = "profiles.${UUID.randomUUID()}"
        val secretStore = EncryptedSecretStore(context)
        // The public pointer changes only after ciphertext has been written and read back.
        val committed = replaceWithVerifiedSecret(
            secretStore = secretStore,
            newSecretId = secretsId,
            plaintext = encoded.secrets,
            commitPointer = {
                prefs.edit()
                    .putInt(KEY_STATE_VERSION, STATE_VERSION_ENCRYPTED_SECRETS)
                    .putString(KEY_PROFILES, encoded.profiles)
                    .putString(KEY_ACTIVE, encoded.activeIds)
                    .putString(KEY_SECRETS_ID, secretsId)
                    .remove(KEY_SELECTED)
                    .remove(KEY_ENABLED)
                    .remove("serverHost")
                    .remove("serverPort")
                    .remove("protocol")
                    .remove("transport")
                    .remove("token")
                    .remove("sni")
                    .remove("path")
                    .remove("tls")
                    .remove("mux")
                    .remove("rawConfig" + "Json")
                    .remove("echEnabled")
                    .remove("echPublicName")
                    .remove("echPublicKey")
                    .remove("echPorts")
                    .commit()
            },
        )
        check(committed) { "failed to persist profile state" }
        if (previousSecretsId.isNotBlank() && previousSecretsId != secretsId) {
            runRecoverableCatching { secretStore.remove(previousSecretsId) }
        }
        mutationRevision.incrementAndGet()
    }

    @Synchronized
    fun clearActive(context: Context): Result<Unit> = runRecoverableCatching {
        val appContext = context.applicationContext ?: context
        val state = loadAuthoritativeInternal(appContext)
        if (state.activeIds.isNotEmpty()) {
            writeState(appContext, state.copy(activeIds = emptySet()))
        }
    }

    @Synchronized
    internal fun replaceActiveIdsIfCurrent(
        context: Context,
        expectedMutationRevision: Long?,
        expectedActiveIds: Set<String>,
        replacementActiveIds: Set<String>,
        commitLock: Any? = null,
        canCommit: () -> Boolean = { true },
    ): Result<Boolean> = runRecoverableCatching {
        if (expectedMutationRevision != null && mutationRevision.get() != expectedMutationRevision) {
            return@runRecoverableCatching false
        }
        val appContext = context.applicationContext ?: context
        val current = loadAuthoritativeInternal(appContext)
        if (expectedMutationRevision != null && mutationRevision.get() != expectedMutationRevision) {
            return@runRecoverableCatching false
        }
        if (current.activeIds != expectedActiveIds) {
            return@runRecoverableCatching false
        }
        val encoded = encodeState(current.copy(activeIds = replacementActiveIds))
        guardedWrite(
            context = appContext,
            encoded = encoded,
            expectedMutationRevision = expectedMutationRevision,
            commitLock = commitLock,
            canCommit = canCommit,
        )
    }

    @Synchronized
    internal fun clearActiveIfCurrent(
        context: Context,
        expectedMutationRevision: Long,
        commitLock: Any,
        canCommit: () -> Boolean,
    ): Result<Boolean> = runRecoverableCatching {
        if (mutationRevision.get() != expectedMutationRevision) return@runRecoverableCatching false
        val appContext = context.applicationContext ?: context
        val current = loadAuthoritativeInternal(appContext)
        if (mutationRevision.get() != expectedMutationRevision) return@runRecoverableCatching false
        if (current.activeIds.isEmpty()) return@runRecoverableCatching true
        val encoded = encodeState(current.copy(activeIds = emptySet()))
        guardedWrite(
            context = appContext,
            encoded = encoded,
            expectedMutationRevision = expectedMutationRevision,
            commitLock = commitLock,
            canCommit = canCommit,
        )
    }

    @Synchronized
    internal fun alignActiveIdsWithPlanIfCurrent(
        context: Context,
        expectedMutationRevision: Long?,
        plan: ProfileRunPlan,
        commitLock: Any,
        canCommit: () -> Boolean,
    ): Result<Boolean> = runRecoverableCatching {
        if (expectedMutationRevision != null && mutationRevision.get() != expectedMutationRevision) {
            return@runRecoverableCatching false
        }
        val appContext = context.applicationContext ?: context
        val current = loadAuthoritativeInternal(appContext)
        if (expectedMutationRevision != null && mutationRevision.get() != expectedMutationRevision) {
            return@runRecoverableCatching false
        }
        val currentById = current.profiles.associateBy(AppConfig::id)
        if (
            plan.activeIds.any { it !in currentById } ||
            plan.profiles.any { profile -> currentById[profile.id] != profile }
        ) {
            return@runRecoverableCatching false
        }
        if (current.activeIds == plan.activeIds) return@runRecoverableCatching true
        val encoded = encodeState(current.copy(activeIds = plan.activeIds))
        guardedWrite(
            context = appContext,
            encoded = encoded,
            expectedMutationRevision = expectedMutationRevision,
            commitLock = commitLock,
            canCommit = canCommit,
        )
    }

    private fun guardedWrite(
        context: Context,
        encoded: EncodedState,
        expectedMutationRevision: Long?,
        commitLock: Any?,
        canCommit: () -> Boolean,
    ): Boolean {
        val writeIfCurrent = {
            if (
                (expectedMutationRevision != null && mutationRevision.get() != expectedMutationRevision) ||
                !canCommit()
            ) {
                false
            } else {
                writeEncodedState(context, encoded)
                true
            }
        }
        return if (commitLock == null) writeIfCurrent() else synchronized(commitLock) { writeIfCurrent() }
    }

    @Synchronized
    internal fun saveIfCurrent(
        context: Context,
        expected: ProfileStoreSnapshot,
        next: ProfilesState,
    ): Result<ProfileStoreSnapshot?> = runRecoverableCatching {
        expected.requireAuthoritativeState()
        if (mutationRevision.get() != expected.mutationRevision) return@runRecoverableCatching null
        val appContext = context.applicationContext ?: context
        val current = readRecoveringInternal(appContext)
        if (!profileStoreCasMatches(expected, mutationRevision.get(), current)) {
            return@runRecoverableCatching null
        }
        writeState(appContext, next)
        ProfileStoreSnapshot(
            state = loadAuthoritativeInternal(appContext),
            mutationRevision = mutationRevision.get(),
        )
    }

    private fun migrateSingleProfile(context: Context): ProfilesState {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val oldHost = prefs.getString("serverHost", "") ?: ""
        if (oldHost.isBlank()) {
            return ProfilesState(emptyList())
        }
        val legacyEch = prefs.getBoolean("echEnabled", false) ||
            listOf("echPublicName", "echPublicKey", "echPorts").any { key ->
                prefs.getString(key, "").orEmpty().isNotBlank()
            }
        if (legacyEch) return ProfilesState(emptyList())
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
        )
        return if (profile.providesVpnTunnelConfidentiality()) {
            ProfilesState(listOf(profile))
        } else {
            ProfilesState(emptyList())
        }
    }

    private fun generateUniqueProfileId(seenIds: MutableSet<String>): String {
        var id: String
        do {
            id = UUID.randomUUID().toString()
        } while (!seenIds.add(id))
        return id
    }

    private fun isUnsupportedLegacyAndroidProfile(public: JSONObject, secrets: JSONObject?): Boolean {
        val legacyRawField = "rawConfig" + "Json"
        if (public.optString(legacyRawField).isNotBlank() || secrets?.optString(legacyRawField).orEmpty().isNotBlank()) {
            return true
        }
        val legacySecurity = public.optString("tunnelSecurity").trim().lowercase()
        val lacksConfidentiality = !public.optBoolean("tls", false) &&
            legacySecurity !in setOf("reality", "reality-tcp", "reality-quic")
        return lacksConfidentiality || public.optBoolean("echEnabled", false) ||
            listOf("echPublicName", "echPublicKey", "echPorts").any { key ->
                public.optString(key).isNotBlank() || secrets?.optString(key).orEmpty().isNotBlank()
            }
    }
}
