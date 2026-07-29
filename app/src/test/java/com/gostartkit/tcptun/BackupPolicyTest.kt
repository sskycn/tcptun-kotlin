package com.tcptun.client

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.w3c.dom.Element

class BackupPolicyTest {
    @Test
    fun applicationBackupIsDisabled() {
        val application = xml("src/main/AndroidManifest.xml")
            .getElementsByTagName("application")
            .item(0) as Element

        assertEquals("false", application.getAttribute("android:allowBackup"))
    }

    @Test
    fun legacyBackupExcludesAllSharedPreferences() {
        assertSharedPreferencesExcluded(
            xml("src/main/res/xml/backup_rules.xml").documentElement,
        )
    }

    @Test
    fun cloudAndDeviceTransferExcludeAllSharedPreferences() {
        val document = xml("src/main/res/xml/data_extraction_rules.xml")

        assertSharedPreferencesExcluded(document.getElementsByTagName("cloud-backup").item(0) as Element)
        assertSharedPreferencesExcluded(document.getElementsByTagName("device-transfer").item(0) as Element)
    }

    private fun assertSharedPreferencesExcluded(parent: Element) {
        val exclusions = parent.getElementsByTagName("exclude")
        val matchingRule = (0 until exclusions.length)
            .map { exclusions.item(it) as Element }
            .firstOrNull {
                it.getAttribute("domain") == "sharedpref" && it.getAttribute("path") == "."
            }

        assertNotNull("all SharedPreferences must be excluded from backup", matchingRule)
    }

    private fun xml(relativePath: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(File(relativePath))
}
