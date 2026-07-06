// Port of iOS Screens/HomeScreen.swift + ViewModels/HomeViewModel.swift.
// Projects list + "New Project" entry point (spec §3.1 REQ-PRJ-001), the
// first-launch onboarding empty state, a low-battery device-health banner,
// and the crash-recovery resume banner for plots left open in the last
// 24 h (iOS CrashRecoveryService.openPlotsWithinLast, inlined here).

package com.hcjeong.forestix.ui.screens.project

import android.content.Context
import android.os.BatteryManager
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Park
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.data.cruise.BreastHeightConvention
import com.hcjeong.forestix.data.cruise.Plot
import com.hcjeong.forestix.data.cruise.Project
import com.hcjeong.forestix.data.cruise.UnitSystem
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.screens.ForestixScaffold
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

// MARK: - Resume candidate (iOS CrashRecoveryService.ResumeCandidate)

/// A plot left open within the last 24 h — surfaced as a resume banner so
/// force-quit / crash doesn't silently strand the cruiser's work.
data class ResumeCandidate(
    val id: UUID,
    val plot: Plot,
    val summary: String,
)

// MARK: - Screen

@Composable
fun HomeScreen(nav: NavController) {
    val env = LocalAppEnvironment.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = Forestix.colors
    val type = Forestix.type

    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var resumeCandidates by remember { mutableStateOf<List<ResumeCandidate>>(emptyList()) }
    // iOS keeps dismissedResumeIds in the HomeViewModel @StateObject, so a
    // dismissal survives pushing into a project and popping back. Plain
    // remember{} is discarded when the Home destination leaves composition,
    // resurrecting dismissed banners — save it across the nav back stack.
    var dismissedResumeIds by rememberSaveable(
        stateSaver = listSaver<Set<UUID>, String>(
            save = { it.map(UUID::toString) },
            restore = { restored -> restored.map(UUID::fromString).toSet() },
        ),
    ) { mutableStateOf<Set<UUID>>(emptySet()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isPresentingNewProject by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(refreshKey) {
        try {
            projects = env.projectRepository.list()
        } catch (e: Exception) {
            errorMessage = "Failed to load projects: ${e.message ?: e}"
        }
        // iOS CrashRecoveryService.openPlotsWithinLast(24 * 3600, ...) —
        // swallow errors; a failed recovery scan shouldn't hide the list.
        resumeCandidates = try {
            val cutoff = System.currentTimeMillis() - 24L * 3600L * 1000L
            val all = mutableListOf<Pair<Long, ResumeCandidate>>()
            for (project in projects) {
                for (plot in env.plotRepository.listByProject(project.id)) {
                    if (plot.closedAt != null) continue
                    // iOS: last activity = max of the plot's startedAt and
                    // every live tree's updatedAt, so a long session tallied
                    // minutes before a crash still surfaces even when the
                    // plot was opened more than 24 h ago.
                    val trees = env.treeRepository.listByPlot(plot.id)
                    val last = (trees.map { it.updatedAt } + plot.startedAt)
                        .maxOrNull() ?: plot.startedAt
                    if (last < cutoff) continue
                    val edited = DateUtils.getRelativeTimeSpanString(last)
                    all += last to ResumeCandidate(
                        id = plot.id,
                        plot = plot,
                        summary = "Plot ${plot.plotNumber} • " +
                            "${trees.size} trees • $edited")
                }
            }
            // Most-recent-first, like CrashRecoveryService's sort.
            all.sortedByDescending { it.first }
                .map { it.second }
                .filter { it.id !in dismissedResumeIds }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun create(name: String, owner: String, units: UnitSystem) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            errorMessage = "Project name is required."
            return
        }
        val now = System.currentTimeMillis()
        // Calibration defaults match spec §7.10 "identity" values (see the
        // iOS HomeViewModel.create comment): depthNoiseMm = 5 mm, bias = 0,
        // DBH correction identity (α = 0, β = 1).
        val project = Project(
            id = UUID.randomUUID(),
            name = trimmed,
            description = "",
            owner = owner.trim(),
            createdAt = now,
            updatedAt = now,
            units = units,
            breastHeightConvention = BreastHeightConvention.UPHILL,
            slopeCorrection = true,
            lidarBiasMm = 0f,
            depthNoiseMm = 5f,
            dbhCorrectionAlpha = 0f,
            dbhCorrectionBeta = 1f,
            vioDriftFraction = 0.02f)
        scope.launch {
            try {
                env.projectRepository.create(project)
                isPresentingNewProject = false
                refreshKey++
            } catch (e: Exception) {
                errorMessage = "Failed to create project: ${e.message ?: e}"
            }
        }
    }

    ForestixScaffold(
        nav, title = "Projects",
        actions = {
            IconButton(onClick = { isPresentingNewProject = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New Project", tint = colors.primary)
            }
        },
    ) { padding ->
        if (projects.isEmpty()) {
            EmptyState(
                modifier = Modifier.padding(padding),
                onCreate = { isPresentingNewProject = true })
        } else {
            Column(
                Modifier
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ForestixSpace.md),
                verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
            ) {
                DeviceHealthBanners(context)
                resumeCandidates.forEach { candidate ->
                    val project = projects.firstOrNull { it.id == candidate.plot.projectId }
                    ResumeBanner(
                        candidate = candidate,
                        project = project,
                        onOpenProject = {
                            project?.let {
                                nav.navigate(ProjectFlowRoutes.dashboard(it.id.toString()))
                            }
                        },
                        onDismiss = {
                            dismissedResumeIds = dismissedResumeIds + candidate.id
                            resumeCandidates =
                                resumeCandidates.filter { it.id != candidate.id }
                        })
                }
                projects.forEach { project ->
                    ProjectRow(
                        project = project,
                        onClick = {
                            nav.navigate(ProjectFlowRoutes.dashboard(project.id.toString()))
                        },
                        onDelete = {
                            scope.launch {
                                try {
                                    env.projectRepository.delete(project.id)
                                    refreshKey++
                                } catch (e: Exception) {
                                    errorMessage =
                                        "Failed to delete project: ${e.message ?: e}"
                                }
                            }
                        })
                }
                Spacer(Modifier.height(ForestixSpace.xl))
            }
        }
    }

    if (isPresentingNewProject) {
        NewProjectSheet(
            onDismiss = { isPresentingNewProject = false },
            onCreate = { name, owner, units -> create(name, owner, units) })
    }

    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("Something went wrong") },
            text = { Text(errorMessage ?: "") },
            confirmButton = {
                TextButton(onClick = { errorMessage = null }) { Text("OK") }
            })
    }
}

