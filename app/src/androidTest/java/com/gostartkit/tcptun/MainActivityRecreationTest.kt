package com.tcptun.client

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityRecreationTest {
    @Test
    fun credentialBearingDeepLinkIsRemovedBeforeActivityRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = "activity-recreation-secret-token"
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("vless://$token@recreation-secret.example:443"),
            context,
            MainActivity::class.java,
        )

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse(activity.intent.toUri(0).contains(token))
                assertFalse(activity.intent.toUri(0).contains("recreation-secret.example"))
            }
            scenario.recreate()
        }
    }
}
