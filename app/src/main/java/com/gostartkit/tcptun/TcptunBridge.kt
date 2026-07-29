package com.tcptun.client

import java.lang.reflect.Proxy
import java.lang.reflect.Method
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import org.json.JSONArray

internal fun Throwable.isFatalProcessError(): Boolean =
    this is VirtualMachineError || this is ThreadDeath

internal fun failureDescription(error: Throwable): String =
    error.message?.take(4_096)?.trim().takeUnless { it.isNullOrEmpty() } ?: error.javaClass.simpleName

internal inline fun <T> runRecoverableCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (error: Throwable) {
    if (error is CancellationException) throw error
    if (error.isFatalProcessError()) throw error
    Result.failure(error)
}

private fun defaultCallbackResult(returnType: Class<*>): Any? = when (returnType) {
    java.lang.Boolean.TYPE -> false
    java.lang.Byte.TYPE -> 0.toByte()
    java.lang.Short.TYPE -> 0.toShort()
    java.lang.Integer.TYPE -> 0
    java.lang.Long.TYPE -> 0L
    java.lang.Float.TYPE -> 0f
    java.lang.Double.TYPE -> 0.0
    java.lang.Character.TYPE -> '\u0000'
    else -> null
}

/** Prevents exceptions in Java proxies from crossing the gomobile/JNI callback boundary. */
internal fun createSafeBridgeCallbackProxy(
    callbackClass: Class<*>,
    label: String,
    invocation: (Method, Array<out Any?>?) -> Any?,
): Any = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { proxy, method, args ->
    if (method.declaringClass == Any::class.java) {
        return@newProxyInstance when (method.name) {
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "SafeBridgeCallback($label)"
            else -> defaultCallbackResult(method.returnType)
        }
    }
    try {
        invocation(method, args)
    } catch (error: Throwable) {
        if (error.isFatalProcessError()) throw error
        try {
            TcptunState.appendLog("$label callback failed: ${failureDescription(error)}")
        } catch (loggingError: Throwable) {
            if (loggingError.isFatalProcessError()) throw loggingError
        }
        defaultCallbackResult(method.returnType)
    }
}

/** Validates through the gomobile core without creating or starting a service. */
internal fun validateTcptunConfig(configJson: String) {
    try {
        val bridgeClass = Class.forName("androidbridge.Androidbridge")
        bridgeClass.getMethod("validateConfig", String::class.java).invoke(null, configJson)
    } catch (error: Throwable) {
        if (error.isFatalProcessError()) throw error
        val cause = error.cause ?: error
        if (cause.isFatalProcessError()) throw cause
        if (error is ReflectiveOperationException && error.cause != null) {
            throw IllegalArgumentException(failureDescription(cause), cause)
        }
        throw IllegalStateException("androidbridge validation API is unavailable. Rebuild app/libs/androidbridge.aar.", cause)
    }
}

internal data class TcptunCoreIdentity(
    val version: String,
    val buildId: String,
)

/** Reads the identity compiled into the loaded AAR without owning an Engine. */
internal fun tcptunCoreIdentity(): TcptunCoreIdentity {
    val bridgeClass = runRecoverableCatching { Class.forName("androidbridge.Androidbridge") }
        .getOrNull()
        ?: return TcptunCoreIdentity(version = "", buildId = "")

    fun read(methodName: String): String = runRecoverableCatching {
        bridgeClass.getMethod(methodName).invoke(null)?.toString()?.trim().orEmpty()
    }.getOrDefault("")

    return TcptunCoreIdentity(
        version = read("coreVersion"),
        buildId = read("coreBuildID"),
    )
}

/** Optional status-event names accepted by Engine.RegisterEvent / UnregisterEvent. */
internal object TcptunBridgeEvents {
    const val RemoteEndpointsChanged = "REMOTE_ENDPOINTS_CHANGED"
    const val RuntimeReconnecting = "RUNTIME_RECONNECTING"
    const val RuntimeConnectionIssue = "RUNTIME_CONNECTION_ISSUE"

    /** Telemetry Android always opts into while a VPN session is live. */
    val DefaultRegistered: List<String> = listOf(
        RemoteEndpointsChanged,
        RuntimeReconnecting,
        RuntimeConnectionIssue,
    )
}

