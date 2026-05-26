package com.fundocareer.app.core.jobalerts

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fundocareer.app.core.logging.FcLog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReliabilitySetupScreen(
    initialStatus: JobAlertReliabilityStatus,
    onRefreshStatus: () -> Unit,
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
    onOpenSettings: (Intent) -> Unit,
    onRequestPermission: (String) -> Unit,
    onConfirmAutostart: () -> Unit,
    onConfirmBattery: () -> Unit,
    onConfirmBackground: () -> Unit,
) {
    var currentStatus by remember(initialStatus) { mutableStateOf(initialStatus) }
    var isRefreshing by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableStateOf(0) }
    val context = LocalContext.current

    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger > 0) {
            isRefreshing = true
            onRefreshStatus()
            isRefreshing = false
            refreshTrigger = 0
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        FcLog.i(FcLog.TAG_PERMISSION, "POST_NOTIFICATIONS result", mapOf(
            "granted" to it,
        ))
        refreshTrigger++
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = {
                Text("Reliability Setup", fontWeight = FontWeight.Bold)
            },
            navigationIcon = {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            },
            actions = {
                IconButton(onClick = { refreshTrigger++ }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Set up these items to ensure reliable job alert delivery.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            for (item in currentStatus.items) {
                ReliabilitySetupCard(
                    item = item,
                    onOpenSettings = onOpenSettings,
                    onRequestPermission = onRequestPermission,
                    onConfirmAutostart = onConfirmAutostart,
                    onConfirmBattery = onConfirmBattery,
                    onConfirmBackground = onConfirmBackground,
                    notificationPermissionLauncher = { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                )
            }

            Spacer(Modifier.height(4.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                ),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Default.Info, null,
                        Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Some Android brands require manual enabling in Settings. " +
                                "Use the instructions for each item above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            val canContinue = currentStatus.canActivateScheduler
            val lockedItems = currentStatus.items.filter {
                it.isMandatory && it.status == ReliabilityItemStatus.NotReady
            }

            if (lockedItems.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            Icons.Default.Warning, null,
                            Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "Complete required items to continue",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                lockedItems.joinToString("\n") { "- ${it.label}" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }

            Button(
                onClick = onContinue,
                enabled = canContinue,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    if (canContinue) "Continue to Scheduler" else "Complete required items first",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ReliabilitySetupCard(
    item: ReliabilityChecklistItem,
    onOpenSettings: (Intent) -> Unit,
    onRequestPermission: (String) -> Unit,
    onConfirmAutostart: () -> Unit,
    onConfirmBattery: () -> Unit,
    onConfirmBackground: () -> Unit,
    notificationPermissionLauncher: () -> Unit,
) {
    val icon = when (item.type) {
        ReliabilityItemType.NotificationPermission -> Icons.Default.Notifications
        ReliabilityItemType.BatteryOptimization -> Icons.Default.Bolt
        ReliabilityItemType.BackgroundData -> Icons.Default.SignalCellularAlt
        ReliabilityItemType.AutostartOem -> Icons.Default.PhoneAndroid
    }

    val (statusColor, statusLabel) = when (item.status) {
        ReliabilityItemStatus.Ready -> Pair(Color(0xFF2E7D32) to Color(0xFFC8E6C9), "Done")
        ReliabilityItemStatus.NotReady -> Pair(Color(0xFFE65100) to Color(0xFFFFF3E0), "Action needed")
        ReliabilityItemStatus.NotApplicable -> Pair(Color(0xFF616161) to Color(0xFFE0E0E0), "Not needed")
        ReliabilityItemStatus.UserConfirmed -> Pair(Color(0xFF1565C0) to Color(0xFFBBDEFB), "Confirmed")
    }

    val mandatoryLabel = if (item.isMandatory) "Required" else "Recommended"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        mandatoryLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (item.isMandatory) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusBadge(label = statusLabel, bg = statusColor.second, fg = statusColor.first)
            }

            Spacer(Modifier.height(8.dp))

            Text(
                item.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (item.status != ReliabilityItemStatus.Ready && item.status != ReliabilityItemStatus.NotApplicable) {
                Spacer(Modifier.height(12.dp))
                ActionButtons(
                    item = item,
                    onOpenSettings = onOpenSettings,
                    onRequestPermission = onRequestPermission,
                    onConfirmAutostart = onConfirmAutostart,
                    onConfirmBattery = onConfirmBattery,
                    onConfirmBackground = onConfirmBackground,
                    notificationPermissionLauncher = notificationPermissionLauncher,
                )
            }
        }
    }
}

@Composable
private fun ActionButtons(
    item: ReliabilityChecklistItem,
    onOpenSettings: (Intent) -> Unit,
    onRequestPermission: (String) -> Unit,
    onConfirmAutostart: () -> Unit,
    onConfirmBattery: () -> Unit,
    onConfirmBackground: () -> Unit,
    notificationPermissionLauncher: () -> Unit,
) {
    val context = LocalContext.current
    val actions = remember(item.status, item.type) {
        JobAlertReliabilityActions.getActionsForItem(item, context)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (action in actions) {
            when (action) {
                is ReliabilityAction.OpenSettings -> {
                    OutlinedButton(
                        onClick = {
                            FcLog.i(FcLog.TAG_PERMISSION, "Action: open settings", mapOf(
                                "type" to item.type.name,
                                "label" to action.label,
                            ))
                            onOpenSettings(action.intent)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(action.label, fontSize = 13.sp)
                    }
                }
                is ReliabilityAction.RequestPermission -> {
                    Button(
                        onClick = {
                            FcLog.i(FcLog.TAG_PERMISSION, "Action: request permission", mapOf(
                                "type" to item.type.name,
                                "permission" to action.permission,
                            ))
                            if (action.permission == Manifest.permission.POST_NOTIFICATIONS) {
                                notificationPermissionLauncher()
                            } else {
                                onRequestPermission(action.permission)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(action.label, fontSize = 13.sp)
                    }
                }
                is ReliabilityAction.Guidance -> {
                    TextButton(
                        onClick = {
                            FcLog.i(FcLog.TAG_PERMISSION, "Action: show guidance", mapOf(
                                "type" to item.type.name,
                            ))
                            JobAlertReliabilityActions.executeGuidanceAction(context, action)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(action.label, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        if (item.type == ReliabilityItemType.AutostartOem && item.status != ReliabilityItemStatus.Ready) {
            OutlinedButton(
                onClick = {
                    FcLog.i(FcLog.TAG_PERMISSION, "Action: confirm autostart setup", mapOf(
                        "brand" to OEMDeviceHelper.getBrandName(),
                    ))
                    onConfirmAutostart()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) {
                Text("I have enabled auto-launch", fontSize = 13.sp)
            }
        }

        if (item.type == ReliabilityItemType.BatteryOptimization && item.status == ReliabilityItemStatus.NotReady) {
            OutlinedButton(
                onClick = {
                    FcLog.i(FcLog.TAG_PERMISSION, "Action: confirm battery optimization setup")
                    onConfirmBattery()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) {
                Text("I have disabled optimization", fontSize = 13.sp)
            }
        }

        if (item.type == ReliabilityItemType.BackgroundData && item.status == ReliabilityItemStatus.NotReady) {
            OutlinedButton(
                onClick = {
                    FcLog.i(FcLog.TAG_PERMISSION, "Action: confirm background data setup")
                    onConfirmBackground()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) {
                Text("I have allowed background data", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun StatusBadge(label: String, bg: Color, fg: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            label,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
