package com.tcptun.client

internal sealed interface LocalProxyAccountsSummary {
    data object NotConfigured : LocalProxyAccountsSummary
    data class Configured(val count: Int) : LocalProxyAccountsSummary
}

internal fun localProxyAccountsSummary(users: List<LocalProxyUser>): LocalProxyAccountsSummary =
    if (users.isEmpty()) LocalProxyAccountsSummary.NotConfigured
    else LocalProxyAccountsSummary.Configured(users.size)

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
