package com.fundocareer.app.core.jobalerts

enum class OverallReliability {
    OPTIMIZED,
    LIMITED,
    NEEDS_SETUP,
    LOCKED
}

enum class ReliabilityItemType {
    NotificationPermission,
    BatteryOptimization,
    BackgroundData,
    AutostartOem
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
    val actionLabel: String? = null,
    val isMandatory: Boolean = false
)

data class JobAlertReliabilityStatus(
    val overall: OverallReliability,
    val items: List<ReliabilityChecklistItem>,
    val activeSchedulerDevice: DeviceSchedulerStateEntity? = null
) {
    val mandatoryItemsComplete: Boolean
        get() = items.filter { it.isMandatory }.all { item ->
            item.status == ReliabilityItemStatus.Ready
                || item.status == ReliabilityItemStatus.UserConfirmed
                || item.status == ReliabilityItemStatus.NotApplicable
        }

    val canActivateScheduler: Boolean
        get() = mandatoryItemsComplete && overall != OverallReliability.LOCKED
}
