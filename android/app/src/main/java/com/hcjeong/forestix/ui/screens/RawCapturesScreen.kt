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
import com.hcjeong.forestix.common.TruthInput
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

/// The ZIP holds every bundle, including ones the field log has moved on from.
/// Byte-identical to the iOS sibling (RawCapturesScreen.corpusCompletenessNotice).
private const val CORPUS_COMPLETENESS_NOTICE =
    "Export ZIP is the COMPLETE corpus, not the field log: a bundle whose " +
        "reading was deleted or retaken is still in it, and a ground truth " +
        "the field log has since corrected keeps its original value here. " +
        "Nothing is filtered out."

/// Minimum scored captures before the ranking table dares to crown a winner
/// (shared with the replay engine so both platforms use the same floor).
private val MIN_RANK_N = RawCaptureReplay.MIN_RANKING_N

/// ONE source for the sweep footers, so the DBH and height views can't drift
/// apart from each other or from the iOS sibling. Both platforms sort by RAW
/// RMSE; the corrected column is an in-sample calibration fit and is
/// labelled as such wherever it appears.
private object SweepCopy {

    /// "scored + skipped == total" is stated explicitly, so a capture that
    /// silently disappeared from the corpus is visible as an arithmetic gap.
    /// `unparseableOnDisk` is OUTSIDE that arithmetic on purpose: those
    /// bundles never reach any listing, so a balanced equation alone would
    /// still hide them.
    fun corpusFooter(
        scored: Int,
        noTruth: Int,
        unreadable: Int,
        total: Int,
        truthNoun: String,
        unparseableOnDisk: Int = 0,
    ): String {
        val accounting = "$scored scored + $noTruth no truth + $unreadable unreadable = $total captures."
        val onDisk = if (unparseableOnDisk > 0) {
            " Plus $unparseableOnDisk bundle" + (if (unparseableOnDisk == 1) "" else "s") +
                " on disk whose manifest can't be read: NOT in the totals above, " +
                "of any kind — this corpus is incomplete."
        } else {
            ""
        }
        if (scored == 0) {
            return accounting + " No captures have a truth value yet — enter true " +
                truthNoun + "s on individual captures, then return here." + onDisk
        }
        var out = accounting + " Ranked over the $scored capture" +
            (if (scored == 1) "" else "s") + " with a ground-truth $truthNoun."
        if (unreadable > 0) {
            out += " Unreadable captures have a truth but their stored bytes " +
                "won't reconstruct — inspect them before trusting the corpus."
        }
        if (scored < MIN_RANK_N) {
            out += " Fewer than $MIN_RANK_N scored captures: no winner is called yet."
        }
        return out + onDisk
    }

    fun rankingFooter(unit: String): String =
        "bias = mean(estimate − truth); RMSE / MAE in $unit. " +
            "Sorted by RAW RMSE — the corrected column never reorders the table. " +
            "corr, a, b come from an IN-SAMPLE fit truth ≈ a + b·estimate on these " +
            "same captures (a calibration fit, not held-out), so corr is optimistic; " +
            "a biased algorithm can look best only after correction. " +
            "No winner is highlighted until n ≥ $MIN_RANK_N."
}

