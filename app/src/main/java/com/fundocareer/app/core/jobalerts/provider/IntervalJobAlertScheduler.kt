package com.fundocareer.app.core.jobalerts.provider

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.fundocareer.app.core.jobalerts.DeviceSchedulerStateEntity
import com.fundocareer.app.core.jobalerts.JobAlertRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

object IntervalJobAlertScheduler {
    private const val TAG = "IntervalJobAlertScheduler"
    private const val UNIQUE_WORK_NAME_PREFIX = "interval_job_alert_"
    private const val UNIQUE_WORK_NAME_IMMEDIATE_PREFIX = "interval_job_alert_immediate_"
    private const val MIN_INTERVAL_MINUTES = 15L

    const val TRIGGER_REASON_IMMEDIATE = "IMMEDIATE"
    const val TRIGGER_REASON_SCHEDULED = "SCHEDULED"
    const val TRIGGER_REASON_BOOT_RECOVERY = "BOOT_RECOVERY"
    const val TRIGGER_REASON_CATCH_UP = "CATCH_UP"
    const val TRIGGER_REASON_ALARM_MIGRATION = "ALARM_MIGRATION"

    // ================================================================
    // 1. startSchedulerAfterSave
    // ================================================================
    suspend fun startSchedulerAfterSave(
        context: Context,
        preferenceId: String,
        intervalMinutes: Long,
        runImmediately: Boolean = true
    ) {
        val clamped = intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES)
        Log.i(TAG, "startSchedulerAfterSave: preferenceId=$preferenceId, intervalMinutes=$clamped, runImmediately=$runImmediately")

