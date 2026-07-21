package com.tcptun.client

import org.junit.Assert.assertThrows
import org.junit.Test

class JsonSafetyTest {
    @Test
    fun maximumSupportedNestingIsAccepted() {
        val json = "[".repeat(MaxJsonNestingDepth) + "0" + "]".repeat(MaxJsonNestingDepth)

        requireSafeJsonNesting(json)
    }

    @Test
    fun excessiveNestingIsRejectedBeforeRecursiveParsing() {
        val json = "[".repeat(MaxJsonNestingDepth + 1) + "0" + "]".repeat(MaxJsonNestingDepth + 1)

        assertThrows(IllegalArgumentException::class.java) {
            requireSafeJsonNesting(json)
        }
    }

    @Test
    fun bracketsAndEscapedQuotesInsideStringsDoNotIncreaseDepth() {
        val json = """{"value":"${"[".repeat(256)}\\\"${"]".repeat(256)}"}"""

        requireSafeJsonNesting(json)
    }
}
