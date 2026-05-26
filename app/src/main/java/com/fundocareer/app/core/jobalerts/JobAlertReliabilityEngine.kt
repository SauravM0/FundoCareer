package com.fundocareer.app.core.jobalerts

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.fundocareer.app.PermissionCoordinator
import com.fundocareer.app.core.logging.FcLog

object JobAlertReliabilityEngine {
    private const val PREFS_NAME = "job_alert_reliability_prefs"

    private const val KEY_SETUP_SEEN = "setupSeen"
    private const val KEY_USER_COMPLETED = "userCompletedReliabilitySetup"
    private const val KEY_CONFIRMED_AUTOSTART = "userConfirmedAutostart"
    private const val KEY_CONFIRMED_BATTERY = "userConfirmedBatteryOptimization"
    private const val KEY_CONFIRMED_BACKGROUND = "userConfirmedBackgroundData"
    private const val KEY_COMPLETED_AT = "reliabilityCompletedAt"
    private const val KEY_DEVICE_INFO = "reliabilityDeviceInfo"

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isSetupSeen(context: Context): Boolean = prefs(context).getBoolean(KEY_SETUP_SEEN, false)

    fun markSetupSeen(context: Context) {
        prefs(context).edit().putBoolean(KEY_SETUP_SEEN, true).apply()
        FcLog.i(FcLog.TAG_RELIABILITY, "markSetupSeen")
    }

