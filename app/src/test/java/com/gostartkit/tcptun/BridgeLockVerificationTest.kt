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
        assertEquals("c4959ca9edf4ecfcdd6370eb058615c8ad7c7ab6", sha)
        assertEquals("3", api)
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
    fun optionalAdditionalCommitAssertionRejectsMismatchButIsNotRequired() {
        val root = repositoryRoot()
        val lock = loadLock(root)
        val sha = requireNotNull(lock.getProperty("coreCommit"))
        val api = requireNotNull(lock.getProperty("bridgeApiVersion"))
        val valid = createAar(sha, api, dirty = false)

        assertEquals(0, verify(root, valid))
        assertEquals(0, verify(root, valid, assertedCommit = sha))
        assertFalse(verify(root, valid, assertedCommit = "0".repeat(40)) == 0)
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

    @Test
    fun localPinnedCommitMissingFromRemoteFailsReleasePreflight() {
        val fixture = createRemotePreflightFixture()

        assertFalse(releasePreflight(fixture) == 0)
    }

    @Test
    fun remoteContainingPinnedCommitPassesReleasePreflight() {
        val fixture = createRemotePreflightFixture()
        git(fixture.core, "push", "origin", "HEAD:refs/heads/main")

        assertEquals(0, releasePreflight(fixture))
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

    private fun verify(root: File, aar: File, assertedCommit: String? = null): Int = ProcessBuilder(
        buildList {
            add("bash")
            add(File(root, "scripts/verify-androidbridge.sh").absolutePath)
            add("strict")
            add(aar.absolutePath)
            add(File(root, "bridge.lock").absolutePath)
            assertedCommit?.let(::add)
        },
    ).redirectErrorStream(true).start().let { process ->
        process.inputStream.bufferedReader().readText()
        process.waitFor()
    }

    private data class RemotePreflightFixture(
        val root: File,
        val core: File,
        val lock: File,
    )

    private fun createRemotePreflightFixture(): RemotePreflightFixture {
        val root = repositoryRoot()
        val core = temporaryFolder.newFolder("release-core-${temporaryFolder.root.listFiles()?.size}")
        val remote = temporaryFolder.newFolder("release-remote-${temporaryFolder.root.listFiles()?.size}.git")
        git(core, "init")
        git(core, "config", "user.name", "Bridge Lock Test")
        git(core, "config", "user.email", "bridge-lock@example.invalid")
        File(core, "core.txt").writeText("published core\n")
        git(core, "add", "core.txt")
        git(core, "commit", "-m", "test core")
        git(remote, "init", "--bare")
        git(core, "remote", "add", "origin", remote.absolutePath)
        val sha = git(core, "rev-parse", "HEAD").output.trim()
        val lock = temporaryFolder.newFile(
            "bridge-${temporaryFolder.root.listFiles()?.size}-${sha.take(8)}.lock",
        ).apply {
            writeText("coreCommit=$sha\nbridgeApiVersion=1\n")
        }
        return RemotePreflightFixture(root, core, lock)
    }

    private fun releasePreflight(fixture: RemotePreflightFixture): Int = ProcessBuilder(
        "bash",
        File(fixture.root, "scripts/build-androidbridge.sh").absolutePath,
        "--verify-release",
    ).redirectErrorStream(true).apply {
        environment()["TCPTUN_GO_DIR"] = fixture.core.absolutePath
        environment()["BRIDGE_LOCK_FILE"] = fixture.lock.absolutePath
    }.start().let { process ->
        process.inputStream.bufferedReader().readText()
        process.waitFor()
    }

    private data class CommandResult(val exitCode: Int, val output: String)

    private fun git(directory: File, vararg arguments: String): CommandResult {
        val process = ProcessBuilder(listOf("git", "-C", directory.absolutePath) + arguments)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        check(exitCode == 0) { "git ${arguments.joinToString(" ")} failed: $output" }
        return CommandResult(exitCode, output)
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
