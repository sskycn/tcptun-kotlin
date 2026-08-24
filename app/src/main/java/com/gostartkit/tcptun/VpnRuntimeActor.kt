package com.tcptun.client

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

internal class VpnRuntimeActorAdmissionException(message: String) : RejectedExecutionException(message)

/**
 * Bounded single-writer mailbox for logical VPN state.
 *
 * Reducer work is deliberately tiny. Blocking platform/JNI effects are returned
 * to the caller and must be dispatched on a lifecycle worker.
 */
internal class VpnRuntimeActor(
    initialState: VpnRuntimeState = VpnRuntimeState(),
    mailboxCapacity: Int = DefaultMailboxCapacity,
    threadName: String = "TcptunRuntimeActor",
    private val admissionTimeoutMillis: Long = ActorAdmissionTimeoutMillis,
    private val responseTimeoutMillis: Long = ActorResponseTimeoutMillis,
    threadFactory: (Runnable, String) -> Thread = ::defaultActorThread,
) {
    private enum class IngressState {
        Accepting,
        Draining,
        Closing,
        Closed,
    }

    private enum class IngressKind { External, Internal }

    private sealed interface Command {
        class Event(
            val event: VpnRuntimeEvent,
            val result: CompletableFuture<VpnRuntimeDecision>,
        ) : Command

        class EventFactory(
            val factory: (VpnRuntimeState) -> VpnRuntimeEvent,
            val result: CompletableFuture<VpnRuntimeDecision>,
        ) : Command

        class CompatibilityMutation(
            val mutation: (VpnRuntimeState) -> VpnRuntimeState,
            val result: CompletableFuture<VpnRuntimeState>,
        ) : Command

        class Shutdown(val result: CompletableFuture<Unit>) : Command
    }

    private val mailbox = ArrayBlockingQueue<Command>(mailboxCapacity)
    private val ingressLock = Any()
    private val ingressState = AtomicReference(IngressState.Accepting)
    private val terminated = CountDownLatch(1)
    private val terminalFailure = AtomicReference<Throwable?>()

    @Volatile
    private var publishedState = initialState

    private val actorThread = threadFactory(Runnable(::runMailbox), threadName).apply {
        isDaemon = true
        start()
    }

    init {
        require(mailboxCapacity > 0) { "runtime actor mailbox capacity must be positive" }
        require(admissionTimeoutMillis > 0L) { "runtime actor admission timeout must be positive" }
        require(responseTimeoutMillis > 0L) { "runtime actor response timeout must be positive" }
    }

    val state: VpnRuntimeState
        get() = publishedState

    fun send(event: VpnRuntimeEvent): VpnRuntimeDecision {
        val result = CompletableFuture<VpnRuntimeDecision>()
        enqueue(Command.Event(event, result), IngressKind.External, event.reliability)
        return await(result)
    }

    fun send(factory: (VpnRuntimeState) -> VpnRuntimeEvent): VpnRuntimeDecision {
        val result = CompletableFuture<VpnRuntimeDecision>()
        enqueue(Command.EventFactory(factory, result), IngressKind.External)
        return await(result)
    }

    /** Completes work admitted before Destroy while new external commands are rejected. */
    fun sendInternal(event: VpnRuntimeEvent): VpnRuntimeDecision {
        val result = CompletableFuture<VpnRuntimeDecision>()
        enqueue(Command.Event(event, result), IngressKind.Internal, event.reliability)
        return await(result)
    }

    fun sendInternal(factory: (VpnRuntimeState) -> VpnRuntimeEvent): VpnRuntimeDecision {
        val result = CompletableFuture<VpnRuntimeDecision>()
        enqueue(Command.EventFactory(factory, result), IngressKind.Internal)
        return await(result)
    }

    /** Atomically closes external ingress before publishing the terminal logical state. */
    fun beginDestroy(factory: (VpnRuntimeState) -> VpnRuntimeEvent.Destroyed): VpnRuntimeDecision {
        val result = CompletableFuture<VpnRuntimeDecision>()
        synchronized(ingressLock) {
            check(ingressState.compareAndSet(IngressState.Accepting, IngressState.Draining)) {
                "runtime actor external ingress is already closed"
            }
            enqueueLocked(Command.EventFactory(factory, result))
        }
        return await(result)
    }

    fun closeExternalIngress() {
        ingressState.compareAndSet(IngressState.Accepting, IngressState.Draining)
    }

    /** Temporary bridge for Coordinator events not migrated in this task. */
    fun compatibilityMutation(
        mutation: (VpnRuntimeState) -> VpnRuntimeState,
    ): VpnRuntimeState {
        val result = CompletableFuture<VpnRuntimeState>()
        enqueue(Command.CompatibilityMutation(mutation, result), IngressKind.External)
        return await(result)
    }

    fun compatibilityMutationInternal(
        mutation: (VpnRuntimeState) -> VpnRuntimeState,
    ): VpnRuntimeState {
        val result = CompletableFuture<VpnRuntimeState>()
        enqueue(Command.CompatibilityMutation(mutation, result), IngressKind.Internal)
        return await(result)
    }

    /** Drains admitted commands, then performs a bounded join of the Actor thread. */
    fun shutdown(): Boolean {
        val result = CompletableFuture<Unit>()
        synchronized(ingressLock) {
            when (ingressState.get()) {
                IngressState.Closed -> return terminalFailure.get() == null
                IngressState.Closing -> return awaitTermination(responseTimeoutMillis) &&
                    terminalFailure.get() == null
                IngressState.Accepting,
                IngressState.Draining,
                -> ingressState.set(IngressState.Closing)
            }
            try {
                enqueueLocked(Command.Shutdown(result))
            } catch (error: VpnRuntimeActorAdmissionException) {
                terminalFailure.compareAndSet(null, error)
                actorThread.interrupt()
                return awaitTermination(responseTimeoutMillis)
            }
        }
        runCatching { await(result) }.onFailure { terminalFailure.compareAndSet(null, it) }
        return awaitTermination(responseTimeoutMillis) && terminalFailure.get() == null
    }

    internal fun actorThreadName(): String = actorThread.name
    internal fun isAcceptingExternal(): Boolean = ingressState.get() == IngressState.Accepting
    internal fun isTerminated(): Boolean = terminated.count == 0L
    internal fun queuedCommandCount(): Int = mailbox.size

    internal fun awaitTermination(timeoutMillis: Long): Boolean {
        if (!terminated.await(timeoutMillis, TimeUnit.MILLISECONDS)) return false
        actorThread.join(timeoutMillis)
        return !actorThread.isAlive
    }

    private fun enqueue(
        command: Command,
        ingressKind: IngressKind,
        reliability: VpnRuntimeEventReliability = VpnRuntimeEventReliability.Critical,
    ) {
        synchronized(ingressLock) {
            val state = ingressState.get()
            val accepted = when (ingressKind) {
                IngressKind.External -> state == IngressState.Accepting
                IngressKind.Internal -> state == IngressState.Accepting || state == IngressState.Draining
            }
            if (!accepted) {
                throw VpnRuntimeActorAdmissionException("runtime actor ingress is closed")
            }
            enqueueLocked(command, reliability)
        }
    }

    private fun enqueueLocked(
        command: Command,
        reliability: VpnRuntimeEventReliability = VpnRuntimeEventReliability.Critical,
    ) {
        check(reliability == VpnRuntimeEventReliability.Critical) {
            "coalescable runtime events require an explicit coalescing admission path"
        }
        val accepted = try {
            mailbox.offer(command, admissionTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw VpnRuntimeActorAdmissionException("runtime actor admission was interrupted")
        }
        if (!accepted) {
            throw VpnRuntimeActorAdmissionException("runtime actor mailbox admission timed out")
        }
    }

    private fun runMailbox() {
        try {
            while (!Thread.currentThread().isInterrupted) {
                when (val command = try {
                    mailbox.take()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }) {
                    is Command.Event -> complete(command.result) {
                        reduceRuntime(publishedState, command.event).also { publishedState = it.state }
                    }
                    is Command.EventFactory -> complete(command.result) {
                        reduceRuntime(publishedState, command.factory(publishedState)).also {
                            publishedState = it.state
                        }
                    }
                    is Command.CompatibilityMutation -> complete(command.result) {
                        command.mutation(publishedState).also { publishedState = it }
                    }
                    is Command.Shutdown -> {
                        command.result.complete(Unit)
                        return
                    }
                }
            }
        } catch (error: Throwable) {
            terminalFailure.compareAndSet(null, error)
            throw error
        } finally {
            ingressState.set(IngressState.Closed)
            failQueuedCommands()
            terminated.countDown()
        }
    }

    private fun failQueuedCommands() {
        val failure = terminalFailure.get()
            ?: VpnRuntimeActorAdmissionException("runtime actor terminated before command completion")
        while (true) {
            when (val command = mailbox.poll() ?: return) {
                is Command.Event -> command.result.completeExceptionally(failure)
                is Command.EventFactory -> command.result.completeExceptionally(failure)
                is Command.CompatibilityMutation -> command.result.completeExceptionally(failure)
                is Command.Shutdown -> command.result.completeExceptionally(failure)
            }
        }
    }

    private fun <T> complete(result: CompletableFuture<T>, action: () -> T) {
        try {
            result.complete(action())
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            result.completeExceptionally(error)
        }
    }

    private fun <T> await(result: CompletableFuture<T>): T = try {
        result.get(responseTimeoutMillis, TimeUnit.MILLISECONDS)
    } catch (error: TimeoutException) {
        throw IllegalStateException("runtime actor did not respond", error)
    }

    private companion object {
        const val DefaultMailboxCapacity = 128
        const val ActorAdmissionTimeoutMillis = 250L
        const val ActorResponseTimeoutMillis = 2_000L

        fun defaultActorThread(runnable: Runnable, name: String): Thread = Thread(runnable, name)
    }
}
