package com.fundocareer.app.core.jobalerts

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.fundocareer.app.core.logging.FcLog

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

        FcLog.d(FcLog.TAG_RELIABILITY, "getActionsForItem", mapOf(
            "type" to item.type.name,
            "status" to item.status.name,
        ))

        return when (item.type) {
            ReliabilityItemType.NotificationPermission -> notificationActions(context)
            ReliabilityItemType.BatteryOptimization -> batteryActions(context)
            ReliabilityItemType.BackgroundData -> backgroundDataActions(context)
            ReliabilityItemType.AutostartOem -> autostartActions(context)
        }
    }

    fun isRequiredForBestReliability(type: ReliabilityItemType): Boolean {
        return when (type) {
            ReliabilityItemType.NotificationPermission -> true
            ReliabilityItemType.BatteryOptimization -> true
            ReliabilityItemType.BackgroundData -> true
            ReliabilityItemType.AutostartOem -> OEMDeviceHelper.hasOemAutostart()
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
        val actions = mutableListOf<ReliabilityAction>()

        val requestIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
        }
        val canResolve = requestIntent.resolveActivity(context.packageManager) != null

        if (canResolve && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            actions.add(
                ReliabilityAction.OpenSettings(
                    label = "Disable battery optimization",
                    intent = requestIntent
                )
            )
        }

        val oemBatteryIntent = OEMDeviceHelper.getBatterySettingsIntent(context)
        if (oemBatteryIntent != null && oemBatteryIntent.resolveActivity(context.packageManager) != null) {
            actions.add(
                ReliabilityAction.OpenSettings(
                    label = "Open ${OEMDeviceHelper.getBrandName()} battery settings",
                    intent = oemBatteryIntent
                )
            )
        }

        actions.add(
            ReliabilityAction.OpenSettings(
                label = "Open app settings",
                intent = createAppDetailsIntent(context)
            )
        )

        actions.add(
            ReliabilityAction.Guidance(
                label = "Show instructions",
                message = OEMDeviceHelper.getBatteryInstructions()
            )
        )

        return actions
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
        val actions = mutableListOf<ReliabilityAction>()

        val autostartIntent = OEMDeviceHelper.getAutostartIntent(context)
        if (autostartIntent != null && autostartIntent.resolveActivity(context.packageManager) != null) {
            actions.add(
                ReliabilityAction.OpenSettings(
                    label = "Open ${OEMDeviceHelper.getBrandName()} autostart settings",
                    intent = autostartIntent
                )
            )
        }

        actions.add(
            ReliabilityAction.OpenSettings(
                label = "Open app settings",
                intent = createAppDetailsIntent(context)
            )
        )

        actions.add(
            ReliabilityAction.Guidance(
                label = "Show instructions",
                message = OEMDeviceHelper.getDeviceSpecificAutostartInstructions()
            )
        )

        return actions
    }

    private fun createNotificationSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
            FcLog.d(FcLog.TAG_RELIABILITY, "executeOpenSettingsAction", mapOf(
                "label" to action.label,
            ))
            context.startActivity(action.intent)
            FcLog.d(FcLog.TAG_RELIABILITY, "Intent launched successfully", mapOf(
                "label" to action.label,
            ))
        } catch (e: Exception) {
            FcLog.w(FcLog.TAG_RELIABILITY, "Failed to start intent", mapOf(
                "label" to action.label,
                "error" to e.message,
            ))
            Toast.makeText(
                context,
                "Unable to open settings. Please go to Settings > Apps > FundoCareer.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun executeGuidanceAction(context: Context, action: ReliabilityAction.Guidance) {
        FcLog.d(FcLog.TAG_RELIABILITY, "executeGuidanceAction", mapOf(
            "label" to action.label,
        ))
        Toast.makeText(context, action.message, Toast.LENGTH_LONG).show()
    }
}
