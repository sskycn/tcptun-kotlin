package com.sskycn.tcptun

import java.lang.reflect.Proxy

interface TcptunBridge {
    fun start(configJson: String)
    fun stop()
    fun status(): String
    fun setLogCallback(onLog: (String) -> Unit)
}

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

    override fun start(configJson: String) {
        invokeStatic("start", "Start", parameterTypes = arrayOf(String::class.java), configJson)
    }

    override fun stop() {
        invokeStatic("stop", "Stop")
    }

    override fun status(): String {
        return (invokeStatic("status", "Status") as? String).orEmpty()
    }

    override fun setLogCallback(onLog: (String) -> Unit) {
        val callbackClass = try {
            Class.forName("androidbridge.LogCallback")
        } catch (err: ClassNotFoundException) {
            TcptunState.appendLog("androidbridge LogCallback is not available: ${err.message}")
            return
        }
        val callback = Proxy.newProxyInstance(
            callbackClass.classLoader,
            arrayOf(callbackClass),
        ) { _, method, args ->
            if (method.name.equals("onLog", ignoreCase = true) && !args.isNullOrEmpty()) {
                onLog(args[0].toString())
            }
            null
        }
        invokeStatic(
            "setLogCallback",
            "SetLogCallback",
            parameterTypes = arrayOf(callbackClass),
            callback,
        )
    }

    private fun invokeStatic(
        lowerName: String,
        upperName: String,
        parameterTypes: Array<Class<*>> = emptyArray(),
        vararg args: Any,
    ): Any? {
        val method = try {
            bridgeClass.getMethod(lowerName, *parameterTypes)
        } catch (_: NoSuchMethodException) {
            bridgeClass.getMethod(upperName, *parameterTypes)
        }
        return try {
            method.invoke(null, *args)
        } catch (err: ReflectiveOperationException) {
            val cause = err.cause ?: err
            throw IllegalStateException(cause.message ?: cause.javaClass.name, cause)
        }
    }
}
