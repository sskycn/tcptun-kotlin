package com.tcptun.client

import java.util.UUID

internal sealed interface PendingUiOperation {
    data class RunPlan(val value: ProfileRunPlan) : PendingUiOperation
    data class Profile(val value: AppConfig) : PendingUiOperation
    data class ProfileUri(val value: String) : PendingUiOperation
}

/**
 * Process-local handoff for UI values that contain credentials. Android SavedState receives only
 * the random operation ID. Losing the process intentionally loses these transient operations;
 * durable profile data remains available from the encrypted profile repository.
 */
internal object PendingUiOperationStore {
    private const val MaxEntries = 64
    private val operations = LinkedHashMap<String, PendingUiOperation>()

    @Synchronized
    fun put(value: PendingUiOperation): String {
        while (operations.size >= MaxEntries) {
            operations.remove(operations.entries.first().key)
        }
        return UUID.randomUUID().toString().also { id -> operations[id] = value }
    }

    @Synchronized
    fun get(id: String): PendingUiOperation? = operations[id]

    @Synchronized
    fun consume(id: String): PendingUiOperation? = operations.remove(id)

    @Synchronized
    fun remove(id: String) {
        operations.remove(id)
    }

    @Synchronized
    internal fun clearForTest() {
        operations.clear()
    }
}

internal fun encodePendingRunPlan(plan: ProfileRunPlan?): String? = plan?.let {
    PendingUiOperationStore.put(PendingUiOperation.RunPlan(it.normalized()))
}

internal fun decodePendingRunPlan(operationId: String?): ProfileRunPlan? = operationId
    ?.let(PendingUiOperationStore::consume)
    ?.let { it as? PendingUiOperation.RunPlan }
    ?.value

internal fun encodePendingProfile(profile: AppConfig?): String? = profile?.let {
    PendingUiOperationStore.put(PendingUiOperation.Profile(it))
}

internal fun decodePendingProfile(operationId: String?): AppConfig? = operationId
    ?.let(PendingUiOperationStore::consume)
    ?.let { it as? PendingUiOperation.Profile }
    ?.value

internal fun encodePendingProfileUri(profileUri: String): String =
    PendingUiOperationStore.put(PendingUiOperation.ProfileUri(profileUri))

internal fun decodePendingProfileUri(operationId: String?): String? = operationId
    ?.let(PendingUiOperationStore::consume)
    ?.let { it as? PendingUiOperation.ProfileUri }
    ?.value
