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
import androidx.compose.material.icons.filled.ErrorOutline
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import android.content.Context
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.fundocareer.app.core.jobalerts.JobAlertReliabilityEngine
import com.fundocareer.app.core.jobalerts.SchedulerDisplayStatus
import com.fundocareer.app.core.jobalerts.ReliabilityChecklistItem
import com.fundocareer.app.core.jobalerts.ReliabilityItemStatus
import com.fundocareer.app.core.jobalerts.ui.theme.FcAmber
import com.fundocareer.app.core.jobalerts.ui.theme.FcAmberLight
import com.fundocareer.app.core.jobalerts.ui.theme.FcGreen
import com.fundocareer.app.core.jobalerts.ui.theme.FcGreenLight
import com.fundocareer.app.core.jobalerts.ui.theme.FcRed
import com.fundocareer.app.core.jobalerts.ui.theme.FcRedLight
import com.fundocareer.app.core.jobalerts.ui.theme.FcSlate200
import com.fundocareer.app.core.jobalerts.ui.theme.FcSlate500
import com.fundocareer.app.core.jobalerts.ReliabilitySetupScreen
import com.fundocareer.app.core.jobalerts.provider.JobAlertApiClient.TimelineEvent
import com.fundocareer.app.core.logging.FcLog
import com.fundocareer.app.core.ui.composables.DetailRow
import com.fundocareer.app.core.ui.composables.ErrorCard
import com.fundocareer.app.core.ui.composables.FormTextField
import com.fundocareer.app.core.ui.composables.LoadingIndicator
import com.fundocareer.app.core.ui.composables.LoginPromptCard
import com.fundocareer.app.core.ui.composables.ResultCard
import com.fundocareer.app.core.ui.composables.SectionHeader
import com.fundocareer.app.core.ui.composables.SetupEmailErrorBanner
import com.fundocareer.app.core.ui.composables.StatusChip
import kotlinx.coroutines.launch

@Composable
fun DiscoverySection(viewModel: JobsViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val formState by viewModel.formState.collectAsState()
    val context = LocalContext.current

    val hasActive = uiState.activePreference != null && uiState.schedulerStatus != SchedulerDisplayStatus.Stopped

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            uiState.isLoading -> LoadingIndicator()
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

                    if (uiState.activePreference != null && !uiState.activePreference!!.setupConfirmationSent && !uiState.activePreference!!.setupConfirmationError.isNullOrBlank()) {
                        SetupEmailErrorBanner(
                            errorMessage = uiState.activePreference!!.setupConfirmationError ?: "Unknown error",
                            errorCode = uiState.activePreference!!.setupConfirmationErrorCode,
                            isRetrying = uiState.isRetryingSetupEmail,
                            onRetry = { viewModel.retrySetupConfirmationEmail() },
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
                                if (uiState.isSaving) {
                                    FcLog.w(FcLog.TAG_APP, "Save already in progress, ignoring duplicate click")
                                } else {
                                    FcLog.i(FcLog.TAG_APP, "Starting save with reliability check")
                                    viewModel.startSaveWithReliabilityCheck()
                                }
                            },
                        )
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        if (uiState.showTakeoverConfirmDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.hideTakeoverDialog() },
                title = { Text("Use this phone for job alerts?") },
                text = {
                    Text(
                        "Another device is currently managing job alerts for your search criteria. " +
                        "Do you want to take over with this phone?"
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { viewModel.takeoverDevice() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) {
                        Icon(Icons.Default.Devices, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Use this phone", fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.hideTakeoverDialog() }) {
                        Text("Keep current device")
                    }
                },
            )
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

        if (uiState.showReliabilitySetup) {
            val status = uiState.reliabilitySetupStatus
            if (status != null) {
                ReliabilitySetupScreen(
                    initialStatus = status,
                    onRefreshStatus = { viewModel.refreshReliabilitySetup() },
                    onDismiss = { viewModel.hideReliabilitySetup() },
                    onContinue = { viewModel.onReliabilitySetupContinue() },
                    onOpenSettings = { intent ->
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            FcLog.w(FcLog.TAG_PERMISSION, "Unable to open reliability settings", mapOf(
                                        "error" to e.message,
                                    ))
                        }
                    },
                    onRequestPermission = { _ ->
                        // POST_NOTIFICATIONS is handled internally by ReliabilitySetupScreen
                    },
                    onConfirmAutostart = { viewModel.confirmReliabilityAutostart() },
                    onConfirmBattery = { viewModel.confirmReliabilityBattery() },
                    onConfirmBackground = { viewModel.confirmReliabilityBackground() },
                )
            }
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
                StatusChip(statusLabel(uiState.schedulerStatus), statusColor(uiState.schedulerStatus))
            }

            Spacer(Modifier.height(14.dp))

            if (pref != null) {
                DetailRow("Role", pref.role, Icons.Default.Search)
                DetailRow("Location", pref.location, Icons.Default.Info)
                DetailRow("Interval", intervalDisplayLabel(pref.intervalMinutes), Icons.Default.AccessTime)
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

            if (pref?.setupConfirmationSent == true) {
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

            TextButton(
                onClick = {
                    FcLog.i(FcLog.TAG_RELIABILITY, "Improve reliability opened from scheduler card")
                    viewModel.openReliabilitySetup()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text("Improve reliability", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
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



// ================================================================
// Helpers
// ================================================================

private fun statusLabel(status: SchedulerDisplayStatus): String = when (status) {
    SchedulerDisplayStatus.Active -> "Active"
    SchedulerDisplayStatus.Paused -> "Paused"
    SchedulerDisplayStatus.WaitingForNextRun -> "Waiting for next run"
    SchedulerDisplayStatus.BackendUnreachable -> "Backend unreachable"
    SchedulerDisplayStatus.EmailFailed -> "Email failed"
    SchedulerDisplayStatus.OtherDeviceActive -> "Another device active"
    SchedulerDisplayStatus.Stopped -> "Stopped"
    SchedulerDisplayStatus.Error -> "Error"
    SchedulerDisplayStatus.Loading -> "..."
}

private fun statusColor(status: SchedulerDisplayStatus): androidx.compose.ui.graphics.Color = when (status) {
    SchedulerDisplayStatus.Active, SchedulerDisplayStatus.WaitingForNextRun -> FcGreen
    SchedulerDisplayStatus.Paused, SchedulerDisplayStatus.BackendUnreachable, SchedulerDisplayStatus.OtherDeviceActive -> FcAmber
    SchedulerDisplayStatus.EmailFailed, SchedulerDisplayStatus.Stopped, SchedulerDisplayStatus.Error -> FcRed
    SchedulerDisplayStatus.Loading -> FcSlate500
}

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