    fun isReliabilityCompleted(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_USER_COMPLETED, false)
    }

    fun markReliabilityCompleted(context: Context) {
        val manufacturer = OEMDeviceHelper.getBrandName()
        val deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.VERSION.SDK_INT})"
        prefs(context).edit()
            .putBoolean(KEY_USER_COMPLETED, true)
            .putLong(KEY_COMPLETED_AT, System.currentTimeMillis())
            .putString(KEY_DEVICE_INFO, "$manufacturer - $deviceInfo")
            .apply()
        FcLog.i(FcLog.TAG_RELIABILITY, "markReliabilityCompleted", mapOf(
            "manufacturer" to manufacturer,
        ))
    }

    fun isAutostartConfirmed(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_CONFIRMED_AUTOSTART, false)
    }

    fun confirmAutostart(context: Context) {
        prefs(context).edit().putBoolean(KEY_CONFIRMED_AUTOSTART, true).apply()
        FcLog.i(FcLog.TAG_RELIABILITY, "confirmAutostart")
    }

    fun isBatteryOptimizationConfirmed(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_CONFIRMED_BATTERY, false)
    }

    fun confirmBatteryOptimization(context: Context) {
        prefs(context).edit().putBoolean(KEY_CONFIRMED_BATTERY, true).apply()
        FcLog.i(FcLog.TAG_RELIABILITY, "confirmBatteryOptimization")
    }

    fun isBackgroundDataConfirmed(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_CONFIRMED_BACKGROUND, false)
    }

    fun confirmBackgroundData(context: Context) {
        prefs(context).edit().putBoolean(KEY_CONFIRMED_BACKGROUND, true).apply()
        FcLog.i(FcLog.TAG_RELIABILITY, "confirmBackgroundData")
    }

    fun getReliabilityCompletedAt(context: Context): Long {
        return prefs(context).getLong(KEY_COMPLETED_AT, 0L)
    }

    fun resetAll(context: Context) {
        prefs(context).edit().clear().apply()
        FcLog.i(FcLog.TAG_RELIABILITY, "resetAll")
    }

    fun getReliabilityStatus(
        context: Context,
        deviceState: DeviceSchedulerStateEntity? = null
    ): JobAlertReliabilityStatus {
        FcLog.i(FcLog.TAG_RELIABILITY, "getReliabilityStatus", mapOf(
            "manufacturer" to OEMDeviceHelper.getBrandName(),
        ))
        val items = mutableListOf<ReliabilityChecklistItem>()

        items.add(buildNotificationItem(context))
        items.add(buildBatteryOptimizationItem(context))
        items.add(buildBackgroundDataItem(context))
        items.add(buildAutostartItem(context))

        val hasLockedIssue = items.any { it.status == ReliabilityItemStatus.NotReady && it.isMandatory }
        val overall = when {
            hasLockedIssue -> OverallReliability.LOCKED
            items.all { it.status == ReliabilityItemStatus.Ready || it.status == ReliabilityItemStatus.NotApplicable || it.status == ReliabilityItemStatus.UserConfirmed } ->
                OverallReliability.OPTIMIZED
            else -> OverallReliability.LIMITED
        }

        FcLog.i(FcLog.TAG_RELIABILITY, "getReliabilityStatus result", mapOf(
            "overall" to overall.name,
            "mandatoryComplete" to items.filter { it.isMandatory }.all { item ->
                item.status == ReliabilityItemStatus.Ready || item.status == ReliabilityItemStatus.UserConfirmed || item.status == ReliabilityItemStatus.NotApplicable
            },
        ))

        return JobAlertReliabilityStatus(
            overall = overall,
            items = items,
            activeSchedulerDevice = if (deviceState != null && deviceState.schedulerEnabled) deviceState else null
        )
    }

    fun refreshStatus(
        context: Context,
        deviceState: DeviceSchedulerStateEntity? = null
    ): JobAlertReliabilityStatus {
        return getReliabilityStatus(context, deviceState)
    }

    private fun buildNotificationItem(context: Context): ReliabilityChecklistItem {
        val enabled = areNotificationsEnabled(context)
        FcLog.i(FcLog.TAG_RELIABILITY, "buildNotificationItem", mapOf(
            "enabled" to enabled,
        ))
        return ReliabilityChecklistItem(
            type = ReliabilityItemType.NotificationPermission,
            status = if (enabled) ReliabilityItemStatus.Ready else ReliabilityItemStatus.NotReady,
            label = "Notifications",
            description = "Receive job alert notifications when new matching jobs are found.",
            actionLabel = if (enabled) null else "Enable notifications",
            isMandatory = true
        )
    }

    private fun buildBatteryOptimizationItem(context: Context): ReliabilityChecklistItem {
        val ignoringOpt = try {
            PermissionCoordinator.isIgnoringBatteryOptimizations(context)
        } catch (e: Exception) {
            FcLog.w(FcLog.TAG_RELIABILITY, "battery optimization check failed, defaulting to true", mapOf(
                "error" to e.message,
            ))
            true
        }
        val userConfirmed = isBatteryOptimizationConfirmed(context)
        val status = when {
            ignoringOpt -> ReliabilityItemStatus.Ready
            userConfirmed -> ReliabilityItemStatus.UserConfirmed
            else -> ReliabilityItemStatus.NotReady
        }
        FcLog.i(FcLog.TAG_RELIABILITY, "buildBatteryOptimizationItem", mapOf(
            "ignoringOptimization" to ignoringOpt,
            "userConfirmed" to userConfirmed,
            "status" to status.name,
        ))
        return ReliabilityChecklistItem(
            type = ReliabilityItemType.BatteryOptimization,
            status = status,
            label = "Battery optimization",
            description = "Prevents the system from stopping background job searches.",
            actionLabel = if (status == ReliabilityItemStatus.Ready || status == ReliabilityItemStatus.UserConfirmed) null else "Disable optimization",
            isMandatory = true
        )
    }

    private fun buildBackgroundDataItem(context: Context): ReliabilityChecklistItem {
        val hasNetwork = hasNetworkConnectivity(context)
        val backgroundAllowed = isBackgroundDataAllowed(context)
        val userConfirmed = isBackgroundDataConfirmed(context)
        val status = when {
            backgroundAllowed && hasNetwork -> ReliabilityItemStatus.Ready
            backgroundAllowed -> ReliabilityItemStatus.Ready
            userConfirmed -> ReliabilityItemStatus.UserConfirmed
            else -> ReliabilityItemStatus.NotReady
        }
        FcLog.i(FcLog.TAG_RELIABILITY, "buildBackgroundDataItem", mapOf(
            "hasNetwork" to hasNetwork,
            "backgroundAllowed" to backgroundAllowed,
            "userConfirmed" to userConfirmed,
            "status" to status.name,
        ))
        return ReliabilityChecklistItem(
            type = ReliabilityItemType.BackgroundData,
            status = status,
            label = "Background data & connectivity",
            description = "Allows the app to search for jobs while running in the background with network access.",
            actionLabel = if (status == ReliabilityItemStatus.Ready) null else "Open settings",
            isMandatory = true
        )
    }

    private fun buildAutostartItem(context: Context): ReliabilityChecklistItem {
        val hasOem = OEMDeviceHelper.hasOemAutostart()
        val confirmed = isAutostartConfirmed(context)
        val status = when {
            !hasOem -> ReliabilityItemStatus.NotApplicable
            confirmed -> ReliabilityItemStatus.UserConfirmed
            else -> ReliabilityItemStatus.NotReady
        }
        FcLog.i(FcLog.TAG_RELIABILITY, "buildAutostartItem", mapOf(
            "hasOemAutostart" to hasOem,
            "confirmed" to confirmed,
            "status" to status.name,
        ))
        return ReliabilityChecklistItem(
            type = ReliabilityItemType.AutostartOem,
            status = status,
            label = "Auto-launch (${OEMDeviceHelper.getBrandName()})",
            description = "Required on some devices for the scheduler to survive reboots.",
            actionLabel = if (status != ReliabilityItemStatus.NotReady) null else "Open settings",
            isMandatory = hasOem
        )
    }

    private fun areNotificationsEnabled(context: Context): Boolean {
        return try {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        } catch (e: Exception) {
            FcLog.w(FcLog.TAG_RELIABILITY, "areNotificationsEnabled check failed", mapOf(
                "error" to e.message,
            ))
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        }
    }

    private fun hasNetworkConnectivity(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            FcLog.w(FcLog.TAG_RELIABILITY, "Network connectivity check failed", mapOf(
                "error" to e.message,
            ))
            true
        }
    }

    private fun isBackgroundDataAllowed(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
            val network = cm.activeNetwork ?: return true
            val caps = cm.getNetworkCapabilities(network) ?: return true
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            FcLog.w(FcLog.TAG_RELIABILITY, "Background data check failed, defaulting to allowed", mapOf(
                "error" to e.message,
            ))
            true
        }
    }
}
