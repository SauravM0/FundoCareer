package com.fundocareer.app.core.jobalerts.provider

import android.util.Log
import com.fundocareer.app.core.jobalerts.JobAlertPreferenceEntity
import com.fundocareer.app.core.jobalerts.JobAlertRepository
import com.fundocareer.app.core.jobalerts.JobAlertStatus
import com.fundocareer.app.core.jobalerts.JobResultEntity
import com.fundocareer.app.core.jobalerts.JobSearchCriteria
import com.fundocareer.app.core.jobalerts.JobSearchError
import com.fundocareer.app.core.jobalerts.JobSearchResult
import com.fundocareer.app.core.jobalerts.ParsedJob

class JobAlertUseCase(
    private val repository: JobAlertRepository,
    private val jobSourceProvider: JobSourceProvider
) {

    companion object {
        private const val TAG = "JobAlertUseCase"
    }

    data class FetchAndStoreResult(
        val allJobs: List<ParsedJob>,
        val newJobs: List<ParsedJob>,
        val error: JobSearchError? = null,
        val isRateLimited: Boolean = false
    )

    suspend fun searchAndStore(
        userEmail: String,
        preferenceId: String,
        runId: String
    ): FetchAndStoreResult {
        Log.i(TAG, "searchAndStore: userEmail=$userEmail, prefId=$preferenceId")

        val preference = repository.getPreferenceById(preferenceId)
        if (preference == null) {
            Log.w(TAG, "Preference not found: $preferenceId")
            return FetchAndStoreResult(allJobs = emptyList(), newJobs = emptyList(), error = JobSearchError.EmptyResponse)
        }

        val criteria = mapPreferenceToCriteria(preference)
        val searchResult = jobSourceProvider.search(criteria)

        if (searchResult.error != null) {
            Log.w(TAG, "Search error: ${searchResult.error}")
        }

        if (searchResult.jobs.isEmpty()) {
            return FetchAndStoreResult(
                allJobs = emptyList(),
                newJobs = emptyList(),
                error = searchResult.error,
                isRateLimited = searchResult.isRateLimited
            )
        }

        val newJobs = storeNewJobs(userEmail, preferenceId, runId, searchResult.jobs)

        return FetchAndStoreResult(
            allJobs = searchResult.jobs,
            newJobs = newJobs,
            error = searchResult.error,
            isRateLimited = searchResult.isRateLimited
        )
    }

    suspend fun searchJobs(preference: JobAlertPreferenceEntity): JobSearchResult {
        val criteria = mapPreferenceToCriteria(preference)
        Log.d(TAG, "searchJobs: role=${criteria.role}, provider=${jobSourceProvider.sourceName}")
        return jobSourceProvider.search(criteria)
    }

    suspend fun storeNewJobs(
        userEmail: String,
        preferenceId: String,
        runId: String,
        parsedJobs: List<ParsedJob>
    ): List<ParsedJob> {
        val newJobs = mutableListOf<ParsedJob>()
        for (pj in parsedJobs) {
            val fingerprint = pj.fingerprint
            if (fingerprint.isNotBlank()) {
                val existing = repository.getJobResultByFingerprint(fingerprint)
                if (existing != null) continue
            }
            val entity = JobResultEntity(
                id = java.util.UUID.randomUUID().toString(),
                userEmail = userEmail,
                preferenceId = preferenceId,
                jobTitle = pj.title,
                company = pj.company,
                location = pj.location,
                description = pj.description,
                url = pj.url,
                salary = pj.salary,
                postedDate = pj.postedDate,
                source = jobSourceProvider.sourceName,
                fingerprint = fingerprint,
                runId = runId,
                fetchedAt = System.currentTimeMillis()
            )
            val inserted = repository.insertJobResult(entity)
            if (inserted > 0L) newJobs.add(pj)
        }
        Log.d(TAG, "storeNewJobs: ${parsedJobs.size} total, ${newJobs.size} new")
        return newJobs
    }

    suspend fun saveSearchRun(
        userEmail: String,
        preferenceId: String,
        runId: String,
        status: String,
        jobsFound: Int,
        jobsNew: Int,
        errorMessage: String?,
        triggeredBy: String = "scheduler"
    ) {
        val now = System.currentTimeMillis()
        val run = com.fundocareer.app.core.jobalerts.JobAlertRunEntity(
            id = runId,
            userEmail = userEmail,
            preferenceId = preferenceId,
            startedAt = now,
            completedAt = now,
            status = status,
            jobsFound = jobsFound,
            jobsNew = jobsNew,
            jobsSkipped = jobsFound - jobsNew,
            errorMessage = errorMessage,
            triggeredBy = triggeredBy
        )
        repository.insertAlertRun(run)

        val pref = repository.getPreferenceById(preferenceId)
        if (pref != null) {
            val updatedPref = pref.copy(
                lastRunAt = now,
                lastError = if (status.startsWith("FAILED") || status.startsWith("SKIPPED")) errorMessage else null,
                lastEmailSentAt = if (status == JobAlertStatus.SUCCESS_EMAIL_SENT) now else pref.lastEmailSentAt
            )
            repository.updatePreference(updatedPref)
        }
    }

    private fun mapPreferenceToCriteria(pref: JobAlertPreferenceEntity): JobSearchCriteria {
        val skills = pref.skills?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        return JobSearchCriteria(
            role = pref.role,
            location = pref.location,
            experience = pref.experience,
            remote = pref.remote,
            salaryMin = pref.salaryMin,
            salaryMax = pref.salaryMax,
            skills = skills,
            company = pref.company,
            datePosted = pref.datePosted
        )
    }
}
