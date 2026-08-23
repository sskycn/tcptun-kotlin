package com.tcptun.client

import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicReference

internal interface TcpingBridgePort {
    fun probeOutbound(
        ownership: VpnRuntimeOwnership,
        tag: String,
        host: String,
        port: Int,
        timeoutMillis: Long,
    ): Long
}

internal data class OutboundTcpingRequest(
    val requestId: Long,
    val targetLabel: String,
    val host: String,
    val port: Int,
)

internal data class OutboundTcpingPolicy(
    val attemptTimeoutMillis: Long = 3_000L,
    val profileTimeoutMillis: Long = 20_000L,
    val batchTimeoutMillis: Long = 60_000L,
) {
    init {
        require(attemptTimeoutMillis > 0L) { "TCPing attempt timeout must be positive" }
        require(profileTimeoutMillis > 0L) { "TCPing profile timeout must be positive" }
        require(batchTimeoutMillis > 0L) { "TCPing batch timeout must be positive" }
    }
}

/** Publication boundary used to keep request and runtime ownership checks together. */
internal interface OutboundTcpingStatePort {
    fun isCurrent(requestId: Long): Boolean
    fun isLatest(requestId: Long): Boolean
    fun beginStep(requestId: Long, index: Int, total: Int, profileName: String)
    fun completeStep(requestId: Long, result: TcpingLinkResult)
    fun finish(requestId: Long)
    fun fail(requestId: Long, error: String)
    fun log(message: String)
}

/**
 * Owns the outbound TCPing control plane. It observes one immutable runtime
 * ownership and plan snapshot; it cannot mutate VPN lifecycle or native state.
 */
