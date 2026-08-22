package com.tcptun.client

import java.io.File
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule

class BridgeLockVerificationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun strictVerifierAcceptsOnlyLockedCleanMetadata() {
        val root = repositoryRoot()
        val lock = loadLock(root)
        val sha = requireNotNull(lock.getProperty("coreCommit"))
        val api = requireNotNull(lock.getProperty("bridgeApiVersion"))
        assertTrue(sha.matches(Regex("[0-9a-f]{40}")))
        assertTrue(api.matches(Regex("[1-9][0-9]*")))

        val valid = createAar(sha, api, dirty = false)
        assertEquals(0, verify(root, valid))

        val dirty = createAar(sha, api, dirty = true)
        assertFalse(verify(root, dirty) == 0)

        val mismatched = createAar("0".repeat(40), api, dirty = false)
        assertFalse(verify(root, mismatched) == 0)
    }

    @Test
    fun lockMatchesLocalCoreHeadWhenCoreCheckoutIsAvailable() {
        val root = repositoryRoot()
        val core = listOf(File(root, "../tcptun-go"), File(root, "tcptun-go"))
            .firstOrNull { File(it, ".git").exists() } ?: return
        val expected = requireNotNull(loadLock(root).getProperty("coreCommit"))
        val process = ProcessBuilder("git", "-C", core.canonicalPath, "rev-parse", "HEAD")
            .redirectErrorStream(true)
            .start()
        val actual = process.inputStream.bufferedReader().readText().trim()
        assertEquals(0, process.waitFor())
        assertEquals(expected, actual)
    }

    @Test
    fun buildPreflightRejectsDirtyLockedCoreCheckout() {
        val root = repositoryRoot()
        val sha = requireNotNull(loadLock(root).getProperty("coreCommit"))
        val fakeBin = temporaryFolder.newFolder("fake-bin")
        val fakeCore = temporaryFolder.newFolder("fake-core")
        val fakeGit = File(fakeBin, "git")
        fakeGit.writeText(
            """#!/usr/bin/env bash
            case "${'$'}*" in
              *"rev-parse HEAD"*) echo "$sha" ;;
              *"status --porcelain"*) echo "?? local-change" ;;
              *) exit 2 ;;
            esac
            """.trimIndent(),
        )
        assertTrue(fakeGit.setExecutable(true))

        val process = ProcessBuilder(
            "bash",
            File(root, "scripts/build-androidbridge.sh").absolutePath,
            "--verify-lock",
        ).redirectErrorStream(true).apply {
            environment()["TCPTUN_GO_DIR"] = fakeCore.absolutePath
            environment()["PATH"] =
                fakeBin.absolutePath + File.pathSeparator + environment()["PATH"].orEmpty()
        }.start()
        process.inputStream.bufferedReader().readText()

        assertFalse(process.waitFor() == 0)
    }

    private fun createAar(sha: String, api: String, dirty: Boolean): File {
        val aar = temporaryFolder.newFile("bridge-${temporaryFolder.root.listFiles()?.size}.aar")
        ZipOutputStream(aar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("bridge-version.properties"))
            zip.write(
                buildString {
                    appendLine("coreCommit=$sha")
                    appendLine("coreVersion=test${if (dirty) "-dirty" else ""}")
                    appendLine("coreDirty=$dirty")
                    appendLine("bridgeApiVersion=$api")
                }.toByteArray(),
            )
            zip.closeEntry()
        }
        return aar
    }

    private fun verify(root: File, aar: File): Int = ProcessBuilder(
        "bash",
        File(root, "scripts/verify-androidbridge.sh").absolutePath,
        "strict",
        aar.absolutePath,
        File(root, "bridge.lock").absolutePath,
    ).redirectErrorStream(true).start().let { process ->
        process.inputStream.bufferedReader().readText()
        process.waitFor()
    }

    private fun loadLock(root: File): Properties = Properties().apply {
        File(root, "bridge.lock").inputStream().use(::load)
    }

    private fun repositoryRoot(): File {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        return generateSequence(working) { it.parentFile }
            .first { File(it, "bridge.lock").isFile }
    }
}
