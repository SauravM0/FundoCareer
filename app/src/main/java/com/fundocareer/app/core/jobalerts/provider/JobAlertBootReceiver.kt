package com.fundocareer.app.core.jobalerts.provider

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fundocareer.app.core.logging.FcLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class JobAlertBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            FcLog.i(FcLog.TAG_SCHEDULER, "Boot completed received, reconciling schedulers")
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    IntervalJobAlertScheduler.reconcileAfterBoot(context)
                    FcLog.i(FcLog.TAG_SCHEDULER, "Scheduler reconciliation after boot complete")
                } catch (e: Exception) {
                    FcLog.e(FcLog.TAG_SCHEDULER, "Boot reconciliation failed", e)
                }
            }
        }
    }
}
