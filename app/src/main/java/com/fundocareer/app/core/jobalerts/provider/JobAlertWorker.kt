package com.fundocareer.app.core.jobalerts.provider

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fundocareer.app.AuthManager
import com.fundocareer.app.BuildConfig
import com.fundocareer.app.SecureTokenStore
import com.fundocareer.app.core.jobalerts.JobAlertNotificationHelper
import com.fundocareer.app.core.jobalerts.JobAlertPreferenceEntity
import com.fundocareer.app.core.jobalerts.JobAlertRepository
import com.fundocareer.app.core.jobalerts.provider.JobAlertPendingSyncStore
import com.fundocareer.app.core.jobalerts.provider.retryPendingSync
import com.fundocareer.app.core.jobalerts.JobAlertStatus
import com.fundocareer.app.core.jobalerts.ParsedJob
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class JobAlertWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "JobAlertWorker"
        private const val INPUT_TRIGGER_REASON_SCHEDULED = "SCHEDULED"
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "=== doWork started ===")
        val ctx = applicationContext
        val inputPrefId = inputData.getString("preferenceId")
        val triggerReason = inputData.getString("triggerReason") ?: INPUT_TRIGGER_REASON_SCHEDULED
        Log.i(TAG, "Worker started: triggerReason=$triggerReason, inputPreferenceId=$inputPrefId")
        Log.i(TAG, "inputData: preferenceId=$inputPrefId, triggerReason=$triggerReason")

        val tokenStore = SecureTokenStore(ctx)
        val sessionEmail = tokenStore.getUserEmail()

        if (sessionEmail.isNullOrBlank()) {
            Log.i(TAG, "No user email in session, stopping")
            return Result.success()
        }

        if (!tokenStore.hasValidSession()) {
            Log.w(TAG, "Token expired for $sessionEmail, attempting refresh")
            if (tokenStore.hasRefreshToken()) {
                val refreshed = tryRefreshToken(ctx, tokenStore)
                if (!refreshed) {
                    Log.w(TAG, "Token refresh failed for $sessionEmail — retry later")
                    return Result.retry()
                }
                Log.i(TAG, "Token refreshed successfully for $sessionEmail")
            } else {
                Log.w(TAG, "No refresh token for $sessionEmail — retry later")
                return Result.retry()
            }
        }

        val authToken = tokenStore.getAccessToken()
        if (authToken.isNullOrBlank()) {
            Log.i(TAG, "No auth token for $sessionEmail, stopping")
            return Result.success()
        }

        val recheckEmail = tokenStore.getUserEmail()
        if (recheckEmail.isNullOrBlank() || recheckEmail != sessionEmail) {
            Log.w(TAG, "User email changed during init: $sessionEmail -> $recheckEmail, stopping")
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
                    Log.w(TAG, "Preference $inputPrefId belongs to different user, stopping")
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
            Log.i(TAG, "No active preferences for $sessionEmail")
            useCase.saveSearchRun(sessionEmail, "none", UUID.randomUUID().toString(),
                JobAlertStatus.NO_ACTIVE_PREFERENCE, 0, 0, null, triggerReason)
            return Result.success()
        }
        Log.i(TAG, "Preference loaded: count=${preferences.size}, ids=${preferences.joinToString { it.id }}")

        val schedulerState = if (!inputPrefId.isNullOrBlank() && preferences.isNotEmpty()) {
            repository.getSchedulerState(preferences.first().userEmail)
        } else {
            repository.getSchedulerState(sessionEmail)
        }

        if (schedulerState != null && !schedulerState.schedulerEnabled) {
            Log.i(TAG, "Scheduler is disabled, stopping worker")
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
            Log.i(TAG, "Synced ${completedRuns.size} runs to backend")
        }

        if (!anyRetryable) {
            for (pref in preferences) {
                repository.clearPendingDueRun(pref.id)
            }

            for (pref in preferences.filterNot { it.id in notActiveDevicePreferenceIds }) {
                val interval = pref.intervalMinutes.coerceAtLeast(15L)
                Log.i(TAG, "Scheduling next interval run for pref ${pref.id} in ${interval}min")
                IntervalJobAlertScheduler.scheduleNextRun(ctx, pref.id, interval)
            }
        }

        Log.i(TAG, "=== doWork finished (retryable=$anyRetryable) ===")
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
        Log.i(TAG, "Processing preference: ${pref.id} (${pref.role}), triggerReason=$triggerReason")

        if (pref.stoppedByUser) {
            Log.i(TAG, "Preference ${pref.id} stopped by user, skipping")
            return Result.success()
        }

        val tokenStore = SecureTokenStore(ctx)
        val currentEmail = tokenStore.getUserEmail()

        if (currentEmail.isNullOrBlank() || !tokenStore.hasValidSession()) {
            Log.w(TAG, "Session invalid mid-run for $userEmail, skipping preference ${pref.id}")
            val status = JobAlertStatus.FAILED_AUTH
            useCase.saveSearchRun(userEmail, pref.id, runId, status, 0, 0,
                "Session invalid mid-run: currentEmail=$currentEmail", triggerReason)
            completedRuns.add(makeRunJson(pref.id, runId, status, 0, 0, triggerReason))
            return Result.success()
        }

        if (currentEmail != userEmail) {
            Log.w(TAG, "User email changed mid-run! Worker started as $userEmail but session is now $currentEmail")
            val status = JobAlertStatus.FAILED_AUTH
            useCase.saveSearchRun(userEmail, pref.id, runId, status, 0, 0,
                "User changed from $userEmail to $currentEmail", triggerReason)
            completedRuns.add(makeRunJson(pref.id, runId, status, 0, 0, triggerReason))
            return Result.success()
        }

        if (pref.userEmail != userEmail) {
            Log.w(TAG, "Preference userEmail ${pref.userEmail} does not match session $userEmail — data isolation violation")
            val status = JobAlertStatus.FAILED_AUTH
            useCase.saveSearchRun(userEmail, pref.id, runId, status, 0, 0,
                "Preference userEmail mismatch", triggerReason)
            completedRuns.add(makeRunJson(pref.id, runId, status, 0, 0, triggerReason))
            return Result.success()
        }

        retryPendingSync(ctx, authToken)
        if (JobAlertPendingSyncStore(ctx).hasPending()) {
            Log.i(TAG, "Pending sync unresolved for ${pref.id}, deferring")
            return Result.retry()
        }

        val verifyResult = apiClient.verifyActiveDevice(deviceId, pref.id)
        if (!verifyResult.success) {
            Log.w(TAG, "Active device verification failed for ${pref.id}: ${verifyResult.error}, deferring")
            return Result.retry()
        }
        if (!verifyResult.canSend) {
            Log.i(TAG, "Device not active for ${pref.id}, recording skip (reason=${verifyResult.reason})")
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
            Log.w(TAG, "heartbeatActiveDevice failed for ${pref.id}: ${e.message}", e)
        }

        val lockResult = apiClient.acquireLock(pref.id, deviceId, "android", appVersion)
        if (!lockResult.granted) {
            Log.i(TAG, "Lock denied for ${pref.id}: reason=${lockResult.reason}, held by ${lockResult.heldBy}")
            val status = JobAlertStatus.SKIPPED_LOCK_HELD_BY_OTHER_DEVICE
            useCase.saveSearchRun(userEmail, pref.id, runId, status, 0, 0,
                lockResult.reason ?: "Lock held by ${lockResult.heldBy}", triggerReason)
            completedRuns.add(makeRunJson(pref.id, runId, status, 0, 0, triggerReason))
            return Result.success()
        }

        var lockAcquired = true
        try {
            Log.i(TAG, "Fetch started: preferenceId=${pref.id}, role=${pref.role}")
            val searchResult = useCase.searchJobs(pref)
            Log.i(TAG, "Fetch completed: preferenceId=${pref.id}, jobs=${searchResult.jobs.size}, error=${searchResult.error}")
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
                Log.w(TAG, "Search failed for ${pref.id}: ${searchResult.error}")
                useCase.saveSearchRun(userEmail, pref.id, runId, status, 0, 0,
                    errorCode, triggerReason)
                return if (errorCode == "BACKEND_UNREACHABLE" || errorCode == "LINKEDIN_RATE_LIMITED") Result.retry() else Result.success()
            }

            if (searchResult.jobs.isEmpty()) {
                Log.i(TAG, "No jobs found for ${pref.id}")
                useCase.saveSearchRun(userEmail, pref.id, runId, JobAlertStatus.SUCCESS_NO_NEW_JOBS,
                    0, 0, null, triggerReason)
                completedRuns.add(makeRunJson(pref.id, runId, JobAlertStatus.SUCCESS_NO_NEW_JOBS, 0, 0, triggerReason))
                return Result.success()
            }

            val newStoredJobs = useCase.storeNewJobs(userEmail, pref.id, runId, searchResult.jobs)
            if (newStoredJobs.isEmpty()) {
                Log.i(TAG, "All ${searchResult.jobs.size} jobs already in local DB for ${pref.id}")
                useCase.saveSearchRun(userEmail, pref.id, runId, JobAlertStatus.SUCCESS_NO_NEW_JOBS,
                    searchResult.jobs.size, 0, null, triggerReason)
                completedRuns.add(makeRunJson(pref.id, runId, JobAlertStatus.SUCCESS_NO_NEW_JOBS,
                    searchResult.jobs.size, 0, triggerReason))
                return Result.success()
            }

            val newFingerprints = newStoredJobs.map { it.fingerprint }.filter { it.isNotBlank() }
            val emailCheck = apiClient.checkEmailedFingerprints(newFingerprints, pref.id)
            if (emailCheck.error != null) {
                Log.w(TAG, "checkEmailedFingerprints failed for ${pref.id}: ${emailCheck.error}")
                useCase.saveSearchRun(userEmail, pref.id, runId, JobAlertStatus.FAILED_JOB_SOURCE,
                    searchResult.jobs.size, newStoredJobs.size, emailCheck.error, triggerReason)
                return Result.retry()
            }
            val trulyNewJobs = newStoredJobs.filter { it.fingerprint in emailCheck.newFingerprints || it.fingerprint.isBlank() }.take(50)

            if (trulyNewJobs.isEmpty()) {
                Log.i(TAG, "All ${newStoredJobs.size} new jobs already emailed for ${pref.id}")
                useCase.saveSearchRun(userEmail, pref.id, runId, JobAlertStatus.SUCCESS_NO_NEW_JOBS,
                    searchResult.jobs.size, 0, null, triggerReason)
                completedRuns.add(makeRunJson(pref.id, runId, JobAlertStatus.SUCCESS_NO_NEW_JOBS,
                    searchResult.jobs.size, 0, triggerReason))
                return Result.success()
            }

            Log.i(TAG, "Email request sent: preferenceId=${pref.id}, runId=$runId, jobs=${trulyNewJobs.size}")
            val emailResult = apiClient.sendJobAlertEmail(
                to = userEmail,
                preferenceId = pref.id,
                preferenceName = pref.role,
                newJobs = trulyNewJobs.map { jobToJson(it) },
                allJobsCount = searchResult.jobs.size,
                deviceId = deviceId,
                runId = runId,
            )
            Log.i(TAG, "Email response received: preferenceId=${pref.id}, success=${emailResult.success}, emailSent=${emailResult.emailSent}, errorCode=${emailResult.errorCode}")

            if (!emailResult.success || !emailResult.emailSent || emailResult.jobsSent <= 0) {
                Log.e(TAG, "Email send failed for ${pref.id}: emailSent=${emailResult.emailSent}, jobsSent=${emailResult.jobsSent}, error=${emailResult.error}")
                useCase.saveSearchRun(userEmail, pref.id, runId, JobAlertStatus.FAILED_EMAIL_BACKEND,
                    searchResult.jobs.size, trulyNewJobs.size, emailResult.errorCode ?: emailResult.error ?: "EMAIL_SEND_FAILED", triggerReason)
                return Result.retry()
            }

            useCase.saveSearchRun(userEmail, pref.id, runId, JobAlertStatus.SUCCESS_EMAIL_SENT,
                searchResult.jobs.size, trulyNewJobs.size, null, triggerReason)

            JobAlertNotificationHelper.showJobAlertNotification(
                ctx, trulyNewJobs.size, pref.role, trulyNewJobs.size, searchResult.jobs.size
            )

            Log.i(TAG, "SUCCESS_EMAIL_SENT for ${pref.id}: ${trulyNewJobs.size} new of ${searchResult.jobs.size}")
            completedRuns.add(makeRunJson(pref.id, runId, JobAlertStatus.SUCCESS_EMAIL_SENT,
                searchResult.jobs.size, trulyNewJobs.size, triggerReason))
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error for ${pref.id}", e)
            useCase.saveSearchRun(userEmail, pref.id, runId, JobAlertStatus.FAILED_JOB_SOURCE,
                0, 0, e.message, triggerReason)
            return Result.retry()
        } finally {
            if (lockAcquired) {
                try {
                    apiClient.releaseLock(pref.id, deviceId)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to release lock for ${pref.id}", e)
                }
            }
        }
    }

    private suspend fun tryRefreshToken(ctx: Context, tokenStore: SecureTokenStore): Boolean {
        return try {
            var result = false
            val latch = java.util.concurrent.CountDownLatch(1)
            val authManager = AuthManager(ctx, tokenStore)
            authManager.refreshAccessToken(object : com.fundocareer.app.AuthManager.AuthCallback {
                override fun onSuccess(authData: org.json.JSONObject?) {
                    result = true
                    latch.countDown()
                }
                override fun onError(error: String?) {
                    Log.e(TAG, "Token refresh failed: $error")
                    result = false
                    latch.countDown()
                }
            })
            latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
            result
        } catch (e: Exception) {
            Log.e(TAG, "Token refresh exception", e)
            false
        }
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
