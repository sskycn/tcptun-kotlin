package com.tcptun.client

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityRecreationTest {
    @Test
    fun credentialBearingDeepLinkIsRemovedBeforeActivityRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val token = "activity-recreation-secret-token"

        val activity = instrumentation.startActivitySync(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("vless://$token@recreation-secret.example:443"),
                context,
                MainActivity::class.java,
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        try {
            instrumentation.runOnMainSync {
                assertFalse(activity.intent.toUri(0).contains(token))
                assertFalse(activity.intent.toUri(0).contains("recreation-secret.example"))
                activity.recreate()
            }
            val recreated = waitForRecreatedActivity(instrumentation, activity)
            instrumentation.runOnMainSync {
                assertNotSame(activity, recreated)
                assertFalse(recreated.intent.toUri(0).contains(token))
                assertFalse(recreated.intent.toUri(0).contains("recreation-secret.example"))
                recreated.finish()
            }
        } finally {
            if (!activity.isDestroyed) {
                instrumentation.runOnMainSync { activity.finish() }
            }
        }
    }

    private fun waitForRecreatedActivity(
        instrumentation: android.app.Instrumentation,
        original: MainActivity,
    ): MainActivity {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            var recreated: MainActivity? = null
            instrumentation.runOnMainSync {
                recreated = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>()
                    .firstOrNull { it !== original }
            }
            recreated?.let { return it }
            Thread.sleep(50)
        }
        throw AssertionError("MainActivity was not recreated into RESUMED state")
    }
}
