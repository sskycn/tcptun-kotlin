package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RuntimeLogLevelTest {
    @Test
    fun normalizationAcceptsSupportedLevelsAndFallsBackToInfo() {
        assertEquals("debug", normalizeLogLevel(" DEBUG "))
        assertEquals("warn", normalizeLogLevel("warn"))
        assertEquals("error", normalizeLogLevel("error"))
        assertEquals("off", normalizeLogLevel("off"))
        assertEquals("off", normalizeLogLevel(" none "))
        assertEquals(DefaultLogLevel, normalizeLogLevel(""))
        assertEquals(DefaultLogLevel, normalizeLogLevel("verbose"))
    }

    @Test
    fun configuredLevelControlsGeneratedConfiguration() {
        assertEquals("warn", effectiveLogLevel(verbose = false, configuredLevel = "warn"))
        assertEquals("error", effectiveLogLevel(verbose = false, configuredLevel = "error"))
        assertEquals(DefaultLogLevel, effectiveLogLevel(verbose = false, configuredLevel = null))
    }

    @Test
    fun verboseCompatibilityStillForcesDebug() {
        assertEquals("debug", effectiveLogLevel(verbose = true, configuredLevel = "error"))
        assertFalse(
            BridgeHealthPolicy.isStructuralRuntimeChange(
                RuntimeSettings(logLevel = "info"),
                RuntimeSettings(logLevel = "debug"),
            ),
        )
    }
}
