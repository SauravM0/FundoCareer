package com.fundocareer.app.core.jobs

import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Description
import androidx.compose.ui.graphics.vector.ImageVector
import com.fundocareer.app.core.jobalerts.ui.theme.FcGreen
import com.fundocareer.app.core.jobalerts.ui.theme.FcRed
import java.text.SimpleDateFormat
import java.util.Locale

enum class HistoryFilter(val label: String) {
    ALL("All"),
    RUNS("Runs"),
    EMAILS("Emails"),
    ERRORS("Errors"),
}

enum class TimelineStatus(val label: String, val color: Color) {
    SUCCESS("Success", FcGreen),
    WARNING("Warning", Color(0xFFFF8F00)),
    FAILED("Failed", FcRed),
    SKIPPED("Skipped", Color(0xFF9E9E9E)),
}

data class EventDisplayInfo(
    val icon: ImageVector,
    val iconTint: Color,
    val status: TimelineStatus,
)

private val amber = Color(0xFFFF8F00)
private val blue = Color(0xFF1976D2)
private val gray = Color(0xFF9E9E9E)

fun eventDisplayInfo(type: String, status: String?): EventDisplayInfo {
    val s = status?.lowercase() ?: ""
    val isFailed = s == "failed"
    val isSkipped = s == "skipped"
    val isWarning = s == "warning"

    return when (type) {
        "scheduler_created" -> EventDisplayInfo(Icons.Default.CheckCircle, FcGreen, TimelineStatus.SUCCESS)
        "scheduler_stopped" -> EventDisplayInfo(Icons.Default.Pause, amber, TimelineStatus.WARNING)
        "scheduler_updated" -> EventDisplayInfo(Icons.Default.Refresh, blue, TimelineStatus.SUCCESS)
        "setup_email_sent" -> EventDisplayInfo(Icons.Default.Notifications, blue, TimelineStatus.SUCCESS)
        "job_alert_sent" -> EventDisplayInfo(Icons.Default.Email, FcGreen, TimelineStatus.SUCCESS)
        "no_jobs_found" -> EventDisplayInfo(Icons.Default.Description, blue, TimelineStatus.SUCCESS)
        "worker_skipped_inactive" -> EventDisplayInfo(Icons.Default.VisibilityOff, gray, TimelineStatus.SKIPPED)
        "worker_skipped_lock_held" -> EventDisplayInfo(Icons.Default.Block, gray, TimelineStatus.SKIPPED)
        "no_preferences" -> EventDisplayInfo(Icons.Default.Schedule, gray, TimelineStatus.SKIPPED)
        "email_failed" -> EventDisplayInfo(Icons.Default.ErrorOutline, FcRed, TimelineStatus.FAILED)
        "backend_sync_failed" -> EventDisplayInfo(Icons.Default.SyncProblem, FcRed, TimelineStatus.FAILED)
        "worker_failed_source" -> EventDisplayInfo(Icons.Default.Warning, FcRed, TimelineStatus.FAILED)
        "worker_failed_auth" -> EventDisplayInfo(Icons.Default.Person, FcRed, TimelineStatus.FAILED)
        "worker_failed" -> EventDisplayInfo(Icons.Default.ErrorOutline, FcRed, TimelineStatus.FAILED)
        "active_device_activated" -> EventDisplayInfo(Icons.Default.CheckCircle, FcGreen, TimelineStatus.SUCCESS)
        "takeover_requested" -> EventDisplayInfo(Icons.Default.ChevronRight, amber, TimelineStatus.WARNING)
        "takeover_completed" -> EventDisplayInfo(Icons.Default.CheckCircle, FcGreen, TimelineStatus.SUCCESS)
        "reliability_setup_completed" -> EventDisplayInfo(Icons.Default.Refresh, FcGreen, TimelineStatus.SUCCESS)
        else -> when {
            isFailed -> EventDisplayInfo(Icons.Default.ErrorOutline, FcRed, TimelineStatus.FAILED)
            isSkipped -> EventDisplayInfo(Icons.Default.Block, gray, TimelineStatus.SKIPPED)
            isWarning -> EventDisplayInfo(Icons.Default.Warning, amber, TimelineStatus.WARNING)
            else -> EventDisplayInfo(Icons.Default.CheckCircle, FcGreen, TimelineStatus.SUCCESS)
        }
    }
}

fun isRunType(type: String): Boolean = when (type) {
    "worker_run_started", "no_jobs_found", "worker_skipped_inactive",
    "worker_skipped_lock_held", "no_preferences", "worker_failed_source",
    "worker_failed_auth", "worker_failed", "worker_run" -> true
    else -> false
}

fun isEmailType(type: String): Boolean = when (type) {
    "job_alert_sent", "setup_email_sent", "email_failed" -> true
    else -> false
}

fun isErrorType(type: String): Boolean = when (type) {
    "email_failed", "backend_sync_failed", "worker_failed_source",
    "worker_failed_auth", "worker_failed" -> true
    else -> false
}

fun matchesFilter(type: String, filter: HistoryFilter): Boolean = when (filter) {
    HistoryFilter.ALL -> true
    HistoryFilter.RUNS -> isRunType(type)
    HistoryFilter.EMAILS -> isEmailType(type)
    HistoryFilter.ERRORS -> isErrorType(type)
}

val knownPositiveTypes = setOf(
    "scheduler_created",
    "scheduler_stopped",
    "setup_email_sent",
    "job_alert_sent",
    "no_jobs_found",
)

fun formatIsoDate(iso: String, dateFormat: SimpleDateFormat): String = formatTs(iso, dateFormat)

fun formatTs(iso: String, dateFormat: SimpleDateFormat): String {
    if (iso.isBlank()) return "Unknown"
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        dateFormat.format(sdf.parse(iso) ?: java.util.Date())
    } catch (_: Exception) {
        iso.take(16)
    }
}

fun intervalDisplayLabel(minutes: Long): String = when (minutes) {
    15L -> "15 minutes"
    30L -> "30 minutes"
    60L -> "1 hour"
    120L -> "2 hours"
    360L -> "6 hours"
    720L -> "12 hours"
    1440L -> "Daily"
    else -> "${minutes} minutes"
}

fun intervalLabel(minutes: Int): String = intervalDisplayLabel(minutes.toLong())

fun intervalLabel(minutes: Int?): String = when (minutes) {
    null -> "Unknown"
    else -> intervalDisplayLabel(minutes.toLong())
}
