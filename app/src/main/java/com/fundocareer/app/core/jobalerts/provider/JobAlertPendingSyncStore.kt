package com.fundocareer.app.core.jobalerts.provider

import android.content.Context
import com.fundocareer.app.core.logging.FcLog

suspend fun retryPendingSync(
    context: Context,
    authToken: String,
) {
    val store = JobAlertPendingSyncStore(context)
    if (!store.hasPending()) return

    val preferenceId = store.getPreferenceId()
    if (preferenceId.isNullOrBlank()) {
        store.clearAll()
        return
    }

    val apiClient = JobAlertApiClient(authToken)
    val identityProvider = JobAlertDeviceIdentityProvider(context)
    val identity = identityProvider.getDeviceIdentity()

    when {
        store.hasPendingDeactivation() -> {
            try {
                val result = apiClient.deactivateDevice(identity.deviceId, preferenceId)
                if (result.success) {
                    FcLog.i(FcLog.TAG_ACTIVE_DEVICE, "Pending deactivation succeeded", mapOf(
                        "preferenceId" to preferenceId,
                    ))
                    store.clearAll()
                } else {
                    FcLog.w(FcLog.TAG_ACTIVE_DEVICE, "Pending deactivation failed", mapOf(
                        "preferenceId" to preferenceId,
                        "error" to result.error,
                    ))
                    store.incrementRetry()
                }
            } catch (e: Exception) {
                FcLog.e(FcLog.TAG_ACTIVE_DEVICE, "Pending deactivation threw", e, mapOf(
                    "preferenceId" to preferenceId,
                ))
                store.incrementRetry()
            }
        }
        store.hasPendingActivation() -> {
            try {
                val result = apiClient.activateDevice(identity, preferenceId)
                if (result.success) {
                    FcLog.i(FcLog.TAG_ACTIVE_DEVICE, "Pending activation succeeded", mapOf(
                        "preferenceId" to preferenceId,
                    ))
                    store.clearAll()
                } else if (result.errorCode == "ACTIVE_DEVICE_EXISTS") {
                    FcLog.w(FcLog.TAG_ACTIVE_DEVICE, "Pending activation blocked by another active device", mapOf(
                        "preferenceId" to preferenceId,
                    ))
                    store.clearAll()
                } else {
                    FcLog.w(FcLog.TAG_ACTIVE_DEVICE, "Pending activation failed", mapOf(
                        "preferenceId" to preferenceId,
                        "error" to result.error,
                    ))
                    store.incrementRetry()
                }
            } catch (e: Exception) {
                FcLog.e(FcLog.TAG_ACTIVE_DEVICE, "Pending activation threw", e, mapOf(
                    "preferenceId" to preferenceId,
                ))
                store.incrementRetry()
            }
        }
    }
}

class JobAlertPendingSyncStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasPendingActivation(): Boolean =
        prefs.getBoolean(KEY_PENDING_ACTIVATION, false)

    fun hasPendingDeactivation(): Boolean =
        prefs.getBoolean(KEY_PENDING_DEACTIVATION, false)

    fun hasPending(): Boolean =
        hasPendingActivation() || hasPendingDeactivation()

    fun getPreferenceId(): String? =
        prefs.getString(KEY_PREF_ID, null)

    fun getRetryCount(): Int =
        prefs.getInt(KEY_RETRY_COUNT, 0)

    fun recordPendingActivation(preferenceId: String) {
        prefs.edit()
            .putBoolean(KEY_PENDING_ACTIVATION, true)
            .putString(KEY_PREF_ID, preferenceId)
            .putLong(KEY_CREATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun recordPendingDeactivation(preferenceId: String) {
        prefs.edit()
            .putBoolean(KEY_PENDING_ACTIVATION, false)
            .putBoolean(KEY_PENDING_DEACTIVATION, true)
            .putString(KEY_PREF_ID, preferenceId)
            .putLong(KEY_CREATED_AT, System.currentTimeMillis())
            .putInt(KEY_RETRY_COUNT, 0)
            .apply()
    }

    fun clearAll() {
        prefs.edit()
            .remove(KEY_PENDING_ACTIVATION)
            .remove(KEY_PENDING_DEACTIVATION)
            .remove(KEY_PREF_ID)
            .remove(KEY_CREATED_AT)
            .remove(KEY_RETRY_COUNT)
            .apply()
    }

    fun incrementRetry() {
        prefs.edit()
            .putInt(KEY_RETRY_COUNT, getRetryCount() + 1)
            .putLong(KEY_CREATED_AT, System.currentTimeMillis())
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "job_alert_pending_sync"
        private const val KEY_PENDING_ACTIVATION = "pending_activation"
        private const val KEY_PENDING_DEACTIVATION = "pending_deactivation"
        private const val KEY_PREF_ID = "pending_pref_id"
        private const val KEY_CREATED_AT = "pending_created_at"
        private const val KEY_RETRY_COUNT = "pending_retry_count"
    }
}
