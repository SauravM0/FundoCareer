package com.fundocareer.app.core.jobs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import android.content.Context
import android.util.Log
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.fundocareer.app.BuildConfig
import com.fundocareer.app.core.jobalerts.JobAlertReliabilityEngine
import com.fundocareer.app.core.jobalerts.JobAlertReliabilityStatus
import com.fundocareer.app.core.jobalerts.OverallReliability
import com.fundocareer.app.core.jobalerts.SchedulerDisplayStatus
import com.fundocareer.app.core.jobalerts.ReliabilityAction
import com.fundocareer.app.core.jobalerts.ReliabilityChecklistItem
import com.fundocareer.app.core.jobalerts.ReliabilityItemStatus
import com.fundocareer.app.core.jobalerts.ReliabilityItemType
import com.fundocareer.app.core.jobalerts.ui.theme.FcAmber
import com.fundocareer.app.core.jobalerts.ui.theme.FcAmberLight
import com.fundocareer.app.core.jobalerts.ui.theme.FcGreen
import com.fundocareer.app.core.jobalerts.ui.theme.FcGreenLight
import com.fundocareer.app.core.jobalerts.ui.theme.FcRed
import com.fundocareer.app.core.jobalerts.ui.theme.FcRedLight
import com.fundocareer.app.core.jobalerts.ui.theme.FcSlate200
import com.fundocareer.app.core.jobalerts.ui.theme.FcSlate500
import com.fundocareer.app.core.jobalerts.provider.JobAlertApiClient.TimelineEvent
import kotlinx.coroutines.launch

