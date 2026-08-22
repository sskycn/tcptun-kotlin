package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DesiredRunningPlanMigrationTest {
    @Test
    fun publicCommitFailureRemovesNewBlobAndKeepsOldAuthoritativeState() {
        val store = FakeSecretStorage(mutableMapOf("old" to "old-plan"))
        var authoritativePointer = "old"

        val committed = replaceWithVerifiedSecret(store, "new", "new-plan") {
            false
        }

        assertFalse(committed)
        assertEquals("old", authoritativePointer)
        assertEquals("old-plan", store.values["old"])
        assertFalse(store.values.containsKey("new"))
    }

    @Test
    fun thrownPublicCommitRemovesNewBlobAndPreservesPreviousBlob() {
        val store = FakeSecretStorage(mutableMapOf("old" to "old-plan"))

        assertThrows(IllegalStateException::class.java) {
            replaceWithVerifiedSecret(store, "new", "new-plan") {
                throw IllegalStateException("public commit failed")
            }
        }

        assertEquals(setOf("old"), store.values.keys)
    }

    @Test
    fun previousBlobIsNeverDeletedBySuccessfulReplacementHelper() {
        val store = FakeSecretStorage(mutableMapOf("old" to "old-plan"))
        var pointer = "old"

        val committed = replaceWithVerifiedSecret(store, "new", "new-plan") {
            pointer = "new"
            true
        }

        assertTrue(committed)
        assertEquals("new", pointer)
        assertEquals("old-plan", store.values["old"])
        assertEquals("new-plan", store.values["new"])
    }

    private class FakeSecretStorage(
        val values: MutableMap<String, String>,
    ) : SecretStorage {
        override fun writeVerified(key: String, plaintext: String) {
            values[key] = plaintext
            check(read(key) == plaintext)
        }

        override fun read(key: String): String? = values[key]

        override fun remove(key: String) {
            values.remove(key)
        }
    }
}
