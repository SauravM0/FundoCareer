package com.fundocareer.app.core.jobs

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fundocareer.app.SecureTokenStore
import com.fundocareer.app.core.jobalerts.DeviceSchedulerStateEntity
import com.fundocareer.app.core.jobalerts.JobAlertPreferenceEntity
import com.fundocareer.app.core.jobalerts.JobAlertReliabilityEngine
import com.fundocareer.app.core.jobalerts.JobAlertReliabilityStatus
import com.fundocareer.app.core.jobalerts.JobAlertRepository
import com.fundocareer.app.core.jobalerts.SchedulerDisplayStatus
import com.fundocareer.app.core.jobalerts.provider.IntervalJobAlertScheduler
import com.fundocareer.app.core.jobalerts.provider.JobAlertApiClient
import com.fundocareer.app.core.jobalerts.provider.JobAlertApiClient.EmailHistoryItem
import com.fundocareer.app.core.jobalerts.provider.JobAlertApiClient.TimelineEvent
import com.fundocareer.app.core.jobalerts.provider.JobAlertDeviceIdentityProvider
import com.fundocareer.app.core.jobalerts.provider.JobAlertPendingSyncStore
import com.fundocareer.app.core.jobalerts.provider.retryPendingSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class ActiveDeviceInfo(
    val loading: Boolean = true,
    val currentDeviceActive: Boolean = false,
    val otherDeviceActive: Boolean = false,
    val otherDeviceName: String? = null,
    val otherDeviceActivatedAt: String? = null,
    val otherDeviceLastSeenAt: String? = null,
    val otherDeviceIntervalMinutes: Int? = null,
    val otherDeviceLastJobEmailAt: String? = null,
    val error: String? = null,
)

data class JobsUiState(
    val isLoading: Boolean = true,
    val isLoggedIn: Boolean = false,
    val userEmail: String = "",
    val authToken: String = "",
    val activePreference: JobAlertPreferenceEntity? = null,
    val schedulerState: DeviceSchedulerStateEntity? = null,
    val schedulerStatus: SchedulerDisplayStatus = SchedulerDisplayStatus.Loading,
    val activeDeviceInfo: ActiveDeviceInfo = ActiveDeviceInfo(),
    val history: List<EmailHistoryItem> = emptyList(),
    val timeline: List<TimelineEvent> = emptyList(),
    val error: String? = null,
    val saveResult: String? = null,
    val isSaving: Boolean = false,
    val showStopConfirmDialog: Boolean = false,
)

data class FormState(
    val role: String = "",
    val location: String = "",
    val experience: String = "",
    val remote: Boolean? = null,
    val salaryMin: String = "",
    val salaryMax: String = "",
    val skills: String = "",
    val company: String = "",
    val datePosted: String = "",
    val intervalMinutes: Long = 60L,
    val reportFormat: String = "html",
    val showAdvanced: Boolean = false,
)

class JobsViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val repository = JobAlertRepository.getInstance(context)
    private val tokenStore = SecureTokenStore(context)
    private val identityProvider = JobAlertDeviceIdentityProvider(context)

    private val _uiState = MutableStateFlow(JobsUiState())
    val uiState: StateFlow<JobsUiState> = _uiState.asStateFlow()

    private val _formState = MutableStateFlow(FormState())
    val formState: StateFlow<FormState> = _formState.asStateFlow()

    private val _reliabilityStatus = MutableStateFlow<JobAlertReliabilityStatus?>(null)
    val reliabilityStatus: StateFlow<JobAlertReliabilityStatus?> = _reliabilityStatus.asStateFlow()

    val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.US)

    init {
        loadData()
    }

    fun reload() {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val email = tokenStore.getUserEmail() ?: ""
                val token = tokenStore.getAccessToken() ?: ""

                if (email.isBlank()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isLoggedIn = false,
                        schedulerStatus = SchedulerDisplayStatus.Stopped,
                    )
                    return@launch
                }

                val prefs = repository.getActivePreferences(email).first()
                val activePref = prefs.firstOrNull { !it.stoppedByUser && it.schedulerEnabled }
                val ds = repository.getSchedulerState(email)

                val status = when {
                    ds?.pausedDueToLogout == true -> SchedulerDisplayStatus.Paused
                    activePref != null -> SchedulerDisplayStatus.Active
                    else -> SchedulerDisplayStatus.Stopped
                }

                if (activePref != null) {
                    _formState.value = FormState(
                        role = activePref.role,
                        location = activePref.location,
                        experience = activePref.experience,
                        remote = activePref.remote,
                        salaryMin = activePref.salaryMin?.toString() ?: "",
                        salaryMax = activePref.salaryMax?.toString() ?: "",
                        skills = activePref.skills ?: "",
                        company = activePref.company ?: "",
                        datePosted = activePref.datePosted ?: "",
                        intervalMinutes = activePref.intervalMinutes.coerceAtLeast(15L),
                        reportFormat = activePref.reportFormat,
                    )
                }

                var historyItems = emptyList<EmailHistoryItem>()
                var timelineEvents = emptyList<TimelineEvent>()
                var deviceInfo = ActiveDeviceInfo()

                if (token.isNotBlank()) {
                    val apiClient = JobAlertApiClient(token)
                    try {
                        val histResult = apiClient.getEmailHistory()
                        if (histResult.success) historyItems = histResult.history.takeLast(5)
                    } catch (_: Exception) {}

                    try {
                        val timelineResult = apiClient.getUserTimeline()
                        if (timelineResult.success) {
                            timelineEvents = timelineResult.timeline.filter { t ->
                                t.type in knownPositiveTypes
                            }
                        }
                    } catch (_: Exception) {}

                    try {
                        val deviceId = identityProvider.getDeviceIdForApi()
                        val activeResult = apiClient.getActiveDevice(deviceId)
                        deviceInfo = when {
                            !activeResult.success -> ActiveDeviceInfo(loading = false, error = "Unavailable")
                            activeResult.isCurrentDeviceActive -> ActiveDeviceInfo(loading = false, currentDeviceActive = true)
                            activeResult.activeDevice != null -> ActiveDeviceInfo(
                                loading = false,
                                otherDeviceActive = true,
                                otherDeviceName = activeResult.activeDevice.deviceName,
                                otherDeviceActivatedAt = activeResult.activeDevice.activatedAt,
                                otherDeviceLastSeenAt = activeResult.activeDevice.activeDeviceLastSeenAt,
                                otherDeviceIntervalMinutes = activeResult.activeDevice.intervalMinutes,
                                otherDeviceLastJobEmailAt = activeResult.activeDevice.lastJobEmailAt,
                            )
                            else -> ActiveDeviceInfo(loading = false)
                        }
                    } catch (_: Exception) {
                        deviceInfo = ActiveDeviceInfo(loading = false, error = "Unavailable")
                    }

                    try { retryPendingSync(context, token) } catch (_: Exception) {}
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isLoggedIn = true,
                    userEmail = email,
                    authToken = token,
                    activePreference = activePref,
                    schedulerState = ds,
                    schedulerStatus = status,
                    activeDeviceInfo = deviceInfo,
                    history = historyItems,
                    timeline = timelineEvents,
                )
                refreshReliability()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
                Log.e(TAG, "loadData failed", e)
            }
        }
    }

    fun updateForm(transform: (FormState) -> FormState) {
        _formState.value = transform(_formState.value)
    }

    fun resetForm() {
        _formState.value = FormState()
    }

    fun savePreference() {
        val form = _formState.value
        if (form.role.isBlank() || form.location.isBlank() || form.experience.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Role, Location, and Experience are required.")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, error = null, saveResult = null)
            try {
                val state = _uiState.value
                val token = state.authToken
                val email = state.userEmail

                if (token.isNotBlank() && email.isNotBlank()) {
                    try {
                        val apiClient = JobAlertApiClient(token)
                        val deviceId = identityProvider.getDeviceIdForApi()
                        val activeResult = apiClient.getActiveDevice(deviceId)
                        if (activeResult.activeDevice != null && !activeResult.isCurrentDeviceActive) {
                            _uiState.value = _uiState.value.copy(
                                isSaving = false,
                                error = "Another device (${activeResult.activeDevice.deviceName ?: "unknown"}) is already managing your job alerts.",
                            )
                            return@launch
                        }
                    } catch (_: Exception) {}
                }

                val existingCount = repository.getActivePreferenceCount(email)
                if (existingCount > 0 && state.activePreference == null) {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        error = "Only one active job alert can be scheduled. Please stop the existing scheduler first.",
                    )
                    return@launch
                }

                val id = state.activePreference?.id ?: UUID.randomUUID().toString()
                val entity = JobAlertPreferenceEntity(
                    id = id,
                    userEmail = email,
                    role = form.role.trim(),
                    location = form.location.trim(),
                    experience = form.experience.trim(),
                    remote = form.remote,
                    salaryMin = form.salaryMin.toLongOrNull(),
                    salaryMax = form.salaryMax.toLongOrNull(),
                    skills = form.skills.trim().ifBlank { null },
                    company = form.company.trim().ifBlank { null },
                    datePosted = form.datePosted.trim().ifBlank { null },
                    reportFormat = form.reportFormat,
                    intervalMinutes = form.intervalMinutes,
                    active = true,
                    schedulerEnabled = true,
                    stoppedByUser = false,
                    pausedDueToLogout = false,
                )
                repository.savePreference(entity)
                repository.saveSchedulerState(
                    DeviceSchedulerStateEntity(
                        id = UUID.randomUUID().toString(),
                        userEmail = email,
                        schedulerEnabled = true,
                        pausedDueToLogout = false,
                    )
                )

                var deviceActivated = false
                if (token.isNotBlank()) {
                    try {
                        val identity = identityProvider.getDeviceIdentity()
                        val apiClient = JobAlertApiClient(token)
                        val activation = apiClient.activateDevice(identity, id, form.intervalMinutes)
                        deviceActivated = activation.success
                        if (!deviceActivated) {
                            JobAlertPendingSyncStore(context).recordPendingActivation(id)
                        }
                    } catch (_: Exception) {
                        JobAlertPendingSyncStore(context).recordPendingActivation(id)
                    }
                }

                IntervalJobAlertScheduler.startSchedulerAfterSave(context, id, form.intervalMinutes, true)

                val intervalLabel = intervalDisplayLabel(form.intervalMinutes)
                val message = if (deviceActivated) {
                    "Job alert saved. This device will manage alerts. Repeats every $intervalLabel."
                } else {
                    "Job alert saved. First search started. Repeats every $intervalLabel."
                }

                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    saveResult = message,
                    schedulerStatus = SchedulerDisplayStatus.Active,
                )

                loadData()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSaving = false, error = "Failed to save: ${e.message}")
                Log.e(TAG, "savePreference failed", e)
            }
        }
    }

    fun stopScheduler() {
        viewModelScope.launch {
            try {
                val state = _uiState.value
                val prefs = listOfNotNull(state.activePreference)
                for (pref in prefs) {
                    repository.updatePreference(pref.copy(
                        schedulerEnabled = false,
                        stoppedByUser = true,
                        nextScheduledRunAt = null,
                        pendingDueRunAt = null,
                        active = false,
                    ))
                    IntervalJobAlertScheduler.cancelScheduler(context, pref.id)
                }

                if (state.authToken.isNotBlank() && prefs.isNotEmpty()) {
                    try {
                        val identity = identityProvider.getDeviceIdentity()
                        val apiClient = JobAlertApiClient(state.authToken)
                        apiClient.deactivateDevice(identity.deviceId, prefs.first().id)
                    } catch (_: Exception) {
                        JobAlertPendingSyncStore(context).recordPendingDeactivation(prefs.first().id)
                    }
                }

                _uiState.value = _uiState.value.copy(
                    saveResult = "Job alert stopped successfully.",
                    schedulerStatus = SchedulerDisplayStatus.Stopped,
                    showStopConfirmDialog = false,
                )
                loadData()
                resetForm()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to stop: ${e.message}",
                    showStopConfirmDialog = false,
                )
                Log.e(TAG, "stopScheduler failed", e)
            }
        }
    }

    fun takeoverDevice() {
        viewModelScope.launch {
            try {
                val state = _uiState.value
                val form = _formState.value
                if (state.authToken.isNotBlank() && state.activePreference != null) {
                    val identity = identityProvider.getDeviceIdentity()
                    val apiClient = JobAlertApiClient(state.authToken)
                    val pref = state.activePreference!!
                    val result = apiClient.activateDevice(identity, pref.id, pref.intervalMinutes)
                    if (result.success) {
                        _uiState.value = _uiState.value.copy(
                            activeDeviceInfo = ActiveDeviceInfo(loading = false, currentDeviceActive = true),
                        )
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun dismissResult() {
        _uiState.value = _uiState.value.copy(saveResult = null)
    }

    fun showStopDialog() {
        _uiState.value = _uiState.value.copy(showStopConfirmDialog = true)
    }

    fun hideStopDialog() {
        _uiState.value = _uiState.value.copy(showStopConfirmDialog = false)
    }

    fun refreshReliability() {
        val state = _uiState.value
        if (state.isLoggedIn) {
            val status = JobAlertReliabilityEngine.getReliabilityStatus(
                context, state.schedulerState
            )
            _reliabilityStatus.value = status
        }
    }

    fun formatTs(ts: Long?): String {
        if (ts == null || ts == 0L) return "Never"
        return dateFormat.format(Date(ts))
    }

    fun formatIsoDate(iso: String): String {
        if (iso.isBlank()) return "Unknown"
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            dateFormat.format(sdf.parse(iso) ?: Date())
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
        1440L -> "24 hours"
        else -> "${minutes} minutes"
    }

    companion object {
        private const val TAG = "JobsViewModel"

        private val knownPositiveTypes = setOf(
            "scheduler_created",
            "scheduler_stopped",
            "setup_email_sent",
            "job_alert_sent",
        )
    }
}
