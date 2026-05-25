package com.fundocareer.app.core.jobalerts

enum class OverallReliability {
    OPTIMIZED,
    LIMITED,
    NEEDS_SETUP
}

enum class ReliabilityItemType {
    NotificationPermission,
    BatteryOptimization,
    MicrophonePermission,
    CameraPermission,
    FileUploadSupport,
    BackgroundData,
    AutostartOem,
    ActiveScheduler
}

enum class ReliabilityItemStatus {
    Ready,
    NotReady,
    NotApplicable,
    UserConfirmed
}

data class ReliabilityChecklistItem(
    val type: ReliabilityItemType,
    val status: ReliabilityItemStatus,
    val label: String,
    val description: String,
    val actionLabel: String? = null
)

data class JobAlertReliabilityStatus(
    val overall: OverallReliability,
    val items: List<ReliabilityChecklistItem>,
    val activeSchedulerDevice: DeviceSchedulerStateEntity? = null
)
