package com.fundocareer.app.core.jobalerts.provider

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.fundocareer.app.core.jobalerts.DeviceSchedulerStateEntity
import com.fundocareer.app.core.jobalerts.JobAlertRepository
import com.fundocareer.app.core.logging.FcLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

object IntervalJobAlertScheduler {
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
        FcLog.i(FcLog.TAG_SCHEDULER, "startSchedulerAfterSave", mapOf(
            "preferenceId" to preferenceId,
            "intervalMinutes" to clamped,
            "runImmediately" to runImmediately,
        ))

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

        FcLog.i(FcLog.TAG_SCHEDULER, "scheduleNextRun", mapOf(
            "preferenceId" to preferenceId,
            "interval" to "${clamped}min",
            "nextRunAt" to nextRunAt,
        ))

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
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(inputData)
            .addTag("interval_job_alert")
            .build()

        val workId = workRequest.id
        val uniqueWorkName = UNIQUE_WORK_NAME_PREFIX + preferenceId
        WorkManager.getInstance(context)
            .enqueueUniqueWork(uniqueWorkName, ExistingWorkPolicy.KEEP, workRequest)

        FcLog.i(FcLog.TAG_SCHEDULER, "Work enqueued", mapOf(
            "preferenceId" to preferenceId,
            "uniqueName" to uniqueWorkName,
            "workId" to workId.toString(),
            "delay" to "${clamped}min",
        ))

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
                FcLog.i(FcLog.TAG_SCHEDULER, "Scheduler state updated", mapOf(
                    "userEmail" to FcLog.maskEmail(pref.userEmail),
                    "nextRunAt" to nextRunAt,
                ))
            }
        } catch (e: Exception) {
            FcLog.e(FcLog.TAG_SCHEDULER, "Failed to update scheduler state", e, mapOf(
                "preferenceId" to preferenceId,
            ))
        }
    }

    // ================================================================
    // 3. enqueueImmediateRun
    // ================================================================
    suspend fun enqueueImmediateRun(
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
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(inputData)
            .addTag("interval_job_alert_immediate")
            .build()

        val workId = workRequest.id
        val uniqueWorkName = UNIQUE_WORK_NAME_IMMEDIATE_PREFIX + preferenceId
        WorkManager.getInstance(context)
            .enqueueUniqueWork(uniqueWorkName, ExistingWorkPolicy.KEEP, workRequest)

        FcLog.i(FcLog.TAG_SCHEDULER, "Immediate work enqueued", mapOf(
            "preferenceId" to preferenceId,
            "reason" to reason,
            "name" to uniqueWorkName,
            "workId" to workId.toString(),
        ))
    }

    // ================================================================
    // 4. cancelScheduler
    // ================================================================
    suspend fun cancelScheduler(context: Context, preferenceId: String) {
        FcLog.i(FcLog.TAG_SCHEDULER, "cancelScheduler", mapOf(
            "preferenceId" to preferenceId,
        ))

        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(UNIQUE_WORK_NAME_PREFIX + preferenceId)
        wm.cancelUniqueWork(UNIQUE_WORK_NAME_IMMEDIATE_PREFIX + preferenceId)
        FcLog.i(FcLog.TAG_SCHEDULER, "Work cancelled", mapOf(
            "preferenceId" to preferenceId,
        ))

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
                    FcLog.i(FcLog.TAG_SCHEDULER, "Scheduler state marked disabled", mapOf(
                        "userEmail" to FcLog.maskEmail(pref.userEmail),
                    ))
                }
            }
        } catch (e: Exception) {
            FcLog.e(FcLog.TAG_SCHEDULER, "Failed to update scheduler state on cancel", e, mapOf(
                "preferenceId" to preferenceId,
            ))
        }
    }

    // ================================================================
    // 5. reconcileOnAppStart
    // ================================================================
    suspend fun reconcileOnAppStart(context: Context) {
        FcLog.i(FcLog.TAG_SCHEDULER, "reconcileOnAppStart")
        val repo = JobAlertRepository.getInstance(context)
        val enabledStates = withContext(Dispatchers.IO) {
            repo.getAllEnabledSchedulerStatesNow()
        }
        if (enabledStates.isEmpty()) {
            FcLog.i(FcLog.TAG_SCHEDULER, "No enabled schedulers to reconcile")
            return
        }
        val nowMs = System.currentTimeMillis()
        for (state in enabledStates) {
            if (!state.isThisDeviceActive) {
                FcLog.i(FcLog.TAG_SCHEDULER, "Skipping reconcile for non-active device", mapOf(
                    "userEmail" to FcLog.maskEmail(state.userEmail),
                ))
                continue
            }
            val prefs = withContext(Dispatchers.IO) {
                repo.getActivePreferences(state.userEmail).first()
            }
            val activePrefs = prefs.filter { !it.stoppedByUser && it.active }
            for (pref in activePrefs) {
                val interval = pref.intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES)
                val nextRun = pref.nextScheduledRunAt
                when {
                    nextRun == null -> {
                        FcLog.i(FcLog.TAG_SCHEDULER, "No nextScheduledRunAt", mapOf(
                            "preferenceId" to pref.id,
                        ))
                        scheduleNextRun(context, pref.id, interval)
                    }
                    nextRun < nowMs -> {
                        FcLog.i(FcLog.TAG_SCHEDULER, "Missed run", mapOf(
                            "preferenceId" to pref.id,
                            "nextRun" to nextRun,
                        ))
                        enqueueImmediateRun(context, pref.id, TRIGGER_REASON_CATCH_UP)
                        scheduleNextRun(context, pref.id, interval)
                    }
                    else -> {
                        FcLog.i(FcLog.TAG_SCHEDULER, "Future run, already enqueued", mapOf(
                            "preferenceId" to pref.id,
                            "nextRun" to nextRun,
                        ))
                    }
                }
            }
        }
    }

    // ================================================================
    // 6. reconcileAfterBoot
    // ================================================================
    suspend fun reconcileAfterBoot(context: Context) {
        FcLog.i(FcLog.TAG_SCHEDULER, "reconcileAfterBoot")
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
            FcLog.i(FcLog.TAG_SCHEDULER, "No enabled schedulers to reconcile")
            return
        }
        for (state in enabledStates) {
            if (!state.isThisDeviceActive) {
                FcLog.i(FcLog.TAG_SCHEDULER, "Skipping reconcile for non-active device", mapOf(
                    "userEmail" to FcLog.maskEmail(state.userEmail),
                ))
                continue
            }
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
                    FcLog.i(FcLog.TAG_SCHEDULER, "Catch-up enqueued", mapOf(
                        "preferenceId" to pref.id,
                        "reason" to catchUpReason,
                    ))
                }
            }
        }
    }
}
