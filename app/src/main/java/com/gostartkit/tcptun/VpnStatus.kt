package com.tcptun.client

/** Application-owned VPN lifecycle state. Bridge wire states remain strings at the adapter boundary. */
enum class VpnStatus(val displayName: String) {
    Stopped("Stopped"),
    Starting("Starting"),
    Running("Running"),
    Stopping("Stopping"),
    Error("Error"),
    ;

    val isActive: Boolean
        get() = this == Starting || this == Running || this == Stopping

    val isTransitioning: Boolean
        get() = this == Starting || this == Stopping

    val isTerminal: Boolean
        get() = this == Stopped || this == Error

    override fun toString(): String = displayName
}