        if (runImmediately) {
            enqueueImmediateRun(context, preferenceId, TRIGGER_REASON_IMMEDIATE)
        }
        scheduleNextRun(context, preferenceId, clamped)
    }

    // ================================================================
    // 2. scheduleNextRun
    // ================================================================
    suspend fun scheduleNextRun(
        context: Context,
        preferenceId: String,
        intervalMinutes: Long
    ) {
        val clamped = intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES)
        val delayMillis = clamped * 60 * 1000L
        val nextRunAt = System.currentTimeMillis() + delayMillis

        Log.i(TAG, "scheduleNextRun: preferenceId=$preferenceId, interval=${clamped}min, nextRunAt=$nextRunAt")

        val inputData = Data.Builder()
            .putString("preferenceId", preferenceId)
            .putString("triggerReason", TRIGGER_REASON_SCHEDULED)
            .putLong("intervalMinutes", clamped)
            .build()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<JobAlertWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .setInputData(inputData)
            .addTag("interval_job_alert")
            .build()

        val uniqueWorkName = UNIQUE_WORK_NAME_PREFIX + preferenceId
        WorkManager.getInstance(context)
            .enqueueUniqueWork(uniqueWorkName, ExistingWorkPolicy.REPLACE, workRequest)

        Log.i(TAG, "Work enqueued: pref=$preferenceId, uniqueName=$uniqueWorkName, delay=${clamped}min")

        val repo = JobAlertRepository.getInstance(context)
        repo.updateNextScheduledRunAt(preferenceId, nextRunAt)

        try {
            val pref = repo.getPreferenceById(preferenceId)
            if (pref != null) {
                val state = repo.getSchedulerState(pref.userEmail)
                val updated = (state ?: DeviceSchedulerStateEntity(
                    id = UUID.randomUUID().toString(),
                    userEmail = pref.userEmail,
                    schedulerEnabled = true
                )).copy(
                    nextScheduledRunAt = nextRunAt,
                    schedulerEnabled = true
                )
                repo.saveSchedulerState(updated)
                Log.i(TAG, "Scheduler state updated for ${pref.userEmail}: nextRunAt=$nextRunAt")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update scheduler state for $preferenceId", e)
        }
    }

    // ================================================================
    // 3. enqueueImmediateRun
    // ================================================================
    fun enqueueImmediateRun(
        context: Context,
        preferenceId: String,
        reason: String = TRIGGER_REASON_IMMEDIATE
    ) {
        val inputData = Data.Builder()
            .putString("preferenceId", preferenceId)
            .putString("triggerReason", reason)
            .putLong("scheduledAtMillis", System.currentTimeMillis())
            .build()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<JobAlertWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .addTag("interval_job_alert_immediate")
            .build()

        val uniqueWorkName = UNIQUE_WORK_NAME_IMMEDIATE_PREFIX + preferenceId
        WorkManager.getInstance(context)
            .enqueueUniqueWork(uniqueWorkName, ExistingWorkPolicy.REPLACE, workRequest)

        Log.i(TAG, "Immediate work enqueued: pref=$preferenceId, reason=$reason, name=$uniqueWorkName")
    }

    // ================================================================
    // 4. cancelScheduler
    // ================================================================
    suspend fun cancelScheduler(context: Context, preferenceId: String) {
        Log.i(TAG, "cancelScheduler: preferenceId=$preferenceId")

        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(UNIQUE_WORK_NAME_PREFIX + preferenceId)
        wm.cancelUniqueWork(UNIQUE_WORK_NAME_IMMEDIATE_PREFIX + preferenceId)
        Log.i(TAG, "Work cancelled for preference $preferenceId")

        try {
            val repo = JobAlertRepository.getInstance(context)
            repo.markSchedulerEnabled(preferenceId, false)
            repo.updateNextScheduledRunAt(preferenceId, null)

            val pref = repo.getPreferenceById(preferenceId)
            if (pref != null) {
                val state = repo.getSchedulerState(pref.userEmail)
                if (state != null) {
                    repo.saveSchedulerState(state.copy(
                        schedulerEnabled = false,
                        nextScheduledRunAt = null
                    ))
                    Log.i(TAG, "Scheduler state marked disabled for ${pref.userEmail}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update scheduler state for $preferenceId", e)
        }
    }

    // ================================================================
    // 5. reconcileOnAppStart
    // ================================================================
    suspend fun reconcileOnAppStart(context: Context) {
        Log.i(TAG, "reconcileOnAppStart")
        val repo = JobAlertRepository.getInstance(context)
        val activePrefs = withContext(Dispatchers.IO) {
            repo.getAllActiveEnabledPreferences()
        }
        if (activePrefs.isEmpty()) {
            Log.i(TAG, "No active scheduler-enabled preferences to reconcile")
            return
        }
        val nowMs = System.currentTimeMillis()
        for (pref in activePrefs) {
            val interval = pref.intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES)
            val nextRun = pref.nextScheduledRunAt
            when {
                nextRun == null -> {
                    Log.i(TAG, "No nextScheduledRunAt for pref ${pref.id} — scheduling next run")
                    scheduleNextRun(context, pref.id, interval)
                }
                nextRun < nowMs -> {
                    Log.i(TAG, "Missed run for pref ${pref.id} (nextRun=$nextRun) — enqueue catch-up + reschedule")
                    enqueueImmediateRun(context, pref.id, TRIGGER_REASON_CATCH_UP)
                    scheduleNextRun(context, pref.id, interval)
                }
                else -> {
                    Log.i(TAG, "Future run for pref ${pref.id} at $nextRun — ensuring next run scheduled")
                    scheduleNextRun(context, pref.id, interval)
                }
            }
        }
    }

    // ================================================================
    // 6. reconcileAfterBoot
    // ================================================================
    suspend fun reconcileAfterBoot(context: Context) {
        Log.i(TAG, "reconcileAfterBoot")
        reconcileEnabledSchedulers(context, TRIGGER_REASON_BOOT_RECOVERY)
    }

    // ================================================================
    // Private helpers
    // ================================================================

    private suspend fun reconcileEnabledSchedulers(context: Context, catchUpReason: String) {
        val repo = JobAlertRepository.getInstance(context)
        val enabledStates = withContext(Dispatchers.IO) {
            repo.getAllEnabledSchedulerStatesNow()
        }
        if (enabledStates.isEmpty()) {
            Log.i(TAG, "No enabled schedulers to reconcile")
            return
        }
        for (state in enabledStates) {
            val prefs = withContext(Dispatchers.IO) {
                repo.getActivePreferences(state.userEmail).first()
            }
            val activePrefs = prefs.filter { !it.stoppedByUser && it.active }
            for (pref in activePrefs) {
                val interval = pref.intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES)
                scheduleNextRun(context, pref.id, interval)
                val nextRun = state.nextScheduledRunAt
                if (nextRun != null && nextRun < System.currentTimeMillis()) {
                    enqueueImmediateRun(context, pref.id, catchUpReason)
                    Log.i(TAG, "Catch-up enqueued for preference ${pref.id} (reason=$catchUpReason)")
                }
            }
        }
    }
}
