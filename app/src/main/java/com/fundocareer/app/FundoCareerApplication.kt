package com.fundocareer.app

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.fundocareer.app.core.jobalerts.JobAlertNotificationHelper
import com.fundocareer.app.core.jobalerts.provider.IntervalJobAlertScheduler
import com.fundocareer.app.core.logging.FcLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FundoCareerApplication : Application() {

    companion object {
        private const val SCHEDULER_DELAY_MS = 5000L
    }

    override fun onCreate() {
        super.onCreate()
        JobAlertNotificationHelper.createNotificationChannel(this)
        Handler(Looper.getMainLooper()).postDelayed({
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    IntervalJobAlertScheduler.reconcileOnAppStart(applicationContext)
                    FcLog.i(FcLog.TAG_APP, "Scheduler reconciled after startup delay")
                } catch (e: Exception) {
                    FcLog.w(FcLog.TAG_APP, "Scheduler reconcile failed", mapOf(
                        "error" to e.message,
                    ))
                }
            }
        }, SCHEDULER_DELAY_MS)
    }
}
