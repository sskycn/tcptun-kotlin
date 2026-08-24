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
    fun credentialsNeverAppearInPublicProfileSettings() {
        val profile = AppConfig(
            id = "profile-id",
            serverHost = "example.com",
            token = "credential-token",
            realityPublicKey = "reality-key",
            realityShortId = "reality-short-id",
            realitySpiderX = "/private-spider",
            echPublicKey = "ech-key",
            rawConfigJson = "{\"token\":\"raw-secret\"}",
        )

        val public = profile.toPublicStorageJson().toString()
        val restored = AppConfig.fromJson(profile.toPublicStorageJson())
            .withStorageSecrets(profile.toSecretStorageJson())

        listOf(
            "credential-token",
            "reality-key",
            "reality-short-id",
            "/private-spider",
            "ech-key",
            "raw-secret",
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
    fun legacyAnonymousLanRuntimeSettingsGeneratePasswordAndEncryptIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("tcptun_runtime", 0)
        val original = RuntimeSettingsRepository.read(context)
        try {
            preferences.edit().clear()
                .putBoolean("runtimeSocksListenAll", true)
                .putString("runtimeSocksUsername", "legacy-user")
                .putString("runtimeSocksPassword", "")
                .commit()

            val migrated = RuntimeSettingsRepository.read(context)
            val encryptedValues = context.getSharedPreferences("tcptun_encrypted_secrets", 0)
                .all.values.filterIsInstance<String>()

            assertEquals("legacy-user", migrated.socksUsername)
            assertTrue(migrated.socksPassword.isNotEmpty())
            assertEquals(32, migrated.socksPassword.length)
            assertFalse(preferences.contains("runtimeSocksUsername"))
            assertFalse(preferences.contains("runtimeSocksPassword"))
            assertFalse(encryptedValues.any { it.contains("legacy-user") })
            assertFalse(encryptedValues.any { it.contains(migrated.socksPassword) })
            assertEquals(2, preferences.getInt("runtimeStorageVersion", 0))
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
