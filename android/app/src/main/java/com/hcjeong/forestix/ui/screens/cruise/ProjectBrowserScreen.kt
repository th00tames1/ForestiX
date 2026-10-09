// Browse projects — the cruise entry point's second door.
//
// Port of iOS Screens/ProjectBrowserScreen.swift; every user-visible string
// here is byte-identical with that file.
//
// FIELD REPORT (item 4) — "there is no way to delete a cruise project, or
// to see the ones that exist". The project sheet does list projects, but it
// lists them as radio rows to switch between: no dates, no way out of a
// project the cruiser no longer wants, and the list shares the sheet with
// export, setup and the footer tools. This screen is the list itself —
// every project, what is in it, when it was last worked, and the only place
// in the app that can delete one.
//
// WHAT DELETING REMOVES. Neither store cascades on its own: the Room
// entities carry no foreign keys, and the iOS Core Data model has no
// relationships either. Deleting the project row alone would leave its
// plots, trees, planned plots, strata, design and H-D fits on disk forever
// — invisible, unexportable, and counted by nothing. So the cascade is
// explicit and it is a HARD delete, the rule the plot peek's "Delete plot"
// already follows (trees hard-deleted with their photos, then the plot
// row). Tree rows are the one soft-deletable thing in cruise, and
// `includeDeleted = true` sweeps those too — a soft-deleted tree whose plot
// is gone can never be restored, so leaving it is just litter.
//
// Order matters: children first, project row last. A storage error part way
// through then leaves a project that still owns whatever survived, rather
// than orphans with no project to belong to.

package com.hcjeong.forestix.ui.screens.cruise

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hcjeong.forestix.AppEnvironment
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.data.cruise.Project
import com.hcjeong.forestix.ui.MeasurePhotoStore
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.screens.ForestixScaffold
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/// One project as this screen shows it: the row plus the counts the
/// confirmation has to quote. Counts come from the same repository calls
/// the project sheet's row already makes — no new query shapes.
private data class ProjectBrowserRow(
    val project: Project,
    val plotCount: Int,
    val closedPlotCount: Int,
    /// LIVE trees (soft-deleted ones excluded), which is what every other
    /// cruise surface counts.
    val treeCount: Int,
    /// Newest plot start / close or tree edit in the project — the "last
    /// activity" line. null when nothing has been measured yet; the row
    /// then says so rather than repeating the creation date under a second
    /// name.
    val lastActivity: Long?,
)

