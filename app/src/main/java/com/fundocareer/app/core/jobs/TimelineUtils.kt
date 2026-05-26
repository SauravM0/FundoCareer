package com.fundocareer.app.core.jobs

import java.text.SimpleDateFormat
import java.util.Locale

val knownPositiveTypes = setOf(
    "scheduler_created",
    "scheduler_stopped",
    "setup_email_sent",
    "job_alert_sent",
)

fun formatIsoDate(iso: String, dateFormat: SimpleDateFormat): String {
    if (iso.isBlank()) return "Unknown"
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        dateFormat.format(sdf.parse(iso) ?: java.util.Date())
    } catch (_: Exception) {
        iso.take(16)
    }
}

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
