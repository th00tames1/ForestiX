// Raw captures — developer-only replay console. Lists every stored bundle
// (filesDir/raw-captures), re-runs the CURRENT estimators over them
// (RawCaptureReplay — the SAME production entry points the scan screens
// call), and lets the owner enter field ground truth, inspect the
// original-vs-rerun-vs-truth deltas per capture, export the whole set as a
// ZIP, or clear it. This is the offline algorithm-iteration surface: change
// the estimator, come back here, hit "Re-run all", read the error summary.

package com.hcjeong.forestix.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.hcjeong.forestix.sensors.RawCaptureReplay
import com.hcjeong.forestix.sensors.RawCaptureStore
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.screens.project.FormDivider
import com.hcjeong.forestix.ui.screens.project.FormSection
import com.hcjeong.forestix.ui.shareFile
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

private const val REL_TOL = 1e-3

@Composable
fun RawCapturesScreen(nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = Forestix.colors
    val type = Forestix.type

    var refresh by remember { mutableIntStateOf(0) }
    val summaries = remember(refresh) { RawCaptureStore.list(context) }
    val totalBytes = remember(refresh) { RawCaptureStore.totalSizeBytes(context) }

    var detailId by remember { mutableStateOf<String?>(null) }
    var rerunSummary by remember { mutableStateOf<RerunSummary?>(null) }
    var rerunning by remember { mutableStateOf(false) }
    var sweepReport by remember { mutableStateOf<SweepReport?>(null) }
    var sweeping by remember { mutableStateOf(false) }
    var sweepReportHeight by remember { mutableStateOf<SweepReport?>(null) }
    var sweepingHeight by remember { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }

    // Detail overrides the list in-place (single scaffold, iOS push feel).
    val openId = detailId
    if (openId != null) {
        RawCaptureDetail(
            nav = nav,
            id = openId,
            onBack = { detailId = null },
            onTruthSaved = { refresh++ },
        )
        return
    }

    ForestixScaffold(nav, title = "Raw captures") { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(ForestixSpace.md),
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
        ) {
            // Summary + actions.
            FormSection(
                header = "Stored captures",
                footer = "Raw depth + poses recorded on every DBH burst and Height " +
                    "compute while “Record raw captures” is on. Re-run the current " +
                    "estimators here against field ground truth.",
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Captures", style = type.body, color = colors.textPrimary,
                        modifier = Modifier.weight(1f))
                    Text(
                        "${summaries.size} · ${formatBytes(totalBytes)}",
                        style = type.body, color = colors.textSecondary,
                    )
                }
                FormDivider()
                SettingsActionRowLocal(
                    title = if (rerunning) "Re-running…" else "Re-run all (current estimators)",
                    icon = Icons.Filled.Replay,
                    enabled = summaries.isNotEmpty() && !rerunning,
                    tint = colors.primary,
                ) {
                    rerunning = true
                    scope.launch {
                        val s = withContext(Dispatchers.Default) { runReRunAll(context) }
                        rerunSummary = s
                        rerunning = false
                    }
                }
                FormDivider()
                SettingsActionRowLocal(
                    title = if (sweeping) "Comparing…" else "Compare algorithms (DBH sweep)",
                    icon = Icons.Filled.Replay,
                    enabled = summaries.any { it.kind == "dbh" } && !sweeping,
                    tint = colors.primary,
                ) {
                    sweeping = true
                    scope.launch {
                        val s = withContext(Dispatchers.Default) { runSweep(context) }
                        sweepReport = s
                        sweeping = false
                    }
                }
                FormDivider()
                SettingsActionRowLocal(
                    title = if (sweepingHeight) "Comparing…" else "Compare algorithms (height sweep)",
                    icon = Icons.Filled.Replay,
                    enabled = summaries.any { it.kind == "height" } && !sweepingHeight,
                    tint = colors.primary,
                ) {
                    sweepingHeight = true
                    scope.launch {
                        val s = withContext(Dispatchers.Default) { runSweepHeight(context) }
                        sweepReportHeight = s
                        sweepingHeight = false
                    }
                }
                FormDivider()
                SettingsActionRowLocal(
                    title = "Export ZIP",
                    icon = Icons.Filled.IosShare,
                    enabled = summaries.isNotEmpty(),
                    tint = colors.primary,
                ) {
                    scope.launch {
                        RawCaptureStore.exportZipUri(context)?.let {
                            shareFile(context, it, "application/zip")
                        }
                    }
                }
                FormDivider()
                SettingsActionRowLocal(
                    title = "Clear all",
                    icon = Icons.Filled.Delete,
                    enabled = summaries.isNotEmpty(),
                    tint = colors.confidenceBad,
                ) { clearConfirm = true }
            }

            // Re-run-all error summary.
            rerunSummary?.let { s ->
                FormSection(header = "Re-run summary") {
                    SummaryLine("Bundles replayed", "${s.replayed}/${s.total}")
                    SummaryLine("Changed vs live", "${s.changedVsLive}")
                    SummaryLine("With ground truth", "${s.withTruth}")
                    if (s.withTruth > 0) {
                        SummaryLine("Mean signed error", fmt(s.meanSignedError))
                        SummaryLine("Median signed error", fmt(s.medianSignedError))
                    }
                }
            }

            // Algorithm sweep: ranked table + per-capture value-vs-truth.
            sweepReport?.let { rep -> SweepView(rep) }
            sweepReportHeight?.let { rep -> SweepView(rep) }

            // The list.
            if (summaries.isEmpty()) {
                Text(
                    "No captures yet. Turn on Settings › Developer › Record raw captures, " +
                        "then measure a tree.",
                    style = type.caption, color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = ForestixSpace.xs),
                )
            } else {
                FormSection(header = "Captures") {
                    summaries.forEachIndexed { i, s ->
                        if (i > 0) FormDivider()
                        CaptureRow(s) { detailId = s.id }
                    }
                }
            }

            Spacer(Modifier.height(ForestixSpace.lg))
        }
    }

    if (clearConfirm) {
        AlertDialog(
            onDismissRequest = { clearConfirm = false },
            title = { Text("Clear all raw captures?") },
            text = { Text("Deletes every stored bundle (depth, poses, images). This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    clearConfirm = false
                    RawCaptureStore.clearAll(context)
                    rerunSummary = null
                    sweepReport = null
                    sweepReportHeight = null
                    refresh++
                }) { Text("Delete all", color = colors.confidenceBad) }
            },
            dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CaptureRow(s: RawCaptureStore.Summary, onClick: () -> Unit) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(
        Modifier.fillMaxWidth().clickableNoRipple(onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
    ) {
        SelfCheckBadge(s.selfCheckStatus)
        Column(Modifier.weight(1f)) {
            val head = buildString {
                append(s.kind.uppercase(Locale.US))
                s.liveValue?.let { append("  ").append(fmt(it)).append(" ").append(s.unit) }
                s.treeNumber?.let { append("  · Tree ").append(it) }
            }
            Text(head, style = type.body, color = colors.textPrimary)
            val sub = buildString {
                append(shortDate(s.createdAt))
                append(" · ").append(s.mode)
                s.tier?.let { append(" · ").append(it) }
                s.truthValue?.let { append(" · truth ").append(fmt(it)).append(" ").append(s.unit) }
            }
            Text(sub, style = type.caption, color = colors.textSecondary, fontFamily = FontFamily.Monospace)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = colors.textTertiary, modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun SelfCheckBadge(status: String?) {
    val colors = Forestix.colors
    when (status) {
        "pass" -> Icon(Icons.Filled.CheckCircle, contentDescription = "self-check pass",
            tint = colors.confidenceOk, modifier = Modifier.size(18.dp))
        "fail" -> Icon(Icons.Filled.Error, contentDescription = "self-check fail",
            tint = colors.confidenceBad, modifier = Modifier.size(18.dp))
        else -> Icon(Icons.Filled.Error, contentDescription = "self-check unknown",
            tint = colors.textTertiary, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = type.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
        Text(value, style = type.body, color = colors.textSecondary, fontFamily = FontFamily.Monospace)
    }
}

/// Local copy of the Settings action-row idiom (icon + tinted label) so this
/// screen doesn't depend on SettingsScreen's private one.
@Composable
private fun SettingsActionRowLocal(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val actualTint = if (enabled) tint else colors.textTertiary
    Row(
        Modifier.fillMaxWidth().clickableNoRipple { if (enabled) onClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
    ) {
        Icon(icon, contentDescription = null, tint = actualTint, modifier = Modifier.size(18.dp))
        Text(title, style = type.body, color = actualTint)
    }
}

// MARK: - Detail

@Composable
private fun RawCaptureDetail(
    nav: NavController,
    id: String,
    onBack: () -> Unit,
    onTruthSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = Forestix.colors
    val type = Forestix.type

    var reloadKey by remember { mutableIntStateOf(0) }
    val data = remember(id, reloadKey) { loadDetail(context, id) }
    var truthText by remember(id) { mutableStateOf("") }

    ForestixScaffold(nav, title = "Capture detail") { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(ForestixSpace.md),
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
        ) {
            if (data == null) {
                Text("Bundle unavailable.", style = type.body, color = colors.textSecondary)
                SettingsActionRowLocal("Back", Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    enabled = true, tint = colors.primary) { onBack() }
                return@Column
            }

            FormSection(header = "${data.kind.uppercase(Locale.US)} · ${data.unit}") {
                SummaryLine("Recorded", shortDate(data.createdAt))
                SummaryLine("Device", data.device)
                SummaryLine("Mode", data.mode)
                data.treeNumber?.let { SummaryLine("Tree", "$it") }
                SummaryLine("Self-check", data.selfStatus ?: "—")
            }

            FormSection(header = "Original vs re-run vs truth") {
                SummaryLine("Live (recorded)", data.liveValue?.let { "${fmt(it)} ${data.unit}" } ?: "—")
                SummaryLine("Re-run (current)", data.rerunValue?.let { "${fmt(it)} ${data.unit}" } ?: "—")
                if (data.kind == "height") {
                    SummaryLine("Re-run (reposed d_h)",
                        data.rerunReposed?.let { "${fmt(it)} ${data.unit}" } ?: "—")
                }
                data.truthValue?.let { SummaryLine("Truth", "${fmt(it)} ${data.unit}") }
                FormDivider()
                // Deltas.
                if (data.liveValue != null && data.rerunValue != null) {
                    SummaryLine("Δ re-run − live", fmt(data.rerunValue - data.liveValue))
                }
                if (data.truthValue != null && data.rerunValue != null) {
                    SummaryLine("Δ re-run − truth", fmt(data.rerunValue - data.truthValue))
                }
                if (data.truthValue != null && data.liveValue != null) {
                    SummaryLine("Δ live − truth", fmt(data.liveValue - data.truthValue))
                }
                if (data.perFrame.isNotEmpty()) {
                    FormDivider()
                    SummaryLine("Per-frame", data.perFrame.joinToString(" ") { fmt(it) })
                }
            }

            // Truth entry — persists into the manifest.
            FormSection(
                header = "Ground truth (${data.unit})",
                footer = "Tape/clinometer value; stored in the manifest and used by " +
                    "Re-run all's error summary.",
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = truthText,
                        onValueChange = { truthText = it.filter { c -> c.isDigit() || c == '.' } },
                        placeholder = { Text(data.truthValue?.let { fmt(it) } ?: "value") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = truthText.toDoubleOrNull() != null,
                        onClick = {
                            val v = truthText.toDoubleOrNull() ?: return@TextButton
                            scope.launch {
                                RawCaptureStore.setTruth(context, id, v)
                                truthText = ""
                                reloadKey++
                                onTruthSaved()
                            }
                        },
                    ) { Text("Save") }
                }
            }

            SettingsActionRowLocal("Re-run this capture", Icons.Filled.Replay,
                enabled = true, tint = colors.primary) { reloadKey++ }
            SettingsActionRowLocal("Back to list", Icons.AutoMirrored.Filled.KeyboardArrowRight,
                enabled = true, tint = colors.primary) { onBack() }

            Spacer(Modifier.height(ForestixSpace.lg))
        }
    }
}

// MARK: - Data plumbing

private data class DetailData(
    val kind: String,
    val unit: String,
    val createdAt: String,
    val device: String,
    val mode: String,
    val treeNumber: Int?,
    val liveValue: Double?,
    val selfStatus: String?,
    val truthValue: Double?,
    val rerunValue: Double?,
    val rerunReposed: Double?,
    val perFrame: List<Double>,
)

private fun loadDetail(context: android.content.Context, id: String): DetailData? {
    val m = RawCaptureStore.manifestOf(context, id) ?: return null
    val dir = RawCaptureStore.dirOf(context, id)
    val kind = m.optString("kind")
    val unit = if (kind == "height") "m" else "cm"
    val res = m.optJSONObject("result_live")
    val truth = m.optJSONObject("truth")
    val self = m.optJSONObject("replay_selfcheck")
    val ctx = m.optJSONObject("context")

    var rerun: Double? = null
    var reposed: Double? = null
    val perFrame = ArrayList<Double>()
    try {
        if (kind == "dbh") {
            RawCaptureReplay.rerunDbh(dir, m)?.let {
                rerun = it.value
                perFrame.addAll(it.perFrame)
            }
        } else if (kind == "height") {
            RawCaptureReplay.rerunHeight(m)?.let {
                rerun = it.value
                reposed = it.valueReposed
            }
        }
    } catch (_: Throwable) { /* re-run failed — show as — */ }

    return DetailData(
        kind = kind,
        unit = unit,
        createdAt = m.optString("created_at"),
        device = m.optString("device"),
        mode = ctx?.optString("mode") ?: "quick",
        treeNumber = ctx?.optInt("tree_number", -1)?.takeIf { it >= 0 },
        liveValue = res?.optDouble("value")?.takeIf { !it.isNaN() },
        selfStatus = self?.optString("status")?.takeIf { it.isNotEmpty() },
        truthValue = truth?.optDouble("value")?.takeIf { !it.isNaN() },
        rerunValue = rerun,
        rerunReposed = reposed,
        perFrame = perFrame,
    )
}

private data class RerunSummary(
    val total: Int,
    val replayed: Int,
    val changedVsLive: Int,
    val withTruth: Int,
    val meanSignedError: Double,
    val medianSignedError: Double,
)

private fun runReRunAll(context: android.content.Context): RerunSummary {
    val summaries = RawCaptureStore.list(context)
    var replayed = 0
    var changed = 0
    val signedErrors = ArrayList<Double>()
    for (s in summaries) {
        val m = RawCaptureStore.manifestOf(context, s.id) ?: continue
        val dir = RawCaptureStore.dirOf(context, s.id)
        val rerun = try { RawCaptureReplay.rerunValue(dir, m) } catch (_: Throwable) { null } ?: continue
        replayed++
        s.liveValue?.let { live ->
            if (abs(rerun - live) > REL_TOL * maxOf(abs(live), 1e-9)) changed++
        }
        s.truthValue?.let { truth -> signedErrors.add(rerun - truth) }
    }
    val mean = if (signedErrors.isEmpty()) 0.0 else signedErrors.average()
    val median = if (signedErrors.isEmpty()) 0.0 else {
        val sorted = signedErrors.sorted()
        sorted[sorted.size / 2]
    }
    return RerunSummary(summaries.size, replayed, changed, signedErrors.size, mean, median)
}

// MARK: - Algorithm sweep (multi-candidate comparison vs truth: DBH + height)

/// Per-algorithm corpus aggregate over the captures that HAVE a truth value.
/// Carries BOTH the raw error stats and a per-algorithm ordinary-least-squares
/// correction fit `truth ≈ a + b·estimate` (in-sample / calibration, NOT
/// held-out) so a systematically-biased algorithm can be judged on how well it
/// ranks AFTER its bias/slope is baked in.
private data class AlgoRank(
    val id: String,
    val label: String,
    val n: Int,               // captures with truth where this algorithm produced a fit
    val bias: Double,         // mean signed error (estimate − truth), in unit
    val rmse: Double,         // RAW RMSE (in unit)
    val mae: Double,          // in unit
    val fitA: Double?,        // OLS intercept a (null = no fit / degenerate)
    val fitB: Double?,        // OLS slope b
    val rmseCorrected: Double, // in-sample RMSE after applying a + b·estimate (= rmse when no fit)
    val fitted: Boolean,      // true when the OLS correction was actually fit
)

/// OLS fit `truth ≈ a + b·estimate` over one candidate's truth-bearing pairs
/// (estimate, truth), plus raw + in-sample corrected error stats. Guards the
/// degenerate cases (n < 2, or zero variance in the estimate) -> no fit,
/// corrected RMSE = raw RMSE, `fitted = false`.
private fun rankOf(id: String, label: String, pairs: List<Pair<Double, Double>>): AlgoRank {
    val n = pairs.size
    if (n == 0) return AlgoRank(id, label, 0, 0.0, 0.0, 0.0, null, null, 0.0, false)
    val errs = pairs.map { (est, truth) -> est - truth }
    val bias = errs.average()
    val mae = errs.sumOf { abs(it) } / n
    val rmse = sqrt(errs.sumOf { it * it } / n)
    val meanX = pairs.sumOf { it.first } / n   // estimate
    val meanY = pairs.sumOf { it.second } / n  // truth
    val varX = pairs.sumOf { (it.first - meanX) * (it.first - meanX) }
    if (n < 2 || varX <= 1e-12) {
        return AlgoRank(id, label, n, bias, rmse, mae, null, null, rmse, false)
    }
    val cov = pairs.sumOf { (it.first - meanX) * (it.second - meanY) }
    val b = cov / varX
    val a = meanY - b * meanX
    val correctedRmse = sqrt(pairs.sumOf { (est, truth) ->
        val r = truth - (a + b * est); r * r
    } / n)
    return AlgoRank(id, label, n, bias, rmse, mae, a, b, correctedRmse, true)
}

/// One capture's row in the per-capture comparison: truth + every candidate's
/// estimate (null = N/A for this capture) + which algorithm won (closest).
private data class SweepCaptureRow(
    val id: String,
    val treeNumber: Int?,
    val truth: Double,
    val values: Map<String, Double?>,
    val winnerId: String?,
)

private data class SweepReport(
    val ranks: List<AlgoRank>,   // best-first (lowest CORRECTED RMSE; N/A-only algorithms last)
    val rows: List<SweepCaptureRow>,
    val total: Int,
    val withTruth: Int,
    val skipped: Int,
    val unit: String,            // "cm" (DBH) / "m" (height)
    val kindLabel: String,       // "DBH" / "height"
)

/// Build the ranked report from per-candidate (estimate, truth) pairs: raw
/// stats + OLS correction fit, ordered best-first by CORRECTED RMSE (so a
/// biased-but-linear algorithm can win after calibration), N/A-only last.
private fun buildRanks(
    ids: List<Pair<String, String>>,
    pairsById: Map<String, List<Pair<Double, Double>>>,
): List<AlgoRank> = ids.map { (id, label) ->
    rankOf(id, label, pairsById[id] ?: emptyList())
}.sortedWith(compareByDescending<AlgoRank> { it.n > 0 }.thenBy { it.rmseCorrected })

/// Run every candidate DBH geometry over the whole corpus and aggregate the
/// error statistics against the hand-measured truth. Pure/off-main (called on
/// Dispatchers.Default, like Re-run all). Skips captures with no truth and
/// reports how many were skipped.
private fun runSweep(context: android.content.Context): SweepReport {
    val summaries = RawCaptureStore.list(context).filter { it.kind == "dbh" }
    val candidates = RawCaptureReplay.DBH_CANDIDATES
    val pairs = LinkedHashMap<String, MutableList<Pair<Double, Double>>>()
    candidates.forEach { pairs[it.id] = ArrayList() }

    val rows = ArrayList<SweepCaptureRow>()
    var withTruth = 0
    var skipped = 0
    for (s in summaries) {
        val m = RawCaptureStore.manifestOf(context, s.id) ?: continue
        val dir = RawCaptureStore.dirOf(context, s.id)
        val sweep = try { RawCaptureReplay.sweepDbh(dir, m) } catch (_: Throwable) { null } ?: continue
        val truth = sweep.truth
        if (truth == null) { skipped++; continue }
        withTruth++
        var winnerId: String? = null
        var bestAbs = Double.MAX_VALUE
        for (c in candidates) {
            val est = sweep.estimates[c.id] ?: continue
            pairs[c.id]!!.add(est to truth)
            val ae = abs(est - truth)
            if (ae < bestAbs) { bestAbs = ae; winnerId = c.id }
        }
        rows.add(SweepCaptureRow(s.id, s.treeNumber, truth, sweep.estimates, winnerId))
    }

    val ranks = buildRanks(candidates.map { it.id to it.label }, pairs)
    return SweepReport(ranks, rows, summaries.size, withTruth, skipped, "cm", "DBH")
}

/// Height mirror of runSweep: every HEIGHT_CANDIDATE over each stored height
/// capture's tangent scalars, same raw + OLS-corrected stats vs the height
/// truth. Works on ALREADY-STORED data — no new geometry, no re-collection.
private fun runSweepHeight(context: android.content.Context): SweepReport {
    val summaries = RawCaptureStore.list(context).filter { it.kind == "height" }
    val candidates = RawCaptureReplay.HEIGHT_CANDIDATES
    val pairs = LinkedHashMap<String, MutableList<Pair<Double, Double>>>()
    candidates.forEach { pairs[it.id] = ArrayList() }

    val rows = ArrayList<SweepCaptureRow>()
    var withTruth = 0
    var skipped = 0
    for (s in summaries) {
        val m = RawCaptureStore.manifestOf(context, s.id) ?: continue
        val sweep = try { RawCaptureReplay.sweepHeight(m) } catch (_: Throwable) { null } ?: continue
        val truth = sweep.truth
        if (truth == null) { skipped++; continue }
        withTruth++
        var winnerId: String? = null
        var bestAbs = Double.MAX_VALUE
        for (c in candidates) {
            val est = sweep.estimates[c.id] ?: continue
            pairs[c.id]!!.add(est to truth)
            val ae = abs(est - truth)
            if (ae < bestAbs) { bestAbs = ae; winnerId = c.id }
        }
        rows.add(SweepCaptureRow(s.id, s.treeNumber, truth, sweep.estimates, winnerId))
    }

    val ranks = buildRanks(candidates.map { it.id to it.label }, pairs)
    return SweepReport(ranks, rows, summaries.size, withTruth, skipped, "m", "height")
}

@Composable
private fun SweepView(rep: SweepReport) {
    FormSection(
        header = "Algorithm ranking (${rep.kindLabel}, vs truth)",
        footer = "Every candidate re-run over each capture's stored inputs. Ranked " +
            "best-first by CORRECTED RMSE (${rep.unit}). n = captures with truth where " +
            "the algorithm produced a fit; N/A = not applicable to that capture. " +
            "corr = in-sample RMSE after the per-algorithm least-squares correction " +
            "truth ≈ a + b·estimate (a calibration/in-sample fit, NOT held-out — " +
            "a, b are the bias/slope to bake in). “—” under a, b = no fit (n<2 or " +
            "flat estimate); corr then equals raw RMSE.",
    ) {
        SummaryLine("${rep.kindLabel} captures", "${rep.withTruth}/${rep.total} with truth")
        if (rep.skipped > 0) SummaryLine("Skipped (no truth)", "${rep.skipped}")
        FormDivider()
        SweepRankRow("algorithm", "n", "bias", "RMSE", "corr", "a", "b",
            header = true, best = false)
        rep.ranks.forEachIndexed { i, r ->
            SweepRankRow(
                r.label,
                if (r.n == 0) "—" else "${r.n}",
                if (r.n == 0) "—" else fmtSigned(r.bias),
                if (r.n == 0) "—" else fmt(r.rmse),
                if (r.n == 0) "—" else fmt(r.rmseCorrected),
                if (r.fitted && r.fitA != null) fmtSigned(r.fitA) else "—",
                if (r.fitted && r.fitB != null) fmt(r.fitB) else "—",
                header = false,
                best = i == 0 && r.n > 0,
            )
        }
    }
    if (rep.rows.isNotEmpty()) {
        FormSection(header = "Per capture (★ = closest to truth)") {
            rep.rows.forEachIndexed { i, row ->
                if (i > 0) FormDivider()
                SweepCaptureRowView(row, rep.ranks, rep.unit)
            }
        }
    }
}

@Composable
private fun SweepRankRow(
    label: String, n: String, bias: String, rmse: String, corr: String,
    a: String, b: String, header: Boolean, best: Boolean,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val color = when {
        header -> colors.textSecondary
        best -> colors.confidenceOk
        else -> colors.textPrimary
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = type.caption, color = color, maxLines = 1,
            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
        Text(n, style = type.caption, color = color, fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End, modifier = Modifier.width(20.dp))
        Text(bias, style = type.caption, color = color, fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
        Text(rmse, style = type.caption, color = color, fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End, modifier = Modifier.width(40.dp))
        Text(corr, style = type.caption,
            color = if (best && !header) colors.confidenceOk else color,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End, modifier = Modifier.width(40.dp))
        Text(a, style = type.caption, color = color, fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End, modifier = Modifier.width(42.dp))
        Text(b, style = type.caption, color = color, fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End, modifier = Modifier.width(36.dp))
    }
}

@Composable
private fun SweepCaptureRowView(row: SweepCaptureRow, ranks: List<AlgoRank>, unit: String) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(Modifier.fillMaxWidth()) {
        val head = buildString {
            row.treeNumber?.let { append("Tree ").append(it).append(" · ") }
            append("truth ").append(fmt(row.truth)).append(" ").append(unit)
        }
        Text(head, style = type.body, color = colors.textPrimary)
        ranks.forEach { r ->
            val v = row.values[r.id]
            val isWinner = r.id == row.winnerId && v != null
            val tint = if (isWinner) colors.confidenceOk else colors.textSecondary
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (isWinner) "★ " else "  ") + r.label,
                    style = type.caption, color = tint,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f),
                )
                val cell = if (v == null) "N/A"
                else "${fmt(v)} (${fmtSigned(v - row.truth)})"
                Text(cell, style = type.caption, color = tint, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

private fun fmtSigned(v: Double): String = String.format(Locale.US, "%+.2f", v)

private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)

private fun fmt(v: Double?): String = if (v == null) "—" else String.format(Locale.US, "%.2f", v)

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun shortDate(iso: String): String =
    if (iso.length >= 19) iso.substring(0, 19).replace('T', ' ') else iso
