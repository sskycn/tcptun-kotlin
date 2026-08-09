package com.tcptun.client

import android.content.Context

/** Persistence boundary for profile state, including its optimistic-concurrency contract. */
internal interface ProfileRepository {
    fun currentMutationRevision(): Long

    fun runIfRevisionCurrent(
        expectedMutationRevision: Long,
        commitLock: Any,
        canCommit: () -> Boolean,
        action: () -> Unit,
    ): Boolean

    fun snapshot(context: Context): ProfileStoreSnapshot

    fun load(context: Context): ProfilesState

    fun saveIfCurrent(
        context: Context,
        expected: ProfileStoreSnapshot,
        next: ProfilesState,
    ): Result<ProfileStoreSnapshot?>

    fun replaceActiveIdsIfCurrent(
        context: Context,
        expectedMutationRevision: Long?,
        expectedActiveIds: Set<String>,
        replacementActiveIds: Set<String>,
        commitLock: Any? = null,
        canCommit: () -> Boolean = { true },
    ): Result<Boolean>

    fun clearActiveIfCurrent(
        context: Context,
        expectedMutationRevision: Long,
        commitLock: Any,
        canCommit: () -> Boolean,
    ): Result<Boolean>

    fun alignActiveIdsWithPlanIfCurrent(
        context: Context,
        expectedMutationRevision: Long?,
        plan: ProfileRunPlan,
        commitLock: Any,
        canCommit: () -> Boolean,
    ): Result<Boolean>
}

internal object SharedPreferencesProfileRepository : ProfileRepository {
    override fun currentMutationRevision(): Long = ProfileStore.currentMutationRevision()

    override fun runIfRevisionCurrent(
        expectedMutationRevision: Long,
        commitLock: Any,
        canCommit: () -> Boolean,
        action: () -> Unit,
    ): Boolean = ProfileStore.runIfRevisionCurrent(
        expectedMutationRevision,
        commitLock,
        canCommit,
        action,
    )

    override fun snapshot(context: Context): ProfileStoreSnapshot = ProfileStore.snapshot(context)

    override fun load(context: Context): ProfilesState = ProfileStore.load(context)

    override fun saveIfCurrent(
        context: Context,
        expected: ProfileStoreSnapshot,
        next: ProfilesState,
    ): Result<ProfileStoreSnapshot?> = ProfileStore.saveIfCurrent(context, expected, next)

    override fun replaceActiveIdsIfCurrent(
        context: Context,
        expectedMutationRevision: Long?,
        expectedActiveIds: Set<String>,
        replacementActiveIds: Set<String>,
        commitLock: Any?,
        canCommit: () -> Boolean,
    ): Result<Boolean> = ProfileStore.replaceActiveIdsIfCurrent(
        context,
        expectedMutationRevision,
        expectedActiveIds,
        replacementActiveIds,
        commitLock,
        canCommit,
    )

    override fun clearActiveIfCurrent(
        context: Context,
        expectedMutationRevision: Long,
        commitLock: Any,
        canCommit: () -> Boolean,
    ): Result<Boolean> = ProfileStore.clearActiveIfCurrent(
        context,
        expectedMutationRevision,
        commitLock,
        canCommit,
    )

    override fun alignActiveIdsWithPlanIfCurrent(
        context: Context,
        expectedMutationRevision: Long?,
        plan: ProfileRunPlan,
        commitLock: Any,
        canCommit: () -> Boolean,
    ): Result<Boolean> = ProfileStore.alignActiveIdsWithPlanIfCurrent(
        context,
        expectedMutationRevision,
        plan,
        commitLock,
        canCommit,
    )
}

internal fun Context.profileRepository(): ProfileRepository {
    val appContext = applicationContext ?: this
    return (appContext as? TcptunApplication)?.profileRepository ?: SharedPreferencesProfileRepository
}
