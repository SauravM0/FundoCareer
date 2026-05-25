package com.fundocareer.app.core.jobalerts

data class JobSearchCriteria(
    val role: String,
    val location: String,
    val experience: String,
    val remote: Boolean? = null,
    val salaryMin: Long? = null,
    val salaryMax: Long? = null,
    val skills: List<String> = emptyList(),
    val company: String? = null,
    val datePosted: String? = null
)

object JobAlertDefaults {
    const val DEFAULT_INTERVAL_VALUE = 15L
    const val DEFAULT_INTERVAL_UNIT = "minutes"
    const val DEFAULT_INTERVAL_MINUTES = 60L
    const val DEFAULT_REPORT_FORMAT = "html"
    const val DEFAULT_WORK_NAME = "fundocareer_job_alerts"
    const val DEFAULT_PAGE_SIZE = 25
    const val MAX_RESULTS = 50
}

data class ParsedJob(
    val title: String,
    val company: String,
    val location: String,
    val description: String = "",
    val url: String? = null,
    val salary: String? = null,
    val postedDate: String? = null,
    val fingerprint: String
)

data class JobSearchResult(
    val jobs: List<ParsedJob>,
    val source: String,
    val error: JobSearchError? = null,
    val isRateLimited: Boolean = false
)

sealed class JobSearchError {
    data class HttpError(val code: Int, val message: String = "") : JobSearchError()
    data class ParseError(val detail: String) : JobSearchError()
    data class NetworkError(val detail: String) : JobSearchError()
    object RateLimited : JobSearchError()
    object EmptyResponse : JobSearchError()
    object Blocked : JobSearchError()
}
