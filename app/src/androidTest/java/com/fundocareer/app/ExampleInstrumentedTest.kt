package com.fundocareer.app

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun appPackageName_isCorrect() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.fundocareer.app", appContext.packageName)
    }

    @Test
    fun secureTokenStore_exists() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SecureTokenStore(appContext)
        assertNotNull(store)
    }

    @Test
    fun notificationChannel_creatable() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val channelManager = com.fundocareer.app.core.jobalerts.JobAlertNotificationHelper
        channelManager.createNotificationChannel(appContext)
        assertNotNull(appContext.getSystemService(android.app.NotificationManager::class.java))
    }
}