interface TcptunBridge {
    fun configure(configJson: String)
    fun setTun(fd: Int, mtu: Int)
    fun start(disabledOutboundTags: List<String>): Long
    fun startOutbound(tag: String)
    fun stopOutbound(tag: String, force: Boolean, timeoutMillis: Long)
    fun probeOutbound(tag: String, host: String, port: Int, timeoutMillis: Long): Long
    fun probeOutboundHealth(tag: String, host: String, port: Int, timeoutMillis: Long): Long
    fun outboundsStatusJson(): String
    fun stop()
    fun sessionId(): Long
    fun waitStopped(sessionId: Long, timeoutMillis: Long)
    fun close()
    fun status(): String
    fun statusJson(): String
    fun setLogLevel(level: String)
    fun logLevel(): String
    fun setLogCallback(onLog: (String) -> Unit)
    fun clearLogCallback()
    fun setStatusCallback(onStatus: (String) -> Unit)
    fun clearStatusCallback()
    fun registerEvent(event: String)
    fun unregisterEvent(event: String)
    fun setSocketProtector(onProtect: (Int) -> Boolean)
    fun clearSocketProtector()
    fun setAppIdentityProvider(onIdentify: (String) -> String?)
    fun clearAppIdentityProvider()
    fun setFlowAnalysisApp(packageName: String)
    fun setFlowCallback(onFlow: (String) -> Unit)
    fun clearFlowCallback()
}

