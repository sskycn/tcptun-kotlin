package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalProxyAccountsTest {
    private val baseSettings = RuntimeSettings(
        mtu = 1360,
        powerSavingMode = false,
        logLevel = "debug",
        socksPort = 19080,
        routeLocalProxyTraffic = true,
        defaultOutbound = "profile-a",
    )

    @Test
    fun accountSummaryDistinguishesNoneOneAndMany() {
        assertEquals(LocalProxyAccountsSummary.NotConfigured, localProxyAccountsSummary(emptyList()))
        assertEquals(
            LocalProxyAccountsSummary.Configured(1),
            localProxyAccountsSummary(listOf(LocalProxyUser("alice", "a"))),
        )
        assertEquals(
            LocalProxyAccountsSummary.Configured(2),
            localProxyAccountsSummary(listOf(LocalProxyUser("alice", "a"), LocalProxyUser("bob", "b"))),
        )
    }

    @Test
    fun addEditAndDeleteOnlyChangeAccounts() {
        val alice = LocalProxyUser("alice", "secret-a")
        val bob = LocalProxyUser("bob", "secret-b")
        val added = addLocalProxyAccount(addLocalProxyAccount(baseSettings, alice), bob)
        val edited = editLocalProxyAccount(added, 0, LocalProxyUser("alice-2", "secret-c"))
        val deletedSecond = deleteLocalProxyAccount(edited, 1)
        val deletedLast = deleteLocalProxyAccount(deletedSecond, 0)

        assertEquals(listOf(alice, bob), added.localProxyUsers)
        assertEquals(listOf(LocalProxyUser("alice-2", "secret-c"), bob), edited.localProxyUsers)
        assertEquals(listOf(LocalProxyUser("alice-2", "secret-c")), deletedSecond.localProxyUsers)
        assertEquals(emptyList<LocalProxyUser>(), deletedLast.localProxyUsers)
        assertEquals(baseSettings, deletedLast)
    }

    @Test
    fun duplicateAndOverflowAccountsAreRejected() {
        val alice = LocalProxyUser("alice", "secret-a")
        val withAlice = addLocalProxyAccount(baseSettings, alice)
        assertThrows(IllegalArgumentException::class.java) {
            addLocalProxyAccount(withAlice, LocalProxyUser("alice", "different"))
        }

        val full = baseSettings.copy(
            localProxyUsers = List(MaxLocalProxyUsers) { LocalProxyUser("user-$it", "secret") },
        )
        assertThrows(IllegalArgumentException::class.java) {
            addLocalProxyAccount(full, LocalProxyUser("overflow", "secret"))
        }
    }

    @Test
    fun listenAllPreventsDeletingLastAccount() {
        val settings = baseSettings.copy(
            socksListenAll = true,
            localProxyUsers = listOf(LocalProxyUser("alice", "secret")),
        )

        assertThrows(IllegalArgumentException::class.java) {
            deleteLocalProxyAccount(settings, 0)
        }
    }

    @Test
    fun scannedNewAccountIsAddedAfterPreviewConfirmation() {
        val alice = LocalProxyUser("alice", "secret-a")
        assertEquals(LocalProxyAccountImportPlan.Add, planLocalProxyAccountImport(baseSettings, alice))

        val imported = applyLocalProxyAccountImport(baseSettings, alice, updateExisting = false)

        assertEquals(listOf(alice), imported.localProxyUsers)
    }

    @Test
    fun scannedIdenticalAccountDoesNotCreateDuplicate() {
        val alice = LocalProxyUser("alice", "secret-a")
        val settings = baseSettings.copy(localProxyUsers = listOf(alice))

        assertEquals(
            LocalProxyAccountImportPlan.AlreadyPresent,
            planLocalProxyAccountImport(settings, alice),
        )
        assertEquals(settings, applyLocalProxyAccountImport(settings, alice, updateExisting = false))
    }

    @Test
    fun scannedPasswordConflictRequiresExplicitUpdate() {
        val settings = baseSettings.copy(
            localProxyUsers = listOf(LocalProxyUser("alice", "old-secret")),
        )
        val scanned = LocalProxyUser("alice", "new-secret")

        assertEquals(
            LocalProxyAccountImportPlan.Conflict(existingIndex = 0),
            planLocalProxyAccountImport(settings, scanned),
        )
        assertThrows(IllegalArgumentException::class.java) {
            applyLocalProxyAccountImport(settings, scanned, updateExisting = false)
        }
        assertEquals(
            listOf(scanned),
            applyLocalProxyAccountImport(settings, scanned, updateExisting = true).localProxyUsers,
        )
    }

    @Test
    fun fullAccountListRejectsNewUsernameButAllowsExistingUpdate() {
        val full = baseSettings.copy(
            localProxyUsers = List(MaxLocalProxyUsers) { index ->
                LocalProxyUser("user-$index", "old-$index")
            },
        )

        assertEquals(
            LocalProxyAccountImportPlan.LimitReached,
            planLocalProxyAccountImport(full, LocalProxyUser("overflow", "secret")),
        )
        assertThrows(IllegalArgumentException::class.java) {
            applyLocalProxyAccountImport(
                full,
                LocalProxyUser("overflow", "secret"),
                updateExisting = false,
            )
        }

        val updated = LocalProxyUser("user-0", "new-secret")
        assertEquals(
            LocalProxyAccountImportPlan.Conflict(existingIndex = 0),
            planLocalProxyAccountImport(full, updated),
        )
        assertEquals(
            updated,
            applyLocalProxyAccountImport(full, updated, updateExisting = true).localProxyUsers.first(),
        )
    }
}
