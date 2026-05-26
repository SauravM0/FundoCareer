package com.fundocareer.app.core.jobalerts.provider

import com.fundocareer.app.BuildConfig
import com.fundocareer.app.core.logging.FcLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class JobAlertApiClient(
    private val authToken: String,
    private val client: OkHttpClient = JobAlertApiClient.defaultClient()
) {
    companion object {
        private const val TIMEOUT = 15L
        private var routingLogged = false

        fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT, TimeUnit.SECONDS)
            .build()
    }

    init {
        if (!routingLogged && BuildConfig.DEBUG) {
            routingLogged = true
            FcLog.i(FcLog.TAG_NETWORK, "API Base URL", mapOf(
                "url" to BuildConfig.API_BASE_URL,
            ))
            val routing = if (BuildConfig.FRONTEND_URL == BuildConfig.API_BASE_URL) "PRODUCTION" else "LOCAL BACKEND"
            FcLog.i(FcLog.TAG_NETWORK, "Routing", mapOf(
                "mode" to routing,
            ))
        }
    }

    private val baseUrl: String get() = BuildConfig.API_BASE_URL
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // ================================================================
    // Error Codes
    // ================================================================

    enum class ErrorCode(val code: String) {
        NETWORK_ERROR("NETWORK_ERROR"),
        HTTP_401("HTTP_401"),
        HTTP_409_ACTIVE_DEVICE_EXISTS("HTTP_409_ACTIVE_DEVICE_EXISTS"),
        HTTP_500("HTTP_500"),
        PARSE_ERROR("PARSE_ERROR"),
        UNKNOWN_ERROR("UNKNOWN_ERROR"),
    }

    // ================================================================
    // Logging Helpers
    // ================================================================

    private fun logApiStart(endpoint: String, vararg pairs: Pair<String, Any?>) {
        val params = pairs.filter { (_, v) -> v != null }
            .joinToString(", ") { (k, v) -> "$k=$v" }
        FcLog.i(FcLog.TAG_NETWORK, ">>> $endpoint", mapOf("params" to params))
    }

    private fun logApiSuccess(endpoint: String, vararg pairs: Pair<String, Any?>) {
        val params = pairs.filter { (_, v) -> v != null }
            .joinToString(", ") { (k, v) -> "$k=$v" }
        FcLog.i(FcLog.TAG_NETWORK, "<<< $endpoint OK", mapOf("result" to params))
    }

    private fun logApiFailure(endpoint: String, error: ErrorCode, vararg pairs: Pair<String, Any?>) {
        val params = pairs.filter { (_, v) -> v != null }
            .joinToString(", ") { (k, v) -> "$k=$v" }
        FcLog.w(FcLog.TAG_NETWORK, "<<< $endpoint FAIL", mapOf(
            "errorCode" to error.code,
            "details" to params,
        ))
    }

    private var lastRequestId: String? = null

    fun getLastRequestId(): String? = lastRequestId

    private data class HttpResponse(
        val httpCode: Int,
        val body: JSONObject
    )

    // ================================================================
    // HTTP Executor (always on Dispatchers.IO)
    // ================================================================

    private suspend fun executePost(path: String, jsonBody: String): JSONObject {
        return executePostWithCode(path, jsonBody).body
    }

    private suspend fun executePostWithCode(path: String, jsonBody: String): HttpResponse {
        return withContext(Dispatchers.IO) {
            val url = "${baseUrl}$path"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $authToken")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toRequestBody(jsonMediaType))
                .build()
            val response = client.newCall(request).execute()
            val code = response.code
            val body = response.body?.string() ?: "{}"
            lastRequestId = response.header("X-Request-Id") ?: response.header("x-request-id")

            if (code !in 200..299) {
                FcLog.w(FcLog.TAG_NETWORK, "POST $path non-200", mapOf(
                    "httpCode" to code,
                    "requestId" to lastRequestId,
                    "body" to body.take(500),
                ))
            }
            FcLog.d(FcLog.TAG_NETWORK, "POST $path", mapOf(
                "httpCode" to code,
                "requestId" to lastRequestId,
                "body" to body.take(300),
            ))

            try {
                HttpResponse(code, JSONObject(body))
            } catch (jsonEx: org.json.JSONException) {
                FcLog.e(FcLog.TAG_NETWORK, "POST $path non-JSON response", null, mapOf(
                    "httpCode" to code,
                    "requestId" to lastRequestId,
                    "body" to body.take(1000),
                ))
                HttpResponse(code, JSONObject())
            }
        }
    }

    private suspend fun executeGet(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        return executeGetWithCode(path, params).body
    }

    private suspend fun executeGetWithCode(path: String, params: Map<String, String> = emptyMap()): HttpResponse {
        return withContext(Dispatchers.IO) {
            val queryString = if (params.isNotEmpty()) {
                "?" + params.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
            } else ""
            val url = "${baseUrl}$path$queryString"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $authToken")
                .addHeader("Content-Type", "application/json")
                .get()
                .build()
            val response = client.newCall(request).execute()
            val code = response.code
            val body = response.body?.string() ?: "{}"
            lastRequestId = response.header("X-Request-Id") ?: response.header("x-request-id")

            if (code !in 200..299) {
                FcLog.w(FcLog.TAG_NETWORK, "GET $path non-200", mapOf(
                    "httpCode" to code,
                    "requestId" to lastRequestId,
                    "body" to body.take(500),
                ))
            }
            FcLog.d(FcLog.TAG_NETWORK, "GET $path", mapOf(
                "httpCode" to code,
                "requestId" to lastRequestId,
                "body" to body.take(300),
            ))

            try {
                HttpResponse(code, JSONObject(body))
            } catch (jsonEx: org.json.JSONException) {
                FcLog.e(FcLog.TAG_NETWORK, "GET $path non-JSON response", null, mapOf(
                    "httpCode" to code,
                    "requestId" to lastRequestId,
                    "body" to body.take(1000),
                ))
                HttpResponse(code, JSONObject())
            }
        }
    }

    private fun mapErrorCode(httpCode: Int, json: JSONObject): ErrorCode {
        return when {
            httpCode == 401 -> ErrorCode.HTTP_401
            httpCode == 409 && json.optString("errorCode") == "ACTIVE_DEVICE_EXISTS" -> ErrorCode.HTTP_409_ACTIVE_DEVICE_EXISTS
            httpCode in 500..599 -> ErrorCode.HTTP_500
            httpCode in 400..499 -> ErrorCode.NETWORK_ERROR
            else -> ErrorCode.UNKNOWN_ERROR
        }
    }

    // ================================================================
    // Result Types
    // ================================================================

    data class LockResult(
        val granted: Boolean,
        val heldBy: String? = null,
        val heldByDeviceType: String? = null,
        val reason: String? = null,
        val expiresAt: String? = null,
        val released: Boolean = false,
        val error: String? = null,
        val errorCode: String? = null,
    )

    data class EmailResult(
        val success: Boolean,
        val emailSent: Boolean = false,
        val setupEmailSent: Boolean = false,
        val jobEmailSent: Boolean = false,
        val jobsSent: Int = 0,
        val pdfAttached: Boolean = false,
        val errorCode: String? = null,
        val error: String? = null,
    )

    data class PingResult(
        val success: Boolean,
        val status: String? = null,
        val errorCode: String? = null,
        val error: String? = null,
    )

    data class CheckEmailedResult(
        val alreadyEmailed: List<String>,
        val newFingerprints: List<String>,
        val error: String? = null,
    )

    data class MarkEmailedResult(
        val success: Boolean,
        val marked: Int = 0,
        val total: Int = 0,
        val error: String? = null,
        val errorCode: String? = null,
    )

    data class SyncPausedStateResult(
        val success: Boolean,
        val error: String? = null,
        val errorCode: String? = null,
    )

    data class EmailHistoryItem(
        val emailSentAt: String,
        val jobsSentCount: Int,
        val pdfAttached: Boolean,
        val title: String,
    )

    data class EmailHistoryResult(
        val success: Boolean,
        val history: List<EmailHistoryItem> = emptyList(),
        val error: String? = null,
    )

    data class TimelineEvent(
        val type: String,
        val timestamp: String,
        val title: String,
        val description: String,
        val status: String? = null,
        val runId: String? = null,
        val metadata: TimelineEventMetadata? = null,
    )

    data class TimelineEventMetadata(
        val deviceName: String? = null,
        val devicePlatform: String? = null,
        val intervalMinutes: Int? = null,
        val jobsSentCount: Int? = null,
        val jobsFound: Int? = null,
        val jobsSent: Int? = null,
        val pdfAttached: Boolean? = null,
        val role: String? = null,
        val location: String? = null,
        val errorMessage: String? = null,
    )

    data class UserTimelineResult(
        val success: Boolean,
        val timeline: List<TimelineEvent> = emptyList(),
        val error: String? = null,
    )

    data class SyncStateResult(
        val success: Boolean,
        val syncedAt: String? = null,
        val error: String? = null,
    )

    data class DeviceState(
        val deviceState: JSONObject? = null,
        val activeLocks: List<JSONObject> = emptyList(),
        val recentRuns: List<JSONObject> = emptyList(),
    )

    data class ActiveDeviceDto(
        val deviceId: String? = null,
        val deviceName: String? = null,
        val devicePlatform: String? = null,
        val activeDeviceLastSeenAt: String? = null,
        val activatedAt: String? = null,
        val stoppedAt: String? = null,
        val lastSetupEmailAt: String? = null,
        val lastJobEmailAt: String? = null,
        val intervalMinutes: Int? = null,
        val schedulerPreferenceId: String? = null,
        val preferences: ActiveSchedulerPreferencesDto? = null,
        val schedulerState: ActiveSchedulerStateDto? = null,
        val lastRunAt: String? = null,
        val nextApproxRunAt: String? = null,
    )

    data class ActiveSchedulerPreferencesDto(
        val preferenceId: String? = null,
        val role: String? = null,
        val location: String? = null,
        val experience: String? = null,
        val skills: String? = null,
        val company: String? = null,
        val datePosted: String? = null,
        val reportFormat: String? = null,
        val intervalMinutes: Int? = null,
        val remote: Boolean? = null,
        val salaryMin: Long? = null,
        val salaryMax: Long? = null,
    )

    data class ActiveSchedulerStateDto(
        val preferenceId: String? = null,
        val schedulerEnabled: Boolean? = null,
        val lastRunAt: String? = null,
        val nextScheduledRunAt: String? = null,
        val intervalMinutes: Int? = null,
    )

    data class ActiveDeviceStatusResult(
        val success: Boolean,
        val activeDevice: ActiveDeviceDto? = null,
        val isCurrentDeviceActive: Boolean = false,
        val deactivated: Boolean = false,
        val reason: String? = null,
        val errorCode: String? = null,
        val error: String? = null,
    )

    data class ActiveDeviceVerificationResult(
        val success: Boolean,
        val canSend: Boolean = false,
        val reason: String? = null,
        val error: String? = null,
        val errorCode: String? = null,
    )

    // ================================================================
    // ACQUIRE LOCK
    // POST /api/scheduler/acquire-lock
    // ================================================================

    suspend fun acquireLock(
        preferenceSetId: String,
        deviceId: String,
        deviceType: String = "android",
        appVersion: String? = null,
    ): LockResult {
        val endpoint = "/api/scheduler/acquire-lock"
        logApiStart(endpoint, "preferenceSetId" to preferenceSetId, "deviceId" to FcLog.maskDeviceId(deviceId))
        return try {
            val body = JSONObject().apply {
                put("preferenceSetId", preferenceSetId)
                put("deviceId", deviceId)
                put("deviceType", deviceType)
                appVersion?.let { put("appVersion", it) }
            }
            val json = executePost(endpoint, body.toString())
            val data = json.optJSONObject("data")
            val granted = data?.optBoolean("granted", false) ?: false
            if (granted) {
                logApiSuccess(endpoint, "granted" to true, "preferenceSetId" to preferenceSetId)
            } else {
                logApiFailure(endpoint, ErrorCode.NETWORK_ERROR, "granted" to false, "reason" to (data?.optString("reason") ?: "unknown"))
            }
            LockResult(
                granted = granted,
                heldBy = data?.optString("heldBy", "")?.takeIf { it.isNotEmpty() },
                heldByDeviceType = data?.optString("heldByDeviceType", "")?.takeIf { it.isNotEmpty() },
                reason = data?.optString("reason", "")?.takeIf { it.isNotEmpty() },
                expiresAt = data?.optString("expiresAt", "")?.takeIf { it.isNotEmpty() },
                error = if (!json.optBoolean("success", false)) json.optString("message", "Unknown error") else null,
            )
        } catch (e: Exception) {
            logApiFailure(endpoint, ErrorCode.NETWORK_ERROR, "exception" to (e.message?.take(100)))
            LockResult(granted = false, error = e.message, errorCode = ErrorCode.NETWORK_ERROR.code)
        }
    }

    // ================================================================
    // RELEASE LOCK
    // POST /api/scheduler/release-lock
    // ================================================================

    suspend fun releaseLock(preferenceSetId: String, deviceId: String): LockResult {
        val endpoint = "/api/scheduler/release-lock"
        logApiStart(endpoint, "preferenceSetId" to preferenceSetId, "deviceId" to FcLog.maskDeviceId(deviceId))
        return try {
            val body = JSONObject().apply {
                put("preferenceSetId", preferenceSetId)
                put("deviceId", deviceId)
            }
            val json = executePost(endpoint, body.toString())
            val released = json.optJSONObject("data")?.optBoolean("released", false) ?: false
            if (released) {
                logApiSuccess(endpoint, "released" to true)
            }
            LockResult(granted = false, released = released)
        } catch (e: Exception) {
            logApiFailure(endpoint, ErrorCode.NETWORK_ERROR, "exception" to (e.message?.take(100)))
            LockResult(granted = false, released = false, error = e.message, errorCode = ErrorCode.NETWORK_ERROR.code)
        }
    }

    // ================================================================
    // CHECK EMAILED FINGERPRINTS
    // POST /api/scheduler/check-emailed-jobs
    // ================================================================

    suspend fun checkEmailedFingerprints(
        fingerprints: List<String>,
        preferenceId: String,
    ): CheckEmailedResult {
        val endpoint = "/api/scheduler/check-emailed-jobs"
        logApiStart(endpoint, "fingerprints" to fingerprints.size, "preferenceId" to preferenceId)
        return try {
            val body = JSONObject().apply {
                put("fingerprints", JSONArray(fingerprints))
                put("preferenceId", preferenceId)
            }
            val json = executePost(endpoint, body.toString())
            val data = json.optJSONObject("data")
            val alreadyEmailed = data?.optJSONArray("alreadyEmailed")
                ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
                ?: emptyList()
            val newFingerprints = data?.optJSONArray("new")
                ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
                ?: emptyList()
            logApiSuccess(endpoint, "alreadyEmailed" to alreadyEmailed.size, "new" to newFingerprints.size)
            CheckEmailedResult(
                alreadyEmailed = alreadyEmailed,
                newFingerprints = newFingerprints,
                error = if (!json.optBoolean("success", false)) json.optString("message", "Unknown error") else null,
            )
        } catch (e: Exception) {
            logApiFailure(endpoint, ErrorCode.NETWORK_ERROR, "exception" to (e.message?.take(100)))
            CheckEmailedResult(alreadyEmailed = emptyList(), newFingerprints = emptyList(), error = e.message)
        }
    }

    // ================================================================
    // MARK EMAILED FINGERPRINTS
    // POST /api/scheduler/mark-emailed-jobs
    // ================================================================

    suspend fun markEmailedFingerprints(
        fingerprints: List<String>,
        preferenceId: String,
        emailRecordId: String? = null,
    ): MarkEmailedResult {
        val endpoint = "/api/scheduler/mark-emailed-jobs"
        logApiStart(endpoint, "fingerprints" to fingerprints.size, "preferenceId" to preferenceId)
        return try {
            val body = JSONObject().apply {
                put("fingerprints", JSONArray(fingerprints))
                put("preferenceId", preferenceId)
                emailRecordId?.let { put("emailRecordId", it) }
            }
            val json = executePost(endpoint, body.toString())
            val data = json.optJSONObject("data")
            val marked = data?.optInt("marked", 0) ?: 0
            val total = data?.optInt("total", 0) ?: 0
            val ok = json.optBoolean("success", false)
            if (ok) {
                logApiSuccess(endpoint, "marked" to marked, "total" to total)
            } else {
                logApiFailure(endpoint, ErrorCode.NETWORK_ERROR, "marked" to marked, "total" to total)
            }
            MarkEmailedResult(
                success = ok,
                marked = marked,
                total = total,
                error = if (!ok) json.optString("message", "Unknown error") else null,
                errorCode = if (!ok) ErrorCode.NETWORK_ERROR.code else null,
            )
        } catch (e: Exception) {
            logApiFailure(endpoint, ErrorCode.NETWORK_ERROR, "exception" to (e.message?.take(100)))
            MarkEmailedResult(success = false, error = e.message, errorCode = ErrorCode.NETWORK_ERROR.code)
        }
    }

    // ================================================================
    // SEND JOB ALERT EMAIL
    // POST /api/notifications/send-email
    // ================================================================

    suspend fun sendJobAlertEmail(
        to: String,
        preferenceId: String,
        preferenceName: String?,
        newJobs: List<JSONObject>,
        allJobsCount: Int,
        deviceId: String? = null,
        runId: String? = null,
    ): EmailResult {
        val endpoint = "/api/notifications/send-email"
        logApiStart(endpoint, "to" to FcLog.maskEmail(to), "preferenceId" to preferenceId, "newJobs" to newJobs.size)
        return try {
            val body = JSONObject().apply {
                put("to", to)
                put("preferenceId", preferenceId)
                preferenceName?.let { put("preferenceName", it) }
                put("newJobs", JSONArray(newJobs))
                put("allJobs", allJobsCount)
                deviceId?.let { put("deviceId", it) }
                runId?.let { put("runId", it) }
            }
            val json = executePost(endpoint, body.toString())
            if (json.optBoolean("success", false)) {
                val emailSent = json.optBoolean("emailSent", false)
                val jobsSent = json.optInt("jobsSent", 0)
                val pdfAttached = json.optBoolean("pdfAttached", false)
                logApiSuccess(endpoint, "emailSent" to emailSent, "jobsSent" to jobsSent, "pdfAttached" to pdfAttached)
                EmailResult(
                    success = true,
                    emailSent = emailSent,
                    jobEmailSent = json.optBoolean("jobEmailSent", false),
                    jobsSent = jobsSent,
                    pdfAttached = pdfAttached,
                    errorCode = json.optString("errorCode", "").takeIf { it.isNotBlank() },
                )
            } else {
                val errorCode = json.optString("errorCode", "").takeIf { it.isNotBlank() } ?: ErrorCode.NETWORK_ERROR.code
                logApiFailure(endpoint, ErrorCode.NETWORK_ERROR, "errorCode" to errorCode)
                EmailResult(
                    success = false,
                    errorCode = errorCode,
                    error = json.optString("message", "Unknown error"),
                )
            }
        } catch (e: Exception) {
            logApiFailure(endpoint, ErrorCode.NETWORK_ERROR, "exception" to (e.message?.take(100)))
            EmailResult(success = false, error = e.message, errorCode = ErrorCode.NETWORK_ERROR.code)
        }
    }

    // ================================================================
    // SEND SETUP CONFIRMATION EMAIL
    // POST /api/notifications/send-setup-email
    // ================================================================

    suspend fun sendSetupConfirmationEmail(
        to: String,
        preferenceId: String,
        preferenceDetails: String? = null,
        role: String? = null,
        location: String? = null,
        experience: String? = null,
        skills: String? = null,
        remote: Boolean? = null,
        datePosted: String? = null,
        intervalMinutes: Long? = null,
    ): EmailResult {
        val endpoint = "/api/notifications/send-setup-email"
        logApiStart(endpoint, "to" to FcLog.maskEmail(to), "preferenceId" to preferenceId)
        return try {
            val body = JSONObject().apply {
                put("to", to)
                put("preferenceId", preferenceId)
                preferenceDetails?.let { put("preferenceDetails", it) }
                role?.let { put("role", it) }
                location?.let { put("location", it) }
                experience?.let { put("experience", it) }
                skills?.let { put("skills", it) }
                remote?.let { put("remote", it) }
                datePosted?.let { put("datePosted", it) }
                intervalMinutes?.let { put("intervalMinutes", it) }
            }
            val json = executePost(endpoint, body.toString())
            if (json.optBoolean("success", false)) {
                val emailSent = json.optBoolean("emailSent", false)
                val setupEmailSent = json.optBoolean("setupEmailSent", json.optBoolean("emailSent", false))
                logApiSuccess(endpoint, "emailSent" to emailSent, "setupEmailSent" to setupEmailSent)
                EmailResult(
                    success = true,
                    emailSent = emailSent,
                    setupEmailSent = setupEmailSent,
                    errorCode = json.optString("errorCode", "").takeIf { it.isNotBlank() },
                )
            } else {
                val errorCode = json.optString("errorCode", "").takeIf { it.isNotBlank() } ?: ErrorCode.NETWORK_ERROR.code
                logApiFailure(endpoint, ErrorCode.NETWORK_ERROR, "errorCode" to errorCode)
                EmailResult(
                    success = false,
                    emailSent = false,
                    setupEmailSent = false,
                    errorCode = errorCode,
                    error = json.optString("message", "Unknown error"),
                )
            }
        } catch (e: Exception) {
            val causeChain = generateSequence(e.cause) { it.cause }
                .take(5)
                .joinToString(" -> ") { "${it::class.java.simpleName}: ${it.message}" }
            val msg = "type=${e::class.java.name} | message=${e.message} | causeChain=[$causeChain]"
            FcLog.e(FcLog.TAG_EMAIL, "sendSetupConfirmationEmail failed", null, mapOf(
                "details" to msg,
            ))
            logApiFailure(endpoint, ErrorCode.NETWORK_ERROR, "exception" to (e.message?.take(100)))
            EmailResult(success = false, errorCode = ErrorCode.NETWORK_ERROR.code, error = e.message)
        }
    }

    // ================================================================
    // PING BACKEND
    // GET /api/health
    // ================================================================

    suspend fun pingBackend(): PingResult {
        val endpoint = "/api/health"
        logApiStart(endpoint)
        return try {
            val json = executeGet(endpoint)
            val ok = json.optBoolean("ok", json.optBoolean("success", false))
            if (ok) {
                logApiSuccess(endpoint, "status" to (json.optString("status").takeIf { it.isNotBlank() } ?: "ok"))
            } else {
                logApiFailure(endpoint, ErrorCode.NETWORK_ERROR, "status" to (json.optString("status") ?: "unhealthy"))
            }
            PingResult(
                success = ok,
                status = json.optString("status", "").takeIf { it.isNotBlank() },
                errorCode = json.optString("errorCode", "").takeIf { it.isNotBlank() },
                error = json.optString("message", "").takeIf { it.isNotBlank() },
            )
        } catch (e: Exception) {
            logApiFailure(endpoint, ErrorCode.NETWORK_ERROR, "exception" to (e.message?.take(100)))
            PingResult(success = false, errorCode = ErrorCode.NETWORK_ERROR.code, error = e.message)
        }
    }

    // ================================================================
    // GET EMAIL HISTORY
    // GET /api/scheduler/history
    // ================================================================

    suspend fun getEmailHistory(preferenceId: String? = null): EmailHistoryResult {
        val endpoint = "/api/scheduler/history"
        logApiStart(endpoint, "preferenceId" to preferenceId)
        return try {
            val params = mutableMapOf<String, String>()
            preferenceId?.let { params["preferenceId"] = it }
            val json = executeGet(endpoint, params)
            if (json.optBoolean("success", false)) {
                val arr = json.optJSONArray("history") ?: JSONArray()
                val items = (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    EmailHistoryItem(
                        emailSentAt = obj.optString("emailSentAt", ""),
                        jobsSentCount = obj.optInt("jobsSentCount", 0),
                        pdfAttached = obj.optBoolean("pdfAttached", false),
                        title = obj.optString("title", "Job alert delivered successfully"),
                    )
                }
                logApiSuccess(endpoint, "historySize" to items.size)
                EmailHistoryResult(success = true, history = items)
            } else {
                logApiFailure(endpoint, ErrorCode.NETWORK_ERROR)
                EmailHistoryResult(success = false, error = json.optString("message", "Unknown error"))
            }
        } catch (e: Exception) {
            logApiFailure(endpoint, ErrorCode.NETWORK_ERROR, "exception" to (e.message?.take(100)))
            EmailHistoryResult(success = false, error = e.message)
        }
    }

    // ================================================================
    // GET USER TIMELINE
    // GET /api/scheduler/user-timeline
    // ================================================================

    suspend fun getUserTimeline(): UserTimelineResult {
        val endpoint = "/api/scheduler/user-timeline"
        logApiStart(endpoint)
        return try {
            val json = executeGet(endpoint)
            if (json.optBoolean("success", false)) {
                val arr = json.optJSONArray("timeline") ?: JSONArray()
                val items = (0 until arr.length()).map { i ->
                    parseTimelineEvent(arr.getJSONObject(i))
                }
                logApiSuccess(endpoint, "timelineSize" to items.size)
                UserTimelineResult(success = true, timeline = items)
            } else {
                logApiFailure(endpoint, ErrorCode.NETWORK_ERROR)
                UserTimelineResult(success = false, error = json.optString("message", "Unknown error"))
            }
        } catch (e: Exception) {
            logApiFailure(endpoint, ErrorCode.NETWORK_ERROR, "exception" to (e.message?.take(100)))
            UserTimelineResult(success = false, error = e.message)
        }
    }

    private fun parseTimelineEvent(obj: JSONObject): TimelineEvent {
        val metaObj = obj.optJSONObject("metadata")
        return TimelineEvent(
            type = obj.optString("type", ""),
            timestamp = obj.optString("timestamp", ""),
            title = obj.optString("title", ""),
            description = obj.optString("description", ""),
            status = obj.optString("status", "").takeIf { s -> s.isNotEmpty() },
            runId = obj.optString("runId", "").takeIf { s -> s.isNotEmpty() },
            metadata = metaObj?.let {
                TimelineEventMetadata(
                    deviceName = it.optString("deviceName", "").takeIf { s -> s.isNotEmpty() },
                    devicePlatform = it.optString("devicePlatform", "").takeIf { s -> s.isNotEmpty() },
                    intervalMinutes = if (it.has("intervalMinutes") && !it.isNull("intervalMinutes")) it.optInt("intervalMinutes") else null,
                    jobsSentCount = if (it.has("jobsSentCount") && !it.isNull("jobsSentCount")) it.optInt("jobsSentCount") else null,
                    jobsFound = if (it.has("jobsFound") && !it.isNull("jobsFound")) it.optInt("jobsFound") else null,
                    jobsSent = if (it.has("jobsSent") && !it.isNull("jobsSent")) it.optInt("jobsSent") else null,
                    pdfAttached = if (it.has("pdfAttached") && !it.isNull("pdfAttached")) it.optBoolean("pdfAttached") else null,
                    role = it.optString("role", "").takeIf { s -> s.isNotEmpty() },
                    location = it.optString("location", "").takeIf { s -> s.isNotEmpty() },
                    errorMessage = it.optString("errorMessage", "").takeIf { s -> s.isNotEmpty() },
                )
            },
        )
    }

    // ================================================================
    // SYNC PAUSED STATE
    // POST /api/scheduler/sync-state
    // ================================================================

    suspend fun syncPausedState(
        deviceId: String,
        deviceType: String = "android",
        userEmail: String,
        paused: Boolean,
    ): SyncPausedStateResult {
        val endpoint = "/api/scheduler/sync-state"
        logApiStart(endpoint, "deviceId" to FcLog.maskDeviceId(deviceId), "paused" to paused)
        return try {
            val schedulerState = JSONObject().apply {
                put("pausedDueToLogout", paused)
                put("schedulerEnabled", !paused)
                put("syncedAt", System.currentTimeMillis())
            }
            val body = JSONObject().apply {
                put("deviceId", deviceId)
                put("deviceType", deviceType)
                put("schedulerState", schedulerState)
            }
            val json = executePost(endpoint, body.toString())
            val ok = json.optBoolean("success", false)
            if (ok) {
                logApiSuccess(endpoint, "paused" to paused)
            } else {
                logApiFailure(endpoint, ErrorCode.NETWORK_ERROR)
            }
            SyncPausedStateResult(
                success = ok,
                error = if (!ok) json.optString("message", "Unknown error") else null,
                errorCode = if (!ok) ErrorCode.NETWORK_ERROR.code else null,
            )
        } catch (e: Exception) {
            logApiFailure(endpoint, ErrorCode.NETWORK_ERROR, "exception" to (e.message?.take(100)))
            SyncPausedStateResult(success = false, error = e.message, errorCode = ErrorCode.NETWORK_ERROR.code)
        }
    }

    // ================================================================
    // SYNC STATE
    // POST /api/scheduler/sync-state
    // ================================================================

    suspend fun syncState(
        deviceId: String,
        deviceType: String = "android",
        appVersion: String? = null,
        preferences: JSONObject? = null,
        schedulerState: JSONObject? = null,
        recentRuns: List<JSONObject>? = null,
    ): SyncStateResult {
        val endpoint = "/api/scheduler/sync-state"
        logApiStart(endpoint, "deviceId" to FcLog.maskDeviceId(deviceId))
        return try {
            val body = JSONObject().apply {
                put("deviceId", deviceId)
                put("deviceType", deviceType)
                appVersion?.let { put("appVersion", it) }
                preferences?.let { put("preferences", it) }
                schedulerState?.let { put("schedulerState", it) }
                if (recentRuns != null && recentRuns.isNotEmpty()) {
                    put("recentRuns", JSONArray(recentRuns))
                }
            }
            val json = executePost(endpoint, body.toString())
            val data = json.optJSONObject("data")
            if (json.optBoolean("success", false)) {
                logApiSuccess(endpoint, "syncedAt" to (data?.optString("syncedAt", "")?.take(24)))
                SyncStateResult(
                    success = true,
                    syncedAt = data?.optString("syncedAt", "")?.takeIf { it.isNotEmpty() },
                )
            } else {
                logApiFailure(endpoint, ErrorCode.NETWORK_ERROR)
                SyncStateResult(
                    success = false,
                    error = json.optString("message", "Unknown error"),
                )
            }
        } catch (e: Exception) {
            logApiFailure(endpoint, ErrorCode.NETWORK_ERROR, "exception" to (e.message?.take(100)))
            SyncStateResult(success = false, error = e.message)
        }
    }

    // ================================================================
    // GET STATE
    // GET /api/scheduler/state?deviceId=xxx
    // ================================================================

    suspend fun getState(deviceId: String): DeviceState {
        val endpoint = "/api/scheduler/state"
        logApiStart(endpoint, "deviceId" to FcLog.maskDeviceId(deviceId))
        return try {
            val json = executeGet(endpoint, mapOf("deviceId" to deviceId))
            val data = json.optJSONObject("data")
            logApiSuccess(endpoint, "hasDeviceState" to (data?.optJSONObject("deviceState") != null))
            DeviceState(
                deviceState = data?.optJSONObject("deviceState"),
                activeLocks = data?.optJSONArray("activeLocks")
                    ?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
                    ?: emptyList(),
                recentRuns = data?.optJSONArray("recentRuns")
                    ?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
                    ?: emptyList(),
            )
        } catch (e: Exception) {
            logApiFailure(endpoint, ErrorCode.NETWORK_ERROR, "exception" to (e.message?.take(100)))
            DeviceState()
        }
    }

    // ================================================================
    // GET ACTIVE DEVICE
    // GET /api/scheduler/active-device?deviceId=xxx
    // ================================================================

    suspend fun getActiveDevice(deviceId: String): ActiveDeviceStatusResult {
        val endpoint = "/api/scheduler/active-device"
        logApiStart(endpoint, "deviceId" to FcLog.maskDeviceId(deviceId))
        return try {
            val json = executeGet(endpoint, mapOf("deviceId" to deviceId))
            val success = json.optBoolean("success", false)
            val activeDeviceJson = json.optJSONObject("activeDevice")
            if (success) {
                logApiSuccess(endpoint, "hasActiveDevice" to (activeDeviceJson != null), "isCurrentDeviceActive" to json.optBoolean("isCurrentDeviceActive", false))
            } else {
                logApiFailure(endpoint, ErrorCode.NETWORK_ERROR)
            }
            ActiveDeviceStatusResult(
                success = success,
                activeDevice = activeDeviceJson?.let { parseActiveDeviceDto(it) },
                isCurrentDeviceActive = json.optBoolean("isCurrentDeviceActive", false),
                error = if (!success) json.optString("message", "Unknown error") else null,
            )
        } catch (e: Exception) {
            logApiFailure(endpoint, ErrorCode.NETWORK_ERROR, "exception" to (e.message?.take(100)))
            ActiveDeviceStatusResult(success = false, error = e.message)
        }
    }

    // ================================================================
    // ACTIVATE DEVICE
    // POST /api/scheduler/active-device/activate
    // ================================================================

    suspend fun activateDevice(
        deviceIdentity: DeviceIdentity,
        schedulerPreferenceId: String? = null,
        intervalMinutes: Long? = null,
        takeover: Boolean = false,
    ): ActiveDeviceStatusResult {
        val endpoint = "/api/scheduler/active-device/activate"
        logApiStart(endpoint, "deviceId" to FcLog.maskDeviceId(deviceIdentity.deviceId), "takeover" to takeover)
        return try {
            val body = JSONObject().apply {
                put("deviceId", deviceIdentity.deviceId)
                put("deviceName", deviceIdentity.deviceName)
                put("devicePlatform", deviceIdentity.devicePlatform)
                schedulerPreferenceId?.let { put("schedulerPreferenceId", it) }
                intervalMinutes?.let { put("intervalMinutes", it) }
                put("takeover", takeover)
            }
            val (httpCode, json) = executePostWithCode(endpoint, body.toString())
            val activeDeviceJson = json.optJSONObject("activeDevice")
            val success = json.optBoolean("success", false)
            if (success) {
                logApiSuccess(endpoint, "isCurrentDeviceActive" to true)
            } else {
                logApiFailure(endpoint, mapErrorCode(httpCode, json), "errorCode" to json.optString("errorCode"))
            }
            ActiveDeviceStatusResult(
                success = success,
                activeDevice = activeDeviceJson?.let { parseActiveDeviceDto(it) },
                isCurrentDeviceActive = json.optBoolean("isCurrentDeviceActive", success),
                errorCode = json.optString("errorCode", "").takeIf { it.isNotEmpty() },
                error = if (!success) json.optString("message", "Unknown error") else null,
            )
        } catch (e: Exception) {
            logApiFailure(endpoint, ErrorCode.NETWORK_ERROR, "exception" to (e.message?.take(100)))
            ActiveDeviceStatusResult(success = false, error = e.message)
        }
    }

    // ================================================================
    // HEARTBEAT ACTIVE DEVICE
    // POST /api/scheduler/active-device/heartbeat
    // ================================================================

    suspend fun heartbeatActiveDevice(
        deviceId: String,
        schedulerPreferenceId: String? = null,
        lastJobEmailAt: String? = null,
    ): ActiveDeviceStatusResult {
        val endpoint = "/api/scheduler/active-device/heartbeat"
        logApiStart(endpoint, "deviceId" to FcLog.maskDeviceId(deviceId))
        return try {
            val body = JSONObject().apply {
                put("deviceId", deviceId)
                schedulerPreferenceId?.let { put("schedulerPreferenceId", it) }
                lastJobEmailAt?.let { put("lastJobEmailAt", it) }
            }
            val json = executePost(endpoint, body.toString())
            val activeDeviceJson = json.optJSONObject("activeDevice")
            val isCurrentDeviceActive = json.optBoolean("isCurrentDeviceActive", false)
            if (isCurrentDeviceActive) {
                logApiSuccess(endpoint, "isCurrentDeviceActive" to true)
            } else {
                logApiFailure(endpoint, ErrorCode.NETWORK_ERROR, "isCurrentDeviceActive" to false)
            }
            ActiveDeviceStatusResult(
                success = json.optBoolean("success", false),
                activeDevice = activeDeviceJson?.let { parseActiveDeviceDto(it) },
                isCurrentDeviceActive = isCurrentDeviceActive,
                error = if (!json.optBoolean("success", false)) json.optString("message", "Unknown error") else null,
            )
        } catch (e: Exception) {
            logApiFailure(endpoint, ErrorCode.NETWORK_ERROR, "exception" to (e.message?.take(100)))
            ActiveDeviceStatusResult(success = false, error = e.message)
        }
    }

    // ================================================================
    // DEACTIVATE DEVICE
    // POST /api/scheduler/active-device/deactivate
    // ================================================================

    suspend fun deactivateDevice(
        deviceId: String,
        schedulerPreferenceId: String? = null,
    ): ActiveDeviceStatusResult {
        val endpoint = "/api/scheduler/active-device/deactivate"
        logApiStart(endpoint, "deviceId" to FcLog.maskDeviceId(deviceId))
        return try {
            val body = JSONObject().apply {
                put("deviceId", deviceId)
                schedulerPreferenceId?.let { put("schedulerPreferenceId", it) }
            }
            val json = executePost(endpoint, body.toString())
            val success = json.optBoolean("success", false)
            val deactivated = json.optBoolean("deactivated", false)
            if (success && deactivated) {
                logApiSuccess(endpoint, "deactivated" to true)
            } else {
                logApiFailure(endpoint, ErrorCode.NETWORK_ERROR, "deactivated" to deactivated)
            }
            ActiveDeviceStatusResult(
                success = success,
                deactivated = deactivated,
                reason = json.optString("reason", "")?.takeIf { it.isNotEmpty() },
                error = if (!success) json.optString("message", "Unknown error") else null,
            )
        } catch (e: Exception) {
            logApiFailure(endpoint, ErrorCode.NETWORK_ERROR, "exception" to (e.message?.take(100)))
            ActiveDeviceStatusResult(success = false, error = e.message)
        }
    }

    // ================================================================
    // VERIFY ACTIVE DEVICE CAN SEND
    // POST /api/scheduler/active-device/verify
    // ================================================================

    suspend fun verifyActiveDevice(
        deviceId: String,
        schedulerPreferenceId: String? = null,
    ): ActiveDeviceVerificationResult {
        val endpoint = "/api/scheduler/active-device/verify"
        logApiStart(endpoint, "deviceId" to FcLog.maskDeviceId(deviceId))
        return try {
            val body = JSONObject().apply {
                put("deviceId", deviceId)
                schedulerPreferenceId?.let { put("schedulerPreferenceId", it) }
            }
            val json = executePost(endpoint, body.toString())
            val success = json.optBoolean("success", false)
            val canSend = json.optBoolean("canSend", false)
            if (success && canSend) {
                logApiSuccess(endpoint, "canSend" to true)
            } else {
                val reason = json.optString("reason", "unknown")
                logApiFailure(endpoint, ErrorCode.NETWORK_ERROR, "canSend" to false, "reason" to reason)
            }
            ActiveDeviceVerificationResult(
                success = success,
                canSend = canSend,
                reason = json.optString("reason", "")?.takeIf { it.isNotEmpty() },
                error = if (!success) json.optString("message", "Unknown error") else null,
                errorCode = if (!success) ErrorCode.NETWORK_ERROR.code else null,
            )
        } catch (e: Exception) {
            logApiFailure(endpoint, ErrorCode.NETWORK_ERROR, "exception" to (e.message?.take(100)))
            ActiveDeviceVerificationResult(success = false, canSend = false, error = e.message, errorCode = ErrorCode.NETWORK_ERROR.code)
        }
    }

    // ================================================================
    // Shared Parsing Helpers
    // ================================================================

    private fun parseActiveDeviceDto(json: JSONObject): ActiveDeviceDto {
        return ActiveDeviceDto(
            deviceId = json.optString("deviceId", "")?.takeIf { it.isNotEmpty() },
            deviceName = json.optString("deviceName", "")?.takeIf { it.isNotEmpty() },
            devicePlatform = json.optString("devicePlatform", "")?.takeIf { it.isNotEmpty() },
            activeDeviceLastSeenAt = json.optString("activeDeviceLastSeenAt", "")?.takeIf { it.isNotEmpty() },
            activatedAt = json.optString("activatedAt", "")?.takeIf { it.isNotEmpty() },
            stoppedAt = json.optString("stoppedAt", "")?.takeIf { it.isNotEmpty() },
            lastSetupEmailAt = json.optString("lastSetupEmailAt", "")?.takeIf { it.isNotEmpty() },
            lastJobEmailAt = json.optString("lastJobEmailAt", "")?.takeIf { it.isNotEmpty() },
            intervalMinutes = if (json.has("intervalMinutes") && !json.isNull("intervalMinutes")) json.optInt("intervalMinutes") else null,
            schedulerPreferenceId = json.optString("schedulerPreferenceId", "")?.takeIf { it.isNotEmpty() },
            preferences = json.optJSONObject("preferences")?.let { parseActiveSchedulerPreferences(it) },
            schedulerState = json.optJSONObject("schedulerState")?.let { parseActiveSchedulerState(it) },
            lastRunAt = json.optString("lastRunAt", "").takeIf { it.isNotEmpty() },
            nextApproxRunAt = json.optString("nextApproxRunAt", "").takeIf { it.isNotEmpty() },
        )
    }

    private fun parseActiveSchedulerPreferences(json: JSONObject): ActiveSchedulerPreferencesDto {
        return ActiveSchedulerPreferencesDto(
            preferenceId = json.optString("preferenceId", "").takeIf { it.isNotEmpty() },
            role = json.optString("role", "").takeIf { it.isNotEmpty() },
            location = json.optString("location", "").takeIf { it.isNotEmpty() },
            experience = json.optString("experience", "").takeIf { it.isNotEmpty() },
            skills = json.optString("skills", "").takeIf { it.isNotEmpty() },
            company = json.optString("company", "").takeIf { it.isNotEmpty() },
            datePosted = json.optString("datePosted", "").takeIf { it.isNotEmpty() },
            reportFormat = json.optString("reportFormat", "").takeIf { it.isNotEmpty() },
            intervalMinutes = if (json.has("intervalMinutes") && !json.isNull("intervalMinutes")) json.optInt("intervalMinutes") else null,
            remote = if (json.has("remote") && !json.isNull("remote")) json.optBoolean("remote") else null,
            salaryMin = if (json.has("salaryMin") && !json.isNull("salaryMin")) json.optLong("salaryMin") else null,
            salaryMax = if (json.has("salaryMax") && !json.isNull("salaryMax")) json.optLong("salaryMax") else null,
        )
    }

    private fun parseActiveSchedulerState(json: JSONObject): ActiveSchedulerStateDto {
        return ActiveSchedulerStateDto(
            preferenceId = json.optString("preferenceId", "").takeIf { it.isNotEmpty() },
            schedulerEnabled = if (json.has("schedulerEnabled") && !json.isNull("schedulerEnabled")) json.optBoolean("schedulerEnabled") else null,
            lastRunAt = json.optString("lastRunAt", "").takeIf { it.isNotEmpty() },
            nextScheduledRunAt = json.optString("nextScheduledRunAt", "").takeIf { it.isNotEmpty() },
            intervalMinutes = if (json.has("intervalMinutes") && !json.isNull("intervalMinutes")) json.optInt("intervalMinutes") else null,
        )
    }
}
