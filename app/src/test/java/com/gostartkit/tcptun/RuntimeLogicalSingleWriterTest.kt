package com.tcptun.client

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeLogicalSingleWriterTest {
    @Test
    fun actorIsTheOnlyMutableBackingStoreForLogicalRuntimeState() {
        val main = File(repositoryRoot(), "app/src/main/java/com/gostartkit/tcptun")
        val mutableStateDeclarations = main.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().asSequence().mapIndexedNotNull { index, line ->
                    if (Regex("\\b(var|AtomicReference<)\\b.*VpnRuntime(State|Snapshot)").containsMatchIn(line)) {
                        "${file.name}:${index + 1}:$line"
                    } else {
                        null
                    }
                }
            }
            .toList()

        assertEquals(emptyList<String>(), mutableStateDeclarations)
        val actor = File(main, "VpnRuntimeActor.kt").readText()
        assertTrue(actor.contains("private var publishedState"))
        val service = File(main, "TcptunVpnService.kt").readText()
        assertTrue(service.contains("get() = runtimeCoordinator.snapshot"))
        assertFalse(service.contains("copy(phase ="))
    }

    private fun repositoryRoot(): File {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        return generateSequence(working) { it.parentFile }
            .first { File(it, "bridge.lock").isFile }
    }
}
