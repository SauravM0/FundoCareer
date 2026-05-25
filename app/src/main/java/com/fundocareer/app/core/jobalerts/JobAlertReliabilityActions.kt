package com.fundocareer.app.core.jobalerts

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast

sealed class ReliabilityAction(val label: String) {

    class OpenSettings(
        label: String,
        val intent: Intent
    ) : ReliabilityAction(label)

    class RequestPermission(
        label: String,
        val permission: String
    ) : ReliabilityAction(label)

    class Guidance(
        label: String,
        val message: String
    ) : ReliabilityAction(label)
}

object JobAlertReliabilityActions {

    fun getActionsForItem(
        item: ReliabilityChecklistItem,
        context: Context
    ): List<ReliabilityAction> {
        if (item.status == ReliabilityItemStatus.Ready
            || item.status == ReliabilityItemStatus.NotApplicable
            || item.status == ReliabilityItemStatus.UserConfirmed
        ) return emptyList()

        Log.d(TAG, "getActionsForItem: type=${item.type}, status=${item.status}")

        return when (item.type) {
            ReliabilityItemType.NotificationPermission -> notificationActions(context)
            ReliabilityItemType.BatteryOptimization -> batteryActions(context)
            ReliabilityItemType.MicrophonePermission -> microphoneActions(context)
            ReliabilityItemType.CameraPermission -> cameraActions(context)
            ReliabilityItemType.FileUploadSupport -> fileUploadActions(context)
            ReliabilityItemType.BackgroundData -> backgroundDataActions(context)
            ReliabilityItemType.AutostartOem -> autostartActions(context)
            ReliabilityItemType.ActiveScheduler -> activeSchedulerActions()
        }
    }

    fun isRequiredForBestReliability(type: ReliabilityItemType): Boolean {
        return when (type) {
            ReliabilityItemType.NotificationPermission -> true
            ReliabilityItemType.BatteryOptimization -> true
            ReliabilityItemType.MicrophonePermission -> true
            ReliabilityItemType.CameraPermission -> true
            ReliabilityItemType.FileUploadSupport -> true
            ReliabilityItemType.BackgroundData -> false
            ReliabilityItemType.AutostartOem -> false
            ReliabilityItemType.ActiveScheduler -> true
        }
    }

    private fun notificationActions(context: Context): List<ReliabilityAction> {
        val actions = mutableListOf<ReliabilityAction>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            actions.add(
                ReliabilityAction.RequestPermission(
                    label = "Grant notification permission",
                    permission = Manifest.permission.POST_NOTIFICATIONS
                )
            )
        }

        actions.add(
            ReliabilityAction.OpenSettings(
                label = "Open notification settings",
                intent = createNotificationSettingsIntent(context)
            )
        )

        return actions
    }

    private fun batteryActions(context: Context): List<ReliabilityAction> {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
        }

        val canResolve = intent.resolveActivity(context.packageManager) != null

        return if (canResolve && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            listOf(
                ReliabilityAction.OpenSettings(
                    label = "Disable battery optimization",
                    intent = intent
                ),
                ReliabilityAction.OpenSettings(
                    label = "Open app settings",
                    intent = createAppDetailsIntent(context)
                )
            )
        } else {
            listOf(
                ReliabilityAction.OpenSettings(
                    label = "Open app settings",
                    intent = createAppDetailsIntent(context)
                )
            )
        }
    }

    private fun microphoneActions(context: Context): List<ReliabilityAction> {
        val actions = mutableListOf<ReliabilityAction>()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            actions.add(
                ReliabilityAction.RequestPermission(
                    label = "Grant microphone permission",
                    permission = Manifest.permission.RECORD_AUDIO
                )
            )
        }

        actions.add(
            ReliabilityAction.OpenSettings(
                label = "Open app settings",
                intent = createAppDetailsIntent(context)
            )
        )

        return actions
    }

    private fun cameraActions(context: Context): List<ReliabilityAction> {
        val actions = mutableListOf<ReliabilityAction>()

        actions.add(
            ReliabilityAction.RequestPermission(
                label = "Grant camera permission",
                permission = Manifest.permission.CAMERA
            )
        )

        actions.add(
            ReliabilityAction.OpenSettings(
                label = "Open app settings",
                intent = createAppDetailsIntent(context)
            )
        )

        return actions
    }

    private fun fileUploadActions(context: Context): List<ReliabilityAction> {
        return listOf(
            ReliabilityAction.Guidance(
                label = "About file upload",
                message = "FundoCareer can upload resumes, cover letters, and other documents " +
                    "during job applications. This works automatically on most devices.\n\n" +
                    "Go to Settings > Apps > FundoCareer > Permissions and ensure " +
                    "\"Files and media\" is enabled if available."
            ),
            ReliabilityAction.OpenSettings(
                label = "Open app settings",
                intent = createAppDetailsIntent(context)
            )
        )
    }

    private fun backgroundDataActions(context: Context): List<ReliabilityAction> {
        val actions = mutableListOf<ReliabilityAction>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                actions.add(
                    ReliabilityAction.OpenSettings(
                        label = "Allow background data",
                        intent = intent
                    )
                )
            }
        }

        actions.add(
            ReliabilityAction.OpenSettings(
                label = "Open app settings",
                intent = createAppDetailsIntent(context)
            )
        )

        return actions
    }

    private fun autostartActions(context: Context): List<ReliabilityAction> {
        return listOf(
            ReliabilityAction.Guidance(
                label = "How to enable autostart",
                message = "On many devices (Xiaomi, Huawei, Oppo, Samsung), " +
                    "apps need autostart permission to run after reboot.\n\n" +
                    "Go to Settings > Apps > FundoCareer > " +
                    "Enable \"Autostart\" or \"Allow background activity\"."
            ),
            ReliabilityAction.OpenSettings(
                label = "Open app settings",
                intent = createAppDetailsIntent(context)
            )
        )
    }

    private fun activeSchedulerActions(): List<ReliabilityAction> {
        return listOf(
            ReliabilityAction.Guidance(
                label = "Set up scheduler",
                message = "Go to the Job Search tab, fill in your search criteria, " +
                    "and tap \"Save & Start Scheduler\" to begin."
            )
        )
    }

    private fun createNotificationSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
    }

    private fun createAppDetailsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun executeOpenSettingsAction(context: Context, action: ReliabilityAction.OpenSettings) {
        try {
            Log.d(TAG, "executeOpenSettingsAction: opening ${action.label}")
            context.startActivity(action.intent)
            Log.d(TAG, "executeOpenSettingsAction: intent launched successfully")
        } catch (e: Exception) {
            Log.w(TAG, "executeOpenSettingsAction: failed to start intent", e)
            Toast.makeText(
                context,
                "Please open app settings and allow background activity for FundoCareer.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun executeGuidanceAction(context: Context, action: ReliabilityAction.Guidance) {
        Log.d(TAG, "executeGuidanceAction: showing guidance: ${action.label}")
        Toast.makeText(context, action.message, Toast.LENGTH_LONG).show()
    }

    private const val TAG = "ReliabilityActions"
}
