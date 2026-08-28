package com.tcptun.client

internal sealed interface LocalProxyAccountsSummary {
    data object NotConfigured : LocalProxyAccountsSummary
    data class Configured(val count: Int) : LocalProxyAccountsSummary
}

internal fun localProxyAccountsSummary(users: List<LocalProxyUser>): LocalProxyAccountsSummary =
    if (users.isEmpty()) LocalProxyAccountsSummary.NotConfigured
    else LocalProxyAccountsSummary.Configured(users.size)

internal sealed interface LocalProxyAccountImportPlan {
    data object Add : LocalProxyAccountImportPlan
    data object AlreadyPresent : LocalProxyAccountImportPlan
    data class Conflict(val existingIndex: Int) : LocalProxyAccountImportPlan
    data object LimitReached : LocalProxyAccountImportPlan
}

internal fun planLocalProxyAccountImport(
    settings: RuntimeSettings,
    account: LocalProxyUser,
): LocalProxyAccountImportPlan {
    validateLocalProxyUsers(listOf(account))
    val existingIndex = settings.localProxyUsers.indexOfFirst { it.username == account.username }
    if (existingIndex >= 0) {
        return if (settings.localProxyUsers[existingIndex] == account) {
            LocalProxyAccountImportPlan.AlreadyPresent
        } else {
            LocalProxyAccountImportPlan.Conflict(existingIndex)
        }
    }
    return if (settings.localProxyUsers.size >= MaxLocalProxyUsers) {
        LocalProxyAccountImportPlan.LimitReached
    } else {
        LocalProxyAccountImportPlan.Add
    }
}

internal fun applyLocalProxyAccountImport(
    settings: RuntimeSettings,
    account: LocalProxyUser,
    updateExisting: Boolean,
): RuntimeSettings = when (val plan = planLocalProxyAccountImport(settings, account)) {
    LocalProxyAccountImportPlan.Add -> addLocalProxyAccount(settings, account)
    LocalProxyAccountImportPlan.AlreadyPresent -> settings
    is LocalProxyAccountImportPlan.Conflict -> {
        require(updateExisting) { "an existing proxy account requires explicit update confirmation" }
        editLocalProxyAccount(settings, plan.existingIndex, account)
    }
    LocalProxyAccountImportPlan.LimitReached -> {
        throw IllegalArgumentException("the local proxy account limit has been reached")
    }
}

internal fun addLocalProxyAccount(
    settings: RuntimeSettings,
    user: LocalProxyUser,
): RuntimeSettings = settings.withLocalProxyUsers(settings.localProxyUsers + user)

internal fun editLocalProxyAccount(
    settings: RuntimeSettings,
    index: Int,
    user: LocalProxyUser,
): RuntimeSettings {
    require(index in settings.localProxyUsers.indices) { "local proxy account index is invalid" }
    return settings.withLocalProxyUsers(
        settings.localProxyUsers.toMutableList().apply { set(index, user) },
    )
}

internal fun deleteLocalProxyAccount(
    settings: RuntimeSettings,
    index: Int,
): RuntimeSettings {
    require(index in settings.localProxyUsers.indices) { "local proxy account index is invalid" }
    val users = settings.localProxyUsers.filterIndexed { current, _ -> current != index }
    require(!settings.socksListenAll || users.isNotEmpty()) {
        "at least one local proxy account is required while listening on all interfaces"
    }
    return settings.withLocalProxyUsers(users)
}

private fun RuntimeSettings.withLocalProxyUsers(users: List<LocalProxyUser>): RuntimeSettings =
    requireSafeRuntimeSettings(copy(localProxyUsers = users))