internal class OutboundTcpingRuntime(
    private val bridgePort: TcpingBridgePort,
    private val currentOwnership: () -> VpnRuntimeOwnership?,
    private val isOwnershipCurrent: (VpnRuntimeOwnership) -> Boolean,
    private val currentPlan: () -> ProfileRunPlan?,
    private val connectionsReady: () -> Boolean,
    private val publishIfOwned: (VpnRuntimeOwnership, () -> Unit) -> Boolean,
    private val publishSessionChanged: (VpnRuntimeOwnership, Long) -> Unit,
    private val state: OutboundTcpingStatePort,
    private val onMemberProbeRequested: (String, Long) -> Unit,
    private val executor: ExecutorService = newBoundedLifecycleExecutor(
        threadName = "TcptunTcping",
        queueCapacity = MaxExecutorQueueCapacity,
    ),
    private val policy: OutboundTcpingPolicy = OutboundTcpingPolicy(),
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val pause: (Long) -> Unit = Thread::sleep,
) {
    private class RequestClaim(
        val request: OutboundTcpingRequest,
        val ownership: VpnRuntimeOwnership,
        val plan: ProfileRunPlan,
    )

    private val currentClaim = AtomicReference<RequestClaim?>()

    fun request(request: OutboundTcpingRequest): Boolean {
        if (!request.isValid()) {
            if (request.requestId > 0L && state.isCurrent(request.requestId)) {
                state.fail(request.requestId, "invalid TCPing request")
            }
            return false
        }
        val ownership = currentOwnership()
        val plan = currentPlan()
        if (
            ownership == null ||
            plan == null ||
            plan.activeProfiles.isEmpty() ||
            !connectionsReady()
        ) {
            if (state.isCurrent(request.requestId)) {
                state.fail(request.requestId, "connections are still starting")
            }
            return false
        }
        val claim = RequestClaim(request.copy(host = request.host.trim()), ownership, plan)
        currentClaim.set(claim)
        return executeCrashGuarded(
            executor = executor,
            taskName = "TCPing",
            onFailure = { error ->
                publish(claim) {
                    state.fail(request.requestId, failureDescription(error))
                }
            },
        ) {
            run(claim)
        }
    }

    fun shutdown() {
        currentClaim.set(null)
        executor.shutdownNow()
    }

    private fun run(claim: RequestClaim) {
        if (!requireActiveSession(claim)) return
        val results = mutableListOf<TcpingLinkResult>()
        val profiles = claim.plan.activeProfiles
        val batchDeadline = deadlineAfter(policy.batchTimeoutMillis)
        for ((index, profile) in profiles.withIndex()) {
            if (!requireActiveSession(claim)) return
            if (!publish(claim) {
                    state.beginStep(
                        requestId = claim.request.requestId,
                        index = index + 1,
                        total = profiles.size,
                        profileName = profile.name,
                    )
                }
            ) {
                requireActiveSession(claim)
                return
            }
            val remainingBatchMillis = batchDeadline - nowMillis()
            val probe = if (remainingBatchMillis <= 0L) {
                Result.failure(IllegalStateException("overall TCPing deadline elapsed"))
            } else {
                probeProfile(claim, profile, remainingBatchMillis)
            }
            if (!requireActiveSession(claim)) return
            val result = probe.fold(
                onSuccess = { elapsedMillis ->
                    TcpingLinkResult(profile.name, elapsedMs = elapsedMillis)
                },
                onFailure = { error ->
                    TcpingLinkResult(
                        profileName = profile.name,
                        error = error.message ?: error.javaClass.simpleName,
                    )
                },
            )
            results += result
            if (!publish(claim) {
                    state.completeStep(claim.request.requestId, result)
                    val detail = result.elapsedMs?.let { "${it}ms" } ?: "failed: ${result.error}"
                    state.log("TCPing ${claim.request.targetLabel} via ${profile.name}: $detail")
                }
            ) {
                requireActiveSession(claim)
                return
            }
        }
        if (!publish(claim) { state.finish(claim.request.requestId) }) {
            requireActiveSession(claim)
            return
        }
        val failures = results.count { it.elapsedMs == null }
        if (failures > 0 && isLatestOwnedClaim(claim)) {
            onMemberProbeRequested("TCPing failed on $failures connection(s)", 0L)
        }
        currentClaim.compareAndSet(claim, null)
    }

    private fun probeProfile(
        claim: RequestClaim,
        profile: AppConfig,
        remainingBatchMillis: Long,
    ): Result<Long> = try {
        runRecoverableCatching {
            probeOutboundWithTransientQuicRetry(
                totalTimeoutMillis = minOf(policy.profileTimeoutMillis, remainingBatchMillis),
                attemptTimeoutMillis = policy.attemptTimeoutMillis,
                isActive = { isActive(claim) },
                nowMillis = nowMillis,
                pause = pause,
            ) { timeoutMillis ->
                checkActive(claim)
                bridgePort.probeOutbound(
                    ownership = claim.ownership,
                    tag = profile.runtimeOutboundTag(),
                    host = claim.request.host,
                    port = claim.request.port,
                    timeoutMillis = timeoutMillis,
                ).also { checkActive(claim) }
            }
        }
    } catch (_: CancellationException) {
        Result.failure(CancellationException("VPN session changed"))
    }

    private fun requireActiveSession(claim: RequestClaim): Boolean {
        if (!isClaimCurrent(claim)) return false
        if (isOwnershipCurrent(claim.ownership)) return true
        publishSessionChanged(claim.ownership, claim.request.requestId)
        currentClaim.compareAndSet(claim, null)
        return false
    }

    private fun checkActive(claim: RequestClaim) {
        if (!isActive(claim)) throw CancellationException("VPN session changed")
    }

    private fun isActive(claim: RequestClaim): Boolean =
        isClaimCurrent(claim) && isOwnershipCurrent(claim.ownership)

    private fun isClaimCurrent(claim: RequestClaim): Boolean =
        currentClaim.get() === claim && state.isCurrent(claim.request.requestId)

    private fun isLatestOwnedClaim(claim: RequestClaim): Boolean =
        currentClaim.get() === claim &&
            state.isLatest(claim.request.requestId) &&
            isOwnershipCurrent(claim.ownership)

    private fun publish(claim: RequestClaim, action: () -> Unit): Boolean {
        if (!isClaimCurrent(claim) || !isOwnershipCurrent(claim.ownership)) return false
        return publishIfOwned(claim.ownership) {
            if (isClaimCurrent(claim)) action()
        }
    }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        val now = nowMillis()
        return if (now > Long.MAX_VALUE - timeoutMillis) Long.MAX_VALUE else now + timeoutMillis
    }

    private fun OutboundTcpingRequest.isValid(): Boolean =
        requestId > 0L &&
            targetLabel.isNotBlank() &&
            host.trim().isNotBlank() &&
            port in 1..65_535

    private companion object {
        const val MaxExecutorQueueCapacity = 2
    }
}
