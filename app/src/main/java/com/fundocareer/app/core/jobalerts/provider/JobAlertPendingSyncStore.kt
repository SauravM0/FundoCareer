package com.fundocareer.app.core.jobalerts.provider

import android.content.Context
import android.util.Log

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
                    Log.i(TAG, "Pending deactivation succeeded for $preferenceId")
                    store.clearAll()
                } else {
                    Log.w(TAG, "Pending deactivation failed for $preferenceId: ${result.error}")
                    store.incrementRetry()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Pending deactivation threw for $preferenceId", e)
                store.incrementRetry()
            }
        }
        store.hasPendingActivation() -> {
            try {
                val result = apiClient.activateDevice(identity, preferenceId)
                if (result.success) {
                    Log.i(TAG, "Pending activation succeeded for $preferenceId")
                    store.clearAll()
                } else {
                    Log.w(TAG, "Pending activation failed for $preferenceId: ${result.error}")
                    store.incrementRetry()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Pending activation threw for $preferenceId", e)
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

private const val TAG = "PendingSyncStore"
