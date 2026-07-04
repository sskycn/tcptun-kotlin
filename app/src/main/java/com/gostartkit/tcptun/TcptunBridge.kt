package com.tcptun.client

import java.lang.reflect.Proxy

interface TcptunBridge {
    fun start(configJson: String)
    fun stop()
    fun status(): String
    fun setLogCallback(onLog: (String) -> Unit)
    fun setStatusCallback(onStatus: (String) -> Unit)
    fun clearStatusCallback()
    fun setSocketProtector(onProtect: (Int) -> Boolean)
    fun clearSocketProtector()
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

    override fun setStatusCallback(onStatus: (String) -> Unit) {
        val callbackClass = try {
            Class.forName("androidbridge.StatusCallback")
        } catch (err: ClassNotFoundException) {
            TcptunState.appendLog("androidbridge StatusCallback is not available: ${err.message}")
            return
        }
        val callback = Proxy.newProxyInstance(
            callbackClass.classLoader,
            arrayOf(callbackClass),
        ) { _, method, args ->
            if (method.name.equals("onStatus", ignoreCase = true) && !args.isNullOrEmpty()) {
                onStatus(args[0].toString())
            }
            null
        }
        invokeStatic(
            "setStatusCallback",
            "SetStatusCallback",
            parameterTypes = arrayOf(callbackClass),
            callback,
        )
    }

    override fun clearStatusCallback() {
        val callbackClass = try {
            Class.forName("androidbridge.StatusCallback")
        } catch (_: ClassNotFoundException) {
            return
        }
        invokeStatic(
            "setStatusCallback",
            "SetStatusCallback",
            parameterTypes = arrayOf(callbackClass),
            null,
        )
    }

    override fun setSocketProtector(onProtect: (Int) -> Boolean) {
        val protectorClass = try {
            Class.forName("androidbridge.SocketProtector")
        } catch (err: ClassNotFoundException) {
            TcptunState.appendLog("androidbridge SocketProtector is not available: ${err.message}")
            return
        }
        val protector = Proxy.newProxyInstance(
            protectorClass.classLoader,
            arrayOf(protectorClass),
        ) { _, method, args ->
            if (method.name.equals("protect", ignoreCase = true) && !args.isNullOrEmpty()) {
                val fd = (args[0] as? Number)?.toInt() ?: return@newProxyInstance false
                return@newProxyInstance onProtect(fd)
            }
            false
        }
        invokeStatic(
            "setSocketProtector",
            "SetSocketProtector",
            parameterTypes = arrayOf(protectorClass),
            protector,
        )
    }

    override fun clearSocketProtector() {
        val protectorClass = try {
            Class.forName("androidbridge.SocketProtector")
        } catch (_: ClassNotFoundException) {
            return
        }
        invokeStatic(
            "setSocketProtector",
            "SetSocketProtector",
            parameterTypes = arrayOf(protectorClass),
            null,
        )
    }

    private fun invokeStatic(
        lowerName: String,
        upperName: String,
        parameterTypes: Array<Class<*>> = emptyArray(),
        vararg args: Any?,
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
