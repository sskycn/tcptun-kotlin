package com.tcptun.client

import java.lang.reflect.Proxy
import org.json.JSONArray

/** Validates through the gomobile core without creating or starting a service. */
internal fun validateTcptunConfig(configJson: String) {
    try {
        val bridgeClass = Class.forName("androidbridge.Androidbridge")
        bridgeClass.getMethod("validateConfig", String::class.java).invoke(null, configJson)
    } catch (err: ReflectiveOperationException) {
        val cause = err.cause ?: err
        throw IllegalArgumentException(cause.message ?: cause.javaClass.name, cause)
    } catch (err: LinkageError) {
        throw IllegalStateException(
            "androidbridge validation API is unavailable. Rebuild app/libs/androidbridge.aar.",
            err,
        )
    }
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
    fun close()
    fun status(): String
    fun statusJson(): String
    fun setLogCallback(onLog: (String) -> Unit)
    fun setStatusCallback(onStatus: (String) -> Unit)
    fun clearStatusCallback()
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
    private val bridgeClass: Class<*> by lazy {
        try {
            Class.forName("androidbridge.Androidbridge")
        } catch (err: ClassNotFoundException) {
            throw IllegalStateException(
                "androidbridge AAR is missing. Run ./scripts/build-androidbridge.sh, then rebuild and reinstall the app.",
                err,
            )
        }
    }
    private val engine: Any by lazy {
        try {
            bridgeClass.getMethod("newEngine").invoke(null)
                ?: throw IllegalStateException("androidbridge.NewEngine returned null")
        } catch (err: ReflectiveOperationException) {
            val cause = err.cause ?: err
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

    override fun configure(configJson: String) {
        invokeEngine("configure", arrayOf(String::class.java), configJson)
    }

    override fun setTun(fd: Int, mtu: Int) {
        invokeEngine(
            "setTun",
            arrayOf(Long::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!),
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
            arrayOf(String::class.java, Boolean::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!),
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
        val method = engine.javaClass.methods.singleOrNull {
            it.name == methodName && it.parameterTypes.size == 4
        } ?: throw IllegalStateException(
            "androidbridge.Engine.$methodName is unavailable. Rebuild app/libs/androidbridge.aar.",
        )
        val portArgument: Any = when (method.parameterTypes[2]) {
            Int::class.javaPrimitiveType, Int::class.javaObjectType -> port
            else -> port.toLong()
        }
        val timeoutArgument: Any = when (method.parameterTypes[3]) {
            Int::class.javaPrimitiveType, Int::class.javaObjectType -> timeoutMillis.toInt()
            else -> timeoutMillis
        }
        return try {
            (method.invoke(engine, tag, host, portArgument, timeoutArgument) as? Number)?.toLong()
                ?: throw IllegalStateException("androidbridge.Engine.$methodName returned no elapsed time")
        } catch (err: ReflectiveOperationException) {
            val cause = err.cause ?: err
            throw IllegalStateException(cause.message ?: cause.javaClass.name, cause)
        }
    }

    override fun outboundsStatusJson(): String = (invokeEngine("outboundsStatusJSON") as? String).orEmpty()

    override fun stop() {
        invokeEngine("stop")
    }

    override fun close() {
        invokeEngine("close")
        logCallback = null
        statusCallback = null
        socketProtector = null
        appIdentityProvider = null
        flowCallback = null
    }

    override fun status(): String = (invokeEngine("status") as? String).orEmpty()

    override fun statusJson(): String = (invokeEngine("statusJSON") as? String).orEmpty()

    override fun setLogCallback(onLog: (String) -> Unit) {
        val callbackClass = callbackClass("androidbridge.LogCallback") ?: return
        val callback = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { _, method, args ->
            if (method.name.equals("onLog", ignoreCase = true) && !args.isNullOrEmpty()) {
                onLog(args[0].toString())
            }
            null
        }
        logCallback = callback
        invokeEngine("setLogCallback", arrayOf(callbackClass), callback)
    }

    override fun setStatusCallback(onStatus: (String) -> Unit) {
        val callbackClass = callbackClass("androidbridge.StatusCallback") ?: return
        val callback = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { _, method, args ->
            if (method.name.equals("onStatus", ignoreCase = true) && !args.isNullOrEmpty()) {
                onStatus(args[0].toString())
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

    override fun setSocketProtector(onProtect: (Int) -> Boolean) {
        val callbackClass = callbackClass("androidbridge.SocketProtector") ?: return
        val callback = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { _, method, args ->
            if (method.name.equals("protect", ignoreCase = true) && !args.isNullOrEmpty()) {
                val fd = (args[0] as? Number)?.toInt() ?: return@newProxyInstance false
                return@newProxyInstance onProtect(fd)
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
        val callback = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { _, method, args ->
            if (method.name.equals("identifyApp", ignoreCase = true) && !args.isNullOrEmpty()) {
                return@newProxyInstance onIdentify(args[0].toString()).orEmpty()
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
        val callback = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { _, method, args ->
            if (method.name.equals("onFlow", ignoreCase = true) && !args.isNullOrEmpty()) {
                onFlow(args[0].toString())
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
        } catch (err: ClassNotFoundException) {
            TcptunState.appendLog("$name is not available: ${err.message}")
            null
        }
    }

    private fun clearCallback(className: String, methodName: String) {
        val callbackClass = try {
            Class.forName(className)
        } catch (_: ClassNotFoundException) {
            return
        }
        invokeEngine(methodName, arrayOf(callbackClass), null)
    }

    private fun invokeEngine(
        name: String,
        parameterTypes: Array<Class<*>> = emptyArray(),
        vararg args: Any?,
    ): Any? {
        return try {
            engine.javaClass.getMethod(name, *parameterTypes).invoke(engine, *args)
        } catch (err: ReflectiveOperationException) {
            val cause = err.cause ?: err
            throw IllegalStateException(cause.message ?: cause.javaClass.name, cause)
        }
    }
}
