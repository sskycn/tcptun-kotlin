package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnMembershipStressFixtureTest {
    @Test
    fun fixtureUsesOneStructuredProfileSetForEveryMembershipPlan() {
        val profileA = structuredProfile("membership-a", 19443)
        val profileB = structuredProfile("membership-b", 29443)

        val fixture = validatedMembershipStressFixture(profileA, profileB)

        assertEquals(listOf(profileA, profileB), fixture.configuredProfiles)
        assertEquals(fixture.configuredProfiles, fixture.planA.profiles)
        assertEquals(fixture.configuredProfiles, fixture.planB.profiles)
        assertEquals(fixture.configuredProfiles, fixture.planAB.profiles)
        assertEquals(setOf(profileA.id), fixture.planA.activeIds)
        assertEquals(setOf(profileB.id), fixture.planB.activeIds)
        assertEquals(setOf(profileA.id, profileB.id), fixture.planAB.activeIds)
        assertNotEquals(fixture.planA.activeIds, fixture.planB.activeIds)
        assertTrue(fixture.configuredProfiles.all { it.rawConfigJson.isBlank() })
    }

    @Test(expected = IllegalArgumentException::class)
    fun fixtureRejectsRawProfilesBeforeBuildingMultiProfilePlan() {
        validatedMembershipStressFixture(
            profileA = AppConfig(
                id = "raw-a",
                name = "raw A",
                rawConfigJson = """{"outbounds":[{"tag":"direct","type":"direct"}]}""",
            ),
            profileB = structuredProfile("membership-b", 29443),
        )
    }

    private fun structuredProfile(id: String, port: Int) = AppConfig(
        id = id,
        name = id,
        serverHost = "127.0.0.1",
        serverPort = port.toString(),
    )
}