// MARK: - Device health banners

/// iOS surfaces a LiDAR-absent "manual-only mode" banner + a low-battery
/// banner (HomeScreen.swift DeviceHealthBanners). The Android analogue of
/// "no LiDAR" is no ARCore support on this device (the same mapping the
/// pre-field checklist uses).
@Composable
private fun DeviceHealthBanners(context: Context) {
    // ARCore availability can transiently report UNKNOWN_CHECKING, so poll
    // it from state (bounded retry, same pattern as ArCameraView) instead
    // of a one-shot remember{}; the banner appears only on a definitive
    // "not supported".
    var arUnsupported by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            var avail = com.google.ar.core.ArCoreApk.getInstance().checkAvailability(context)
            var guard = 0
            while (avail == com.google.ar.core.ArCoreApk.Availability.UNKNOWN_CHECKING && guard < 50) {
                kotlinx.coroutines.delay(200)
                avail = com.google.ar.core.ArCoreApk.getInstance().checkAvailability(context)
                guard++
            }
            arUnsupported = !avail.isSupported &&
                avail != com.google.ar.core.ArCoreApk.Availability.UNKNOWN_CHECKING
        } catch (_: Exception) {
            // Leave the banner hidden when the check itself fails.
        }
    }
    if (arUnsupported) {
        HealthBanner(
            tint = Color(0xFFFF9500),   // iOS .orange
            title = "Manual-only mode",
            body = "This device has no ARCore support. DBH will need a caliper, " +
                "height will need a tape. All project and export features " +
                "remain available.")
    }
    val bm = remember { context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager }
    val level = remember {
        (bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100) / 100f
    }
    val isCharging = remember { bm?.isCharging ?: false }
    if (level <= 0.15f && !isCharging) {
        HealthBanner(
            tint = Forestix.colors.confidenceBad,
            title = "Low battery (${(level * 100).toInt()}%)",
            body = "Scan auto-save has stepped up to every 10 seconds to protect " +
                "in-progress work. Charge before your next plot.")
    }
}

@Composable
private fun HealthBanner(tint: Color, title: String, body: String) {
    val type = Forestix.type
    Surface(color = tint, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = type.bodyBold, color = Color.White)
            Text(body, style = type.caption, color = Color.White.copy(alpha = 0.95f))
        }
    }
}

// MARK: - Resume banner

