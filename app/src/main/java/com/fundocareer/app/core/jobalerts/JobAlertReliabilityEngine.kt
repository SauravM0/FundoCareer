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
        prefs(context).edit().putBoolean(KEY_SETUP_SEEN, true).commit()
    }

    fun isLimitedReliabilityAcknowledged(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ACKNOWLEDGED_LIMITED, false)
    }

    fun acknowledgeLimitedReliability(context: Context) {
        prefs(context).edit().putBoolean(KEY_ACKNOWLEDGED_LIMITED, true).commit()
    }

    fun isAutostartConfirmed(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_CONFIRMED_AUTOSTART, false)
    }

    fun confirmAutostart(context: Context) {
        prefs(context).edit().putBoolean(KEY_CONFIRMED_AUTOSTART, true).commit()
    }

    fun isFileUploadConfirmed(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_CONFIRMED_FILE_UPLOAD, false)
    }

    fun confirmFileUpload(context: Context) {
        prefs(context).edit().putBoolean(KEY_CONFIRMED_FILE_UPLOAD, true).commit()
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

        val overall = computeOverall(items)

        Log.i(TAG, "getReliabilityStatus: items=${
            items.joinToString { "${it.type.name}=${it.status.name}" }
        }, overall=$overall")

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
        return if (allReady) OverallReliability.OPTIMIZED else OverallReliability.LIMITED
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
        val ignoringOpt = try {
            PermissionCoordinator.isIgnoringBatteryOptimizations(context)
        } catch (e: Exception) {
            Log.w(TAG, "battery optimization check failed, defaulting to Ready", e)
            true
        }
        return ReliabilityChecklistItem(
            type = ReliabilityItemType.BatteryOptimization,
            status = if (ignoringOpt) ReliabilityItemStatus.Ready else ReliabilityItemStatus.NotReady,
            label = "Battery optimization",
            description = "Helps the system run background job searches reliably.",
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
            description = if (hasHardware) "Allows recording audio responses during job applications." else "No microphone detected on this device.",
            actionLabel = if (enabled || !hasHardware) null else "Grant permission"
        )
    }

    private fun buildCameraItem(context: Context): ReliabilityChecklistItem {
        val enabled = PermissionCoordinator.hasCameraPermission(context)
        return ReliabilityChecklistItem(
            type = ReliabilityItemType.CameraPermission,
            status = if (enabled) ReliabilityItemStatus.Ready else ReliabilityItemStatus.NotReady,
            label = "Camera access",
            description = "Allows taking photos or recording video during job applications.",
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
            description = "Job searches use the internet. Ensure background data is allowed for this app.",
            actionLabel = null
        )
    }

    private fun buildAutostartItem(context: Context): ReliabilityChecklistItem {
        val confirmed = isAutostartConfirmed(context)
        return ReliabilityChecklistItem(
            type = ReliabilityItemType.AutostartOem,
            status = if (confirmed) ReliabilityItemStatus.UserConfirmed else ReliabilityItemStatus.NotReady,
            label = "Autostart permission",
            description = "Helps the scheduler survive device reboots on some devices.",
            actionLabel = if (confirmed) null else "Confirm autostart configured"
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
