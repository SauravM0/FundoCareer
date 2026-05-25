package com.fundocareer.app.core.jobalerts.provider

import android.util.Log
import com.fundocareer.app.BuildConfig
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
        private const val TAG = "JobAlertApiClient"
        private const val TIMEOUT = 15L

        fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT, TimeUnit.SECONDS)
            .build()
    }

    private val baseUrl: String get() = BuildConfig.API_BASE_URL
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    data class LockResult(
        val granted: Boolean,
        val heldBy: String? = null,
        val heldByDeviceType: String? = null,
        val expiresAt: String? = null,
        val error: String? = null
    )

    data class EmailResult(
        val success: Boolean,
        val emailSent: Boolean = false,
        val setupEmailSent: Boolean = false,
        val jobEmailSent: Boolean = false,
        val jobsSent: Int = 0,
        val pdfAttached: Boolean = false,
        val error: String? = null
    )

    data class CheckEmailedResult(
        val alreadyEmailed: List<String>,
        val newFingerprints: List<String>,
        val error: String? = null
    )

    data class EmailHistoryItem(
        val emailSentAt: String,
        val jobsSentCount: Int,
        val pdfAttached: Boolean,
        val title: String
    )

    data class EmailHistoryResult(
        val success: Boolean,
        val history: List<EmailHistoryItem> = emptyList(),
        val error: String? = null
    )

    data class TimelineEvent(
        val type: String,
        val timestamp: String,
        val title: String,
        val description: String,
        val metadata: TimelineEventMetadata? = null,
    )

    data class TimelineEventMetadata(
        val deviceName: String? = null,
        val devicePlatform: String? = null,
        val intervalMinutes: Int? = null,
        val jobsSentCount: Int? = null,
        val pdfAttached: Boolean? = null,
        val role: String? = null,
        val location: String? = null,
    )

    data class UserTimelineResult(
        val success: Boolean,
        val timeline: List<TimelineEvent> = emptyList(),
        val error: String? = null,
    )

    suspend fun acquireLock(
        preferenceSetId: String,
        deviceId: String,
        deviceType: String = "android",
        appVersion: String? = null
    ): LockResult {
        return try {
            val body = JSONObject().apply {
                put("preferenceSetId", preferenceSetId)
                put("deviceId", deviceId)
                put("deviceType", deviceType)
                appVersion?.let { put("appVersion", it) }
            }
            val json = post("/api/scheduler/acquire-lock", body.toString())
            val data = json.optJSONObject("data")
            LockResult(
                granted = data?.optBoolean("granted", false) ?: false,
                heldBy = data?.optString("heldBy", "")?.takeIf { it.isNotEmpty() },
                heldByDeviceType = data?.optString("heldByDeviceType", "")?.takeIf { it.isNotEmpty() },
                expiresAt = data?.optString("expiresAt", "")?.takeIf { it.isNotEmpty() },
                error = if (!json.optBoolean("success", false)) json.optString("message", "Unknown error") else null
            )
        } catch (e: Exception) {
            Log.e(TAG, "acquireLock failed", e)
            LockResult(granted = false, error = e.message)
        }
    }

    suspend fun releaseLock(preferenceSetId: String, deviceId: String): Boolean {
        return try {
            val body = JSONObject().apply {
                put("preferenceSetId", preferenceSetId)
                put("deviceId", deviceId)
            }
            val json = post("/api/scheduler/release-lock", body.toString())
            json.optJSONObject("data")?.optBoolean("released", false) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "releaseLock failed", e)
            false
        }
    }

    suspend fun checkEmailedFingerprints(
        fingerprints: List<String>,
        preferenceId: String
    ): CheckEmailedResult {
        return try {
            val body = JSONObject().apply {
                put("fingerprints", JSONArray(fingerprints))
                put("preferenceId", preferenceId)
            }
            val json = post("/api/scheduler/check-emailed-jobs", body.toString())
            val data = json.optJSONObject("data")
            CheckEmailedResult(
                alreadyEmailed = data?.optJSONArray("alreadyEmailed")
                    ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
                    ?: emptyList(),
                newFingerprints = data?.optJSONArray("new")
                    ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
                    ?: emptyList()
            )
        } catch (e: Exception) {
            Log.e(TAG, "checkEmailedFingerprints failed", e)
            CheckEmailedResult(alreadyEmailed = emptyList(), newFingerprints = emptyList(), error = e.message)
        }
    }

    suspend fun getEmailHistory(preferenceId: String? = null): EmailHistoryResult {
        return try {
            val params = mutableMapOf<String, String>()
            preferenceId?.let { params["preferenceId"] = it }
            val json = get("/api/scheduler/history", params)
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
                EmailHistoryResult(success = true, history = items)
            } else {
                EmailHistoryResult(success = false, error = json.optString("message", "Unknown error"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getEmailHistory failed", e)
            EmailHistoryResult(success = false, error = e.message)
        }
    }

    suspend fun getUserTimeline(): UserTimelineResult {
        return try {
            val json = get("/api/scheduler/user-timeline")
            if (json.optBoolean("success", false)) {
                val arr = json.optJSONArray("timeline") ?: JSONArray()
                val items = (0 until arr.length()).map { i ->
                    parseTimelineEvent(arr.getJSONObject(i))
                }
                UserTimelineResult(success = true, timeline = items)
            } else {
                UserTimelineResult(success = false, error = json.optString("message", "Unknown error"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getUserTimeline failed", e)
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
            metadata = metaObj?.let {
                TimelineEventMetadata(
                    deviceName = it.optString("deviceName", "").takeIf { s -> s.isNotEmpty() },
                    devicePlatform = it.optString("devicePlatform", "").takeIf { s -> s.isNotEmpty() },
                    intervalMinutes = if (it.has("intervalMinutes") && !it.isNull("intervalMinutes")) it.optInt("intervalMinutes") else null,
                    jobsSentCount = if (it.has("jobsSentCount") && !it.isNull("jobsSentCount")) it.optInt("jobsSentCount") else null,
                    pdfAttached = if (it.has("pdfAttached") && !it.isNull("pdfAttached")) it.optBoolean("pdfAttached") else null,
                    role = it.optString("role", "").takeIf { s -> s.isNotEmpty() },
                    location = it.optString("location", "").takeIf { s -> s.isNotEmpty() },
                )
            },
        )
    }

    suspend fun markEmailedFingerprints(
        fingerprints: List<String>,
        preferenceId: String,
        emailRecordId: String? = null
    ): Boolean {
        return try {
            val body = JSONObject().apply {
                put("fingerprints", JSONArray(fingerprints))
                put("preferenceId", preferenceId)
                emailRecordId?.let { put("emailRecordId", it) }
            }
            val json = post("/api/scheduler/mark-emailed-jobs", body.toString())
            json.optBoolean("success", false)
        } catch (e: Exception) {
            Log.e(TAG, "markEmailedFingerprints failed", e)
            false
        }
    }

    suspend fun sendJobAlertEmail(
        to: String,
        preferenceId: String,
        preferenceName: String?,
        newJobs: List<JSONObject>,
        allJobsCount: Int,
        deviceId: String? = null,
        runId: String? = null,
    ): EmailResult {
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
            val json = post("/api/notifications/send-email", body.toString())
            if (json.optBoolean("success", false)) {
                EmailResult(
                    success = true,
                    emailSent = json.optBoolean("emailSent", false),
                    jobEmailSent = json.optBoolean("jobEmailSent", false),
                    jobsSent = json.optInt("jobsSent", 0),
                    pdfAttached = json.optBoolean("pdfAttached", false),
                )
            } else {
                EmailResult(
                    success = false,
                    error = json.optString("message", "Unknown error")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendJobAlertEmail failed", e)
            EmailResult(success = false, error = e.message)
        }
    }

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
            val json = post("/api/notifications/send-setup-email", body.toString())
            if (json.optBoolean("success", false)) {
                EmailResult(
                    success = true,
                    setupEmailSent = json.optBoolean("setupEmailSent", false),
                )
            } else {
                EmailResult(
                    success = false,
                    error = json.optString("message", "Unknown error")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendSetupConfirmationEmail failed", e)
            EmailResult(success = false, error = e.message)
        }
    }

    suspend fun syncPausedState(
        deviceId: String,
        deviceType: String = "android",
        userEmail: String,
        paused: Boolean
    ): Boolean {
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
            val json = post("/api/scheduler/sync-state", body.toString())
            json.optBoolean("success", false)
        } catch (e: Exception) {
            Log.e(TAG, "syncPausedState failed", e)
            false
        }
    }

    data class SyncStateResult(
        val success: Boolean,
        val syncedAt: String? = null,
        val error: String? = null
    )

    suspend fun syncState(
        deviceId: String,
        deviceType: String = "android",
        appVersion: String? = null,
        preferences: JSONObject? = null,
        schedulerState: JSONObject? = null,
        recentRuns: List<JSONObject>? = null
    ): SyncStateResult {
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
            val json = post("/api/scheduler/sync-state", body.toString())
            val data = json.optJSONObject("data")
            if (json.optBoolean("success", false)) {
                SyncStateResult(
                    success = true,
                    syncedAt = data?.optString("syncedAt", null)
                )
            } else {
                SyncStateResult(
                    success = false,
                    error = json.optString("message", "Unknown error")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "syncState failed", e)
            SyncStateResult(success = false, error = e.message)
        }
    }

    data class DeviceState(
        val deviceState: JSONObject? = null,
        val activeLocks: List<JSONObject> = emptyList(),
        val recentRuns: List<JSONObject> = emptyList()
    )

    // ================================================================
    // Active Device Models
    // ================================================================

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
    )

    data class ActiveDeviceStatusResult(
        val success: Boolean,
        val activeDevice: ActiveDeviceDto? = null,
        val isCurrentDeviceActive: Boolean = false,
        val deactivated: Boolean = false,
        val reason: String? = null,
        val error: String? = null,
    )

    data class ActiveDeviceVerificationResult(
        val success: Boolean,
        val canSend: Boolean = false,
        val reason: String? = null,
        val error: String? = null,
    )

    suspend fun getState(deviceId: String): DeviceState {
        return try {
            val json = get("/api/scheduler/state", mapOf("deviceId" to deviceId))
            val data = json.optJSONObject("data")
            DeviceState(
                deviceState = data?.optJSONObject("deviceState"),
                activeLocks = data?.optJSONArray("activeLocks")
                    ?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
                    ?: emptyList(),
                recentRuns = data?.optJSONArray("recentRuns")
                    ?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
                    ?: emptyList()
            )
        } catch (e: Exception) {
            Log.e(TAG, "getState failed", e)
            DeviceState()
        }
    }

    // ================================================================
    // GET ACTIVE DEVICE
    // GET /api/scheduler/active-device?deviceId=xxx
    // ================================================================
    suspend fun getActiveDevice(deviceId: String): ActiveDeviceStatusResult {
        return try {
            val json = get("/api/scheduler/active-device", mapOf("deviceId" to deviceId))
            val activeDeviceJson = json.optJSONObject("activeDevice")
            ActiveDeviceStatusResult(
                success = json.optBoolean("success", false),
                activeDevice = activeDeviceJson?.let { parseActiveDeviceDto(it) },
                isCurrentDeviceActive = json.optBoolean("isCurrentDeviceActive", false),
                error = if (!json.optBoolean("success", false)) json.optString("message", "Unknown error") else null,
            )
        } catch (e: Exception) {
            Log.e(TAG, "getActiveDevice failed", e)
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
    ): ActiveDeviceStatusResult {
        return try {
            val body = JSONObject().apply {
                put("deviceId", deviceIdentity.deviceId)
                put("deviceName", deviceIdentity.deviceName)
                put("devicePlatform", deviceIdentity.devicePlatform)
                schedulerPreferenceId?.let { put("schedulerPreferenceId", it) }
                intervalMinutes?.let { put("intervalMinutes", it) }
            }
            val json = post("/api/scheduler/active-device/activate", body.toString())
            val activeDeviceJson = json.optJSONObject("activeDevice")
            ActiveDeviceStatusResult(
                success = json.optBoolean("success", false),
                activeDevice = activeDeviceJson?.let { parseActiveDeviceDto(it) },
                isCurrentDeviceActive = true,
                error = if (!json.optBoolean("success", false)) json.optString("message", "Unknown error") else null,
            )
        } catch (e: Exception) {
            Log.e(TAG, "activateDevice failed", e)
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
        return try {
            val body = JSONObject().apply {
                put("deviceId", deviceId)
                schedulerPreferenceId?.let { put("schedulerPreferenceId", it) }
                lastJobEmailAt?.let { put("lastJobEmailAt", it) }
            }
            val json = post("/api/scheduler/active-device/heartbeat", body.toString())
            val activeDeviceJson = json.optJSONObject("activeDevice")
            ActiveDeviceStatusResult(
                success = json.optBoolean("success", false),
                activeDevice = activeDeviceJson?.let { parseActiveDeviceDto(it) },
                isCurrentDeviceActive = json.optBoolean("isCurrentDeviceActive", false),
                error = if (!json.optBoolean("success", false)) json.optString("message", "Unknown error") else null,
            )
        } catch (e: Exception) {
            Log.e(TAG, "heartbeatActiveDevice failed", e)
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
        return try {
            val body = JSONObject().apply {
                put("deviceId", deviceId)
                schedulerPreferenceId?.let { put("schedulerPreferenceId", it) }
            }
            val json = post("/api/scheduler/active-device/deactivate", body.toString())
            ActiveDeviceStatusResult(
                success = json.optBoolean("success", false),
                deactivated = json.optBoolean("deactivated", false),
                reason = json.optString("reason", "")?.takeIf { it.isNotEmpty() },
                error = if (!json.optBoolean("success", false)) json.optString("message", "Unknown error") else null,
            )
        } catch (e: Exception) {
            Log.e(TAG, "deactivateDevice failed", e)
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
        return try {
            val body = JSONObject().apply {
                put("deviceId", deviceId)
                schedulerPreferenceId?.let { put("schedulerPreferenceId", it) }
            }
            val json = post("/api/scheduler/active-device/verify", body.toString())
            ActiveDeviceVerificationResult(
                success = json.optBoolean("success", false),
                canSend = json.optBoolean("canSend", false),
                reason = json.optString("reason", "")?.takeIf { it.isNotEmpty() },
                error = if (!json.optBoolean("success", false)) json.optString("message", "Unknown error") else null,
            )
        } catch (e: Exception) {
            Log.e(TAG, "verifyActiveDevice failed", e)
            ActiveDeviceVerificationResult(success = false, canSend = false, error = e.message)
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
        )
    }

    private fun post(path: String, jsonBody: String): JSONObject {
        val url = "${baseUrl}$path"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $authToken")
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toRequestBody(jsonMediaType))
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: "{}"
        Log.d(TAG, "POST $path → ${response.code}: ${body.take(300)}")
        return JSONObject(body)
    }

    private fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject {
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
        val body = response.body?.string() ?: "{}"
        Log.d(TAG, "GET $path → ${response.code}: ${body.take(300)}")
        return JSONObject(body)
    }
}
