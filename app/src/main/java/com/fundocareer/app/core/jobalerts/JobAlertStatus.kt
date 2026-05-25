package com.fundocareer.app.core.jobalerts

enum class SchedulerDisplayStatus {
    Loading, Active, Paused, Stopped, Error
}

object JobAlertStatus {
    const val SUCCESS_EMAIL_SENT = "SUCCESS_EMAIL_SENT"
    const val SUCCESS_NO_NEW_JOBS = "SUCCESS_NO_NEW_JOBS"
    const val SKIPPED_LOCK_HELD_BY_OTHER_DEVICE = "SKIPPED_LOCK_HELD_BY_OTHER_DEVICE"
    const val FAILED_NETWORK = "FAILED_NETWORK"
    const val FAILED_JOB_SOURCE = "FAILED_JOB_SOURCE"
    const val FAILED_PARSE_CHANGED = "FAILED_PARSE_CHANGED"
    const val FAILED_EMAIL_BACKEND = "FAILED_EMAIL_BACKEND"
    const val FAILED_AUTH = "FAILED_AUTH"
    const val PAUSED_LOGOUT = "PAUSED_LOGOUT"
    const val NO_ACTIVE_PREFERENCE = "NO_ACTIVE_PREFERENCE"
}
