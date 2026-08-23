package com.tcptun.client

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/** Owns ConnectivityManager callback registration and ranked network selection. */
internal class UnderlyingNetworkCoordinator(
    private val connectivity: () -> ConnectivityManager,
    private val canHandleCallback: () -> Boolean,
    private val onSelectionChanged: (Network?, RankedSelectionClaim<Network>, String) -> Unit,
    private val log: (String) -> Unit,
) {
    private val registrationLock = Any()
    private val callbackEpochGate = CallbackEpochGate()
    private val selection = RankedSelectionTracker<Network>()
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var callbackEpoch = 0L
    private var registered = false

    fun register() {
        synchronized(registrationLock) {
            if (registered || !canHandleCallback()) return
            val epoch = callbackEpochGate.activateNext()
            val networkCallback = createCallback(epoch)
            callback = networkCallback
            callbackEpoch = epoch
            try {
                connectivity().registerNetworkCallback(underlyingNetworkRequest(), networkCallback)
                registered = true
                log("underlying network callback registered")
            } catch (error: Throwable) {
                if (error.isFatalProcessError()) throw error
                registered = false
                callbackEpochGate.invalidate(epoch)
                if (callback === networkCallback) {
                    callback = null
                    callbackEpoch = 0L
                }
                log("underlying network callback unavailable: ${failureDescription(error)}")
            }
        }
    }

    fun unregister(): Boolean {
        val networkCallback = synchronized(registrationLock) {
            if (!registered) return false
            registered = false
            val registeredCallback = callback
            callback = null
            callbackEpochGate.invalidate(callbackEpoch)
            callbackEpoch = 0L
            selection.clear()
            registeredCallback
        }
        if (networkCallback != null) {
            try {
                connectivity().unregisterNetworkCallback(networkCallback)
            } catch (error: Throwable) {
                if (error.isFatalProcessError()) throw error
                log("underlying network callback unregister failed: ${failureDescription(error)}")
            }
        }
        return true
    }

    /** Rebinds the selected network after the Bridge creates a replacement epoch. */
    fun republishCurrent(reason: String) {
        if (!canHandleCallback()) return
        val claim = selection.currentClaim() ?: return
        onSelectionChanged(claim.value, claim, reason)
    }

    private fun createCallback(epoch: Long): ConnectivityManager.NetworkCallback {
        return object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runCallback(epoch, "available") {
                    connectivity().getNetworkCapabilities(network)?.let { capabilities ->
                        update(network, capabilities)
                    }
                }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                runCallback(epoch, "capabilities changed") {
                    update(network, capabilities)
                }
            }

            override fun onLost(network: Network) {
                runCallback(epoch, "lost") {
                    publish(selection.remove(network), "underlying network lost")
                }
            }
        }
    }

    private fun runCallback(epoch: Long, event: String, action: () -> Unit) {
        callbackEpochGate.runIfActive(epoch) callback@{
            if (!canHandleCallback()) return@callback
            try {
                action()
            } catch (error: Throwable) {
                if (error.isFatalProcessError()) throw error
                if (canHandleCallback()) {
                    log("underlying network $event callback failed: ${failureDescription(error)}")
                }
            }
        }
    }

    private fun update(network: Network, capabilities: NetworkCapabilities) {
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        ) {
            return
        }
        val selected = selection.update(
            network,
            underlyingNetworkScore(
                validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                ethernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                cellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            ),
        )
        publish(selected, "underlying network changed")
    }

    private fun publish(network: Network?, reason: String) {
        if (!canHandleCallback()) return
        val claim = selection.claim(network) ?: return
        if (!canHandleCallback()) return
        onSelectionChanged(network, claim, reason)
    }

    private fun underlyingNetworkRequest(): NetworkRequest = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()
}
