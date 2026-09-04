package com.tcptun.client

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileSecretSerializationAndroidTest {
    @Test
    fun runtimeAccountListRoundTripsAndVersionTwoSecretMigratesOnWrite() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(RuntimeSettingsStorageKeys.Prefs, 0)
        val secretStore = EncryptedSecretStore(context)
        val legacySecretId = "runtime.android-test-v2"
        val original = RuntimeSettingsRepository.read(context).requireAuthoritativeSettings()
        try {
            preferences.edit().clear()
                .putInt(RuntimeSettingsStorageKeys.StorageVersion, RuntimeSettingsStorageKeys.LegacyEncryptedSecretsVersion)
                .putString(RuntimeSettingsStorageKeys.SecretsId, legacySecretId)
                .commit()
            secretStore.writeVerified(
                legacySecretId,
                "{\"username\":\"legacy-user\",\"password\":\"legacy-secret\"}",
            )
            val migrated = RuntimeSettingsRepository.read(context).requireAuthoritativeSettings()
            assertEquals(listOf(LocalProxyUser("legacy-user", "legacy-secret")), migrated.localProxyUsers)

            val users = listOf(LocalProxyUser("alice", "secret-a"), LocalProxyUser("bob", "secret-b"))
            RuntimeSettingsRepository.write(context, migrated.copy(localProxyUsers = users))
            val roundTripped = RuntimeSettingsRepository.read(context).requireAuthoritativeSettings()

            assertEquals(users, roundTripped.localProxyUsers)
            assertEquals(
                RuntimeSettingsStorageKeys.EncryptedSecretsVersion,
                preferences.getInt(RuntimeSettingsStorageKeys.StorageVersion, 0),
            )
            assertFalse(preferences.all.values.any { value -> users.any { value.toString().contains(it.password) } })
            assertFalse(secretStore.read(legacySecretId)?.contains("legacy-secret") == true)
        } finally {
            runRecoverableCatching { secretStore.remove(legacySecretId) }
            preferences.edit().clear().commit()
            RuntimeSettingsRepository.write(context, original)
        }
    }

    @Test
    fun credentialsNeverAppearInPublicProfileSettings() {
        val profile = AppConfig(
            id = "profile-id",
            serverHost = "example.com",
            token = "credential-token",
            realityPublicKey = "reality-key",
            realityShortId = "reality-short-id",
            realitySpiderX = "/private-spider",
        )

        val public = profile.toPublicStorageJson().toString()
        val restored = AppConfig.fromJson(profile.toPublicStorageJson())
            .withStorageSecrets(profile.toSecretStorageJson())

        listOf(
            "credential-token",
            "reality-key",
            "reality-short-id",
            "/private-spider",
        ).forEach { secret -> assertFalse(public.contains(secret)) }
        assertEquals(profile, restored)
    }

    @Test
    fun plaintextLegacyProfilesMigrateOnlyAfterEncryptedRoundTrip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("tcptun", 0)
        val original = ProfileStore.load(context)
        val legacy = AppConfig(
            id = "legacy-encryption-test",
            serverHost = "legacy.example.com",
            token = "legacy-profile-token",
        )
        try {
            preferences.edit().clear()
                .putInt("profileStateVersion", 2)
                .putString("profiles", JSONArray().put(legacy.toJson()).toString())
                .putString("activeProfileIds", "[]")
                .putString("token", "obsolete-legacy-plaintext-token")
                .commit()

            val migrated = ProfileStore.load(context)
            val publicProfiles = preferences.getString("profiles", "").orEmpty()
            val encryptedValues = context.getSharedPreferences("tcptun_encrypted_secrets", 0)
                .all.values.filterIsInstance<String>()

            assertEquals("legacy-profile-token", migrated.profiles.single().token)
            assertFalse(publicProfiles.contains("legacy-profile-token"))
            assertFalse(preferences.contains("token"))
            assertFalse(encryptedValues.any { it.contains("legacy-profile-token") })
            assertEquals(3, preferences.getInt("profileStateVersion", 0))
        } finally {
            preferences.edit().clear().commit()
            ProfileStore.save(context, original).getOrThrow()
        }
    }

    @Test
    fun legacyConfidentialityMigrationDropsRawAndEchWithoutChangingStructuredProfiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("tcptun", 0)
        val original = ProfileStore.load(context)
        val tls = AppConfig(
            id = "legacy-tls",
            name = "TLS",
            serverHost = "tls.example.com",
            token = "tls-token",
        )
        val reality = AppConfig(
            id = "legacy-reality",
            name = "REALITY",
            serverHost = "reality.example.com",
            token = "reality-token",
            tls = false,
            tunnelSecurity = "reality",
            sni = "reality.example.com",
            realityPublicKey = "BKZcJpZLNtpVnJcQ7kj6_y2IySMqgYlyjKq-M2OW_yY",
            realityShortId = "a65f93c1",
        )
        val none = tls.copy(id = "legacy-none", name = "None", tls = false)
        val raw = tls.copy(id = "legacy-raw", name = "Raw JSON").toJson()
            .put("rawConfig" + "Json", "{\"outbounds\":[]}")
        val ech = tls.copy(id = "legacy-ech", name = "ECH").toJson()
            .put("echEnabled", true)
            .put("echPublicName", "public.example.com")
        val profiles = JSONArray()
            .put(tls.toJson())
            .put(reality.toJson())
            .put(none.toJson())
            .put(raw)
            .put(ech)
        val active = JSONArray(listOf(tls.id, reality.id, none.id, "legacy-raw", "legacy-ech"))

        try {
            preferences.edit().clear()
                .putInt("profileStateVersion", 2)
                .putString("profiles", profiles.toString())
                .putString("activeProfileIds", active.toString())
                .commit()

            val migrated = ProfileStore.load(context)
            val byId = migrated.profiles.associateBy(AppConfig::id)

            assertEquals(setOf(tls.id, reality.id), byId.keys)
            assertEquals(tls, byId.getValue(tls.id))
            assertEquals(reality, byId.getValue(reality.id))
            assertEquals(setOf(tls.id, reality.id), migrated.activeIds)
            assertFalse(preferences.getString("profiles", "").orEmpty().contains(none.id))
            assertFalse(preferences.getString("profiles", "").orEmpty().contains("legacy-raw"))
            assertFalse(preferences.getString("profiles", "").orEmpty().contains("legacy-ech"))
        } finally {
            preferences.edit().clear().commit()
            ProfileStore.save(context, original).getOrThrow()
        }
    }

    @Test
    fun legacyAnonymousLanRuntimeSettingsGeneratePasswordAndEncryptIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("tcptun_runtime", 0)
        val original = RuntimeSettingsRepository.read(context).requireAuthoritativeSettings()
        try {
            preferences.edit().clear()
                .putBoolean("runtimeSocksListenAll", true)
                .putString("runtimeSocksUsername", "legacy-user")
                .putString("runtimeSocksPassword", "")
                .commit()

            val migrated = RuntimeSettingsRepository.read(context).requireAuthoritativeSettings()
            val encryptedValues = context.getSharedPreferences("tcptun_encrypted_secrets", 0)
                .all.values.filterIsInstance<String>()

            assertEquals("legacy-user", migrated.localProxyUsers.single().username)
            assertTrue(migrated.localProxyUsers.single().password.isNotEmpty())
            assertEquals(32, migrated.localProxyUsers.single().password.length)
            assertFalse(preferences.contains("runtimeSocksUsername"))
            assertFalse(preferences.contains("runtimeSocksPassword"))
            assertFalse(encryptedValues.any { it.contains("legacy-user") })
            assertFalse(encryptedValues.any { it.contains(migrated.localProxyUsers.single().password) })
            assertEquals(RuntimeSettingsStorageKeys.EncryptedSecretsVersion, preferences.getInt("runtimeStorageVersion", 0))
        } finally {
            preferences.edit().clear().commit()
            RuntimeSettingsRepository.write(context, original)
        }
    }

    @Test
    fun corruptedCiphertextFailsClosedWithoutDeletingPublicProfileData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("tcptun", 0)
        val encryptedPreferences = context.getSharedPreferences("tcptun_encrypted_secrets", 0)
        val original = ProfileStore.load(context)
        val profile = AppConfig(
            id = "corrupt-ciphertext-test",
            serverHost = "corrupt.example.com",
            token = "must-not-be-lost",
        )
        ProfileStore.save(context, ProfilesState(listOf(profile))).getOrThrow()
        val secretsId = preferences.getString("profileSecretsId", null)!!
        val ciphertext = encryptedPreferences.getString(secretsId, null)!!
        try {
            val corrupted = ciphertext.dropLast(1) + if (ciphertext.last() == '0') '1' else '0'
            encryptedPreferences.edit().putString(secretsId, corrupted).commit()

            val snapshot = ProfileStore.snapshot(context)

            assertFalse(snapshot.isAuthoritative)
            assertFalse(preferences.getString("profiles", "").orEmpty().isEmpty())
            assertTrue(preferences.getString("profiles", "").orEmpty().contains(profile.id))
        } finally {
            encryptedPreferences.edit().putString(secretsId, ciphertext).commit()
            ProfileStore.save(context, original).getOrThrow()
        }
    }
}
