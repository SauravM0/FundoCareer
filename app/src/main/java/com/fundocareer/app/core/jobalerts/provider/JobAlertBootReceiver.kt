package com.fundocareer.app.core.jobalerts.provider

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class JobAlertBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "JobAlertBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        Log.i(TAG, "Received $action — reconciling active schedulers")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                IntervalJobAlertScheduler.reconcileAfterBoot(context)
                Log.i(TAG, "Reconcile completed for $action")
            } catch (e: Exception) {
                Log.e(TAG, "Reconcile failed for $action", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
