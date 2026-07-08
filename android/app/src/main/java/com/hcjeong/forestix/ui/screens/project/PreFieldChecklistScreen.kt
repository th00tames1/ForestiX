// Port of iOS Screens/PreFieldChecklistScreen.swift.
// Phase 7 — pre-field checklist. Reached from the project dashboard
// ("Pre-field checklist" row). Surfaces seven readiness checks and a big
// green/orange/red summary banner so the cruiser knows at a glance whether
// it's safe to drive out.

package com.hcjeong.forestix.ui.screens.project

import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.data.cruise.Project
import com.hcjeong.forestix.ui.screens.ForestixScaffold
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixProminentButton
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.util.UUID
import kotlinx.coroutines.launch

@Composable
fun PreFieldChecklistScreen(nav: NavController, projectId: String) {
    val env = LocalAppEnvironment.current
    var project by remember { mutableStateOf<Project?>(null) }
    LaunchedEffect(projectId) {
        project = env.projectRepository.read(UUID.fromString(projectId))
    }
    val loaded = project
    if (loaded == null) {
        ForestixScaffold(nav, title = "Pre-field check") { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }
    PreFieldChecklistContent(nav, loaded)
}

@Composable
private fun PreFieldChecklistContent(nav: NavController, project: Project) {
    val env = LocalAppEnvironment.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = Forestix.colors
    val type = Forestix.type
    val viewModel = remember(project.id) { PreFieldChecklistViewModel(project) }

    val items by viewModel.items.collectAsState()

    // iOS `.task` — configure + run all checks on appear.
    LaunchedEffect(Unit) {
        viewModel.configure(env)
        viewModel.runAll(context)
    }

    ForestixScaffold(nav, title = "Pre-field check") { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ForestixSpace.md),
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
        ) {
            SummaryBanner(items)

            FormSection(header = "Checks") {
                items.forEach { item ->
                    ChecklistRow(item)
                }
            }

            ForestixProminentButton(
                label = "Re-run checks",
                icon = Icons.Filled.Refresh,        // iOS arrow.clockwise
                modifier = Modifier.fillMaxWidth(),
            ) { scope.launch { viewModel.runAll(context) } }

            Spacer(Modifier.height(ForestixSpace.xl))
        }
    }
}

// MARK: - Summary banner

@Composable
private fun SummaryBanner(items: List<ChecklistItem>) {
    val colors = Forestix.colors
    val type = Forestix.type

    val (title, tint, icon) = bannerDescriptor(items, colors.confidenceOk,
        colors.confidenceWarn, colors.confidenceBad, colors.textSecondary)
    val pass = items.count { it.severity == ChecklistItem.Severity.PASS }
    val subtitle = "$pass of ${items.size} checks passed"

    Row(
        Modifier
            .fillMaxWidth()
            .clip(ForestixRadius.card)
            .background(tint)
            .padding(ForestixSpace.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
    ) {
        Icon(icon, contentDescription = null, tint = Color.White,
            modifier = Modifier.size(32.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = type.title, color = Color.White)
            Text(subtitle, style = type.caption, color = Color.White.copy(alpha = 0.9f))
        }
    }
}

private fun bannerDescriptor(
    items: List<ChecklistItem>,
    ok: Color,
    warn: Color,
    bad: Color,
    gray: Color,
): Triple<String, Color, ImageVector> {
    if (items.isEmpty()) {
        return Triple("Running checks…", gray, Icons.Filled.Schedule)
    }
    if (items.any { it.severity == ChecklistItem.Severity.FAIL }) {
        return Triple("Not ready", bad, Icons.Filled.Cancel)
    }
    if (items.any { it.severity == ChecklistItem.Severity.WARN }) {
        return Triple("Ready with warnings", warn, Icons.Filled.Warning)
    }
    return Triple("Ready for field", ok, Icons.Filled.Verified)
}

// MARK: - Row

@Composable
private fun ChecklistRow(item: ChecklistItem) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
    ) {
        val (icon, tint) = when (item.severity) {
            ChecklistItem.Severity.PASS -> Icons.Filled.CheckCircle to colors.confidenceOk
            ChecklistItem.Severity.WARN -> Icons.Filled.Warning to colors.confidenceWarn
            ChecklistItem.Severity.FAIL -> Icons.Filled.Cancel to colors.confidenceBad
        }
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, style = type.bodyBold, color = colors.textPrimary)
            Text(item.message, style = type.caption, color = colors.textSecondary)
        }
    }
}
