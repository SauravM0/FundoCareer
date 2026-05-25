package com.fundocareer.app

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.fundocareer.app.core.jobalerts.JobAlertNotificationHelper
import com.fundocareer.app.core.jobalerts.provider.IntervalJobAlertScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FundoCareerApplication : Application() {

    companion object {
        private const val TAG = "FundoCareerApp"
        private const val SCHEDULER_DELAY_MS = 5000L
    }

    override fun onCreate() {
        super.onCreate()
        JobAlertNotificationHelper.createNotificationChannel(this)
        Handler(Looper.getMainLooper()).postDelayed({
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    IntervalJobAlertScheduler.reconcileOnAppStart(applicationContext)
                    Log.i(TAG, "Scheduler reconciled after startup delay")
                } catch (e: Exception) {
                    Log.w(TAG, "Scheduler reconcile failed", e)
                }
            }
        }, SCHEDULER_DELAY_MS)
    }
}