@Composable
fun ProjectBrowserScreen(nav: NavController) {
    val env = LocalAppEnvironment.current
    val settings by env.settings.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = Forestix.colors
    val type = Forestix.type

    var rows by remember { mutableStateOf<List<ProjectBrowserRow>>(emptyList()) }
    var reloadTick by remember { mutableIntStateOf(0) }
    /// The row whose delete confirmation is up. Held (rather than deleting
    /// on tap) because a cruise project is days of fieldwork.
    var deleteCandidate by remember { mutableStateOf<ProjectBrowserRow?>(null) }
    /// Set when the cascade could not finish. The screen says so in plain
    /// language instead of pretending the project is gone.
    var deleteFailure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reloadTick) { rows = loadProjectRows(env) }

    val activeId = activeProjectId(rows, settings.cruiseProjectId)

    ForestixScaffold(nav, title = "Projects") { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(horizontal = ForestixSpace.md)
                .verticalScroll(rememberScrollState()),
        ) {
            if (rows.isEmpty()) {
                Column(Modifier.padding(vertical = ForestixSpace.md)) {
                    Text(
                        "No projects yet",
                        style = type.bodyBold.copy(fontWeight = FontWeight.Bold),
                        color = colors.textPrimary,
                    )
                    Text(
                        "Start one with New project on the map's project sheet.",
                        style = type.caption,
                        color = colors.textTertiary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            rows.forEach { row ->
                ProjectBrowserRowView(
                    row = row,
                    isActive = row.project.id == activeId,
                    // Tapping the row IS "use this project" — the same act
                    // as the project sheet's radio row, and it drops
                    // straight back to the map so the cruiser is one tap
                    // from tallying. The map's cruise effect re-runs on
                    // return, so pins, strip and AR ring reconcile there.
                    onOpen = {
                        env.settings.setCruiseProjectId(row.project.id.toString())
                        // Same pair as the project sheet's switcher row: the
                        // active-plot pointer belongs to the project being
                        // left, and carrying it over would aim the tally at
                        // a plot in another project.
                        env.settings.setCruisePlotId(null)
                        nav.popBackStack()
                    },
                    // The KEPT StandSummaryScreen, scoped to the browsed
                    // project. There is no second summary screen.
                    onStandSummary = {
                        nav.navigate(CruiseRoutes.standSummary(row.project.id.toString()))
                    },
                    onDelete = { deleteCandidate = row },
                )
                HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
            }
            Spacer(Modifier.size(ForestixSpace.lg))
        }
    }

    val candidate = deleteCandidate
    if (candidate != null) {
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Delete this project?") },
            text = {
                Text(deleteMessage(candidate, candidate.project.id == activeId))
            },
            confirmButton = {
                TextButton(onClick = {
                    val wasActive = candidate.project.id == activeId
                    deleteCandidate = null
                    scope.launch {
                        val failure = runCatching {
                            deleteProjectCascade(env, context, candidate.project.id)
                        }.exceptionOrNull()
                        if (failure != null) {
                            deleteFailure = deleteFailureMessage(
                                candidate.project.name,
                                failure.message ?: failure.toString())
                            reloadTick++
                            return@launch
                        }
                        // A pointer at a project that no longer exists would
                        // leave the map strip, the export button and the
                        // tally loop aimed at nothing. Name the successor
                        // here rather than leaning on the fallback, so this
                        // screen and the map agree on which project is next
                        // — and so Android, whose fallback is "newest
                        // created", lands on the same project as iOS's
                        // "most recently updated".
                        if (wasActive) {
                            val next = env.projectRepository.list()
                                .maxByOrNull { it.updatedAt }
                            env.settings.setCruiseProjectId(next?.id?.toString())
                        }
                        reloadTick++
                    }
                }) { Text("Delete project") }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text("Cancel") }
            },
        )
    }

    val failure = deleteFailure
    if (failure != null) {
        AlertDialog(
            onDismissRequest = { deleteFailure = null },
            title = { Text("Project not deleted") },
            text = { Text(failure) },
            confirmButton = {
                TextButton(onClick = { deleteFailure = null }) { Text("OK") }
            },
        )
    }
}

@Composable
private fun ProjectBrowserRowView(
    row: ProjectBrowserRow,
    isActive: Boolean,
    onOpen: () -> Unit,
    onStandSummary: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(
        Modifier
            .fillMaxWidth()
            .clickableNoRipple(onOpen)
            .padding(vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.project.name,
                style = type.bodyBold.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isActive) {
                Text(
                    "IN USE",
                    style = type.caption.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                    ),
                    color = colors.accent,
                    modifier = Modifier
                        .clip(ForestixRadius.chip)
                        .background(colors.accent.copy(alpha = 0.12f))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            countsLine(row),
            style = type.dataSmall.copy(fontSize = 11.sp),
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            datesLine(row),
            style = type.dataSmall.copy(fontSize = 11.sp),
            color = colors.textTertiary,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(ForestixSpace.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Stand summary",
                style = type.bodyBold.copy(fontSize = 13.sp),
                color = colors.primary,
                modifier = Modifier
                    .heightIn(min = 32.dp)
                    .clickableNoRipple(onStandSummary),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "Delete",
                style = type.bodyBold.copy(fontSize = 13.sp),
                color = colors.confidenceBad,
                modifier = Modifier
                    .heightIn(min = 32.dp)
                    .clickableNoRipple(onDelete),
            )
        }
    }
}

// MARK: - Reading

private suspend fun loadProjectRows(env: AppEnvironment): List<ProjectBrowserRow> =
    env.projectRepository.list().map { project ->
        val plots = env.plotRepository.listByProject(project.id)
        var trees = 0
        var activity: Long? = null
        for (plot in plots) {
            val live = env.treeRepository.listByPlot(plot.id)
            trees += live.size
            activity = newer(activity, plot.startedAt)
            activity = newer(activity, plot.closedAt)
            for (tree in live) activity = newer(activity, tree.updatedAt)
        }
        ProjectBrowserRow(
            project = project,
            plotCount = plots.size,
            closedPlotCount = plots.count { it.closedAt != null },
            treeCount = trees,
            lastActivity = activity,
        )
    }

