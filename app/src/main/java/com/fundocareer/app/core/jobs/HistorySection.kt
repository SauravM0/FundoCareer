package com.fundocareer.app.core.jobs

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fundocareer.app.core.jobalerts.provider.JobAlertApiClient.TimelineEvent
import com.fundocareer.app.core.jobalerts.ui.theme.FcGreen
import com.fundocareer.app.core.jobalerts.ui.theme.FcRed
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySection(
    timeline: List<TimelineEvent>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
) {
    var selectedFilter by remember { mutableStateOf(HistoryFilter.ALL) }
    val dateFormat = remember { SimpleDateFormat("MMM d, h:mm a", Locale.US) }

    val filtered = timeline.filter { matchesFilter(it.type, selectedFilter) }

    Column(modifier = Modifier.fillMaxSize()) {
        FilterBar(
            selectedFilter = selectedFilter,
            onFilterSelected = { selectedFilter = it },
            onRefresh = onRefresh,
            isLoading = isLoading,
        )

        if (filtered.isEmpty()) {
            HistoryEmptyState(Modifier.fillMaxSize())
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filtered) { event ->
                    TimelineEventCard(event, dateFormat)
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

@Composable
private fun FilterBar(
    selectedFilter: HistoryFilter,
    onFilterSelected: (HistoryFilter) -> Unit,
    onRefresh: () -> Unit,
    isLoading: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.History, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(
                "Activity History",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        IconButton(onClick = onRefresh, enabled = !isLoading) {
            Icon(
                Icons.Default.Refresh,
                "Refresh",
                tint = if (isLoading) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                       else MaterialTheme.colorScheme.primary,
            )
        }
    }

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(HistoryFilter.entries) { filter ->
            FilterChip(
                selected = selectedFilter == filter,
                onClick = { onFilterSelected(filter) },
                label = {
                    Text(
                        filter.label,
                        fontSize = 13.sp,
                        fontWeight = if (selectedFilter == filter) FontWeight.SemiBold else FontWeight.Normal,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    borderColor = MaterialTheme.colorScheme.outlineVariant,
                    selectedBorderColor = MaterialTheme.colorScheme.primary,
                    enabled = true,
                    selected = selectedFilter == filter,
                ),
            )
        }
    }
    Spacer(Modifier.height(4.dp))
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
                "No job alert activity yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Once your scheduler runs, activity will appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}

@Composable
private fun TimelineEventCard(event: TimelineEvent, dateFormat: SimpleDateFormat) {
    val info = eventDisplayInfo(event.type, event.status)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        info.icon, null,
                        Modifier.size(16.dp),
                        tint = info.iconTint,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        formatTs(event.timestamp, dateFormat),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusChip(info.status)
            }

            Spacer(Modifier.height(8.dp))

            Text(
                event.title.ifBlank { defaultTitleForType(event.type) },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            val desc = event.description.ifBlank { defaultDescriptionForType(event, info) }
            if (desc.isNotBlank()) {
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            val meta = event.metadata
            val detailLines = buildDetailLines(event, meta)
            if (detailLines.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                detailLines.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: TimelineStatus) {
    val bgColor = status.color.copy(alpha = 0.12f)
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            status.label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = status.color,
        )
    }
}

private fun defaultTitleForType(type: String): String = when (type) {
    "scheduler_created" -> "Job alerts activated"
    "scheduler_stopped" -> "Job alerts stopped"
    "scheduler_updated" -> "Scheduler updated"
    "setup_email_sent" -> "Setup confirmation sent"
    "job_alert_sent" -> "Jobs found and emailed"
    "no_jobs_found" -> "No new jobs found"
    "worker_skipped_inactive" -> "Scheduler skipped — device not active"
    "worker_skipped_lock_held" -> "Scheduler skipped — lock held elsewhere"
    "no_preferences" -> "No active job preferences"
    "email_failed" -> "Email delivery failed"
    "backend_sync_failed" -> "Backend sync failed"
    "worker_failed_source" -> "Job search failed"
    "worker_failed_auth" -> "Authentication failed"
    "worker_failed" -> "Scheduler run failed"
    "active_device_activated" -> "Device activated"
    "takeover_requested" -> "Takeover requested"
    "takeover_completed" -> "Takeover completed"
    "reliability_setup_completed" -> "Reliability setup completed"
    else -> "Activity"
}

private fun defaultDescriptionForType(event: TimelineEvent, info: EventDisplayInfo): String {
    val meta = event.metadata
    return when (event.type) {
        "scheduler_created" -> {
            val parts = mutableListOf<String>()
            meta?.role?.takeIf { it.isNotBlank() }?.let { parts.add("Searching for $it") }
            meta?.location?.takeIf { it.isNotBlank() }?.let { parts.add("in $it") }
            parts.joinToString(" ")
        }
        "job_alert_sent" -> {
            val count = meta?.jobsSent ?: meta?.jobsSentCount ?: 0
            "$count new job${if (count != 1) "s" else ""} sent via email"
        }
        "no_jobs_found" -> {
            val count = meta?.jobsFound ?: 0
            "Checked $count job${if (count != 1) "s" else ""}, all already seen"
        }
        else -> event.description
    }
}

private fun buildDetailLines(event: TimelineEvent, meta: com.fundocareer.app.core.jobalerts.provider.JobAlertApiClient.TimelineEventMetadata?): List<String> {
    val lines = mutableListOf<String>()
    when (event.type) {
        "scheduler_created", "scheduler_stopped" -> {
            meta?.deviceName?.takeIf { it.isNotBlank() }?.let {
                lines.add("Device: $it${meta.devicePlatform?.let { p -> " ($p)" } ?: ""}")
            }
            meta?.intervalMinutes?.let { lines.add("Check interval: ${intervalLabel(it)}") }
        }
        "job_alert_sent", "email_failed" -> {
            meta?.jobsFound?.let { if (it > 0) lines.add("$it job${if (it != 1) "s" else ""} found") }
            meta?.pdfAttached?.let { if (it) lines.add("PDF report attached") }
        }
        "worker_skipped_inactive", "worker_skipped_lock_held", "no_preferences",
        "worker_failed_source", "worker_failed_auth", "worker_failed", "backend_sync_failed" -> {
            meta?.errorMessage?.takeIf { it.isNotBlank() }?.let {
                lines.add(it.take(120))
            }
        }
    }
    if (event.runId != null) {
        lines.add("Run ID: ${event.runId.take(8)}…")
    }
    return lines
}
