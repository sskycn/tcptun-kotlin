package com.tcptun.client

/** Legal, structured plans used only by the opt-in device membership stress test. */
internal data class VpnMembershipStressFixture(
    val configuredProfiles: List<AppConfig>,
    val planA: ProfileRunPlan,
    val planB: ProfileRunPlan,
    val planAB: ProfileRunPlan,
)

internal fun validatedMembershipStressFixture(
    profileA: AppConfig,
    profileB: AppConfig,
): VpnMembershipStressFixture {
    require(profileA.rawConfigJson.isBlank() && profileB.rawConfigJson.isBlank()) {
        "membership stress requires structured profiles"
    }
    require(profileA.id != profileB.id) { "membership stress profile IDs must be distinct" }

    val configuredProfiles = listOf(profileA, profileB)
    val planA = ProfileRunPlan(
        profiles = configuredProfiles,
        activeIds = setOf(profileA.id),
    ).normalized()
    val planB = ProfileRunPlan(
        profiles = configuredProfiles,
        activeIds = setOf(profileB.id),
    ).normalized()
    val planAB = ProfileRunPlan(
        profiles = configuredProfiles,
        activeIds = setOf(profileA.id, profileB.id),
    ).normalized()

    check(planA.profiles == planB.profiles)
    check(planA.profiles == planAB.profiles)
    check(planA.activeIds != planB.activeIds)

    return VpnMembershipStressFixture(configuredProfiles, planA, planB, planAB)
}