private fun newer(current: Long?, candidate: Long?): Long? {
    if (candidate == null) return current
    if (current == null) return candidate
    return if (candidate > current) candidate else current
}

/// The project cruise is actually scoped to — the persisted pick when it
/// still exists, else the most recently updated project. A browser that
/// marked a different row "IN USE" than the map's strip would be lying
/// about which project the next tree lands in.
private fun activeProjectId(rows: List<ProjectBrowserRow>, picked: String?): UUID? {
    val id = picked?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    if (id != null && rows.any { it.project.id == id }) return id
    return rows.maxByOrNull { it.project.updatedAt }?.project?.id
}

// MARK: - Cascade

/// The whole-project hard delete, in one place because it is the only
/// operation in cruise that can remove days of work and it must remove ALL
/// of it. See the file header for why nothing cascades on its own.
private suspend fun deleteProjectCascade(
    env: AppEnvironment,
    context: Context,
    projectId: UUID,
) {
    for (plot in env.plotRepository.listByProject(projectId)) {
        // includeDeleted: the soft-deleted rows are about to lose the plot
        // that gives them meaning, exactly as "Delete plot" does.
        for (tree in env.treeRepository.listByPlot(plot.id, includeDeleted = true)) {
            // Plain Context, not `as? Activity`: the cast is null wherever
            // the caller sits inside a dialog window, and a null there would
            // drop the photo deletion without a word.
            tree.photoPath?.let { name -> MeasurePhotoStore.delete(context, name) }
            env.treeRepository.hardDelete(tree.id)
        }
        env.plotRepository.delete(plot.id)
    }
    // Project-scoped rows the plot cascade never touches. Each would
    // otherwise outlive its project with no screen able to reach it.
    for (planned in env.plannedPlotRepository.listByProject(projectId)) {
        env.plannedPlotRepository.delete(planned.id)
    }
    for (stratum in env.stratumRepository.listByProject(projectId)) {
        env.stratumRepository.delete(stratum.id)
    }
    for (design in env.cruiseDesignRepository.forProject(projectId)) {
        env.cruiseDesignRepository.delete(design.id)
    }
    for (fit in env.heightDiameterFitRepository.listByProject(projectId)) {
        env.heightDiameterFitRepository.delete(fit.id)
    }
    env.projectRepository.delete(projectId)
}

// MARK: - Strings (byte-identical with the iOS sibling)

private fun countsLine(row: ProjectBrowserRow): String =
    "Plots ${row.closedPlotCount}/${row.plotCount} · Trees ${row.treeCount}"

private fun datesLine(row: ProjectBrowserRow): String {
    val made = "Made ${browserDate(row.project.createdAt)}"
    val activity = row.lastActivity ?: return "$made · No measurements yet"
    return "$made · Last activity ${browserDate(activity)}"
}

/// Names the project AND the size of the loss — a cruise project is days of
/// fieldwork, and "Delete?" alone does not tell the cruiser what they are
/// about to spend.
private fun deleteMessage(row: ProjectBrowserRow, isActive: Boolean): String {
    var text = "${row.project.name} holds " +
        "${count(row.plotCount, "plot", "plots")} and " +
        "${count(row.treeCount, "tree", "trees")}. " +
        "Deleting removes the project and every plot, tree and photo in it. " +
        "This can't be undone."
    if (isActive) {
        text += " Cruise is using this project now — the map will switch to " +
            "your most recently updated project."
    }
    return text
}

/// The cascade removes children before the project row, so a storage error
/// part way through leaves a REAL project holding less than it did. Saying
/// "deleted" here would be a lie, and saying nothing would be worse.
private fun deleteFailureMessage(name: String, reason: String): String =
    "$name could not be fully deleted: $reason. Some of it may already be " +
        "gone — open the project to see what is left, then try again."

private fun count(n: Int, one: String, many: String): String =
    "$n ${if (n == 1) one else many}"

/// Fixed POSIX-equivalent format so the row reads the same on every device
/// — the same choice the project sheet's "d MMM" row makes, widened with
/// the year because a browse list spans seasons.
private fun browserDate(epochMillis: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(epochMillis))