@Composable
private fun ResumeBanner(
    candidate: ResumeCandidate,
    project: Project?,
    onOpenProject: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    Surface(
        color = colors.primary,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Resume in-progress plot", style = type.bodyBold, color = Color.White)
            Text(
                candidate.summary + (project?.let { " · ${it.name}" } ?: ""),
                style = type.caption, color = Color.White.copy(alpha = 0.95f))
            if (project != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs)) {
                    TextButton(onClick = onOpenProject) {
                        Text("Open project", style = type.caption.copy(fontWeight = FontWeight.Bold), color = Color.White)
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Dismiss", style = type.caption.copy(fontWeight = FontWeight.Bold), color = Color.White)
                    }
                }
            }
        }
    }
}

// MARK: - Row

@Composable
private fun ProjectRow(project: Project, onClick: () -> Unit, onDelete: () -> Unit) {
    val colors = Forestix.colors
    val type = Forestix.type
    var confirmingDelete by remember { mutableStateOf(false) }

    val owner = project.owner.ifEmpty { "No owner" }
    val units = project.units.raw.replaceFirstChar { it.uppercase(Locale.US) }
    val whenLabel = DateUtils.getRelativeTimeSpanString(project.createdAt)

    Surface(
        color = colors.surface,
        shape = ForestixRadius.card,
        modifier = Modifier
            .fillMaxWidth()
            .clip(ForestixRadius.card)
            .border(0.5.dp, colors.divider, ForestixRadius.card)
            .clickableNoRipple(onClick),
    ) {
        Row(
            Modifier.padding(ForestixSpace.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ForestixSpace.md),
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(ForestixRadius.controlDp)).background(colors.primaryMuted),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Folder, contentDescription = null, tint = colors.primary, modifier = Modifier.size(17.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(project.name, style = type.bodyBold, color = colors.textPrimary)
                Text(
                    "$owner · $units · $whenLabel",
                    style = type.caption, color = colors.textSecondary)
            }
            IconButton(onClick = { confirmingDelete = true }) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = colors.textTertiary, modifier = Modifier.size(18.dp))
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(12.dp))
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete “${project.name}”?") },
            text = { Text("All strata, plots and trees in this project will be removed.") },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; onDelete() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
            })
    }
}

// MARK: - Empty state

@Composable
private fun EmptyState(modifier: Modifier, onCreate: () -> Unit) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ForestixSpace.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
    ) {
        Spacer(Modifier.height(ForestixSpace.lg))
        Icon(
            Icons.Filled.Park, contentDescription = null,
            tint = colors.confidenceOk, modifier = Modifier.size(54.dp))
        Text("Welcome to Forestix", style = type.title, color = colors.textPrimary)
        Text(
            "A phone-based timber cruising app. Measure DBH with LiDAR, tree height " +
                "with AR, and compute stand statistics automatically.",
            style = type.body, color = colors.textSecondary, textAlign = TextAlign.Center)

        Surface(
            color = colors.primaryMuted,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("How to get started", style = type.bodyBold, color = colors.textPrimary)
                OnboardingStep(1, "Create a project — name, units, cruiser")
                OnboardingStep(2, "Draw strata on the map — tap the corners of each cutting block")
                OnboardingStep(3, "Design the cruise — plot size and sampling method")
                OnboardingStep(4, "Measure in the field — visit plots, add trees, auto-summarise")
            }
        }

        Button(onClick = onCreate, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(ForestixSpace.xs))
            Text("Create your first project", style = type.bodyBold)
        }

        Text(
            "All data stays on this device — nothing is sent to any server.",
            style = type.caption, color = colors.textTertiary,
            modifier = Modifier.padding(bottom = ForestixSpace.lg))
    }
}

@Composable
private fun OnboardingStep(n: Int, text: String) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(colors.primary.copy(alpha = 0.20f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("$n", style = type.caption.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp), color = colors.primary)
        }
        Text(text, style = type.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
    }
}

// MARK: - New Project sheet

@Composable
private fun NewProjectSheet(
    onDismiss: () -> Unit,
    onCreate: (name: String, owner: String, units: UnitSystem) -> Unit,
) {
    val type = Forestix.type
    var name by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("") }
    var units by remember { mutableStateOf(UnitSystem.IMPERIAL) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Project name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = owner, onValueChange = { owner = it },
                    label = { Text("Owner / cruiser") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = units == UnitSystem.IMPERIAL,
                        onClick = { units = UnitSystem.IMPERIAL },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("Imperial (ft, in, acres)", style = type.caption) }
                    SegmentedButton(
                        selected = units == UnitSystem.METRIC,
                        onClick = { units = UnitSystem.METRIC },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("Metric (m, cm, ha)", style = type.caption) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.trim().isNotEmpty(),
                onClick = { onCreate(name, owner, units) },
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        })
}
