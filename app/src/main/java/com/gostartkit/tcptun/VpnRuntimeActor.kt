package com.tcptun.client

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

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
) {
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

        data object Shutdown : Command
    }

    private val mailbox = ArrayBlockingQueue<Command>(mailboxCapacity)
    private val accepting = AtomicBoolean(true)

    @Volatile
    private var publishedState = initialState

    private val actorThread = Thread(::runMailbox, threadName).apply {
        isDaemon = true
        start()
    }

    init {
        require(mailboxCapacity > 0) { "runtime actor mailbox capacity must be positive" }
    }

    val state: VpnRuntimeState
        get() = publishedState

    fun send(event: VpnRuntimeEvent): VpnRuntimeDecision {
        val result = CompletableFuture<VpnRuntimeDecision>()
        enqueue(Command.Event(event, result))
        return await(result)
    }

    fun send(factory: (VpnRuntimeState) -> VpnRuntimeEvent): VpnRuntimeDecision {
        val result = CompletableFuture<VpnRuntimeDecision>()
        enqueue(Command.EventFactory(factory, result))
        return await(result)
    }

    /** Temporary bridge for Coordinator events not migrated in this task. */
    fun compatibilityMutation(
        mutation: (VpnRuntimeState) -> VpnRuntimeState,
    ): VpnRuntimeState {
        val result = CompletableFuture<VpnRuntimeState>()
        enqueue(Command.CompatibilityMutation(mutation, result))
        return await(result)
    }

    fun shutdown() {
        if (!accepting.compareAndSet(true, false)) return
        if (!mailbox.offer(Command.Shutdown)) {
            actorThread.interrupt()
        }
    }

    internal fun actorThreadName(): String = actorThread.name

    private fun enqueue(command: Command) {
        if (!accepting.get() || !mailbox.offer(command)) {
            throw RejectedExecutionException("runtime actor mailbox is unavailable")
        }
    }

    private fun runMailbox() {
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
                Command.Shutdown -> return
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
        result.get(ActorResponseTimeoutMillis, TimeUnit.MILLISECONDS)
    } catch (error: TimeoutException) {
        throw IllegalStateException("runtime actor did not respond", error)
    }

    private companion object {
        const val DefaultMailboxCapacity = 128
        const val ActorResponseTimeoutMillis = 2_000L
    }
}