@Composable
fun DiscoverySection(viewModel: JobsViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val formState by viewModel.formState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showReliabilitySetup by rememberSaveable { mutableStateOf(false) }
    var setupChecklistItems by remember { mutableStateOf<List<ReliabilityChecklistItem>>(emptyList()) }
    var setupReliabilityStatus by remember { mutableStateOf<JobAlertReliabilityStatus?>(null) }
    var pendingSetupSave by rememberSaveable { mutableStateOf(false) }
    var setupRefreshKey by remember { mutableIntStateOf(0) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                setupRefreshKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { setupRefreshKey++ }

    val hasActive = uiState.activePreference != null && uiState.schedulerStatus != SchedulerDisplayStatus.Stopped
    val showHistory = uiState.timeline.isNotEmpty() || hasActive

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            uiState.isLoading -> LoadingView()
            !uiState.isLoggedIn -> LoginPromptCard(
                onLoginSuccess = { viewModel.reload() },
                modifier = Modifier.fillMaxSize(),
            )
            else -> {
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (uiState.error != null) {
                        ErrorCard(
                            message = uiState.error!!,
                            onDismiss = { viewModel.dismissError() },
                            onRetry = { viewModel.reload() },
                        )
                    }

                    if (uiState.saveResult != null) {
                        ResultCard(
                            message = uiState.saveResult!!,
                            onDismiss = { viewModel.dismissResult() },
                        )
                    }

                    if (uiState.saveFlowSteps.isNotEmpty()) {
                        SaveFlowStatusCard(steps = uiState.saveFlowSteps)
                    }

                    if (BuildConfig.DEBUG) {
                        SchedulerDiagnosticsCard(
                            diagnostics = uiState.diagnostics,
                            onPing = { viewModel.testBackendPing() },
                            onSetupEmail = { viewModel.sendSetupTestEmail() },
                            onRunNow = { viewModel.runSchedulerNow() },
                            onZeroJobEmail = { viewModel.sendZeroJobTestEmail() },
                            onVerifyDevice = { viewModel.verifyActiveDeviceDiagnostic() },
                        )
                    }

                    val isOtherDeviceActive = uiState.activeDeviceInfo.otherDeviceActive

                    if (hasActive && !isOtherDeviceActive) {
                        ActiveSchedulerCard(
                            viewModel = viewModel,
                            uiState = uiState,
                        )
                    }

                    if (isOtherDeviceActive) {
                        OtherDeviceBanner(
                            deviceName = uiState.activeDeviceInfo.otherDeviceName,
                            activatedAt = uiState.activeDeviceInfo.otherDeviceActivatedAt,
                            lastSeenAt = uiState.activeDeviceInfo.otherDeviceLastSeenAt,
                            intervalMinutes = uiState.activeDeviceInfo.otherDeviceIntervalMinutes,
                            lastJobEmailAt = uiState.activeDeviceInfo.otherDeviceLastJobEmailAt,
                            role = uiState.activeDeviceInfo.role,
                            location = uiState.activeDeviceInfo.location,
                            experience = uiState.activeDeviceInfo.experience,
                            skills = uiState.activeDeviceInfo.skills,
                            company = uiState.activeDeviceInfo.company,
                            datePosted = uiState.activeDeviceInfo.datePosted,
                            remote = uiState.activeDeviceInfo.remote,
                            salaryMin = uiState.activeDeviceInfo.salaryMin,
                            salaryMax = uiState.activeDeviceInfo.salaryMax,
                            lastRunAt = uiState.activeDeviceInfo.lastRunAt,
                            nextApproxRunAt = uiState.activeDeviceInfo.nextApproxRunAt,
                            dateFormat = viewModel.dateFormat,
                            onTakeover = { viewModel.takeoverDevice() },
                        )
                    }

                    if (!hasActive && !isOtherDeviceActive) {
                        SchedulerForm(
                            formState = formState,
                            onFormChange = { transform -> viewModel.updateForm(transform) },
                            isSaving = uiState.isSaving,
                    onSave = {
                                if (uiState.isSaving || pendingSetupSave) {
                                    Log.w("DiscoverySection", "save already in progress, ignoring duplicate click")
                                } else if (!JobAlertReliabilityEngine.isSetupSeen(context)) {
                                    Log.i("DiscoverySection", "setup sheet shown — pendingSetupSave=true")
                                    scope.launch {
                                        val status = JobAlertReliabilityEngine.getReliabilityStatus(
                                            context, uiState.schedulerState
                                        )
                                        setupChecklistItems = status.items
                                        setupReliabilityStatus = status
                                        pendingSetupSave = true
                                        showReliabilitySetup = true
                                    }
                                } else {
                                    Log.i("DiscoverySection", "setup already seen — saving directly")
                                    viewModel.savePreference()
                                }
                            },
                        )
                    }

                    if (hasActive && !isOtherDeviceActive) {
                        val reliabilityStatus = remember(setupRefreshKey, uiState.schedulerState) {
                            JobAlertReliabilityEngine.getReliabilityStatus(context, uiState.schedulerState)
                        }
                        ReliabilityStatusChip(
                            status = reliabilityStatus,
                            onClick = {
                                Log.i("DiscoverySection", "reliability setup opened from status chip")
                                scope.launch {
                                    val status = JobAlertReliabilityEngine.getReliabilityStatus(
                                        context, uiState.schedulerState
                                    )
                                    setupChecklistItems = status.items
                                    setupReliabilityStatus = status
                                    showReliabilitySetup = true
                                }
                            },
                        )
                    }

                    if (showHistory) {
                        HistoryPreview(
                            timeline = uiState.timeline,
                            dateFormat = viewModel.dateFormat,
                            hasActiveScheduler = hasActive,
                        )
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        if (uiState.showStopConfirmDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.hideStopDialog() },
                title = { Text("Stop Job Search?") },
                text = { Text("This will stop the scheduler and delete your saved preferences permanently.") },
                confirmButton = {
                    Button(
                        onClick = { viewModel.stopScheduler() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text("Stop & Delete", fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.hideStopDialog() }) {
                        Text("Cancel")
                    }
                },
            )
        }

        LaunchedEffect(setupRefreshKey) {
            if (showReliabilitySetup) {
                val freshStatus = JobAlertReliabilityEngine.getReliabilityStatus(
                    context, uiState.schedulerState
                )
                setupChecklistItems = freshStatus.items
                setupReliabilityStatus = freshStatus
            }
        }

        if (showReliabilitySetup) {
            ReliabilitySetupSheet(
                items = setupChecklistItems,
                onContinueAndStart = {
                    Log.i("DiscoverySection", "setup continued — marking setupSeen=true")
                    JobAlertReliabilityEngine.markSetupSeen(context)
                    JobAlertReliabilityEngine.acknowledgeLimitedReliability(context)
                    showReliabilitySetup = false
                    if (pendingSetupSave) {
                        pendingSetupSave = false
                        Log.i("DiscoverySection", "scheduler save resumed after setup")
                        viewModel.savePreference()
                    }
                },
                onCancel = {
                    Log.i("DiscoverySection", "setup cancelled — marking setupSeen, clearing pendingSetupSave")
                    JobAlertReliabilityEngine.markSetupSeen(context)
                    showReliabilitySetup = false
                    pendingSetupSave = false
                },
                onOpenSettings = { intent ->
                    try {
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Log.w("DiscoverySection", "Unable to open reliability settings: ${e.message}", e)
                    }
                },
                onRequestPermission = { permission ->
                    notificationPermissionLauncher.launch(permission)
                },
                onConfirmAutostart = {
                    JobAlertReliabilityEngine.confirmAutostart(context)
                    setupRefreshKey++
                    scope.launch {
                        val status = JobAlertReliabilityEngine.getReliabilityStatus(
                            context, uiState.schedulerState
                        )
                        setupChecklistItems = status.items
                        setupReliabilityStatus = status
                    }
                },
                onConfirmFileUpload = {
                    JobAlertReliabilityEngine.confirmFileUpload(context)
                    setupRefreshKey++
                    scope.launch {
                        val status = JobAlertReliabilityEngine.getReliabilityStatus(
                            context, uiState.schedulerState
                        )
                        setupChecklistItems = status.items
                        setupReliabilityStatus = status
                    }
                },
                onRefreshStatus = {
                    scope.launch {
                        val status = JobAlertReliabilityEngine.getReliabilityStatus(
                            context, uiState.schedulerState
                        )
                        setupChecklistItems = status.items
                        setupReliabilityStatus = status
                    }
                },
            )
        }
    }
}

@Composable
private fun LoadingView() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(40.dp), strokeWidth = 3.dp)
            Spacer(Modifier.height(16.dp))
            Text(
                "Loading job search…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActiveSchedulerCard(
    viewModel: JobsViewModel,
    uiState: JobsUiState,
) {
    val pref = uiState.activePreference
    val isThisDevice = uiState.activeDeviceInfo.currentDeviceActive

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, null, Modifier.size(22.dp), tint = FcGreen)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Active Scheduler",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                StatusPill(uiState.schedulerStatus)
            }

            Spacer(Modifier.height(14.dp))

            if (pref != null) {
                DetailRow("Role", pref.role, Icons.Default.Search)
                DetailRow("Location", pref.location, Icons.Default.Info)
                DetailRow("Interval", viewModel.intervalDisplayLabel(pref.intervalMinutes), Icons.Default.AccessTime)
            }

            pref?.lastRunAt?.let { lastRun ->
                DetailRow("Last run", viewModel.formatTs(lastRun), Icons.Default.History)
            }

            uiState.schedulerState?.nextScheduledRunAt?.let { nextRun ->
                DetailRow(
                    "Next run",
                    viewModel.formatTs(nextRun),
                    Icons.Default.Schedule,
                )
            }

            pref?.lastEmailSentAt?.let { lastEmail ->
                DetailRow("Last email", viewModel.formatTs(lastEmail), Icons.Default.CheckCircle)
            }

            DetailRow(
                "Active device",
                if (isThisDevice) "This device" else (uiState.activeDeviceInfo.otherDeviceName ?: "Unknown"),
                Icons.Default.Devices,
            )

            val backendWarning = uiState.schedulerStatus == SchedulerDisplayStatus.BackendUnreachable
            if (backendWarning) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Backend connection is unavailable. Saved settings remain on this device, but scheduled emails will not run until backend activation is restored.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FcAmber,
                )
            }

            if (uiState.schedulerState?.lastError != null) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))
                Text(
                    "Last error: ${uiState.schedulerState.lastError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (pref != null && !pref.setupConfirmationSent) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    if (pref.setupConfirmationError.isNullOrBlank()) {
                        "Setup email has not been sent yet."
                    } else {
                        "Setup email failed: ${pref.setupConfirmationError}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                if (!pref.setupConfirmationErrorCode.isNullOrBlank()) {
                    Text(
                        "Error code: ${pref.setupConfirmationErrorCode}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = { viewModel.retrySetupConfirmationEmail() },
                    enabled = !uiState.isRetryingSetupEmail,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (uiState.isRetryingSetupEmail) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("Retry setup email", fontWeight = FontWeight.SemiBold)
                }
            } else if (pref?.setupConfirmationSent == true) {
                DetailRow(
                    "Setup email",
                    pref.setupConfirmationSentAt?.let { viewModel.formatTs(it) } ?: "Sent",
                    Icons.Default.CheckCircle,
                )
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            TextButton(
                onClick = { viewModel.showStopDialog() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Pause, null, Modifier.size(18.dp), tint = FcRed)
                Spacer(Modifier.width(6.dp))
                Text("Stop Scheduler", color = FcRed, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun OtherDeviceBanner(
    deviceName: String?,
    activatedAt: String?,
    lastSeenAt: String?,
    intervalMinutes: Int?,
    lastJobEmailAt: String?,
    role: String?,
    location: String?,
    experience: String?,
    skills: String?,
    company: String?,
    datePosted: String?,
    remote: Boolean?,
    salaryMin: Long?,
    salaryMax: Long?,
    lastRunAt: String?,
    nextApproxRunAt: String?,
    dateFormat: java.text.SimpleDateFormat,
    onTakeover: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = FcAmberLight),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, null, Modifier.size(24.dp), tint = FcAmber)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Active on another device",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = FcAmber,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Job alerts are managed by \"${deviceName ?: "another device"}\".",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            role?.let { DetailRow("Role", it, Icons.Default.Search) }
            location?.let { DetailRow("Location", it, Icons.Default.Info) }
            experience?.let { DetailRow("Experience", it) }
            skills?.let { DetailRow("Skills/keywords", it) }
            company?.let { DetailRow("Company", it) }
            datePosted?.let { DetailRow("Date posted", it) }
            remote?.let { DetailRow("Remote", if (it) "Remote only" else "Any/on-site") }
            val salary = when {
                salaryMin != null && salaryMax != null -> "$salaryMin - $salaryMax"
                salaryMin != null -> "$salaryMin+"
                salaryMax != null -> "Up to $salaryMax"
                else -> null
            }
            salary?.let { DetailRow("Salary", it) }
            intervalMinutes?.let { DetailRow("Interval", intervalDisplayLabel(it.toLong()), Icons.Default.AccessTime) }
            activatedAt?.let { DetailRow("Activated", com.fundocareer.app.core.jobs.formatIsoDate(it, dateFormat)) }
            lastSeenAt?.let { DetailRow("Last seen", com.fundocareer.app.core.jobs.formatIsoDate(it, dateFormat)) }
            lastRunAt?.let { DetailRow("Last run", com.fundocareer.app.core.jobs.formatIsoDate(it, dateFormat)) }
            lastJobEmailAt?.let { DetailRow("Last email", com.fundocareer.app.core.jobs.formatIsoDate(it, dateFormat)) }
            nextApproxRunAt?.let { DetailRow("Next run approx.", com.fundocareer.app.core.jobs.formatIsoDate(it, dateFormat)) }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onTakeover,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = FcAmber),
            ) {
                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Take over scheduler on this device", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SchedulerForm(
    formState: FormState,
    onFormChange: (transform: (FormState) -> FormState) -> Unit,
    isSaving: Boolean,
    onSave: () -> Unit,
) {
    val intervalOptions = listOf(
        15L to "15 minutes",
        30L to "30 minutes",
        60L to "1 hour",
        120L to "2 hours",
        360L to "6 hours",
        720L to "12 hours",
        1440L to "Daily",
    )

    SectionHeader("Search Criteria", Icons.Default.Search)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            FormTextField("Role *", formState.role, { v -> onFormChange { it.copy(role = v) } }, "e.g. Android Developer")
            Spacer(Modifier.height(10.dp))
            FormTextField("Location *", formState.location, { v -> onFormChange { it.copy(location = v) } }, "e.g. Remote, New York")
            Spacer(Modifier.height(10.dp))
            ExperienceSelector(formState.experience) { v -> onFormChange { it.copy(experience = v) } }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().clickable { onFormChange { it.copy(showAdvanced = !it.showAdvanced) } },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (formState.showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "More filters",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(visible = formState.showAdvanced, enter = expandVertically(), exit = shrinkVertically()) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    RemoteSelector(formState.remote) { v -> onFormChange { it.copy(remote = v) } }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FormTextField("Min salary", formState.salaryMin, { v -> onFormChange { it.copy(salaryMin = v) } },
                            "e.g. 80000", modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number)
                        FormTextField("Max salary", formState.salaryMax, { v -> onFormChange { it.copy(salaryMax = v) } },
                            "e.g. 120000", modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number)
                    }
                    Spacer(Modifier.height(8.dp))
                    FormTextField("Skills (comma-separated)", formState.skills, { v -> onFormChange { it.copy(skills = v) } }, "Kotlin, Compose, Android")
                    Spacer(Modifier.height(8.dp))
                    FormTextField("Company", formState.company, { v -> onFormChange { it.copy(company = v) } }, "Optional")
                    Spacer(Modifier.height(8.dp))
                    DatePostedSelector(formState.datePosted) { v -> onFormChange { it.copy(datePosted = v) } }
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AccessTime, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Search Interval", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "How often should Fundo search for new jobs?",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

    val selectedLabel = intervalOptions.find { it.first == formState.intervalMinutes }?.second
        ?: com.fundocareer.app.core.jobs.intervalDisplayLabel(formState.intervalMinutes)
            var intervalExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = intervalExpanded, onExpandedChange = { intervalExpanded = it }) {
                OutlinedTextField(
                    value = selectedLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Search interval") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(intervalExpanded) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                )
                ExposedDropdownMenu(expanded = intervalExpanded, onDismissRequest = { intervalExpanded = false }) {
                    intervalOptions.forEach { (minutes, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { intervalExpanded = false; onFormChange { it.copy(intervalMinutes = minutes) } },
                            trailingIcon = {
                                if (formState.intervalMinutes == minutes) {
                                    Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    Button(
        onClick = onSave,
        enabled = !isSaving,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        if (isSaving) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
            Spacer(Modifier.width(8.dp))
            Text("Saving…", fontWeight = FontWeight.SemiBold)
        } else {
            Icon(Icons.Default.CheckCircle, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Save & Start Scheduler", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
    }
}

@Composable
private fun SectionHeader(title: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatusPill(status: SchedulerDisplayStatus) {
    val (bg, fg, label) = when (status) {
        SchedulerDisplayStatus.Active -> Triple(FcGreenLight, FcGreen, "Active")
        SchedulerDisplayStatus.Paused -> Triple(FcAmberLight, FcAmber, "Paused")
        SchedulerDisplayStatus.WaitingForNextRun -> Triple(FcGreenLight, FcGreen, "Waiting for next run")
        SchedulerDisplayStatus.BackendUnreachable -> Triple(FcAmberLight, FcAmber, "Backend unreachable")
        SchedulerDisplayStatus.EmailFailed -> Triple(FcRedLight, FcRed, "Email failed")
        SchedulerDisplayStatus.OtherDeviceActive -> Triple(FcAmberLight, FcAmber, "Another device active")
        SchedulerDisplayStatus.Stopped -> Triple(FcRedLight, FcRed, "Stopped")
        SchedulerDisplayStatus.Error -> Triple(FcRedLight, FcRed, "Error")
        SchedulerDisplayStatus.Loading -> Triple(FcSlate200, FcSlate500, "...")
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SchedulerDiagnosticsCard(
    diagnostics: SchedulerDiagnosticsState,
    onPing: () -> Unit,
    onSetupEmail: () -> Unit,
    onRunNow: () -> Unit,
    onZeroJobEmail: () -> Unit,
    onVerifyDevice: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Developer diagnostics", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (diagnostics.busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
            DetailRow("API base URL", diagnostics.apiBaseUrl)
            DetailRow("Logged-in email", diagnostics.loggedInEmail.ifBlank { "Unknown" })
            DetailRow("Backend ping", diagnostics.backendPingStatus)
            DetailRow("Local scheduler", diagnostics.localSchedulerState)
            DetailRow("Backend device", diagnostics.activeDeviceBackendStatus)
            DetailRow("Last worker run", diagnostics.lastWorkerRunTime)
            DetailRow("Last worker result", diagnostics.lastWorkerResult)
            DetailRow("Last email attempt", diagnostics.lastEmailAttempt)
            diagnostics.lastErrorCode?.let { DetailRow("Last error code", it) }
            diagnostics.lastAction?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilledTonalButton(onClick = onPing, enabled = !diagnostics.busy, modifier = Modifier.weight(1f)) {
                        Text("Test ping", fontSize = 12.sp)
                    }
                    FilledTonalButton(onClick = onVerifyDevice, enabled = !diagnostics.busy, modifier = Modifier.weight(1f)) {
                        Text("Verify device", fontSize = 12.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onRunNow, enabled = !diagnostics.busy, modifier = Modifier.weight(1f)) {
                        Text("Run now", fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = onSetupEmail, enabled = !diagnostics.busy, modifier = Modifier.weight(1f)) {
                        Text("Setup email", fontSize = 12.sp)
                    }
                }
                OutlinedButton(onClick = onZeroJobEmail, enabled = !diagnostics.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Send zero-job test email", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, icon: ImageVector? = null) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun FormTextField(
    label: String, value: String, onValueChange: (String) -> Unit,
    placeholder: String, modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExperienceSelector(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf("Entry-Level", "Mid-Level", "Senior", "Lead", "Any")
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.ifBlank { "Any" },
            onValueChange = {},
            readOnly = true,
            label = { Text("Experience *") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = { expanded = false; onSelect(opt) }
                )
            }
        }
    }
}

@Composable
private fun RemoteSelector(selected: Boolean?, onSelect: (Boolean?) -> Unit) {
    Text("Remote", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Any" to null, "Remote" to true, "On-site" to false).forEach { (label, value) ->
            val isSelected = selected == value
            val bg by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                label = "chipBg",
            )
            val textColor by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                label = "chipText",
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(bg)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(label, color = textColor, fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePostedSelector(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf("Any", "Past 24 hours", "Past week", "Past month")
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.ifBlank { "Any" },
            onValueChange = {},
            readOnly = true,
            label = { Text("Date posted") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = { expanded = false; onSelect(opt) }
                )
            }
        }
    }
}

@Composable
private fun HistoryPreview(timeline: List<TimelineEvent>, dateFormat: java.text.SimpleDateFormat, hasActiveScheduler: Boolean) {
    val filtered = timeline.filter { it.type in com.fundocareer.app.core.jobs.knownPositiveTypes }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.History, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (hasActiveScheduler) "Recent Activity" else "Activity History",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (filtered.isEmpty()) {
                Text(
                    "Your job alert activity will appear here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                filtered.take(5).forEach { event -> TimelineRow(event, dateFormat) }
            }
        }
    }
}

@Composable
private fun TimelineRow(event: TimelineEvent, dateFormat: java.text.SimpleDateFormat) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                com.fundocareer.app.core.jobs.formatTs(event.timestamp, dateFormat),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            when (event.type) {
                "scheduler_created" -> {
                    Icon(Icons.Default.CheckCircle, null, Modifier.size(14.dp), tint = FcGreen)
                    Spacer(Modifier.width(3.dp))
                    Text("Active", style = MaterialTheme.typography.labelSmall, color = FcGreen, fontWeight = FontWeight.SemiBold)
                }
                "scheduler_stopped" -> {
                    val amber = Color(0xFFFF8F00)
                    Icon(Icons.Default.Pause, null, Modifier.size(14.dp), tint = amber)
                    Spacer(Modifier.width(3.dp))
                    Text("Stopped", style = MaterialTheme.typography.labelSmall, color = amber, fontWeight = FontWeight.SemiBold)
                }
                "setup_email_sent" -> {
                    val blue = Color(0xFF1976D2)
                    Icon(Icons.Default.Notifications, null, Modifier.size(14.dp), tint = blue)
                    Spacer(Modifier.width(3.dp))
                    Text("Confirmation", style = MaterialTheme.typography.labelSmall, color = blue, fontWeight = FontWeight.SemiBold)
                }
                "job_alert_sent" -> {
                    val meta = event.metadata
                    Text(
                        "${meta?.jobsSentCount ?: 0} job${if (meta?.jobsSentCount != 1) "s" else ""} sent",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.PictureAsPdf, null,
                            Modifier.size(14.dp),
                            tint = if (meta?.pdfAttached == true) FcGreen else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            if (meta?.pdfAttached == true) "PDF report" else "No PDF",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (meta?.pdfAttached == true) FcGreen else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }
        if (event.title.isNotBlank()) {
            Text(
                event.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.Warning, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Something went wrong", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onRetry, shape = MaterialTheme.shapes.small) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Retry")
                }
                OutlinedButton(onClick = onDismiss, shape = MaterialTheme.shapes.small) {
                    Text("Dismiss")
                }
            }
        }
    }
}

@Composable
private fun ResultCard(message: String, onDismiss: () -> Unit) {
    val isSuccess = !message.startsWith("Error")
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (isSuccess) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.errorContainer
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Warning,
                null, Modifier.size(20.dp),
                tint = if (isSuccess) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(8.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, "Dismiss", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SaveFlowStatusCard(steps: List<SaveFlowStep>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Scheduler save status",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            steps.forEach { step ->
                val (icon, tint, label) = when (step.status) {
                    SaveFlowStatus.Pending -> Triple(Icons.Default.Info, MaterialTheme.colorScheme.onSurfaceVariant, "Pending")
                    SaveFlowStatus.Running -> Triple(Icons.Default.Refresh, MaterialTheme.colorScheme.primary, "Running")
                    SaveFlowStatus.Done -> Triple(Icons.Default.CheckCircle, FcGreen, "Done")
                    SaveFlowStatus.Failed -> Triple(Icons.Default.Warning, MaterialTheme.colorScheme.error, "Failed")
                    SaveFlowStatus.Skipped -> Triple(Icons.Default.Info, FcAmber, "Skipped")
                }
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                    Icon(icon, null, Modifier.size(18.dp), tint = tint)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            step.phase.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        val detail = buildString {
                            append(label)
                            if (!step.errorCode.isNullOrBlank()) append(" (${step.errorCode})")
                            if (!step.detail.isNullOrBlank()) append(": ${step.detail}")
                        }
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (step.status == SaveFlowStatus.Failed) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReliabilityStatusChip(
    status: JobAlertReliabilityStatus,
    onClick: () -> Unit,
) {
    val (label, bgColor, fgColor, icon) = when (status.overall) {
        OverallReliability.OPTIMIZED -> listOf("Job alerts optimized", FcGreenLight, FcGreen, Icons.Default.CheckCircle)
        OverallReliability.LIMITED -> listOf("Optimize reliability", FcAmberLight, FcAmber, Icons.Default.Info)
        else -> listOf("Optimize reliability", FcAmberLight, FcAmber, Icons.Default.Info)
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = bgColor as Color),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon as ImageVector, null, Modifier.size(18.dp), tint = fgColor as Color)
            Spacer(Modifier.width(10.dp))
            Text(label as String, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = fgColor as Color)
        }
    }
}

// ================================================================
// Reliability Setup Sheet
// ================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReliabilitySetupSheet(
    items: List<ReliabilityChecklistItem>,
    onContinueAndStart: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: (android.content.Intent) -> Unit,
    onRequestPermission: (String) -> Unit,
    onConfirmAutostart: () -> Unit,
    onConfirmFileUpload: () -> Unit,
    onRefreshStatus: () -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Reliability Setup",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Complete these steps for best on-time job alerts.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(
                    onClick = onRefreshStatus,
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Refresh", fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(16.dp))

            items.forEach { item ->
                ReliabilitySetupItemRow(
                    item = item,
                    onOpenSettings = onOpenSettings,
                    onRequestPermission = onRequestPermission,
                    onConfirmAutostart = if (item.type == ReliabilityItemType.AutostartOem
                        && item.status == ReliabilityItemStatus.NotReady
                    ) onConfirmAutostart else null,
                    onConfirmFileUpload = if (item.type == ReliabilityItemType.FileUploadSupport
                        && item.status == ReliabilityItemStatus.NotReady
                    ) onConfirmFileUpload else null,
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = onContinueAndStart,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text("Continue & Start Scheduler", fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text("Cancel", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ReliabilitySetupItemRow(
    item: ReliabilityChecklistItem,
    onOpenSettings: (android.content.Intent) -> Unit,
    onRequestPermission: (String) -> Unit,
    onConfirmAutostart: (() -> Unit)?,
    onConfirmFileUpload: (() -> Unit)?,
) {
    val isComplete = item.status == ReliabilityItemStatus.Ready
        || item.status == ReliabilityItemStatus.NotApplicable
        || item.status == ReliabilityItemStatus.UserConfirmed

    val required = com.fundocareer.app.core.jobalerts.JobAlertReliabilityActions.isRequiredForBestReliability(item.type)
    val bgColor = if (isComplete) FcGreenLight else MaterialTheme.colorScheme.surfaceVariant
    val borderColor = if (isComplete) FcGreen else MaterialTheme.colorScheme.outline

    val statusBadge: @Composable () -> Unit = {
        if (isComplete) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(FcGreenLight)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text("Ready", fontSize = 10.sp, color = FcGreen, fontWeight = FontWeight.SemiBold)
            }
        } else if (required) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(FcRedLight)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text("Needs action", fontSize = 10.sp, color = FcRed, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Box(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(FcAmberLight)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text("Recommended", fontSize = 10.sp, color = FcAmber, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(borderColor)
        ),
    ) {
        Row(
            Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isComplete) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (isComplete) FcGreen else FcAmber,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(6.dp))
                    statusBadge()
                }
                Spacer(Modifier.height(2.dp))
                Text(item.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onConfirmAutostart != null) {
                FilledTonalButton(
                    onClick = onConfirmAutostart,
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text("Mark as done", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            } else if (onConfirmFileUpload != null) {
                FilledTonalButton(
                    onClick = onConfirmFileUpload,
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text("Mark as done", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            } else if (!isComplete) {
                val ctx = LocalContext.current
                val actions = com.fundocareer.app.core.jobalerts.JobAlertReliabilityActions.getActionsForItem(item, ctx)
                if (actions.isNotEmpty()) {
                    val primaryAction = actions.first()
                    when (primaryAction) {
                        is ReliabilityAction.OpenSettings -> {
                            FilledTonalButton(
                                onClick = { onOpenSettings(primaryAction.intent) },
                                shape = MaterialTheme.shapes.small,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) { Text("Open Settings", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                        }
                        is ReliabilityAction.RequestPermission -> {
                            FilledTonalButton(
                                onClick = { onRequestPermission(primaryAction.permission) },
                                shape = MaterialTheme.shapes.small,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) { Text("Grant", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                        }
                        is ReliabilityAction.Guidance -> {
                            FilledTonalButton(
                                onClick = {
                                    android.widget.Toast.makeText(ctx, primaryAction.message, android.widget.Toast.LENGTH_LONG).show()
                                },
                                shape = MaterialTheme.shapes.small,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) { Text("Learn how", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
            }
        }
    }
}

// ================================================================
// Login Prompt Card (preserved from original)
// ================================================================

@Composable
private fun LoginPromptCard(
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val gso = remember {
        com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder(com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(context.getString(com.fundocareer.app.R.string.default_web_client_id))
            .requestEmail()
            .requestProfile()
            .build()
    }
    val googleSignInClient = remember { com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(context, gso) }
    val tokenStore = remember { com.fundocareer.app.SecureTokenStore(context) }
    val authManager = remember { com.fundocareer.app.AuthManager(context, tokenStore) }

    var isSigningIn by remember { mutableStateOf(false) }

    fun exchangeGoogleTokenSuspend(idToken: String, onResult: (Boolean) -> Unit) {
        authManager.exchangeGoogleToken(idToken, object : com.fundocareer.app.AuthManager.AuthCallback {
            override fun onSuccess(authData: org.json.JSONObject) { onResult(true) }
            override fun onError(error: String) { onResult(false) }
        })
    }

    val googleSignInLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
                scope.launch {
                    isSigningIn = true
                    try {
                        val task = com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(result.data)
                        val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
                        val idToken = account.idToken
                        if (idToken != null) {
                            exchangeGoogleTokenSuspend(idToken) { success ->
                                if (success) {
                                    val email = tokenStore.getUserEmail()
                                    if (!email.isNullOrBlank()) {
                                        com.fundocareer.app.core.jobalerts.JobAlertLifecycle.onLogin(context, email, "")
                                    }
                                    onLoginSuccess()
                                } else {
                                    android.widget.Toast.makeText(context, "Sign-in failed", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        } else {
                            android.widget.Toast.makeText(context, "Sign-in failed: no ID token", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: com.google.android.gms.common.api.ApiException) {
                        if (e.statusCode != 12501) {
                            android.widget.Toast.makeText(context, "Sign-in cancelled", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(context, "Sign-in error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                    } finally {
                        isSigningIn = false
                    }
                }
            }
        }

    val scrollState = rememberScrollState()
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.verticalScroll(scrollState).fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Default.Notifications, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text("Sign in to use job search", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Your job search settings will appear here after login.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = {
                            googleSignInClient.signOut().addOnCompleteListener {
                                val signInIntent = googleSignInClient.signInIntent
                                googleSignInLauncher.launch(signInIntent)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        enabled = !isSigningIn,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        if (isSigningIn) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Text("Sign in with Google", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

// ================================================================
// Helpers
// ================================================================

private fun Context.findActivity(): android.app.Activity? {
    var ctx = this
    while (true) {
        when (ctx) {
            is android.app.Activity -> return ctx
            is android.content.ContextWrapper -> ctx = ctx.baseContext
            else -> return null
        }
    }
}
