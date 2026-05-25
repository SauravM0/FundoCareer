package com.fundocareer.app.core.jobs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fundocareer.app.core.jobalerts.provider.JobAlertApiClient.TimelineEvent
import com.fundocareer.app.core.jobalerts.ui.theme.FcGreen
import java.text.SimpleDateFormat
import java.util.Locale


@Composable
fun HistorySection(
    timeline: List<TimelineEvent>,
) {
    val filtered = timeline.filter { it.type in knownPositiveTypes }
    if (filtered.isEmpty()) {
        HistoryEmptyState(Modifier.fillMaxSize())
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.History, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Activity History",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            items(filtered) { event ->
                TimelineEventCard(event)
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun HistoryEmptyState(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.History, null,
                Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "No activity yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Job alerts and scheduler activity will appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun TimelineEventCard(event: TimelineEvent) {
    val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.US)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            when (event.type) {
                "scheduler_created" -> SchedulerCreatedRow(event, dateFormat)
                "scheduler_stopped" -> SchedulerStoppedRow(event, dateFormat)
                "setup_email_sent" -> SetupEmailRow(event, dateFormat)
                "job_alert_sent" -> JobAlertRow(event, dateFormat)
            }
        }
    }
}

@Composable
private fun SchedulerCreatedRow(event: TimelineEvent, dateFormat: SimpleDateFormat) {
    val meta = event.metadata
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatTs(event.timestamp, dateFormat),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, null, Modifier.size(14.dp), tint = FcGreen)
            Spacer(Modifier.width(4.dp))
            Text(
                "Scheduler Active",
                style = MaterialTheme.typography.labelSmall,
                color = FcGreen,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        event.title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (meta?.intervalMinutes != null) {
        Text(
            "Every ${intervalLabel(meta.intervalMinutes)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (meta?.deviceName != null) {
        Text(
            "Device: ${meta.deviceName}${meta.devicePlatform?.let { " ($it)" } ?: ""}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SchedulerStoppedRow(event: TimelineEvent, dateFormat: SimpleDateFormat) {
    val meta = event.metadata
    val amber = Color(0xFFFF8F00)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatTs(event.timestamp, dateFormat),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Pause, null, Modifier.size(14.dp), tint = amber)
            Spacer(Modifier.width(4.dp))
            Text(
                "Scheduler Stopped",
                style = MaterialTheme.typography.labelSmall,
                color = amber,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        event.title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (meta?.deviceName != null) {
        Text(
            "Device: ${meta.deviceName}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (meta?.intervalMinutes != null) {
        Text(
            "Was checking every ${intervalLabel(meta.intervalMinutes)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SetupEmailRow(event: TimelineEvent, dateFormat: SimpleDateFormat) {
    val blue = Color(0xFF1976D2)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatTs(event.timestamp, dateFormat),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Notifications, null, Modifier.size(14.dp), tint = blue)
            Spacer(Modifier.width(4.dp))
            Text(
                "Confirmation Sent",
                style = MaterialTheme.typography.labelSmall,
                color = blue,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        event.title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        event.description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun JobAlertRow(event: TimelineEvent, dateFormat: SimpleDateFormat) {
    val meta = event.metadata
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatTs(event.timestamp, dateFormat),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Email sent",
            style = MaterialTheme.typography.labelSmall,
            color = FcGreen,
            fontWeight = FontWeight.SemiBold,
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            Text(
                "${meta?.jobsSentCount ?: 0}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Jobs sent",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (meta?.pdfAttached == true) Icons.Default.PictureAsPdf else Icons.Default.Description,
                    null,
                    Modifier.size(16.dp),
                    tint = if (meta?.pdfAttached == true) FcGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    if (meta?.pdfAttached == true) "PDF attached" else "No PDF",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        event.title,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Medium,
    )
}


