package com.tcptun.client.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startupAndProfileList() = baselineProfileRule.collect(
        packageName = "com.tcptun.client",
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
    }
}
