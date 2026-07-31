package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ProfileStoreCasTest {
    @Test
    fun recoveringReadKeepsFallbackForDisplayButMarksItNonAuthoritative() {
        val fallback = ProfilesState(emptyList())
        val failure = IllegalStateException("temporary storage failure")

        val read = recoverProfileStoreRead(fallback) { throw failure }

        assertEquals(fallback, read.state)
        assertFalse(read.isAuthoritative)
        assertSame(failure, read.readFailure)
        val rejected = assertThrows(IllegalStateException::class.java) {
            read.requireAuthoritativeState()
        }
        assertSame(failure, rejected.cause)
    }

    @Test
    fun successfulReadIsAuthoritative() {
        val state = ProfilesState(listOf(validProfile("read-success")))

        val read = recoverProfileStoreRead(ProfilesState(emptyList())) { state }

        assertTrue(read.isAuthoritative)
        assertNull(read.readFailure)
        assertEquals(state, read.requireAuthoritativeState())
    }

    @Test
    fun failedStartupSnapshotCannotBeInterpretedAsNoActiveProfiles() {
        val failure = IllegalStateException("startup snapshot read failed")
        val snapshot = ProfileStoreSnapshot(
            state = ProfilesState(emptyList()),
            mutationRevision = 5,
            readFailure = failure,
        )

        val rejected = assertThrows(IllegalStateException::class.java) {
            snapshot.requireAuthoritativeState().activeIds.isEmpty()
        }

        assertSame(failure, rejected.cause)
    }

    @Test
    fun failedExpectedSnapshotCanNeverParticipateInCas() {
        val fallback = ProfilesState(emptyList())
        val failure = IllegalStateException("snapshot read failed")
        val expected = ProfileStoreSnapshot(
            state = fallback,
            mutationRevision = 7,
            readFailure = failure,
        )

        val rejected = assertThrows(IllegalStateException::class.java) {
            profileStoreCasMatches(
                expected = expected,
                currentMutationRevision = 7,
                current = RecoveringProfileStoreRead(fallback),
            )
        }

        assertFalse(expected.isAuthoritative)
        assertSame(failure, rejected.cause)
    }

    @Test
    fun failedCasRereadIsNotTreatedAsMatchingEmptyState() {
        val empty = ProfilesState(emptyList())
        val failure = IllegalStateException("CAS reread failed")
        val expected = ProfileStoreSnapshot(empty, mutationRevision = 11)

        val rejected = assertThrows(IllegalStateException::class.java) {
            profileStoreCasMatches(
                expected = expected,
                currentMutationRevision = 11,
                current = RecoveringProfileStoreRead(empty, readFailure = failure),
            )
        }

        assertSame(failure, rejected.cause)
    }

    @Test
    fun authoritativeCasRequiresBothRevisionAndStateToMatch() {
        val state = ProfilesState(listOf(validProfile("cas-profile")))
        val expected = ProfileStoreSnapshot(state, mutationRevision = 13)

        assertTrue(profileStoreCasMatches(expected, 13, RecoveringProfileStoreRead(state)))
        assertFalse(profileStoreCasMatches(expected, 14, RecoveringProfileStoreRead(state)))
        assertFalse(
            profileStoreCasMatches(
                expected,
                13,
                RecoveringProfileStoreRead(ProfilesState(emptyList())),
            ),
        )
    }

    private fun validProfile(id: String) = AppConfig(
        id = id,
        name = id,
        serverHost = "192.0.2.1",
        serverPort = "443",
        token = "token",
    )
}
