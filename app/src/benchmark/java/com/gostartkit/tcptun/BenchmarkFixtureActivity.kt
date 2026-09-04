package com.tcptun.client

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import kotlin.concurrent.thread

/** Benchmark-build-only fixture. It is absent from debug and release manifests. */
class BenchmarkFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.BENCHMARK)
        val mode = intent.getStringExtra(EXTRA_MODE).orEmpty()
        val count = intent.getIntExtra(EXTRA_COUNT, 0).coerceIn(0, 1_000)
        thread(name = "benchmark-fixture") {
            when (mode) {
                MODE_PROFILES -> seedProfiles(count)
                MODE_VPN -> seedVpnProfile()
                MODE_FLOW -> seedFlowEvents(count)
            }
            runOnUiThread {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .putExtra(EXTRA_DESTINATION, if (mode == MODE_FLOW) "flow" else "profiles"),
                )
                finish()
            }
        }
    }

    private fun seedProfiles(count: Int) {
        val profiles = List(count) { index ->
            AppConfig(
                id = "benchmark-$index",
                name = "Benchmark ${index.toString().padStart(4, '0')}",
                serverHost = "192.0.2.${index % 250 + 1}",
                token = "benchmark-token-$index",
            )
        }
        ProfileStore.save(this, ProfilesState(profiles)).getOrThrow()
    }

    private fun seedVpnProfile() {
        val profile = AppConfig(
            id = "benchmark-vpn",
            name = "Benchmark VPN",
            serverHost = "192.0.2.1",
            token = "benchmark-token",
        )
        ProfileStore.save(this, ProfilesState(listOf(profile))).getOrThrow()
        RuntimeSettingsRepository.write(this, RuntimeSettings(powerSavingMode = true, socksListenAll = false))
    }

    private fun seedFlowEvents(count: Int) {
        TcptunState.setFlowAnalysisApp("com.tcptun.client")
        TcptunState.clearFlowEvents()
        val epoch = TcptunState.beginBridgeSession()
        repeat(count) { index ->
            TcptunState.applyBridgeFlowEvent(
                epoch,
                """{"session_id":1,"sequence":${index + 1},"dropped_events":0,"timestamp_ms":$index,"type":"connected","network":"tcp","source":"10.0.0.2:1234","destination":"api$index.example.com:443","domain":"api$index.example.com","port":443,"outbound_tag":"direct","app":{"id":"com.tcptun.client","platform":"android"}}""",
            )
        }
        TcptunState.publishFlowEventsNow()
    }

    companion object {
        const val EXTRA_MODE = "benchmarkMode"
        const val EXTRA_COUNT = "benchmarkCount"
        const val EXTRA_DESTINATION = "benchmarkDestination"
        const val MODE_PROFILES = "profiles"
        const val MODE_VPN = "vpn"
        const val MODE_FLOW = "flow"
    }
}
