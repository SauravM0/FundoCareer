package com.fundocareer.app.core.jobalerts.provider

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.fundocareer.app.AuthManager
import com.fundocareer.app.BuildConfig
import com.fundocareer.app.R
import com.fundocareer.app.SecureTokenStore
import com.fundocareer.app.core.jobalerts.JobAlertNotificationHelper
import com.fundocareer.app.core.jobalerts.JobAlertPreferenceEntity
import com.fundocareer.app.core.jobalerts.JobAlertRepository
import com.fundocareer.app.core.jobalerts.provider.JobAlertPendingSyncStore
import com.fundocareer.app.core.jobalerts.provider.retryPendingSync
import com.fundocareer.app.core.jobalerts.JobAlertStatus
import com.fundocareer.app.core.jobalerts.ParsedJob
import com.fundocareer.app.core.logging.FcLog
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

class JobAlertWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val FOREGROUND_CHANNEL_ID = "job_alert_worker"
        private const val FOREGROUND_NOTIFICATION_ID = 1001
        private const val INPUT_TRIGGER_REASON_SCHEDULED = "SCHEDULED"
    }

    override suspend fun doWork(): Result {
        FcLog.i(FcLog.TAG_WORKER, "doWorkStarted")
        val ctx = applicationContext
        val inputPrefId = inputData.getString("preferenceId")
        val triggerReason = inputData.getString("triggerReason") ?: INPUT_TRIGGER_REASON_SCHEDULED
        FcLog.i(FcLog.TAG_WORKER, "Worker started", mapOf(
            "triggerReason" to triggerReason,
            "inputPreferenceId" to inputPrefId,
        ))

        try {
            setForeground(createForegroundInfo("Checking job alerts..."))
        } catch (e: Exception) {
            FcLog.w(FcLog.TAG_WORKER, "setForeground failed (expected on some API levels)", mapOf(
                "error" to e.message,
            ))
        }

        val tokenStore = SecureTokenStore(ctx)
        val sessionEmail = tokenStore.getUserEmail()

        if (sessionEmail.isNullOrBlank()) {
            FcLog.i(FcLog.TAG_WORKER, "No user email in session, stopping")
            return Result.success()
        }

        if (!tokenStore.hasValidSession()) {
            FcLog.w(FcLog.TAG_WORKER, "Token expired, attempting refresh", mapOf(
                "userEmail" to FcLog.maskEmail(sessionEmail),
            ))
            if (tokenStore.hasRefreshToken()) {
                val refreshed = tryRefreshToken(ctx, tokenStore)
                if (!refreshed) {
                    FcLog.w(FcLog.TAG_WORKER, "Token refresh failed", mapOf(
                        "userEmail" to FcLog.maskEmail(sessionEmail),
                    ))
                    return Result.retry()
                }
                FcLog.i(FcLog.TAG_WORKER, "Token refreshed successfully", mapOf(
                    "userEmail" to FcLog.maskEmail(sessionEmail),
                ))
            } else {
                FcLog.w(FcLog.TAG_WORKER, "No refresh token", mapOf(
                    "userEmail" to FcLog.maskEmail(sessionEmail),
                ))
                return Result.retry()
            }
        }

        val authToken = tokenStore.getAccessToken()
        if (authToken.isNullOrBlank()) {
            FcLog.i(FcLog.TAG_WORKER, "No auth token, stopping", mapOf(
                "userEmail" to FcLog.maskEmail(sessionEmail),
            ))
            return Result.success()
        }

        val recheckEmail = tokenStore.getUserEmail()
        if (recheckEmail.isNullOrBlank() || recheckEmail != sessionEmail) {
            FcLog.w(FcLog.TAG_WORKER, "User email changed during init", mapOf(
                "sessionEmail" to FcLog.maskEmail(sessionEmail),
                "recheckEmail" to FcLog.maskEmail(recheckEmail),
            ))
            return Result.success()
        }

        val repository = JobAlertRepository.getInstance(ctx)
        val sourceProvider = LinkedInGuestJobSourceProvider()
        val useCase = JobAlertUseCase(repository, sourceProvider)
        val apiClient = JobAlertApiClient(authToken)
        val deviceIdentityProvider = JobAlertDeviceIdentityProvider(ctx)
        val deviceId = deviceIdentityProvider.getDeviceIdForApi()
        val appVersion = BuildConfig.VERSION_NAME

        val preferences = if (!inputPrefId.isNullOrBlank()) {
            val singlePref = repository.getPreferenceById(inputPrefId)
            if (singlePref != null && singlePref.active && !singlePref.stoppedByUser) {
                if (singlePref.userEmail != sessionEmail) {
                    FcLog.w(FcLog.TAG_WORKER, "Preference belongs to different user, stopping", mapOf(
                        "preferenceId" to inputPrefId,
                    ))
                    return Result.success()
                }
                listOf(singlePref)
            } else {
                emptyList()
            }
        } else {
            repository.getActivePreferences(sessionEmail).first()
                .filter { !it.stoppedByUser }
        }

        if (preferences.isEmpty()) {
            FcLog.i(FcLog.TAG_WORKER, "No active preferences", mapOf(
                "userEmail" to FcLog.maskEmail(sessionEmail),
            ))
            useCase.saveSearchRun(sessionEmail, "none", UUID.randomUUID().toString(),
                JobAlertStatus.NO_ACTIVE_PREFERENCE, 0, 0, null, triggerReason)
            return Result.success()
        }
        FcLog.i(FcLog.TAG_WORKER, "Active preferences loaded", mapOf(
            "count" to preferences.size,
        ))

        val schedulerState = if (!inputPrefId.isNullOrBlank() && preferences.isNotEmpty()) {
            repository.getSchedulerState(preferences.first().userEmail)
        } else {
            repository.getSchedulerState(sessionEmail)
        }

        if (schedulerState != null && !schedulerState.schedulerEnabled) {
            FcLog.i(FcLog.TAG_WORKER, "Scheduler disabled, stopping worker")
            return Result.success()
        }

        val completedRuns = mutableListOf<JSONObject>()
        val notActiveDevicePreferenceIds = mutableSetOf<String>()
        var anyRetryable = false
        for (pref in preferences) {
            val result = processPreference(ctx, sessionEmail, authToken, pref,
                repository, useCase, apiClient, deviceId, appVersion, completedRuns, notActiveDevicePreferenceIds, triggerReason)
            if (result == Result.retry()) anyRetryable = true
        }

        if (completedRuns.isNotEmpty()) {
            val firstPref = preferences.first()
            val schedulerStateJson = JSONObject().apply {
                put("preferenceId", firstPref.id)
                put("schedulerEnabled", true)
                put("intervalMinutes", firstPref.intervalMinutes)
                put("lastRunAt", System.currentTimeMillis())
                put("nextScheduledRunAt", isoDate(System.currentTimeMillis() + firstPref.intervalMinutes.coerceAtLeast(15L) * 60_000L))
            }
            apiClient.syncState(
                deviceId = deviceId,
                deviceType = "android",
                appVersion = appVersion,
                preferences = preferenceSnapshotJson(firstPref),
                schedulerState = schedulerStateJson,
                recentRuns = completedRuns
            )
            FcLog.i(FcLog.TAG_WORKER, "Synced runs to backend", mapOf(
                "count" to completedRuns.size,
            ))
        }

        if (!anyRetryable) {
            for (pref in preferences) {
                repository.clearPendingDueRun(pref.id)
            }

            for (pref in preferences.filterNot { it.id in notActiveDevicePreferenceIds }) {
                val interval = pref.intervalMinutes.coerceAtLeast(15L)
                FcLog.i(FcLog.TAG_WORKER, "Scheduling next interval run", mapOf(
                    "preferenceId" to pref.id,
                    "intervalMin" to interval,
                ))
                IntervalJobAlertScheduler.scheduleNextRun(ctx, pref.id, interval)
            }
        }

        FcLog.i(FcLog.TAG_WORKER, "doWorkFinished", mapOf(
            "retryable" to anyRetryable,
        ))
        return if (anyRetryable) Result.retry() else Result.success()
    }

    private suspend fun processPreference(
        ctx: Context,
        userEmail: String,
        authToken: String,
        pref: JobAlertPreferenceEntity,
        repository: JobAlertRepository,
        useCase: JobAlertUseCase,
        apiClient: JobAlertApiClient,
        deviceId: String,
        appVersion: String,
        completedRuns: MutableList<JSONObject>,
        notActiveDevicePreferenceIds: MutableSet<String>,
        triggerReason: String
    ): Result {
        val runId = UUID.randomUUID().toString()
        FcLog.i(FcLog.TAG_WORKER, "Processing preference", mapOf(
            "preferenceId" to pref.id,
            "role" to pref.role,
            "triggerReason" to triggerReason,
        ))

        if (pref.stoppedByUser) {
            FcLog.i(FcLog.TAG_WORKER, "Preference stopped by user, skipping", mapOf(
                "preferenceId" to pref.id,
            ))
            return Result.success()
        }

        val tokenStore = SecureTokenStore(ctx)
        val currentEmail = tokenStore.getUserEmail()

        if (currentEmail.isNullOrBlank() || !tokenStore.hasValidSession()) {
            FcLog.w(FcLog.TAG_WORKER, "Session invalid mid-run", mapOf(
                "userEmail" to FcLog.maskEmail(userEmail),
                "preferenceId" to pref.id,
            ))
            val status = JobAlertStatus.FAILED_AUTH
            useCase.saveSearchRun(userEmail, pref.id, runId, status, 0, 0,
                "Session invalid mid-run", triggerReason)
            completedRuns.add(makeRunJson(pref.id, runId, status, 0, 0, triggerReason))
            return Result.success()
        }

        if (currentEmail != userEmail) {
            FcLog.w(FcLog.TAG_WORKER, "User email changed mid-run", mapOf(
                "startedAs" to FcLog.maskEmail(userEmail),
                "currentSession" to FcLog.maskEmail(currentEmail),
            ))
            val status = JobAlertStatus.FAILED_AUTH
            useCase.saveSearchRun(userEmail, pref.id, runId, status, 0, 0,
                "User changed from $userEmail to $currentEmail", triggerReason)
            completedRuns.add(makeRunJson(pref.id, runId, status, 0, 0, triggerReason))
            return Result.success()
        }

        if (pref.userEmail != userEmail) {
            FcLog.w(FcLog.TAG_WORKER, "Preference userEmail mismatch - data isolation violation", mapOf(
                "prefUserEmail" to FcLog.maskEmail(pref.userEmail),
                "sessionUserEmail" to FcLog.maskEmail(userEmail),
            ))
            val status = JobAlertStatus.FAILED_AUTH
            useCase.saveSearchRun(userEmail, pref.id, runId, status, 0, 0,
                "Preference userEmail mismatch", triggerReason)
            completedRuns.add(makeRunJson(pref.id, runId, status, 0, 0, triggerReason))
            return Result.success()
        }

        retryPendingSync(ctx, authToken)
        if (JobAlertPendingSyncStore(ctx).hasPending()) {
            FcLog.i(FcLog.TAG_WORKER, "Pending sync unresolved, deferring", mapOf(
                "preferenceId" to pref.id,
            ))
            return Result.retry()
        }

        val verifyResult = apiClient.verifyActiveDevice(deviceId, pref.id)
        if (!verifyResult.success) {
            FcLog.w(FcLog.TAG_WORKER, "Active device verification failed, deferring", mapOf(
                "preferenceId" to pref.id,
                "error" to verifyResult.error,
            ))
            return Result.retry()
        }
        if (!verifyResult.canSend) {
            FcLog.i(FcLog.TAG_WORKER, "Device not active, recording skip", mapOf(
                "preferenceId" to pref.id,
                "reason" to verifyResult.reason,
            ))
            val status = JobAlertStatus.SKIPPED_NOT_ACTIVE_DEVICE
            notActiveDevicePreferenceIds.add(pref.id)
            useCase.saveSearchRun(userEmail, pref.id, runId, status, 0, 0,
                "NOT_ACTIVE_DEVICE: ${verifyResult.reason ?: "device_not_active"}", triggerReason)
            completedRuns.add(makeRunJson(pref.id, runId, status, 0, 0, triggerReason))
            repository.updateNextScheduledRunAt(pref.id, null)
            return Result.success()
        }
        try {
            apiClient.heartbeatActiveDevice(deviceId, pref.id)
        } catch (e: Exception) {
            FcLog.w(FcLog.TAG_WORKER, "Heartbeat failed", mapOf(
                "preferenceId" to pref.id,
                "error" to (e.message ?: "unknown"),
            ))
        }

        val lockResult = apiClient.acquireLock(pref.id, deviceId, "android", appVersion)
        if (!lockResult.granted) {
            FcLog.i(FcLog.TAG_WORKER, "Lock denied", mapOf(
                "preferenceId" to pref.id,
                "reason" to lockResult.reason,
                "heldBy" to lockResult.heldBy,
            ))
            val status = JobAlertStatus.SKIPPED_LOCK_HELD_BY_OTHER_DEVICE
            useCase.saveSearchRun(userEmail, pref.id, runId, status, 0, 0,
                lockResult.reason ?: "Lock held by ${lockResult.heldBy}", triggerReason)
            completedRuns.add(makeRunJson(pref.id, runId, status, 0, 0, triggerReason))
            return Result.success()
        }

        var lockAcquired = true
        try {
            FcLog.i(FcLog.TAG_WORKER, "Fetch started", mapOf(
                "preferenceId" to pref.id,
                "role" to pref.role,
            ))
            val searchResult = useCase.searchJobs(pref)
            FcLog.i(FcLog.TAG_WORKER, "Fetch completed", mapOf(
                "preferenceId" to pref.id,
                "jobs" to searchResult.jobs.size,
                "error" to searchResult.error,
            ))
            if (searchResult.error != null) {
                val status = when (searchResult.error) {
                    is com.fundocareer.app.core.jobalerts.JobSearchError.NetworkError -> JobAlertStatus.FAILED_NETWORK
                    is com.fundocareer.app.core.jobalerts.JobSearchError.RateLimited -> JobAlertStatus.FAILED_JOB_SOURCE
                    else -> JobAlertStatus.FAILED_JOB_SOURCE
                }
                val errorCode = when (searchResult.error) {
                    is com.fundocareer.app.core.jobalerts.JobSearchError.Blocked -> "LINKEDIN_BLOCKED"
                    is com.fundocareer.app.core.jobalerts.JobSearchError.RateLimited -> "LINKEDIN_RATE_LIMITED"
                    is com.fundocareer.app.core.jobalerts.JobSearchError.NetworkError -> "BACKEND_UNREACHABLE"
                    else -> "JOB_SOURCE_FAILED"
                }
                FcLog.w(FcLog.TAG_WORKER, "Search failed", mapOf(
                    "preferenceId" to pref.id,
                    "errorCode" to errorCode,
                ))
                useCase.saveSearchRun(userEmail, pref.id, runId, status, 0, 0,
                    errorCode, triggerReason)
                return if (errorCode == "BACKEND_UNREACHABLE" || errorCode == "LINKEDIN_RATE_LIMITED") Result.retry() else Result.success()
            }

            if (searchResult.jobs.isEmpty()) {
                FcLog.i(FcLog.TAG_WORKER, "No jobs found", mapOf(
                    "preferenceId" to pref.id,
                ))
                useCase.saveSearchRun(userEmail, pref.id, runId, JobAlertStatus.SUCCESS_NO_NEW_JOBS,
                    0, 0, null, triggerReason)
                completedRuns.add(makeRunJson(pref.id, runId, JobAlertStatus.SUCCESS_NO_NEW_JOBS, 0, 0, triggerReason))
                return Result.success()
            }

            val newStoredJobs = useCase.storeNewJobs(userEmail, pref.id, runId, searchResult.jobs)
            if (newStoredJobs.isEmpty()) {
                FcLog.i(FcLog.TAG_WORKER, "All jobs already in local DB", mapOf(
                    "preferenceId" to pref.id,
                    "totalFound" to searchResult.jobs.size,
                ))
                useCase.saveSearchRun(userEmail, pref.id, runId, JobAlertStatus.SUCCESS_NO_NEW_JOBS,
                    searchResult.jobs.size, 0, null, triggerReason)
                completedRuns.add(makeRunJson(pref.id, runId, JobAlertStatus.SUCCESS_NO_NEW_JOBS,
                    searchResult.jobs.size, 0, triggerReason))
                return Result.success()
            }

            val newFingerprints = newStoredJobs.map { it.fingerprint }.filter { it.isNotBlank() }
            val emailCheck = apiClient.checkEmailedFingerprints(newFingerprints, pref.id)
            if (emailCheck.error != null) {
                FcLog.w(FcLog.TAG_WORKER, "checkEmailedFingerprints failed", mapOf(
                    "preferenceId" to pref.id,
                    "error" to emailCheck.error,
                ))
                useCase.saveSearchRun(userEmail, pref.id, runId, JobAlertStatus.FAILED_JOB_SOURCE,
                    searchResult.jobs.size, newStoredJobs.size, emailCheck.error, triggerReason)
                return Result.retry()
            }
            val trulyNewJobs = newStoredJobs.filter { it.fingerprint in emailCheck.newFingerprints || it.fingerprint.isBlank() }.take(50)

            if (trulyNewJobs.isEmpty()) {
                FcLog.i(FcLog.TAG_WORKER, "All new jobs already emailed", mapOf(
                    "preferenceId" to pref.id,
                    "newStored" to newStoredJobs.size,
                ))
                useCase.saveSearchRun(userEmail, pref.id, runId, JobAlertStatus.SUCCESS_NO_NEW_JOBS,
                    searchResult.jobs.size, 0, null, triggerReason)
                completedRuns.add(makeRunJson(pref.id, runId, JobAlertStatus.SUCCESS_NO_NEW_JOBS,
                    searchResult.jobs.size, 0, triggerReason))
                return Result.success()
            }

            FcLog.i(FcLog.TAG_WORKER, "Email request sent", mapOf(
                "preferenceId" to pref.id,
                "newJobs" to trulyNewJobs.size,
            ))
            val emailResult = apiClient.sendJobAlertEmail(
                to = userEmail,
                preferenceId = pref.id,
                preferenceName = pref.role,
                newJobs = trulyNewJobs.map { jobToJson(it) },
                allJobsCount = searchResult.jobs.size,
                deviceId = deviceId,
                runId = runId,
            )
            FcLog.i(FcLog.TAG_WORKER, "Email response received", mapOf(
                "preferenceId" to pref.id,
                "success" to emailResult.success,
                "emailSent" to emailResult.emailSent,
                "errorCode" to emailResult.errorCode,
            ))

            if (!emailResult.success || !emailResult.emailSent || emailResult.jobsSent <= 0) {
                FcLog.e(FcLog.TAG_WORKER, "Email send failed", null, mapOf(
                    "preferenceId" to pref.id,
                    "emailSent" to emailResult.emailSent,
                    "jobsSent" to emailResult.jobsSent,
                    "error" to emailResult.error,
                ))
                useCase.saveSearchRun(userEmail, pref.id, runId, JobAlertStatus.FAILED_EMAIL_BACKEND,
                    searchResult.jobs.size, trulyNewJobs.size, emailResult.errorCode ?: emailResult.error ?: "EMAIL_SEND_FAILED", triggerReason)
                return Result.retry()
            }

            useCase.saveSearchRun(userEmail, pref.id, runId, JobAlertStatus.SUCCESS_EMAIL_SENT,
                searchResult.jobs.size, trulyNewJobs.size, null, triggerReason)

            JobAlertNotificationHelper.showJobAlertNotification(
                ctx, trulyNewJobs.size, pref.role, trulyNewJobs.size, searchResult.jobs.size
            )

            FcLog.i(FcLog.TAG_WORKER, "SUCCESS_EMAIL_SENT", mapOf(
                "preferenceId" to pref.id,
                "newJobs" to trulyNewJobs.size,
                "totalFound" to searchResult.jobs.size,
            ))
            completedRuns.add(makeRunJson(pref.id, runId, JobAlertStatus.SUCCESS_EMAIL_SENT,
                searchResult.jobs.size, trulyNewJobs.size, triggerReason))
            return Result.success()
        } catch (e: Exception) {
            FcLog.e(FcLog.TAG_WORKER, "Unexpected error processing preference", e, mapOf(
                "preferenceId" to pref.id,
            ))
            useCase.saveSearchRun(userEmail, pref.id, runId, JobAlertStatus.FAILED_JOB_SOURCE,
                0, 0, e.message, triggerReason)
            return Result.retry()
        } finally {
            if (lockAcquired) {
                try {
                    apiClient.releaseLock(pref.id, deviceId)
                } catch (e: Exception) {
                    FcLog.e(FcLog.TAG_WORKER, "Failed to release lock", e, mapOf(
                        "preferenceId" to pref.id,
                    ))
                }
            }
        }
    }

    private suspend fun tryRefreshToken(ctx: Context, tokenStore: SecureTokenStore): Boolean {
        return try {
            suspendCancellableCoroutine { cont: CancellableContinuation<Boolean> ->
                val authManager = AuthManager(ctx, tokenStore)
                authManager.refreshAccessToken(object : com.fundocareer.app.AuthManager.AuthCallback {
                    override fun onSuccess(authData: org.json.JSONObject?) {
                        cont.resume(true)
                    }
                    override fun onError(error: String?) {
                        FcLog.e(FcLog.TAG_AUTH, "Token refresh failed", null, mapOf(
                            "error" to error,
                        ))
                        cont.resume(false)
                    }
                })
                cont.invokeOnCancellation {
                    FcLog.w(FcLog.TAG_AUTH, "Token refresh cancelled")
                }
            }
        } catch (e: Exception) {
            FcLog.e(FcLog.TAG_AUTH, "Token refresh exception", e)
            false
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo("Checking job alerts...")
    }

    private fun createForegroundInfo(progress: String): ForegroundInfo {
        val channelId = FOREGROUND_CHANNEL_ID
        val channelName = "Job Alert Worker"
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, channelName, NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when job alert worker is running in the background"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("Job Alert Worker")
            .setContentText(progress)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notification)
    }

    private fun makeRunJson(preferenceId: String, runId: String, status: String, jobsFound: Int, jobsNew: Int, triggeredBy: String): JSONObject {
        return JSONObject().apply {
            put("preferenceId", preferenceId)
            put("runId", runId)
            put("status", status)
            put("jobsFound", jobsFound)
            put("jobsNew", jobsNew)
            put("startedAt", System.currentTimeMillis())
            put("completedAt", System.currentTimeMillis())
            put("triggeredBy", triggeredBy)
        }
    }

    private fun jobToJson(job: ParsedJob): JSONObject {
        return JSONObject().apply {
            put("jobTitle", job.title)
            job.jobId?.let { put("jobId", it) }
            put("company", job.company)
            put("location", job.location)
            put("description", job.description)
            put("url", job.url ?: "")
            put("salary", job.salary ?: "")
            put("postedDate", job.postedDate ?: "")
            put("fingerprint", job.fingerprint)
        }
    }

    private fun preferenceSnapshotJson(pref: JobAlertPreferenceEntity): JSONObject {
        return JSONObject().apply {
            put("preferenceId", pref.id)
            put("role", pref.role)
            put("location", pref.location)
            put("experience", pref.experience)
            pref.remote?.let { put("remote", it) }
            pref.salaryMin?.let { put("salaryMin", it) }
            pref.salaryMax?.let { put("salaryMax", it) }
            pref.skills?.let { put("skills", it) }
            pref.company?.let { put("company", it) }
            pref.datePosted?.let { put("datePosted", it) }
            put("intervalMinutes", pref.intervalMinutes)
            put("reportFormat", pref.reportFormat)
            put("schedulerEnabled", pref.schedulerEnabled)
        }
    }

    private fun isoDate(ts: Long): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(ts))
    }
}
