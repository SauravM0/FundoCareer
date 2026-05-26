package com.fundocareer.app.core.jobs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fundocareer.app.BuildConfig
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
import com.fundocareer.app.core.jobalerts.JobAlertDefaults
import com.fundocareer.app.core.jobalerts.provider.JobAlertApiClient.TimelineEventMetadata
import com.fundocareer.app.core.jobalerts.provider.JobAlertDeviceIdentityProvider
import com.fundocareer.app.core.jobalerts.provider.JobAlertPendingSyncStore
import com.fundocareer.app.core.jobalerts.provider.retryPendingSync
import com.fundocareer.app.core.logging.FcLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.json.JSONObject

data class ActiveDeviceInfo(
    val loading: Boolean = true,
    val currentDeviceActive: Boolean = false,
    val otherDeviceActive: Boolean = false,
    val otherDeviceName: String? = null,
    val otherDeviceActivatedAt: String? = null,
    val otherDeviceLastSeenAt: String? = null,
    val otherDeviceIntervalMinutes: Int? = null,
    val otherDeviceLastJobEmailAt: String? = null,
    val schedulerPreferenceId: String? = null,
    val role: String? = null,
    val location: String? = null,
    val experience: String? = null,
    val skills: String? = null,
    val company: String? = null,
    val datePosted: String? = null,
    val remote: Boolean? = null,
    val salaryMin: Long? = null,
    val salaryMax: Long? = null,
    val reportFormat: String? = null,
    val lastRunAt: String? = null,
    val nextApproxRunAt: String? = null,
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
    val saveFlowSteps: List<SaveFlowStep> = emptyList(),
    val isSaving: Boolean = false,
    val isRetryingSetupEmail: Boolean = false,
    val showStopConfirmDialog: Boolean = false,
    val showTakeoverConfirmDialog: Boolean = false,
    val showReliabilitySetup: Boolean = false,
    val reliabilitySetupStatus: JobAlertReliabilityStatus? = null,
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
    val intervalMinutes: Long = JobAlertDefaults.DEFAULT_INTERVAL_MINUTES,
    val reportFormat: String = "html",
    val showAdvanced: Boolean = false,
)

enum class SaveFlowPhase(val label: String) {
    ValidateForm("Validate form"),
    SaveLocal("Saved locally"),
    BackendConnected("Backend connected"),
    SetupEmailSent("Setup email sent"),
    SetupEmailFailed("Setup email failed"),
    ImmediateRunQueued("Immediate scheduler run queued"),
}

enum class SaveFlowStatus {
    Pending,
    Running,
    Done,
    Failed,
    Skipped,
}

