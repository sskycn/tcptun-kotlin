package com.tcptun.client

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeAbiContractTest {
    @Test
    fun androidbridgeStaticApiMatchesKotlinContract() {
        val bridge = bridgeClass("androidbridge.Androidbridge")
        val engine = bridgeClass("androidbridge.Engine")

        assertMethod(bridge, "newEngine", engine)
        assertMethod(bridge, "validateConfig", Void.TYPE, String::class.java)
        assertMethod(bridge, "coreVersion", String::class.java)
        assertMethod(bridge, "coreBuildID", String::class.java)
    }

    @Test
    fun engineApiMatchesEveryMethodUsedByKotlin() {
        val engine = bridgeClass("androidbridge.Engine")
        val string = String::class.java
        val long = java.lang.Long.TYPE
        val bool = java.lang.Boolean.TYPE
        val void = Void.TYPE

        listOf(
            MethodContract("configure", void, string),
            MethodContract("setTun", void, long, long),
            MethodContract("startConfiguredSessionWithDisabledOutbounds", long, string),
            MethodContract("startOutbound", void, string),
            MethodContract("stopOutbound", void, string, bool, long),
            MethodContract("switchOutbound", void, string, bool, long),
            MethodContract("probeOutbound", long, string, string, long, long),
            MethodContract("probeOutboundHealth", long, string, string, long, long),
            MethodContract("outboundsStatusJSON", string),
            MethodContract("stop", void),
            MethodContract("abort", void),
            MethodContract("sessionID", long),
            MethodContract("waitStopped", void, long, long),
            MethodContract("close", void),
            MethodContract("status", string),
            MethodContract("statusJSON", string),
            MethodContract("setLogLevel", void, string),
            MethodContract("logLevel", string),
            MethodContract("registerEvent", void, string),
            MethodContract("unregisterEvent", void, string),
            MethodContract("setFlowAnalysisApp", void, string),
            MethodContract("setLogCallback", void, bridgeClass("androidbridge.LogCallback")),
            MethodContract("setStatusCallback", void, bridgeClass("androidbridge.StatusCallback")),
            MethodContract("setSocketProtector", void, bridgeClass("androidbridge.SocketProtector")),
            MethodContract("setAppIdentityProvider", void, bridgeClass("androidbridge.AppIdentityProvider")),
            MethodContract("setFlowCallback", void, bridgeClass("androidbridge.FlowCallback")),
        ).forEach { contract ->
            assertMethod(engine, contract.name, contract.returnType, *contract.parameterTypes)
        }
    }

    @Test
    fun callbackInterfacesMatchNativeCallbackContract() {
        val string = String::class.java
        val void = Void.TYPE
        val long = java.lang.Long.TYPE
        val callbacks = listOf(
            CallbackContract("androidbridge.LogCallback", "onLog", void, string),
            CallbackContract("androidbridge.StatusCallback", "onStatus", void, string),
            CallbackContract("androidbridge.SocketProtector", "protect", java.lang.Boolean.TYPE, long),
            CallbackContract("androidbridge.AppIdentityProvider", "identifyApp", string, string),
            CallbackContract("androidbridge.FlowCallback", "onFlow", void, string),
        )

        callbacks.forEach { contract ->
            val callback = bridgeClass(contract.className)
            assertTrue("${contract.className} must remain an interface", callback.isInterface)
            assertMethod(callback, contract.methodName, contract.returnType, *contract.parameterTypes)
        }
    }

    private fun bridgeClass(name: String): Class<*> =
        Class.forName(name, false, javaClass.classLoader)

    private fun assertMethod(
        owner: Class<*>,
        name: String,
        returnType: Class<*>,
        vararg parameterTypes: Class<*>,
    ) {
        val method = owner.getMethod(name, *parameterTypes)
        assertEquals("${owner.name}.$name return type", returnType, method.returnType)
        assertEquals("${owner.name}.$name parameter count", parameterTypes.size, method.parameterCount)
        assertEquals(parameterTypes.toList(), method.parameterTypes.toList())
        assertTrue("${owner.name}.$name must be public", Modifier.isPublic(method.modifiers))
    }

    private class MethodContract(
        val name: String,
        val returnType: Class<*>,
        vararg val parameters: Class<*>,
    ) {
        val parameterTypes: Array<out Class<*>> = parameters
    }

    private class CallbackContract(
        val className: String,
        val methodName: String,
        val returnType: Class<*>,
        vararg val parameters: Class<*>,
    ) {
        val parameterTypes: Array<out Class<*>> = parameters
    }
}
