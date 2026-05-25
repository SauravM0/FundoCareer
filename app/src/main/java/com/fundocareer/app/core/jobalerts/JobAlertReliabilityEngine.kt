package com.fundocareer.app.core.jobalerts

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.fundocareer.app.PermissionCoordinator

object JobAlertReliabilityEngine {
    private const val TAG = "JobAlertReliability"
    private const val PREFS_NAME = "job_alert_reliability_prefs"

    private const val KEY_SETUP_SEEN = "setupSeen"
    private const val KEY_ACKNOWLEDGED_LIMITED = "userAcknowledgedLimitedReliability"
    private const val KEY_CONFIRMED_AUTOSTART = "userConfirmedAutostart"
    private const val KEY_CONFIRMED_FILE_UPLOAD = "userConfirmedFileUpload"
    private const val KEY_CHECKLIST_VIEWED_AT = "lastChecklistViewedAt"

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isSetupSeen(context: Context): Boolean = prefs(context).getBoolean(KEY_SETUP_SEEN, false)

    fun markSetupSeen(context: Context) {
        prefs(context).edit().putBoolean(KEY_SETUP_SEEN, true).apply()
    }

    fun isLimitedReliabilityAcknowledged(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ACKNOWLEDGED_LIMITED, false)
    }

    fun acknowledgeLimitedReliability(context: Context) {
        prefs(context).edit().putBoolean(KEY_ACKNOWLEDGED_LIMITED, true).apply()
    }

    fun isAutostartConfirmed(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_CONFIRMED_AUTOSTART, false)
    }

    fun confirmAutostart(context: Context) {
        prefs(context).edit().putBoolean(KEY_CONFIRMED_AUTOSTART, true).apply()
    }

    fun isFileUploadConfirmed(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_CONFIRMED_FILE_UPLOAD, false)
    }

    fun confirmFileUpload(context: Context) {
        prefs(context).edit().putBoolean(KEY_CONFIRMED_FILE_UPLOAD, true).apply()
    }

    fun getLastChecklistViewedAt(context: Context): Long {
        return prefs(context).getLong(KEY_CHECKLIST_VIEWED_AT, 0L)
    }

    fun markChecklistViewed(context: Context) {
        prefs(context).edit().putLong(KEY_CHECKLIST_VIEWED_AT, System.currentTimeMillis()).apply()
    }

    fun resetAll(context: Context) {
        prefs(context).edit().clear().apply()
        Log.i(TAG, "All reliability preferences reset")
    }

    fun getReliabilityStatus(
        context: Context,
        deviceState: DeviceSchedulerStateEntity? = null
    ): JobAlertReliabilityStatus {
        val items = mutableListOf<ReliabilityChecklistItem>()

        items.add(buildNotificationItem(context))
        items.add(buildBatteryOptimizationItem(context))
        items.add(buildMicrophoneItem(context))
        items.add(buildCameraItem(context))
        items.add(buildFileUploadItem(context))
        items.add(buildBackgroundDataItem(context))
        items.add(buildAutostartItem(context))
        items.add(buildActiveSchedulerItem(deviceState))

        val overall = computeOverall(items)

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

    private fun computeOverall(items: List<ReliabilityChecklistItem>): OverallReliability {
        val allReady = items.all { item ->
            item.status == ReliabilityItemStatus.Ready
                || item.status == ReliabilityItemStatus.NotApplicable
                || item.status == ReliabilityItemStatus.UserConfirmed
        }
        val schedulerActive = items.any { item ->
            item.type == ReliabilityItemType.ActiveScheduler
                && item.status == ReliabilityItemStatus.Ready
        }

        return when {
            allReady -> OverallReliability.OPTIMIZED
            schedulerActive -> OverallReliability.LIMITED
            else -> OverallReliability.NEEDS_SETUP
        }
    }

    private fun buildNotificationItem(context: Context): ReliabilityChecklistItem {
        val enabled = PermissionCoordinator.areNotificationsEnabled(context)
        return ReliabilityChecklistItem(
            type = ReliabilityItemType.NotificationPermission,
            status = if (enabled) ReliabilityItemStatus.Ready else ReliabilityItemStatus.NotReady,
            label = "Notifications",
            description = "Receive alerts when new matching jobs are found.",
            actionLabel = if (enabled) null else "Enable notifications"
        )
    }

    private fun buildBatteryOptimizationItem(context: Context): ReliabilityChecklistItem {
        val ignoringOpt = PermissionCoordinator.isIgnoringBatteryOptimizations(context)
        return ReliabilityChecklistItem(
            type = ReliabilityItemType.BatteryOptimization,
            status = if (ignoringOpt) ReliabilityItemStatus.Ready else ReliabilityItemStatus.NotReady,
            label = "Battery optimization",
            description = "Prevents the system from stopping background job searches.",
            actionLabel = if (ignoringOpt) null else "Disable battery optimization"
        )
    }

    private fun buildMicrophoneItem(context: Context): ReliabilityChecklistItem {
        val enabled = PermissionCoordinator.hasMicrophonePermission(context)
        val hasHardware = PermissionCoordinator.hasMicrophoneHardware(context)
        return ReliabilityChecklistItem(
            type = ReliabilityItemType.MicrophonePermission,
            status = when {
                !hasHardware -> ReliabilityItemStatus.NotApplicable
                enabled -> ReliabilityItemStatus.Ready
                else -> ReliabilityItemStatus.NotReady
            },
            label = "Microphone access",
            description = if (hasHardware) "Required for recording audio responses during job applications." else "No microphone detected on this device.",
            actionLabel = if (enabled || !hasHardware) null else "Grant permission"
        )
    }

    private fun buildCameraItem(context: Context): ReliabilityChecklistItem {
        val enabled = PermissionCoordinator.hasCameraPermission(context)
        return ReliabilityChecklistItem(
            type = ReliabilityItemType.CameraPermission,
            status = if (enabled) ReliabilityItemStatus.Ready else ReliabilityItemStatus.NotReady,
            label = "Camera access",
            description = "Required for taking photos or recording video during job applications.",
            actionLabel = if (enabled) null else "Grant permission"
        )
    }

    private fun buildFileUploadItem(context: Context): ReliabilityChecklistItem {
        val confirmed = isFileUploadConfirmed(context)
        return ReliabilityChecklistItem(
            type = ReliabilityItemType.FileUploadSupport,
            status = if (confirmed) ReliabilityItemStatus.UserConfirmed else ReliabilityItemStatus.NotReady,
            label = "File & resume upload",
            description = "Upload resumes, cover letters, and supporting documents. Not required for basic job alerts.",
            actionLabel = if (confirmed) null else "Confirm file upload support"
        )
    }

    private fun buildBackgroundDataItem(context: Context): ReliabilityChecklistItem {
        val hasNetwork = hasNetworkConnectivity(context)
        return ReliabilityChecklistItem(
            type = ReliabilityItemType.BackgroundData,
            status = if (hasNetwork) ReliabilityItemStatus.Ready else ReliabilityItemStatus.NotReady,
            label = "Internet & background data",
            description = "Job searches require an active internet connection. Ensure background data is not restricted for this app.",
            actionLabel = null
        )
    }

    private fun buildAutostartItem(context: Context): ReliabilityChecklistItem {
        val confirmed = isAutostartConfirmed(context)
        return ReliabilityChecklistItem(
            type = ReliabilityItemType.AutostartOem,
            status = if (confirmed) ReliabilityItemStatus.UserConfirmed else ReliabilityItemStatus.NotReady,
            label = "Autostart permission",
            description = "Required on some devices (Xiaomi, Huawei, Oppo, etc.) to keep the scheduler alive after reboots.",
            actionLabel = if (confirmed) null else "Confirm autostart configured"
        )
    }

    private fun buildActiveSchedulerItem(deviceState: DeviceSchedulerStateEntity?): ReliabilityChecklistItem {
        val schedulerActive = deviceState != null && deviceState.schedulerEnabled
        return ReliabilityChecklistItem(
            type = ReliabilityItemType.ActiveScheduler,
            status = if (schedulerActive) ReliabilityItemStatus.Ready else ReliabilityItemStatus.NotReady,
            label = "Active job alert scheduler",
            description = if (schedulerActive) {
                "Job alerts are scheduled and running."
            } else {
                "No active job alert scheduler found. Start one on the Job Search page."
            },
            actionLabel = if (schedulerActive) null else "Set up scheduler"
        )
    }

    private fun hasNetworkConnectivity(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            Log.w(TAG, "Network connectivity check failed", e)
            true
        }
    }
}