data class SaveFlowStep(
    val phase: SaveFlowPhase,
    val status: SaveFlowStatus,
    val detail: String? = null,
    val errorCode: String? = null,
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

    private var loadJob: Job? = null

    init {
        loadData()
    }

    fun reload() {
        loadData()
    }

    private fun loadData() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val prevState = _uiState.value
            val isFirstLoad = prevState.isLoading && prevState.userEmail.isBlank()
            if (isFirstLoad) {
                _uiState.value = prevState.copy(isLoading = true, error = null)
            }
            try {
                val email = resolveStableUserEmail(prevState)
                val token = tokenStore.getAccessToken() ?: ""

                if (email.isBlank()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isLoggedIn = false,
                        schedulerStatus = SchedulerDisplayStatus.Stopped,
                    )
                    return@launch
                }

                val activePref = repository.getActivePreferenceNow(email)
                val ds = repository.getSchedulerState(email)
                FcLog.i(FcLog.TAG_SCHEDULER, "Preference loaded", mapOf(
                    "hasActive" to (activePref != null),
                    "hasSchedulerState" to (ds != null),
                ))

                var status = deriveSchedulerStatus(activePref, ds, token)

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
                        else historyItems = prevState.history
                    } catch (e: Exception) {
                        FcLog.w(FcLog.TAG_NETWORK, "getEmailHistory failed during load", mapOf(
                            "error" to e.message,
                        ))
                        historyItems = prevState.history
                    }

                    try {
                        val timelineResult = apiClient.getUserTimeline()
                        if (timelineResult.success) {
                            timelineEvents = timelineResult.timeline.toMutableList()
                            FcLog.i(FcLog.TAG_HISTORY, "Timeline loaded", mapOf(
                                "count" to timelineEvents.size,
                            ))
                        } else {
                            timelineEvents = prevState.timeline
                        }
                    } catch (e: Exception) {
                        FcLog.w(FcLog.TAG_HISTORY, "getUserTimeline failed during load", mapOf(
                            "error" to e.message,
                        ))
                        timelineEvents = prevState.timeline
                    }

                    if (activePref != null && !activePref.setupConfirmationSent && !activePref.setupConfirmationError.isNullOrBlank()) {
                        val syntheticEvent = TimelineEvent(
                            type = "email_failed",
                            timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(activePref.updatedAt)),
                            title = "Setup confirmation email failed",
                            description = activePref.setupConfirmationError ?: "Unknown error",
                            status = "failed",
                            runId = null,
                            metadata = TimelineEventMetadata(
                                errorMessage = activePref.setupConfirmationError,
                            ),
                        )
                        timelineEvents = listOf(syntheticEvent) + timelineEvents
                        FcLog.w(FcLog.TAG_HISTORY, "Setup email failure appended to timeline", mapOf(
                            "error" to activePref.setupConfirmationError,
                        ))
                    }

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
                                schedulerPreferenceId = activeResult.activeDevice.schedulerPreferenceId,
                                role = activeResult.activeDevice.preferences?.role,
                                location = activeResult.activeDevice.preferences?.location,
                                experience = activeResult.activeDevice.preferences?.experience,
                                skills = activeResult.activeDevice.preferences?.skills,
                                company = activeResult.activeDevice.preferences?.company,
                                datePosted = activeResult.activeDevice.preferences?.datePosted,
                                remote = activeResult.activeDevice.preferences?.remote,
                                salaryMin = activeResult.activeDevice.preferences?.salaryMin,
                                salaryMax = activeResult.activeDevice.preferences?.salaryMax,
                                reportFormat = activeResult.activeDevice.preferences?.reportFormat,
                                lastRunAt = activeResult.activeDevice.lastRunAt ?: activeResult.activeDevice.schedulerState?.lastRunAt,
                                nextApproxRunAt = activeResult.activeDevice.nextApproxRunAt ?: activeResult.activeDevice.schedulerState?.nextScheduledRunAt,
                            )
                            else -> ActiveDeviceInfo(loading = false)
                        }
                        FcLog.i(FcLog.TAG_ACTIVE_DEVICE, "Backend connectivity state", mapOf(
                            "success" to activeResult.success,
                            "currentDevice" to activeResult.isCurrentDeviceActive,
                            "hasRemote" to (activeResult.activeDevice != null),
                        ))
                        if (activeResult.activeDevice != null && !activeResult.isCurrentDeviceActive) {
                            status = SchedulerDisplayStatus.OtherDeviceActive
                            val activeDto = activeResult.activeDevice
                            repository.saveSchedulerState(
                                (ds ?: DeviceSchedulerStateEntity(
                                    id = UUID.randomUUID().toString(),
                                    userEmail = email,
                                )).copy(
                                    isThisDeviceActive = false,
                                    activeDeviceName = activeDto.deviceName,
                                    activeDeviceLastSeen = activeDto.activeDeviceLastSeenAt,
                                    takeoverRequired = ds?.takeoverRequired ?: false,
                                    updatedAt = System.currentTimeMillis(),
                                )
                            )
                        } else if (activeResult.isCurrentDeviceActive && ds != null) {
                            repository.saveSchedulerState(
                                ds.copy(
                                    isThisDeviceActive = true,
                                    takeoverRequired = false,
                                    updatedAt = System.currentTimeMillis(),
                                )
                            )
                        }
                    } catch (e: Exception) {
                        FcLog.w(FcLog.TAG_ACTIVE_DEVICE, "getActiveDevice failed during load", mapOf(
                            "error" to e.message,
                        ))
                        if (activePref != null) status = SchedulerDisplayStatus.BackendUnreachable
                        deviceInfo = prevState.activeDeviceInfo
                    }

                    try {
                        retryPendingSync(context, token)
                    } catch (e: Exception) {
                        FcLog.w(FcLog.TAG_ACTIVE_DEVICE, "retryPendingSync failed during load", mapOf(
                            "error" to e.message,
                        ))
                    }
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isLoggedIn = token.isNotBlank() || activePref != null,
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
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
                FcLog.e(FcLog.TAG_SCHEDULER, "loadData failed", e)
            }
        }
    }

    fun updateForm(transform: (FormState) -> FormState) {
        _formState.value = transform(_formState.value)
    }

    fun resetForm() {
        _formState.value = FormState()
    }

    private suspend fun resolveStableUserEmail(prevState: JobsUiState): String {
        val directEmail = tokenStore.getUserEmail()
        if (!directEmail.isNullOrBlank()) return directEmail

        val lastKnownEmail = tokenStore.getLastKnownUserEmail()
        if (!lastKnownEmail.isNullOrBlank()) return lastKnownEmail

        if (prevState.userEmail.isNotBlank()) return prevState.userEmail

        val roomActivePref = repository.getAnyActivePreferenceNow()
        return roomActivePref?.userEmail ?: ""
    }

    private fun deriveSchedulerStatus(
        activePref: JobAlertPreferenceEntity?,
        schedulerState: DeviceSchedulerStateEntity?,
        token: String,
    ): SchedulerDisplayStatus {
        if (schedulerState?.pausedDueToLogout == true) return SchedulerDisplayStatus.Paused
        if (activePref == null) return SchedulerDisplayStatus.Stopped
        if (!activePref.setupConfirmationSent && !activePref.setupConfirmationError.isNullOrBlank()) {
            return SchedulerDisplayStatus.EmailFailed
        }
        if (activePref.lastError?.contains("email", ignoreCase = true) == true
            || schedulerState?.lastError?.contains("email", ignoreCase = true) == true
        ) {
            return SchedulerDisplayStatus.EmailFailed
        }
        if (token.isBlank()) return SchedulerDisplayStatus.BackendUnreachable
        if (activePref.nextScheduledRunAt != null || schedulerState?.nextScheduledRunAt != null) {
            return SchedulerDisplayStatus.WaitingForNextRun
        }
        return SchedulerDisplayStatus.Active
    }

    fun savePreference() {
        if (_uiState.value.isSaving) {
            FcLog.w(FcLog.TAG_SCHEDULER, "savePreference: already saving, ignoring duplicate call")
            return
        }

        val form = _formState.value
        setSaveFlow(
            SaveFlowStep(SaveFlowPhase.ValidateForm, SaveFlowStatus.Running),
            SaveFlowStep(SaveFlowPhase.SaveLocal, SaveFlowStatus.Pending),
            SaveFlowStep(SaveFlowPhase.BackendConnected, SaveFlowStatus.Pending),
            SaveFlowStep(SaveFlowPhase.SetupEmailSent, SaveFlowStatus.Pending),
            SaveFlowStep(SaveFlowPhase.ImmediateRunQueued, SaveFlowStatus.Pending),
        )
        if (form.role.isBlank() || form.location.isBlank() || form.experience.isBlank()) {
            updateSaveFlow(SaveFlowPhase.ValidateForm, SaveFlowStatus.Failed, "Role, Location, and Experience are required.", "VALIDATION_FAILED")
            _uiState.value = _uiState.value.copy(error = "Role, Location, and Experience are required.")
            return
        }
        updateSaveFlow(SaveFlowPhase.ValidateForm, SaveFlowStatus.Done)

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, error = null, saveResult = null)
            try {
                val state = _uiState.value
                val token = state.authToken
                val email = state.userEmail.ifBlank { resolveStableUserEmail(state) }
                if (email.isBlank()) {
                    updateSaveFlow(SaveFlowPhase.SaveLocal, SaveFlowStatus.Failed, "Sign in is still restoring. Please try again.", "USER_EMAIL_UNAVAILABLE")
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        error = "Unable to identify the signed-in user yet. Please wait a moment and try again.",
                    )
                    return@launch
                }
                val apiClient = if (token.isNotBlank()) JobAlertApiClient(token) else null

                val existingCount = repository.getActivePreferenceCount(email)
                if (existingCount > 0 && state.activePreference == null) {
                    updateSaveFlow(SaveFlowPhase.SaveLocal, SaveFlowStatus.Failed, "Only one active scheduler is allowed.", "ACTIVE_PREFERENCE_EXISTS")
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        error = "Only one active job alert can be scheduled. Please stop the existing scheduler first.",
                    )
                    return@launch
                }

                val existingPreference = state.activePreference
                val isFirstCreation = existingPreference == null
                val id = existingPreference?.id ?: UUID.randomUUID().toString()
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
                    setupConfirmationSent = existingPreference?.setupConfirmationSent ?: false,
                    setupConfirmationSentAt = existingPreference?.setupConfirmationSentAt,
                    setupConfirmationError = existingPreference?.setupConfirmationError,
                    setupConfirmationErrorCode = existingPreference?.setupConfirmationErrorCode,
                    createdAt = existingPreference?.createdAt ?: System.currentTimeMillis(),
                )
                repository.savePreference(entity)
                val existingSchedulerState = repository.getSchedulerState(email)
                repository.saveSchedulerState(
                    (existingSchedulerState ?: DeviceSchedulerStateEntity(
                        id = UUID.randomUUID().toString(),
                        userEmail = email,
                    )).copy(
                        schedulerEnabled = true,
                        pausedDueToLogout = false,
                        lastError = null,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                updateSaveFlow(SaveFlowPhase.SaveLocal, SaveFlowStatus.Done)

                var deviceActivated = false
                if (apiClient != null) {
                    try {
                        val identity = identityProvider.getDeviceIdentity()
                        syncPreferenceSnapshot(apiClient, identity.deviceId, entity)
                        val activation = apiClient.activateDevice(identity, id, form.intervalMinutes, takeover = false)
                        deviceActivated = activation.success
                        if (!deviceActivated) {
                            if (activation.errorCode == "ACTIVE_DEVICE_EXISTS") {
                                val activeDeviceName = activation.activeDevice?.deviceName
                                val activeDeviceLastSeen = activation.activeDevice?.activeDeviceLastSeenAt
                                repository.saveSchedulerState(
                                    (repository.getSchedulerState(email) ?: DeviceSchedulerStateEntity(
                                        id = UUID.randomUUID().toString(),
                                        userEmail = email,
                                    )).copy(
                                        schedulerEnabled = true,
                                        pausedDueToLogout = false,
                                        isThisDeviceActive = false,
                                        activeDeviceName = activeDeviceName,
                                        activeDeviceLastSeen = activeDeviceLastSeen,
                                        takeoverRequired = true,
                                        updatedAt = System.currentTimeMillis(),
                                    )
                                )
                                _uiState.value = _uiState.value.copy(
                                    activeDeviceInfo = activation.activeDevice?.let { dto -> activeDeviceInfoFromDto(dto) }
                                        ?: _uiState.value.activeDeviceInfo,
                                    schedulerStatus = SchedulerDisplayStatus.OtherDeviceActive,
                                    showTakeoverConfirmDialog = true,
                                )
                            } else {
                                JobAlertPendingSyncStore(context).recordPendingActivation(id)
                            }
                            updateSaveFlow(
                                SaveFlowPhase.BackendConnected,
                                SaveFlowStatus.Failed,
                                activation.error ?: "Backend activation was queued for retry.",
                                activation.errorCode ?: "BACKEND_ACTIVATION_FAILED",
                            )
                        } else {
                            repository.saveSchedulerState(
                                (repository.getSchedulerState(email) ?: DeviceSchedulerStateEntity(
                                    id = UUID.randomUUID().toString(),
                                    userEmail = email,
                                )).copy(
                                    schedulerEnabled = true,
                                    pausedDueToLogout = false,
                                    isThisDeviceActive = true,
                                    takeoverRequired = false,
                                    updatedAt = System.currentTimeMillis(),
                                )
                            )
                            updateSaveFlow(SaveFlowPhase.BackendConnected, SaveFlowStatus.Done)
                        }
                    } catch (e: Exception) {
                        FcLog.w(FcLog.TAG_ACTIVE_DEVICE, "activateDevice failed during save", mapOf(
                            "error" to e.message,
                        ))
                        JobAlertPendingSyncStore(context).recordPendingActivation(id)
                        updateSaveFlow(SaveFlowPhase.BackendConnected, SaveFlowStatus.Failed, e.message, "BACKEND_ACTIVATION_EXCEPTION")
                    }
                } else {
                    updateSaveFlow(SaveFlowPhase.BackendConnected, SaveFlowStatus.Skipped, "No auth token available.")
                }

                val shouldSendSetupEmail = isFirstCreation && !entity.setupConfirmationSent && deviceActivated
                if (shouldSendSetupEmail && apiClient != null) {
                    sendSetupConfirmationEmailForPreference(apiClient, entity)
                } else {
                    updateSaveFlow(
                        SaveFlowPhase.SetupEmailSent,
                        SaveFlowStatus.Skipped,
                        if (!isFirstCreation) "Setup email only sends on first creation." else "Setup email already sent or auth unavailable.",
                    )
                }

                if (deviceActivated) {
                    IntervalJobAlertScheduler.startSchedulerAfterSave(context, id, form.intervalMinutes, true)
                    updateSaveFlow(SaveFlowPhase.ImmediateRunQueued, SaveFlowStatus.Done)
                } else {
                    updateSaveFlow(SaveFlowPhase.ImmediateRunQueued, SaveFlowStatus.Skipped, "This device is not the active scheduler device.")
                }

                val intervalLabel = intervalDisplayLabel(form.intervalMinutes)
                val message = if (deviceActivated) {
                    "Saved locally. Backend connected. Repeats every $intervalLabel."
                } else {
                    "Saved locally. This device is not active yet, so no scheduled emails will run here."
                }

                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    saveResult = message,
                    schedulerStatus = if (deviceActivated) SchedulerDisplayStatus.Active else _uiState.value.schedulerStatus,
                    activePreference = repository.getPreferenceById(id) ?: entity,
                )

                refreshAfterSave(email, token)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSaving = false, error = "Failed to save: ${e.message}")
                FcLog.e(FcLog.TAG_SCHEDULER, "savePreference failed", e)
            }
        }
    }

    fun retrySetupConfirmationEmail() {
        val state = _uiState.value
        val pref = state.activePreference ?: return
        if (state.isRetryingSetupEmail || pref.setupConfirmationSent) return
        if (state.authToken.isBlank()) {
            _uiState.value = state.copy(error = "Sign in again to retry setup email.")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRetryingSetupEmail = true, error = null)
            setSaveFlow(
                SaveFlowStep(SaveFlowPhase.SetupEmailSent, SaveFlowStatus.Running, "Retrying setup email."),
            )
            try {
                sendSetupConfirmationEmailForPreference(JobAlertApiClient(state.authToken), pref)
                val latest = repository.getPreferenceById(pref.id)
                _uiState.value = _uiState.value.copy(
                    isRetryingSetupEmail = false,
                    activePreference = latest ?: _uiState.value.activePreference,
                )
            } catch (e: Exception) {
                updateSaveFlow(SaveFlowPhase.SetupEmailFailed, SaveFlowStatus.Failed, e.message, "SETUP_EMAIL_RETRY_EXCEPTION")
                _uiState.value = _uiState.value.copy(isRetryingSetupEmail = false)
                FcLog.e(FcLog.TAG_EMAIL, "retrySetupConfirmationEmail failed", e)
            }
        }
    }

    private fun setSaveFlow(vararg steps: SaveFlowStep) {
        _uiState.value = _uiState.value.copy(saveFlowSteps = steps.toList())
    }

    private fun updateSaveFlow(
        phase: SaveFlowPhase,
        status: SaveFlowStatus,
        detail: String? = null,
        errorCode: String? = null,
    ) {
        val current = _uiState.value.saveFlowSteps
        val updated = if (current.any { it.phase == phase }) {
            current.map { step ->
                if (step.phase == phase) step.copy(status = status, detail = detail, errorCode = errorCode) else step
            }
        } else {
            current + SaveFlowStep(phase, status, detail, errorCode)
        }
        _uiState.value = _uiState.value.copy(saveFlowSteps = updated)
    }

    private suspend fun sendSetupConfirmationEmailForPreference(
        apiClient: JobAlertApiClient,
        pref: JobAlertPreferenceEntity,
    ): Boolean {
        updateSaveFlow(SaveFlowPhase.SetupEmailSent, SaveFlowStatus.Running)
        FcLog.i(FcLog.TAG_EMAIL, "sendSetupConfirmationEmailForPreference", mapOf(
            "preferenceId" to pref.id,
            "role" to pref.role,
            "location" to pref.location,
        ))
        val result = apiClient.sendSetupConfirmationEmail(
            to = pref.userEmail,
            preferenceId = pref.id,
            role = pref.role,
            location = pref.location,
            experience = pref.experience,
            skills = pref.skills,
            remote = pref.remote,
            datePosted = pref.datePosted,
            intervalMinutes = pref.intervalMinutes,
        )
        FcLog.i(FcLog.TAG_EMAIL, "Setup confirmation email result", mapOf(
            "success" to result.success,
            "sent" to result.setupEmailSent,
            "errorCode" to result.errorCode,
        ))

        return if (result.success && result.setupEmailSent) {
            val sentAt = System.currentTimeMillis()
            FcLog.i(FcLog.TAG_EMAIL, "Setup email confirmed sent", mapOf(
                "preferenceId" to pref.id,
            ))
            repository.markSetupConfirmationSent(pref.id, sentAt)
            updateSaveFlow(SaveFlowPhase.SetupEmailSent, SaveFlowStatus.Done)
            appendSetupEmailTimelineEvent()
            val latest = repository.getPreferenceById(pref.id)
            if (latest != null && _uiState.value.activePreference?.id == pref.id) {
                _uiState.value = _uiState.value.copy(activePreference = latest)
            }
            true
        } else {
            val errorMsg = result.error ?: "Setup email was not sent."
            val errorCode = result.errorCode ?: "SETUP_EMAIL_NOT_SENT"
            FcLog.w(FcLog.TAG_EMAIL, "Setup email NOT sent", mapOf(
                "preferenceId" to pref.id,
                "errorCode" to errorCode,
                "error" to errorMsg,
            ))
            repository.markSetupConfirmationFailed(pref.id, errorMsg, errorCode)
            updateSaveFlow(SaveFlowPhase.SetupEmailSent, SaveFlowStatus.Failed, errorMsg, errorCode)
            updateSaveFlow(SaveFlowPhase.SetupEmailFailed, SaveFlowStatus.Failed, errorMsg, errorCode)
            val latest = repository.getPreferenceById(pref.id)
            if (latest != null && _uiState.value.activePreference?.id == pref.id) {
                _uiState.value = _uiState.value.copy(activePreference = latest)
            }
            false
        }
    }

    private fun appendSetupEmailTimelineEvent() {
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val nowIso = isoFormat.format(Date(System.currentTimeMillis()))
        val setupEvent = TimelineEvent(
            type = "setup_email_sent",
            timestamp = nowIso,
            title = "Setup confirmation sent",
            description = "Preferences confirmation email delivered",
            metadata = null,
        )
        val currentTimeline = _uiState.value.timeline
        _uiState.value = _uiState.value.copy(timeline = listOf(setupEvent) + currentTimeline)
    }

    private fun refreshAfterSave(email: String, token: String) {
        viewModelScope.launch {
            try {
                val ds = repository.getSchedulerState(email)
                val prefs = repository.getActivePreferences(email).first()
                val activePref = prefs.firstOrNull { !it.stoppedByUser && it.schedulerEnabled }
                _uiState.value = _uiState.value.copy(
                    activePreference = activePref ?: _uiState.value.activePreference,
                    schedulerState = ds,
                    schedulerStatus = if (activePref != null) deriveSchedulerStatus(activePref, ds, token) else _uiState.value.schedulerStatus,
                )
                refreshReliability()

                if (token.isNotBlank()) {
                    try {
                        val apiClient = JobAlertApiClient(token)
                        val timelineResult = apiClient.getUserTimeline()
                        if (timelineResult.success) {
                            _uiState.value = _uiState.value.copy(
                                timeline = timelineResult.timeline
                            )
                        }
                    } catch (e: Exception) {
                        FcLog.w(FcLog.TAG_NETWORK, "Timeline refresh failed after save", mapOf(
                            "error" to e.message,
                        ))
                    }
                }
            } catch (e: Exception) {
                FcLog.w(FcLog.TAG_SCHEDULER, "refreshAfterSave failed", mapOf(
                    "error" to e.message,
                ))
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
                val schedulerState = repository.getSchedulerState(state.userEmail)
                if (schedulerState != null) {
                    repository.saveSchedulerState(
                        schedulerState.copy(
                            schedulerEnabled = false,
                            isThisDeviceActive = false,
                            takeoverRequired = false,
                            activeDeviceName = null,
                            activeDeviceLastSeen = null,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                }

                if (state.authToken.isNotBlank() && prefs.isNotEmpty()) {
                    try {
                        val identity = identityProvider.getDeviceIdentity()
                        val apiClient = JobAlertApiClient(state.authToken)
                        apiClient.deactivateDevice(identity.deviceId, prefs.first().id)
                    } catch (e: Exception) {
                        FcLog.w(FcLog.TAG_ACTIVE_DEVICE, "deactivateDevice failed during stop, queuing pending sync", mapOf(
                            "error" to e.message,
                        ))
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
                FcLog.e(FcLog.TAG_SCHEDULER, "stopScheduler failed", e)
            }
        }
    }

    fun takeoverDevice() {
        viewModelScope.launch {
            try {
                val state = _uiState.value
                if (state.authToken.isBlank()) {
                    _uiState.value = state.copy(error = "Sign in again to take over the scheduler on this device.")
                    return@launch
                }
                val email = state.userEmail.ifBlank { resolveStableUserEmail(state) }
                if (email.isBlank()) {
                    _uiState.value = state.copy(error = "Unable to identify the signed-in user yet. Please try again.")
                    return@launch
                }

                val pref = state.activePreference ?: preferenceFromRemoteActiveDevice(state, email)
                if (pref == null) {
                    _uiState.value = state.copy(error = "No scheduler details were available to take over.")
                    return@launch
                }

                repository.savePreference(pref)
                repository.saveSchedulerState(
                    (repository.getSchedulerState(email) ?: DeviceSchedulerStateEntity(
                        id = UUID.randomUUID().toString(),
                        userEmail = email,
                    )).copy(
                        schedulerEnabled = true,
                        pausedDueToLogout = false,
                        isThisDeviceActive = true,
                        takeoverRequired = false,
                        activeDeviceName = null,
                        activeDeviceLastSeen = null,
                        lastError = null,
                        nextScheduledRunAt = System.currentTimeMillis() + pref.intervalMinutes.coerceAtLeast(15L) * 60_000L,
                        updatedAt = System.currentTimeMillis(),
                    )
                )

                val identity = identityProvider.getDeviceIdentity()
                val apiClient = JobAlertApiClient(state.authToken)
                syncPreferenceSnapshot(apiClient, identity.deviceId, pref)
                val result = apiClient.activateDevice(identity, pref.id, pref.intervalMinutes, takeover = true)
                if (result.success) {
                    syncPreferenceSnapshot(apiClient, identity.deviceId, pref)
                    IntervalJobAlertScheduler.startSchedulerAfterSave(context, pref.id, pref.intervalMinutes, true)

                    val shouldSendSetupEmail = !pref.setupConfirmationSent
                    if (shouldSendSetupEmail) {
                        sendSetupConfirmationEmailForPreference(apiClient, pref)
                    }

                    _formState.value = formFromPreference(pref)
                    _uiState.value = _uiState.value.copy(
                        showTakeoverConfirmDialog = false,
                        activePreference = pref,
                        schedulerState = repository.getSchedulerState(email),
                        activeDeviceInfo = ActiveDeviceInfo(loading = false, currentDeviceActive = true),
                        schedulerStatus = SchedulerDisplayStatus.Active,
                        saveResult = "Scheduler taken over on this device.",
                        error = null,
                    )
                    refreshAfterSave(email, state.authToken)
                } else {
                    _uiState.value = _uiState.value.copy(
                        error = result.error ?: "Failed to take over scheduler.",
                        activeDeviceInfo = result.activeDevice?.let { dto -> activeDeviceInfoFromDto(dto) } ?: _uiState.value.activeDeviceInfo,
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Failed to activate this device: ${e.message}")
                FcLog.e(FcLog.TAG_ACTIVE_DEVICE, "takeoverDevice failed", e)
            }
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

    fun showTakeoverDialog() {
        _uiState.value = _uiState.value.copy(showTakeoverConfirmDialog = true)
    }

    fun hideTakeoverDialog() {
        _uiState.value = _uiState.value.copy(showTakeoverConfirmDialog = false)
    }

    fun startSaveWithReliabilityCheck() {
        if (_uiState.value.isSaving) {
            FcLog.w(FcLog.TAG_SCHEDULER, "startSaveWithReliabilityCheck: already saving, ignoring")
            return
        }
        val status = JobAlertReliabilityEngine.getReliabilityStatus(context, _uiState.value.schedulerState)
        FcLog.i(FcLog.TAG_RELIABILITY, "startSaveWithReliabilityCheck", mapOf(
            "canActivate" to status.canActivateScheduler,
            "overall" to status.overall.name,
        ))
        if (!status.canActivateScheduler) {
            _uiState.value = _uiState.value.copy(
                showReliabilitySetup = true,
                reliabilitySetupStatus = status,
            )
        } else {
            savePreference()
        }
    }

    fun hideReliabilitySetup() {
        _uiState.value = _uiState.value.copy(showReliabilitySetup = false, reliabilitySetupStatus = null)
    }

    fun refreshReliabilitySetup() {
        val status = JobAlertReliabilityEngine.getReliabilityStatus(context, _uiState.value.schedulerState)
        _uiState.value = _uiState.value.copy(reliabilitySetupStatus = status)
        FcLog.i(FcLog.TAG_RELIABILITY, "refreshReliabilitySetup", mapOf(
            "canActivate" to status.canActivateScheduler,
        ))
    }

    fun openReliabilitySetup() {
        val status = JobAlertReliabilityEngine.getReliabilityStatus(context, _uiState.value.schedulerState)
        _uiState.value = _uiState.value.copy(
            showReliabilitySetup = true,
            reliabilitySetupStatus = status,
        )
        FcLog.i(FcLog.TAG_RELIABILITY, "openReliabilitySetup", mapOf(
            "canActivate" to status.canActivateScheduler,
        ))
    }

    fun onReliabilitySetupContinue() {
        JobAlertReliabilityEngine.markReliabilityCompleted(context)
        val status = JobAlertReliabilityEngine.getReliabilityStatus(context, _uiState.value.schedulerState)
        _uiState.value = _uiState.value.copy(showReliabilitySetup = false, reliabilitySetupStatus = null)
        if (status.canActivateScheduler) {
            FcLog.i(FcLog.TAG_RELIABILITY, "Reliability complete, proceeding with save")
            savePreference()
        } else {
            FcLog.w(FcLog.TAG_RELIABILITY, "Reliability still incomplete after continue")
            _uiState.value = _uiState.value.copy(
                reliabilitySetupStatus = status,
                showReliabilitySetup = true,
                error = "Please complete all required setup items first.",
            )
        }
    }

    fun confirmReliabilityAutostart() {
        JobAlertReliabilityEngine.confirmAutostart(context)
        refreshReliabilitySetup()
    }

    fun confirmReliabilityBattery() {
        JobAlertReliabilityEngine.confirmBatteryOptimization(context)
        refreshReliabilitySetup()
    }

    fun confirmReliabilityBackground() {
        JobAlertReliabilityEngine.confirmBackgroundData(context)
        refreshReliabilitySetup()
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

    private suspend fun syncPreferenceSnapshot(
        apiClient: JobAlertApiClient,
        deviceId: String,
        pref: JobAlertPreferenceEntity,
    ) {
        val intervalMs = pref.intervalMinutes.coerceAtLeast(15L) * 60_000L
        val nextRunAt = pref.nextScheduledRunAt ?: (System.currentTimeMillis() + intervalMs)
        val result = apiClient.syncState(
            deviceId = deviceId,
            deviceType = "android",
            appVersion = BuildConfig.VERSION_NAME,
            preferences = preferenceSnapshotJson(pref),
            schedulerState = schedulerStateSnapshotJson(pref, nextRunAt),
        )
        if (!result.success) {
            FcLog.w(FcLog.TAG_ACTIVE_DEVICE, "syncPreferenceSnapshot failed", mapOf(
                "preferenceId" to pref.id,
                "error" to result.error,
            ))
        }
    }

    private fun preferenceSnapshotJson(pref: JobAlertPreferenceEntity): JSONObject {
        return JSONObject().apply {
            put("preferenceId", pref.id)
            put("role", pref.role)
            put("location", pref.location)
            put("experience", pref.experience)
            pref.remote?.let { put("remote", it) }
            pref.salaryMin?.let { put("salaryMin", it) }
            pref.salaryMax?.let { put("salaryMax", it) }
            pref.skills?.let { put("skills", it) }
            pref.company?.let { put("company", it) }
            pref.datePosted?.let { put("datePosted", it) }
            put("intervalMinutes", pref.intervalMinutes)
            put("reportFormat", pref.reportFormat)
            put("schedulerEnabled", pref.schedulerEnabled)
            put("nextScheduledRunAt", isoDate(pref.nextScheduledRunAt ?: System.currentTimeMillis() + pref.intervalMinutes.coerceAtLeast(15L) * 60_000L))
        }
    }

    private fun schedulerStateSnapshotJson(pref: JobAlertPreferenceEntity, nextRunAt: Long): JSONObject {
        return JSONObject().apply {
            put("preferenceId", pref.id)
            put("schedulerEnabled", pref.schedulerEnabled && !pref.stoppedByUser)
            put("intervalMinutes", pref.intervalMinutes)
            pref.lastRunAt?.let { put("lastRunAt", isoDate(it)) }
            put("nextScheduledRunAt", isoDate(nextRunAt))
            pref.lastEmailSentAt?.let { put("lastJobEmailAt", isoDate(it)) }
        }
    }

    private fun activeDeviceInfoFromDto(dto: JobAlertApiClient.ActiveDeviceDto): ActiveDeviceInfo {
        return ActiveDeviceInfo(
            loading = false,
            otherDeviceActive = true,
            otherDeviceName = dto.deviceName,
            otherDeviceActivatedAt = dto.activatedAt,
            otherDeviceLastSeenAt = dto.activeDeviceLastSeenAt,
            otherDeviceIntervalMinutes = dto.intervalMinutes,
            otherDeviceLastJobEmailAt = dto.lastJobEmailAt,
            schedulerPreferenceId = dto.schedulerPreferenceId,
            role = dto.preferences?.role,
            location = dto.preferences?.location,
            experience = dto.preferences?.experience,
            skills = dto.preferences?.skills,
            company = dto.preferences?.company,
            datePosted = dto.preferences?.datePosted,
            remote = dto.preferences?.remote,
            salaryMin = dto.preferences?.salaryMin,
            salaryMax = dto.preferences?.salaryMax,
            reportFormat = dto.preferences?.reportFormat,
            lastRunAt = dto.lastRunAt ?: dto.schedulerState?.lastRunAt,
            nextApproxRunAt = dto.nextApproxRunAt ?: dto.schedulerState?.nextScheduledRunAt,
        )
    }

    private fun preferenceFromRemoteActiveDevice(state: JobsUiState, email: String): JobAlertPreferenceEntity? {
        val remote = state.activeDeviceInfo
        if (!remote.otherDeviceActive) return null
        val role = remote.role?.trim().orEmpty().ifBlank { return null }
        val location = remote.location?.trim().orEmpty().ifBlank { "Any" }
        val experience = remote.experience?.trim().orEmpty().ifBlank { "Any" }
        val interval = remote.otherDeviceIntervalMinutes?.toLong()?.coerceAtLeast(15L) ?: 60L
        return JobAlertPreferenceEntity(
            id = remote.schedulerPreferenceId ?: UUID.randomUUID().toString(),
            userEmail = email,
            role = role,
            location = location,
            experience = experience,
            remote = remote.remote,
            salaryMin = remote.salaryMin,
            salaryMax = remote.salaryMax,
            skills = remote.skills,
            company = remote.company,
            datePosted = remote.datePosted,
            reportFormat = remote.reportFormat ?: "html",
            intervalMinutes = interval,
            active = true,
            schedulerEnabled = true,
            stoppedByUser = false,
            pausedDueToLogout = false,
            createdAt = System.currentTimeMillis(),
        )
    }

    private fun formFromPreference(pref: JobAlertPreferenceEntity): FormState {
        return FormState(
            role = pref.role,
            location = pref.location,
            experience = pref.experience,
            remote = pref.remote,
            salaryMin = pref.salaryMin?.toString() ?: "",
            salaryMax = pref.salaryMax?.toString() ?: "",
            skills = pref.skills ?: "",
            company = pref.company ?: "",
            datePosted = pref.datePosted ?: "",
            intervalMinutes = pref.intervalMinutes,
            reportFormat = pref.reportFormat,
        )
    }

    private fun isoDate(ts: Long): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(ts))
    }
}