/** Owns exactly one gomobile Engine for the lifetime of one VpnService. */
class ReflectionTcptunBridge : TcptunBridge {
    private val engineLock = ReentrantReadWriteLock()
    @Volatile private var closed = false
    private val bridgeClassDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            Class.forName("androidbridge.Androidbridge")
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            throw IllegalStateException(
                "androidbridge AAR is missing. Run ./scripts/build-androidbridge.sh, then rebuild and reinstall the app.",
                error,
            )
        }
    }
    private val bridgeClass: Class<*> get() = bridgeClassDelegate.value
    private val engineDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            bridgeClass.getMethod("newEngine").invoke(null)
                ?: throw IllegalStateException("androidbridge.NewEngine returned null")
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            val cause = error.cause ?: error
            if (cause.isFatalProcessError()) throw cause
            throw IllegalStateException(
                "androidbridge Engine API is missing. Rebuild app/libs/androidbridge.aar from the current tcptun-go checkout.",
                cause,
            )
        }
    }

    // Keep Java proxies strongly reachable for as long as Go can call them.
    private var logCallback: Any? = null
    private var statusCallback: Any? = null
    private var socketProtector: Any? = null
    private var appIdentityProvider: Any? = null
    private var flowCallback: Any? = null

    private inline fun <T> withOpenEngine(action: (Any) -> T): T = engineLock.read {
        check(!closed) { "androidbridge Engine is already closed" }
        action(engineDelegate.value)
    }

    override fun configure(configJson: String) {
        invokeEngine("configure", arrayOf(String::class.java), configJson)
    }

    override fun setTun(fd: Int, mtu: Int) {
        invokeEngine(
            "setTun",
            arrayOf(java.lang.Long.TYPE, java.lang.Long.TYPE),
            fd.toLong(),
            mtu.toLong(),
        )
    }

    override fun start(disabledOutboundTags: List<String>): Long {
        val disabledTagsJson = JSONArray().apply { disabledOutboundTags.forEach(::put) }.toString()
        return (
            invokeEngine(
                "startConfiguredSessionWithDisabledOutbounds",
                arrayOf(String::class.java),
                disabledTagsJson,
            ) as? Number
        )?.toLong()
            ?: throw IllegalStateException(
                "androidbridge.Engine.startConfiguredSessionWithDisabledOutbounds returned no session ID",
            )
    }

    override fun startOutbound(tag: String) {
        invokeEngine("startOutbound", arrayOf(String::class.java), tag)
    }

    override fun stopOutbound(tag: String, force: Boolean, timeoutMillis: Long) {
        invokeEngine(
            "stopOutbound",
            arrayOf(String::class.java, java.lang.Boolean.TYPE, java.lang.Long.TYPE),
            tag,
            force,
            timeoutMillis,
        )
    }

    override fun probeOutbound(tag: String, host: String, port: Int, timeoutMillis: Long): Long {
        return invokeProbe("probeOutbound", tag, host, port, timeoutMillis)
    }

    override fun probeOutboundHealth(tag: String, host: String, port: Int, timeoutMillis: Long): Long {
        return invokeProbe("probeOutboundHealth", tag, host, port, timeoutMillis)
    }

    private fun invokeProbe(methodName: String, tag: String, host: String, port: Int, timeoutMillis: Long): Long {
        return withOpenEngine { engine ->
            val method = engine.javaClass.methods.singleOrNull {
                it.name == methodName && it.parameterTypes.size == 4
            } ?: throw IllegalStateException(
                "androidbridge.Engine.$methodName is unavailable. Rebuild app/libs/androidbridge.aar.",
            )
            val portArgument: Any = when (method.parameterTypes[2]) {
                java.lang.Integer.TYPE, Int::class.javaObjectType -> port
                else -> port.toLong()
            }
            val timeoutArgument: Any = when (method.parameterTypes[3]) {
                java.lang.Integer.TYPE, Int::class.javaObjectType -> timeoutMillis.toInt()
                else -> timeoutMillis
            }
            invokeMethod(engine, method, tag, host, portArgument, timeoutArgument)
                .let { it as? Number }
                ?.toLong()
                ?: throw IllegalStateException("androidbridge.Engine.$methodName returned no elapsed time")
        }
    }

    override fun outboundsStatusJson(): String = (invokeEngine("outboundsStatusJSON") as? String).orEmpty()

    override fun stop() {
        invokeEngine("stop")
    }

    override fun sessionId(): Long {
        return (invokeEngine("sessionID") as? Number)?.toLong()
            ?: throw IllegalStateException("androidbridge.Engine.sessionID returned no session ID")
    }

    override fun waitStopped(sessionId: Long, timeoutMillis: Long) {
        invokeEngine(
            "waitStopped",
            arrayOf(java.lang.Long.TYPE, java.lang.Long.TYPE),
            sessionId,
            timeoutMillis,
        )
    }

    override fun close() {
        engineLock.write {
            if (closed) return
            if (engineDelegate.isInitialized()) {
                try {
                    invokeMethod(engineDelegate.value, engineDelegate.value.javaClass.getMethod("close"))
                } catch (error: Throwable) {
                    // tcptun-go deliberately retains host callbacks when Close
                    // cannot confirm that the runtime stopped. Keep both this
                    // wrapper and its Java proxies alive so a later teardown
                    // attempt remains safe and possible.
                    throw error
                }
            }
            closed = true
            logCallback = null
            statusCallback = null
            socketProtector = null
            appIdentityProvider = null
            flowCallback = null
        }
    }

    override fun status(): String = (invokeEngine("status") as? String).orEmpty()

    override fun statusJson(): String = (invokeEngine("statusJSON") as? String).orEmpty()

    override fun setLogLevel(level: String) {
        invokeEngine("setLogLevel", arrayOf(String::class.java), level.trim())
    }

    override fun logLevel(): String = (invokeEngine("logLevel") as? String).orEmpty()

    override fun setLogCallback(onLog: (String) -> Unit) {
        val callbackClass = callbackClass("androidbridge.LogCallback") ?: return
        val callback = createSafeBridgeCallbackProxy(callbackClass, "tcptun log") { method, args ->
            if (method.name.equals("onLog", ignoreCase = true) && !args.isNullOrEmpty()) {
                onLog(args[0]?.toString().orEmpty())
            }
            null
        }
        logCallback = callback
        invokeEngine("setLogCallback", arrayOf(callbackClass), callback)
    }

    override fun clearLogCallback() {
        clearCallback("androidbridge.LogCallback", "setLogCallback")
        logCallback = null
    }

    override fun setStatusCallback(onStatus: (String) -> Unit) {
        val callbackClass = callbackClass("androidbridge.StatusCallback") ?: return
        val callback = createSafeBridgeCallbackProxy(callbackClass, "tcptun status") { method, args ->
            if (method.name.equals("onStatus", ignoreCase = true) && !args.isNullOrEmpty()) {
                onStatus(args[0]?.toString().orEmpty())
            }
            null
        }
        statusCallback = callback
        invokeEngine("setStatusCallback", arrayOf(callbackClass), callback)
    }

    override fun clearStatusCallback() {
        clearCallback("androidbridge.StatusCallback", "setStatusCallback")
        statusCallback = null
    }

    override fun registerEvent(event: String) {
        invokeEngine("registerEvent", arrayOf(String::class.java), event.trim())
    }

    override fun unregisterEvent(event: String) {
        invokeEngine("unregisterEvent", arrayOf(String::class.java), event.trim())
    }

    override fun setSocketProtector(onProtect: (Int) -> Boolean) {
        val callbackClass = callbackClass("androidbridge.SocketProtector") ?: return
        val callback = createSafeBridgeCallbackProxy(callbackClass, "socket protector") { method, args ->
            if (method.name.equals("protect", ignoreCase = true) && !args.isNullOrEmpty()) {
                val fd = (args[0] as? Number)?.toInt() ?: return@createSafeBridgeCallbackProxy false
                return@createSafeBridgeCallbackProxy onProtect(fd)
            }
            false
        }
        socketProtector = callback
        invokeEngine("setSocketProtector", arrayOf(callbackClass), callback)
    }

    override fun clearSocketProtector() {
        clearCallback("androidbridge.SocketProtector", "setSocketProtector")
        socketProtector = null
    }

    override fun setAppIdentityProvider(onIdentify: (String) -> String?) {
        val callbackClass = callbackClass("androidbridge.AppIdentityProvider") ?: return
        val callback = createSafeBridgeCallbackProxy(callbackClass, "app identity") { method, args ->
            if (method.name.equals("identifyApp", ignoreCase = true) && !args.isNullOrEmpty()) {
                return@createSafeBridgeCallbackProxy onIdentify(args[0]?.toString().orEmpty()).orEmpty()
            }
            ""
        }
        appIdentityProvider = callback
        invokeEngine("setAppIdentityProvider", arrayOf(callbackClass), callback)
    }

    override fun clearAppIdentityProvider() {
        clearCallback("androidbridge.AppIdentityProvider", "setAppIdentityProvider")
        appIdentityProvider = null
    }

    override fun setFlowAnalysisApp(packageName: String) {
        invokeEngine("setFlowAnalysisApp", arrayOf(String::class.java), packageName.trim())
    }

    override fun setFlowCallback(onFlow: (String) -> Unit) {
        val callbackClass = callbackClass("androidbridge.FlowCallback")
            ?: throw IllegalStateException("androidbridge.FlowCallback is unavailable. Rebuild app/libs/androidbridge.aar.")
        val callback = createSafeBridgeCallbackProxy(callbackClass, "flow analysis") { method, args ->
            if (method.name.equals("onFlow", ignoreCase = true) && !args.isNullOrEmpty()) {
                onFlow(args[0]?.toString().orEmpty())
            }
            null
        }
        flowCallback = callback
        invokeEngine("setFlowCallback", arrayOf(callbackClass), callback)
    }

    override fun clearFlowCallback() {
        clearCallback("androidbridge.FlowCallback", "setFlowCallback")
        flowCallback = null
    }

    private fun callbackClass(name: String): Class<*>? {
        return try {
            Class.forName(name)
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            val cause = error.cause
            if (cause != null && cause.isFatalProcessError()) throw cause
            TcptunState.appendLog("$name is not available: ${failureDescription(error)}")
            null
        }
    }

    private fun clearCallback(className: String, methodName: String) {
        val callbackClass = try {
            Class.forName(className)
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            val cause = error.cause
            if (cause != null && cause.isFatalProcessError()) throw cause
            return
        }
        invokeEngine(methodName, arrayOf(callbackClass), null)
    }

    private fun invokeEngine(
        name: String,
        parameterTypes: Array<Class<*>> = emptyArray(),
        vararg args: Any?,
    ): Any? {
        return withOpenEngine { engine ->
            val method = try {
                engine.javaClass.getMethod(name, *parameterTypes)
            } catch (error: Throwable) {
                if (error.isFatalProcessError()) throw error
                throw IllegalStateException(failureDescription(error), error)
            }
            invokeMethod(engine, method, *args)
        }
    }

    private fun invokeMethod(target: Any, method: Method, vararg args: Any?): Any? {
        return try {
            method.invoke(target, *args)
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            val cause = error.cause ?: error
            if (cause.isFatalProcessError()) throw cause
            throw IllegalStateException(failureDescription(cause), cause)
        }
    }
}
