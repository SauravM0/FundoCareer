package com.fundocareer.app.core.jobalerts.provider

import android.content.Context
import android.os.Build
import com.fundocareer.app.BuildConfig
import java.util.UUID

data class DeviceIdentity(
    val deviceId: String,
    val deviceName: String,
    val devicePlatform: String = "android",
    val appVersion: String,
    val androidVersion: String,
)

class JobAlertDeviceIdentityProvider(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun getDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (existing != null) return existing
        val newId = UUID.randomUUID().toString()
        prefs.edit()
            .putString(KEY_DEVICE_ID, newId)
            .putLong(KEY_CREATED_AT, System.currentTimeMillis())
            .apply()
        return newId
    }

    fun getDeviceName(): String {
        val override = prefs.getString(KEY_DEVICE_NAME_OVERRIDE, null)
        if (!override.isNullOrBlank()) return override
        val manufacturer = Build.MANUFACTURER?.trim() ?: ""
        val model = Build.MODEL?.trim() ?: ""
        return when {
            model.isBlank() && manufacturer.isBlank() -> "Android Device"
            model.isBlank() -> "$manufacturer Android Device"
            manufacturer.isBlank() -> model
            model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model"
        }
    }

    fun getDeviceIdentity(): DeviceIdentity {
        return DeviceIdentity(
            deviceId = getDeviceId(),
            deviceName = getDeviceName(),
            devicePlatform = "android",
            appVersion = BuildConfig.VERSION_NAME,
            androidVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
        )
    }

    fun getDeviceIdForApi(): String = getDeviceId()

    companion object {
        private const val PREFS_NAME = "job_alert_device_identity"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME_OVERRIDE = "device_name_override"
        private const val KEY_CREATED_AT = "created_at"
    }
}