@Composable
fun RawCapturesScreen(nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = Forestix.colors
    val type = Forestix.type

    var refresh by remember { mutableIntStateOf(0) }
    val summaries = remember(refresh) { RawCaptureStore.list(context) }
    // Bundle DIRECTORIES on disk vs manifests that actually parse. A bundle
    // whose manifest is missing or truncated is skipped by list(), so it used
    // to be absent from the total, from every skip counter and from the sweep
    // footer — whose arithmetic still balanced. It is now counted.
    val inventory = remember(refresh) { RawCaptureStore.inventory(context) }
    val totalBytes = remember(refresh) { RawCaptureStore.totalSizeBytes(context) }
    // Free space next to the corpus size: the recorder refuses to start a
    // bundle below 2 GB, so the cruiser must be able to see the margin
    // BEFORE walking into the stand.
    val freeBytes = remember(refresh) { RawCaptureStore.freeSpaceBytes(context) }
    val lowSpace = freeBytes < RawCaptureStore.MIN_FREE_BYTES

    var detailId by remember { mutableStateOf<String?>(null) }
    var rerunSummary by remember { mutableStateOf<RerunSummary?>(null) }
    var rerunning by remember { mutableStateOf(false) }
    var sweepReport by remember { mutableStateOf<SweepReport?>(null) }
    var sweeping by remember { mutableStateOf(false) }
    var sweepReportHeight by remember { mutableStateOf<SweepReport?>(null) }
    var sweepingHeight by remember { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }

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
                // Directories on disk minus manifests that parse. Anything
                // here is a capture that EXISTS but appears in no list, no
                // total and no sweep — the vanishing class.
                SummaryLine(
                    "Unreadable on disk",
                    "${inventory.unparseable}",
                    warn = inventory.unparseable > 0,
                )
                if (inventory.unparseable > 0) {
                    Text(
                        "${inventory.unparseable} capture " +
                            (if (inventory.unparseable == 1) "directory exists" else "directories exist") +
                            " on disk but the manifest can't be read — those captures are in " +
                            "NO count or comparison below. Export the ZIP before clearing.",
                        style = type.caption, color = colors.confidenceWarn,
                    )
                }
                FormDivider()
                SummaryLine(
                    "Free space",
                    if (lowSpace) "${formatBytes(freeBytes)} — recording blocked"
                    else formatBytes(freeBytes),
                    warn = lowSpace,
                )
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
                    title = if (sweeping) "Comparing…" else "Compare algorithms (DBH)",
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
                    title = if (sweepingHeight) "Comparing…" else "Compare algorithms (height)",
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
                // WHAT THE ZIP IS, said where the ZIP is exported. The research
                // CSV export splits itself against the field log; this one
                // deliberately does not, because a complete capture archive is
                // the thing worth having and a bundle whose reading was retaken
                // is exactly what an accuracy study wants to see. Nothing is
                // left out — so the honest notice is the inverse one: what is
                // IN it that the field log no longer shows. Byte-identical to
                // the iOS sibling.
                Text(
                    CORPUS_COMPLETENESS_NOTICE,
                    style = type.caption, color = colors.textSecondary,
                )
                SettingsActionRowLocal(
                    title = if (exporting) "Exporting…" else "Export ZIP",
                    icon = Icons.Filled.IosShare,
                    // Unreadable bundles are still on disk and still exportable
                    // — the ZIP is the only way to get their bytes off the
                    // phone, so it must not be greyed out when they are all
                    // that is left.
                    enabled = (summaries.isNotEmpty() || inventory.unparseable > 0) && !exporting,
                    tint = colors.primary,
                ) {
                    exporting = true
                    scope.launch {
                        // A failed export used to be a silent no-op — the
                        // corpus looked exportable and simply never left the
                        // phone. Now the reason is shown.
                        val res = RawCaptureStore.exportZip(context)
                        exporting = false
                        val uri = res.uri
                        if (uri != null) shareFile(context, uri, "application/zip")
                        else exportError = res.error ?: "Export failed."
                    }
                }
                FormDivider()
                SettingsActionRowLocal(
                    title = "Clear all",
                    icon = Icons.Filled.Delete,
                    enabled = summaries.isNotEmpty() || inventory.unparseable > 0,
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
                // "No captures yet" would be a lie while unreadable bundles
                // sit on disk — the count above already says how many.
                Text(
                    if (inventory.unparseable > 0) {
                        "No READABLE captures. ${inventory.unparseable} bundle" +
                            (if (inventory.unparseable == 1) " is" else "s are") +
                            " on disk but cannot be parsed — export the ZIP to keep the bytes."
                    } else {
                        "No captures yet. Turn on Settings › Developer › Record raw captures, " +
                            "then measure a tree."
                    },
                    style = type.caption,
                    color = if (inventory.unparseable > 0) colors.confidenceWarn else colors.textSecondary,
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

    exportError?.let { msg ->
        AlertDialog(
            onDismissRequest = { exportError = null },
            title = { Text("Export failed") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { exportError = null }) { Text("OK") } },
        )
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
private fun SummaryLine(label: String, value: String, warn: Boolean = false) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = type.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
        Text(
            value, style = type.body,
            color = if (warn) colors.confidenceWarn else colors.textSecondary,
            fontFamily = FontFamily.Monospace,
        )
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
    // Result of the last Save / Clear — the console never changes a stored
    // truth without saying what it did.
    var truthStatus by remember(id) { mutableStateOf<String?>(null) }
    var clearTruthConfirm by remember(id) { mutableStateOf(false) }

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
                data.frameCount?.let {
                    // A thin burst (dusk / canopy / tracking loss) is recorded
                    // rather than dropped — make the count visible so it can
                    // be weighed in analysis. Height bundles carry 0–2 aim
                    // frames; a chained height that lost the recorder arm
                    // shows up here as 0.
                    if (data.kind == "height") SummaryLine("Aim depth frames", "$it", warn = it < 2)
                    else SummaryLine("Depth frames", "$it", warn = it < 5)
                }
                SummaryLine("Self-check", data.selfStatus ?: "—")
                SummaryLine("Fit tier OK (record time)", if (data.tierOk) "yes" else "no")
                SummaryLine("Operator accepted", if (data.operatorAccepted) "yes" else "no")
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

            // Truth entry — persists into the manifest. An empty or
            // unparseable field can NEVER reach the store: Save leaves the
            // stored value (and the typed text) exactly as they were, so a
            // stored truth is only ever removed by the explicit Clear below.
            //
            // This is the DESK console, not a field-entry surface: it types in
            // the bundle's own metric base, stated in the header and in the
            // placeholder. There is no per-entry unit toggle here (that lives
            // on the scan screens, where the cruiser's active system decides),
            // but the unit is still RECORDED with the value, so every truth in
            // the corpus carries a truth_unit and nothing has to be inferred.
            val consoleQuantity =
                if (data.kind == "height") TruthInput.Quantity.HEIGHT
                else TruthInput.Quantity.DIAMETER
            val consoleUnit = TruthInput.defaultUnit(consoleQuantity, imperial = false)
            FormSection(
                header = "Ground truth (${data.unit})",
                footer = "Persists into the bundle manifest; used as the reference in " +
                    "Re-run comparisons. Blank or unreadable input is never saved — " +
                    "use Clear to remove a stored value.",
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = truthText,
                        // ',' is accepted as the decimal separator and normalised.
                        onValueChange = { truthText = TruthInput.sanitize(it) },
                        placeholder = {
                            Text(TruthInput.promptLabel(consoleQuantity, consoleUnit))
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            // Save guard: an empty or unparseable field NEVER
                            // overwrites a stored truth, and the input is left
                            // alone so nothing typed is lost.
                            //
                            // parsePositiveBASE, not parsePositive: the value
                            // stored is the metric base and `consoleUnit` is
                            // what the field says it is being typed in. They
                            // agree today only because this console is
                            // hardcoded metric — the shared helper exists so
                            // that adding a toggle here cannot leave a number
                            // unconverted under a truth_unit that says it was
                            // converted.
                            val v = TruthInput.parsePositiveBase(truthText, consoleUnit)
                            if (v == null) {
                                truthStatus = if (TruthInput.normalized(truthText).isEmpty()) {
                                    "Nothing entered — stored truth left as it was."
                                } else {
                                    "Not a number — stored truth left as it was."
                                }
                                return@TextButton
                            }
                            scope.launch {
                                // The field is cleared ONLY on SAVED. QUEUED
                                // means the bundle is still being written and
                                // the value is not in any manifest yet — the
                                // console reaches finished bundles, so this is
                                // the belt-and-braces branch.
                                when (RawCaptureStore.setTruth(context, id, v, consoleUnit)) {
                                    RawCaptureStore.TruthWrite.SAVED -> {
                                        truthStatus = "Truth saved."
                                        truthText = ""
                                    }
                                    RawCaptureStore.TruthWrite.QUEUED ->
                                        truthStatus = RawCaptureStrings.TRUTH_PENDING
                                    RawCaptureStore.TruthWrite.FAILED ->
                                        truthStatus = RawCaptureStrings.TRUTH_WRITE_FAILED
                                }
                                reloadKey++
                                onTruthSaved()
                            }
                        },
                    ) { Text("Save") }
                }
                TruthInput.fieldWarning(truthText, consoleQuantity, consoleUnit)?.let { w ->
                    TruthFieldWarning(w)
                }
                // Clearing a stored truth is EXPLICIT.
                if (data.truthValue != null) {
                    SettingsActionRowLocal(
                        title = "Clear stored truth",
                        icon = Icons.Filled.Delete,
                        enabled = true,
                        tint = colors.confidenceBad,
                    ) { clearTruthConfirm = true }
                }
                truthStatus?.let { st ->
                    Text(st, style = type.caption, color = colors.textSecondary)
                }
            }

            SettingsActionRowLocal("Re-run this capture", Icons.Filled.Replay,
                enabled = true, tint = colors.primary) { reloadKey++ }
            SettingsActionRowLocal("Back to list", Icons.AutoMirrored.Filled.KeyboardArrowRight,
                enabled = true, tint = colors.primary) { onBack() }

            Spacer(Modifier.height(ForestixSpace.lg))
        }
    }

    if (clearTruthConfirm) {
        AlertDialog(
            onDismissRequest = { clearTruthConfirm = false },
            title = { Text("Remove the stored ground truth?") },
            text = { Text("The hand-measured value for this capture is deleted from the manifest.") },
            confirmButton = {
                TextButton(onClick = {
                    clearTruthConfirm = false
                    scope.launch {
                        // A clear carries no unit — there is no longer a typed
                        // value for one to describe.
                        truthStatus = when (RawCaptureStore.setTruth(context, id, null, null)) {
                            RawCaptureStore.TruthWrite.SAVED -> "Truth cleared."
                            RawCaptureStore.TruthWrite.QUEUED ->
                                "Clear pending — applied when the capture is written."
                            RawCaptureStore.TruthWrite.FAILED -> "Couldn't clear the truth."
                        }
                        reloadKey++
                        onTruthSaved()
                    }
                }) { Text("Clear", color = colors.confidenceBad) }
            },
            dismissButton = {
                TextButton(onClick = { clearTruthConfirm = false }) { Text("Cancel") }
            },
        )
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
    val frameCount: Int?,
    /// Record-time fit verdict (tier not red) — NOT the operator's decision.
    val tierOk: Boolean,
    /// The cruiser actually pressed Accept on this reading.
    val operatorAccepted: Boolean,
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
        // `result_live.frame_count` is the cross-platform key; older bundles
        // fall back to the length of the stored frames array.
        frameCount = res?.optInt("frame_count", -1)?.takeIf { it >= 0 }
            ?: m.optJSONObject("dbh")?.optJSONArray("frames")?.length(),
        // Bundles written before the split carry only `accepted` (which meant
        // the operator's decision on Android) — fall back to it so old
        // captures still read sensibly.
        tierOk = res?.optBoolean("tier_ok", res.optString("tier") != "red") ?: false,
        operatorAccepted = res?.optBoolean("operator_accepted", res.optBoolean("accepted")) ?: false,
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

/// Skip accounting is EXPLICIT: scored + skippedNoTruth + skippedUnreadable
/// == total, always. The old report folded unreadable bundles into "no
/// truth" — a corpus that silently failed to replay looked like a corpus
/// nobody had measured yet.
private data class SweepReport(
    val ranks: List<AlgoRank>,   // best-first (lowest RAW RMSE; N/A-only algorithms last)
    val rows: List<SweepCaptureRow>,
    val total: Int,
    val scored: Int,             // replayed AND carrying a ground truth
    val skippedNoTruth: Int,     // replayed fine, no truth entered yet
    val skippedUnreadable: Int,  // manifest missing / replay threw / no usable inputs
    val unit: String,            // "cm" (DBH) / "m" (height)
    val kindLabel: String,       // "DBH" / "Height"
    /// Bundle directories whose manifest won't parse at all. These have no
    /// kind, so they are OUTSIDE the per-kind arithmetic above — reported
    /// separately rather than silently missing from it.
    val unparseableOnDisk: Int,
)

/// Build the ranked report from per-candidate (estimate, truth) pairs: raw
/// stats + OLS correction fit, ordered best-first by RAW RMSE, N/A-only
/// last. The CORRECTED RMSE is an in-sample calibration fit — using it as
/// the sort key would let an algorithm win on its own fitted residuals, so
/// it is reported but never ranked on (identical rule on iOS).
private fun buildRanks(
    ids: List<Pair<String, String>>,
    pairsById: Map<String, List<Pair<Double, Double>>>,
): List<AlgoRank> = ids.map { (id, label) ->
    rankOf(id, label, pairsById[id] ?: emptyList())
}.sortedWith(compareByDescending<AlgoRank> { it.n > 0 }.thenBy { it.rmse })

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
    var scored = 0
    var skippedNoTruth = 0
    var skippedUnreadable = 0
    for (s in summaries) {
        val m = RawCaptureStore.manifestOf(context, s.id)
        if (m == null) { skippedUnreadable++; continue }
        val dir = RawCaptureStore.dirOf(context, s.id)
        val sweep = try { RawCaptureReplay.sweepDbh(dir, m) } catch (_: Throwable) { null }
        if (sweep == null) { skippedUnreadable++; continue }
        val truth = sweep.truth
        if (truth == null) { skippedNoTruth++; continue }
        scored++
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
    return SweepReport(
        ranks, rows, summaries.size, scored, skippedNoTruth, skippedUnreadable, "cm", "DBH",
        unparseableOnDisk = RawCaptureStore.inventory(context).unparseable)
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
    var scored = 0
    var skippedNoTruth = 0
    var skippedUnreadable = 0
    for (s in summaries) {
        val m = RawCaptureStore.manifestOf(context, s.id)
        if (m == null) { skippedUnreadable++; continue }
        val sweep = try { RawCaptureReplay.sweepHeight(m) } catch (_: Throwable) { null }
        if (sweep == null) { skippedUnreadable++; continue }
        val truth = sweep.truth
        if (truth == null) { skippedNoTruth++; continue }
        scored++
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
    return SweepReport(
        ranks, rows, summaries.size, scored, skippedNoTruth, skippedUnreadable, "m", "Height",
        unparseableOnDisk = RawCaptureStore.inventory(context).unparseable)
}

@Composable
private fun SweepView(rep: SweepReport) {
    // Best row is crowned only on enough scored captures — below MIN_RANK_N
    // the ordering is noise and a green row would read as a decision.
    val best = rep.ranks.firstOrNull { it.n > 0 }
    val winnerIsCallable = (best?.n ?: 0) >= MIN_RANK_N
    // The lowest CORRECTED RMSE gets its cell tinted (same n floor) so a
    // systematically-biased algorithm that only wins after correction is
    // visible WITHOUT reordering the table.
    val bestCorrectedId =
        if (winnerIsCallable) rep.ranks.filter { it.n > 0 }.minByOrNull { it.rmseCorrected }?.id
        else null
    val truthNoun = if (rep.unit == "m") "height" else "diameter"

    FormSection(
        header = "Corpus",
        footer = SweepCopy.corpusFooter(
            scored = rep.scored,
            noTruth = rep.skippedNoTruth,
            unreadable = rep.skippedUnreadable,
            total = rep.total,
            truthNoun = truthNoun,
            unparseableOnDisk = rep.unparseableOnDisk,
        ),
    ) {
        // scored + skipped == total, always.
        SummaryLine("${rep.kindLabel} captures", "${rep.total}")
        SummaryLine("Scored (with truth)", "${rep.scored}")
        SummaryLine("Skipped (no truth)", "${rep.skippedNoTruth}")
        // Unreadable captures used to be tallied as "no truth" and vanish.
        SummaryLine(
            "Skipped (unreadable)", "${rep.skippedUnreadable}",
            warn = rep.skippedUnreadable > 0,
        )
        // OUTSIDE the arithmetic above: bundles that never reach any listing,
        // so a balanced equation alone would still hide them.
        if (rep.unparseableOnDisk > 0) {
            SummaryLine(
                "Not counted (unreadable on disk)", "${rep.unparseableOnDisk}", warn = true)
        }
        best?.let { b ->
            SummaryLine(
                "Best (lowest raw RMSE)",
                if (winnerIsCallable) b.label else "not called (n < $MIN_RANK_N)",
                warn = !winnerIsCallable,
            )
        }
    }

    FormSection(
        header = "Algorithm ranking (${rep.kindLabel}, vs truth)",
        footer = SweepCopy.rankingFooter(rep.unit),
    ) {
        SweepRankRow("Algorithm", "n", "bias", "RMSE", "corr*", "a", "b",
            header = true, best = false, bestCorrected = false)
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
                best = winnerIsCallable && i == 0 && r.n > 0,
                bestCorrected = r.id == bestCorrectedId,
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
    a: String, b: String, header: Boolean, best: Boolean, bestCorrected: Boolean,
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
            color = if (bestCorrected && !header) colors.confidenceOk else color,
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
