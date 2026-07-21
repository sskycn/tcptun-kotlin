package com.tcptun.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

private interface GuardedTestCallback {
    fun booleanResult(): Boolean
    fun intResult(): Int
}

class CrashGuardTest {
    @Test
    fun executorTaskFailureIsReportedWithoutEscapingWorkerBoundary() {
        val failure = IllegalStateException("boom")
        var reported: Throwable? = null

        val accepted = executeCrashGuarded(
            executor = Executor(Runnable::run),
            taskName = "test task",
            onFailure = { reported = it },
        ) { throw failure }

        assertTrue(accepted)
        assertSame(failure, reported?.cause)
    }

    @Test
    fun rejectedExecutorSubmissionIsReportedAndTaskDoesNotRun() {
        val rejected = RejectedExecutionException("closed")
        var reported: Throwable? = null
        var ran = false

        val accepted = executeCrashGuarded(
            executor = Executor { throw rejected },
            taskName = "rejected task",
            onFailure = { reported = it },
        ) { ran = true }

        assertFalse(accepted)
        assertFalse(ran)
        assertSame(rejected, reported?.cause)
    }

    @Test
    fun fatalVmErrorsAreNeverConvertedIntoOrdinaryTaskFailures() {
        val fatal = OutOfMemoryError("fatal")

        val thrown = assertThrows(OutOfMemoryError::class.java) {
            executeCrashGuarded(Executor(Runnable::run), "fatal task") { throw fatal }
        }

        assertSame(fatal, thrown)
    }

    @Test
    fun bridgeProxyContainsCallbackFailuresAndImplementsObjectMethods() {
        val proxy = createSafeBridgeCallbackProxy(
            callbackClass = GuardedTestCallback::class.java,
            label = "test",
        ) { _, _ -> throw IllegalArgumentException("callback bug") } as GuardedTestCallback

        assertFalse(proxy.booleanResult())
        assertEquals(0, proxy.intResult())
        assertTrue(proxy == proxy)
        assertNotEquals(proxy, Any())
        assertEquals(System.identityHashCode(proxy), proxy.hashCode())
        assertEquals("SafeBridgeCallback(test)", proxy.toString())
    }
}
