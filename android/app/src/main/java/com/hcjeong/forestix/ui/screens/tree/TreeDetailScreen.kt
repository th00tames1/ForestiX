// Port of iOS Screens/TreeDetailScreen.swift.
// Phase 5 §5.3 TreeDetailScreen. REQ-TAL-006.
//
// Single-tree inspector.
//
// ONE FORM FOR BOTH KINDS OF TREE. A cruise tree opens here; a quick
// measurement opens the field log's record sheet. They are the same form: the
// same sections in the same order, the same row labels, the same words.
//   1. DETAIL       — plot, species, the name that species resolves to, time,
//                     the tree's own name, status, damage, notes. Plot,
//                     species and time are TAPPABLE and write what they pick.
//   2. MEASUREMENT  — diameter and height, each tappable to set in place,
//                     each carrying its ± band as a sub-line of its own row,
//                     with the confidence beneath it. The band is that
//                     measurement's own precision, not a row of its own.
//   3. COMPUTED     — basal area and volume, from the measurements above —
//                     from what is IN THE FORM, not from what was last saved.
//   4. RECORDED     — position, where that position came from, and how the
//                     measurement was captured. All three, always, on both
//                     forms: a cruise tree carries no position source, so
//                     that row says "—" rather than being left out.
// A field a kind of tree does not carry shows an em dash. It never
// disappears: a row that is missing on one of the two screens is how the two
// start reading as different screens again.
//
// The shared pieces live at the foot of this file and are `internal`, so the
// field log's sheet draws the same rows from the same definitions rather than
// from a second copy that drifts.
//
// FIELD REPORT F8 — what the measurement group dropped, and why:
//   • The "Method" readout is gone. `lidarDepth` / `vioWalkoffTangent` told a
//     cruiser nothing; it is still on the record and in exports.
//   • Confidence stays, and the tier chip is TAPPABLE — it opens a
//     plain-language explanation of what the tiers mean and what actually
//     drives them, so the criteria are learnable in the field instead of
//     being folklore.
//   • "Irregular" is gone (nobody set it; the flag is untouched on the model).
//   • "Placement" (bearing / distance from plot centre) is gone. Both were
//     hand-typed number fields, and a cruiser standing at a tree has no way
//     to type a meaningful bearing. The FIELDS SURVIVE on `Tree` and in every
//     export — the cruise chain still auto-computes `distanceFromCenterM`
//     from the capture fix. Only the manual UI went.
//
// FIELD REPORT F6 — the read-only raw-metadata block is developer-mode only.
// A normal cruiser never sees `dbhCoverageDeg` / `heightAlphaTopDeg`.

package com.hcjeong.forestix.ui.screens.tree

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.common.CoordinateInput
import com.hcjeong.forestix.common.MeasuredTimeInput
import com.hcjeong.forestix.common.MeasurementFormatter
import com.hcjeong.forestix.common.RegionalSpecies
import com.hcjeong.forestix.common.TruthInput
import com.hcjeong.forestix.common.UnitSystem
import com.hcjeong.forestix.common.Units
import com.hcjeong.forestix.data.cruise.TreeStatus
import com.hcjeong.forestix.data.cruise.displayTitle
import com.hcjeong.forestix.inventory.VolumeEquationFactory
import com.hcjeong.forestix.inventory.basalAreaM2
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.screens.FieldLogCruiseData
import com.hcjeong.forestix.ui.screens.FieldLogWords
import com.hcjeong.forestix.ui.screens.ForestixScaffold
import com.hcjeong.forestix.ui.screens.MeasuredTimeEditorDialog
import com.hcjeong.forestix.ui.screens.SpeciesPickerField
import com.hcjeong.forestix.ui.screens.TreeMoveDestination
import com.hcjeong.forestix.ui.screens.TreeMovePicker
import com.hcjeong.forestix.ui.screens.TreeMovePlan
import com.hcjeong.forestix.ui.screens.TreeMoveWords
import com.hcjeong.forestix.ui.screens.TreeMover
import com.hcjeong.forestix.ui.screens.loadFieldLogCruiseData
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixBorderedButton
import com.hcjeong.forestix.ui.theme.ForestixProminentButton
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import com.hcjeong.forestix.ui.theme.confidenceDescriptor
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

// MARK: - Loader

@Composable
fun TreeDetailScreen(nav: NavController, treeId: String) {
    val env = LocalAppEnvironment.current
    var viewModel by remember { mutableStateOf<TreeDetailViewModel?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(treeId) {
        try {
            val tree = env.treeRepository.read(UUID.fromString(treeId), includeDeleted = true)
                ?: throw IllegalStateException("Tree not found")
            viewModel = TreeDetailViewModel(tree, env.treeRepository)
        } catch (e: Exception) {
            loadError = "Failed to load tree: ${e.message ?: e}"
        }
    }

    val vm = viewModel
    if (vm == null) {
        ForestixScaffold(nav, title = "Tree") { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                loadError?.let { Text(it, style = Forestix.type.body, color = Forestix.colors.confidenceBad) }
                    ?: CircularProgressIndicator()
            }
        }
        return
    }
    TreeDetailContent(nav, vm)
}

/// Which row has its editor open. Only one at a time — two open number fields
/// on a phone push the Save button off the screen that decides whether they
/// land.
private enum class TreeDetailEditor { DBH, HEIGHT, NOTE }

// MARK: - Content

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TreeDetailContent(nav: NavController, viewModel: TreeDetailViewModel) {
    val scope = rememberCoroutineScope()
    val colors = Forestix.colors
    val type = Forestix.type

    val env = LocalAppEnvironment.current
    val settings by env.settings.state.collectAsStateWithLifecycle()
    val system = settings.unitSystem

    val tree by viewModel.tree.collectAsState()
    val speciesCode by viewModel.speciesCode.collectAsState()
    val status by viewModel.status.collectAsState()
    val measuredAt by viewModel.measuredAt.collectAsState()
    // The measurements AS THE FORM CURRENTLY HOLDS THEM. The numeric FIELDS
    // are still driven by their own text and the stored tree — feeding the
    // mirror back into a field is what re-formats a half-typed number under
    // the cruiser's thumb — but everything DERIVED from a measurement reads
    // these, because a basal area worked out from the stored diameter while
    // the diameter row already shows a taped 42 cm is two diameters on one
    // screen, and one of them is a number this stem never had.
    val editedDbhCm by viewModel.dbhCm.collectAsState()
    val editedHeightM by viewModel.heightM.collectAsState()

    // TRUE WHEN THE DIAMETER ON SCREEN WAS HAND TYPED rather than produced by a
    // capture, and so carries no ± band and no grade of its own.
    //
    // Two ways to be typed, and both count. The STORED stamp is what a diameter
    // typed on an earlier visit wears: `save()` writes `dbhCaptureMode =
    // "typed"` and leaves the σ and the tier the last capture put on the
    // record, so without this test the row prints that capture's ±3.2 mm and
    // its Good beside a number no sensor ever produced — and goes on printing
    // them for the life of the tree. The LIVE test catches the same claim one
    // step earlier: from the moment the field holds something other than the
    // stored measurement, the stored precision has stopped describing what is
    // on screen.
    //
    // SUPPRESSED HERE, NOT CLEARED ON THE RECORD. The σ, the RMSE, the coverage
    // and the inlier count are the audit trail of a measurement that really was
    // taken; they stay readable in the raw-metadata block, and destroying them
    // is not the fix for printing them in the wrong place.
    val dbhIsTyped = tree.dbhCaptureMode == "typed" || editedDbhCm != tree.dbhCm
    // The same rule for the height, minus the half the record cannot keep: a
    // Tree carries no capture-mode column for its height — `save()` stamps
    // `heightSource = "measured"` whether the number came off the sensors or
    // off a tape — so this only catches a height typed on THIS screen, before
    // it is saved. Mirrors iOS, which cannot tell the difference either.
    val heightIsTyped = editedHeightM != tree.heightM
    val notes by viewModel.notes.collectAsState()
    val damageCodes by viewModel.damageCodes.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val dirty by viewModel.dirty.collectAsState()

    val isDeleted = tree.deletedAt != null

    // Which measurement's tier explainer is open. null = closed.
    var explaining by remember { mutableStateOf<TierExplainerKind?>(null) }
    // Which row is being edited in place. null = every row is a readout.
    var editing by remember { mutableStateOf<TreeDetailEditor?>(null) }
    var settingTime by remember { mutableStateOf(false) }

    // The cruise store's projects and plots, read once. The Plot row names the
    // plot from it and the picker it opens is fed from it, so the name a
    // cruiser reads and the list they choose from are the same read.
    var cruiseData by remember { mutableStateOf<FieldLogCruiseData?>(null) }
    LaunchedEffect(Unit) { cruiseData = loadFieldLogCruiseData(env) }
    var pickingPlot by remember { mutableStateOf(false) }
    /// The worked-out move, held while the confirmation is up. Nothing has
    /// been written at this point.
    var pendingMove by remember { mutableStateOf<TreeMovePlan?>(null) }
    /// Anything the move has to say afterwards, as (title, message): the
    /// destination could not be read, the tree is already there, or the write
    /// itself failed. Each keeps its own title — "Some trees did not move"
    /// over "it is already there" would be a lie about what happened.
    var moveReport by remember { mutableStateOf<Pair<String, String>?>(null) }

    // The stored measurements at the precision the rest of the app prints
    // them, IN THE CRUISER'S OWN UNIT — one decimal, without the unit the row
    // already carries. A tree with no height prefills empty, so the
    // placeholder shows and clearing the field stays meaningful.
    val dbhPrefill = MeasurementFormatter.entryText(
        treeDisplayDiameter(tree.dbhCm.toDouble(), system), 1)
    val heightPrefill = tree.heightM?.let {
        MeasurementFormatter.entryText(treeDisplayHeight(it.toDouble(), system), 1)
    } ?: ""

    // The text each number field holds, owned HERE rather than by the row, so
    // closing an editor and opening it again does not re-seed the field from
    // the stored value and throw away what the cruiser typed. Keyed on the
    // prefill: it changes only when the STORED tree changes (a save landed),
    // which is the one time the field should re-seed.
    var dbhText by remember(dbhPrefill) { mutableStateOf(dbhPrefill) }
    var heightText by remember(heightPrefill) { mutableStateOf(heightPrefill) }

    // False while a numeric field holds something that is not a usable
    // measurement. The row says so and Save stays off, rather than the form
    // picking a number the tree never had.
    var dbhEntryValid by remember { mutableStateOf(true) }
    var heightEntryValid by remember { mutableStateOf(true) }

    // WHICH PLOT THIS TREE IS FILED UNDER — and, when it cannot be named, WHY.
    //
    // Three different things can leave this row without a plot name, and the
    // em dash means only one thing in this form: the tree does not carry the
    // field. It carries this one. So a plot that no longer exists and a cruise
    // store that would not open each say what happened, in the same words the
    // quick form uses for the same states. The em dash is left to stand for
    // the moment before the first read comes back, which is the one case where
    // nothing is yet known.
    val data = cruiseData
    val plot = data?.plot(tree.plotId)
    //
    // THE LOOKUP IS TESTED BEFORE THE FAILURE, because a failure is only an
    // explanation for a miss. A re-read that fails over a feed that already
    // came back good still has the plot in hand: naming it is the truth, and
    // "Plot unavailable" over a plot the form can name is a worse answer than
    // no answer. Same order as iOS.
    val plotLabel = when {
        data == null -> null
        plot == null -> if (data.failure != null) TreeFormWords.PLOT_UNAVAILABLE
                        else FieldLogWords.UNKNOWN_PLOT
        else -> data.project(tree.plotId)?.name
            ?.let { it + FieldLogWords.HEADING_SEPARATOR + FieldLogWords.plotName(plot.plotNumber) }
            ?: FieldLogWords.plotName(plot.plotNumber)
    }

    ForestixScaffold(nav, title = tree.displayTitle) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
        ) {
            if (isDeleted) {
                TreeFormSection {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Delete, contentDescription = null,
                            tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Removed from the tally — this tree is left out of every total. " +
                                "You can put it back below.",
                            style = type.body,
                            color = colors.textSecondary,
                        )
                    }
                }
            }

            // MARK: 1 — Detail
            TreeFormSection(header = TreeFormWords.DETAIL) {
                TreeFormRow(
                    label = TreeFormWords.PLOT,
                    value = plotLabel,
                    onTap = { pickingPlot = true },
                )
                TreeFormSpeciesRows(
                    code = speciesCode,
                    onCode = {
                        viewModel.speciesCode.value = it.orEmpty()
                        viewModel.markDirty()
                    },
                )
                TreeFormRow(
                    label = TreeFormWords.TIME,
                    value = MeasuredTimeInput.text(measuredAt),
                    onTap = { settingTime = true },
                )
                TreeFormRow(label = TreeFormWords.TREE, value = tree.displayTitle)
                TreeFormStatusRow(status) {
                    viewModel.status.value = it
                    viewModel.markDirty()
                }
                TreeFormRow(
                    label = TreeFormWords.DAMAGE,
                    value = damageCodes.takeIf { it.isNotEmpty() }?.joinToString(", "),
                )
                TreeFormRow(
                    label = TreeFormWords.NOTES,
                    value = notes.trim().takeIf { it.isNotEmpty() },
                    onTap = {
                        editing = if (editing == TreeDetailEditor.NOTE) null else TreeDetailEditor.NOTE
                    },
                )
                if (editing == TreeDetailEditor.NOTE) {
                    OutlinedTextField(
                        value = notes,
                        onValueChange = {
                            viewModel.notes.value = it
                            viewModel.markDirty()
                        },
                        placeholder = { Text(TreeFormWords.NOTES) },
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (tree.isMultistem) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CallSplit, contentDescription = null,
                            tint = colors.textSecondary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("One stem of a multi-stem tree", style = type.caption, color = colors.textSecondary)
                    }
                }
            }

            // MARK: 2 — Measurement. Tapping the VALUE is how a cruiser
            // changes it; there is no separate diameter or height screen to
            // reach for.
            TreeFormSection(header = TreeFormWords.MEASUREMENT) {
                TreeFormMeasurementRow(
                    label = TreeFormWords.DBH,
                    value = treeEnteredText(
                        dbhText, MeasurementFormatter.diameterUnit(system),
                        MeasurementFormatter.diameter(tree.dbhCm.toDouble(), system),
                        blankClears = false),
                    sigma = tree.dbhSigmaMm
                        ?.takeIf { !dbhIsTyped && it > 0f }
                        ?.let { MeasurementFormatter.diameterSigma(it.toDouble(), system) },
                    onTap = {
                        editing = if (editing == TreeDetailEditor.DBH) null else TreeDetailEditor.DBH
                    },
                )
                if (editing == TreeDetailEditor.DBH) {
                    TreeFormNumberField(
                        value = dbhText,
                        placeholder = "0.0",
                        unit = MeasurementFormatter.diameterUnit(system),
                    ) { entered ->
                        dbhText = entered
                        // Text still EQUAL to the prefill restores the stored
                        // Float verbatim. The prefill is rounded — a tree
                        // stored at 18.27 cm prefills "18.3" — so parsing the
                        // prefill back and saving it would coarsen a
                        // measurement nobody asked to change: open the form,
                        // press Save, lose 3 mm. The comparison is on the
                        // STRING for that reason, not on the number.
                        val typed = TruthInput.parsePositive(entered)
                        when {
                            entered == dbhPrefill -> {
                                viewModel.dbhCm.value = tree.dbhCm
                                dbhEntryValid = true
                            }
                            typed != null -> {
                                viewModel.dbhCm.value = treeStoredDiameterCm(typed, system).toFloat()
                                dbhEntryValid = true
                            }
                            else -> {
                                // Blank or not a number. Leave the stored
                                // diameter alone and say so on the row:
                                // writing 0 cm here would be a measurement
                                // this tree never had, and every tally
                                // downstream would believe it.
                                viewModel.dbhCm.value = tree.dbhCm
                                dbhEntryValid = false
                            }
                        }
                        viewModel.markDirty()
                    }
                }
                // Said whether or not the editor is open. Closing it does not
                // discard what was typed, so a Save that stays grey over a
                // collapsed field must still be able to say why.
                if (!dbhEntryValid) {
                    TreeFormWarning("A typed diameter must be a number greater than zero.")
                }
                TreeFormConfidenceRow(
                    label = TreeFormWords.DBH_CONFIDENCE,
                    // A hand-typed diameter has no tier, so the row reads "—".
                    // That is what it honestly carries, and the Capture row
                    // below says in words that this number was typed by hand.
                    tier = if (dbhIsTyped) null else tree.dbhConfidence.raw,
                ) { explaining = TierExplainerKind.DIAMETER }

                TreeFormMeasurementRow(
                    label = TreeFormWords.HEIGHT,
                    value = treeEnteredText(
                        heightText, MeasurementFormatter.heightUnit(system),
                        tree.heightM?.let { MeasurementFormatter.height(it.toDouble(), system) },
                        blankClears = true),
                    sigma = tree.heightSigmaM
                        ?.takeIf { !heightIsTyped && it > 0f }
                        ?.let { MeasurementFormatter.heightSigma(it.toDouble(), system) },
                    onTap = {
                        editing = if (editing == TreeDetailEditor.HEIGHT) null else TreeDetailEditor.HEIGHT
                    },
                )
                if (editing == TreeDetailEditor.HEIGHT) {
                    TreeFormNumberField(
                        value = heightText,
                        placeholder = TreeFormWords.EMPTY,
                        unit = MeasurementFormatter.heightUnit(system),
                    ) { entered ->
                        heightText = entered
                        // Same rule as the DBH field, with one difference:
                        // emptying it is how a cruiser says this tree has no
                        // height, so blank is a valid entry that clears the
                        // stored value rather than a refusal.
                        val typed = TruthInput.parsePositive(entered)
                        when {
                            entered == heightPrefill -> {
                                viewModel.heightM.value = tree.heightM
                                heightEntryValid = true
                            }
                            TruthInput.normalized(entered).isEmpty() -> {
                                viewModel.heightM.value = null
                                heightEntryValid = true
                            }
                            typed != null -> {
                                viewModel.heightM.value = treeStoredHeightM(typed, system).toFloat()
                                heightEntryValid = true
                            }
                            else -> {
                                viewModel.heightM.value = tree.heightM
                                heightEntryValid = false
                            }
                        }
                        viewModel.markDirty()
                    }
                }
                if (!heightEntryValid) {
                    TreeFormWarning("A typed height must be a number greater than zero.")
                }
                TreeFormConfidenceRow(
                    label = TreeFormWords.HEIGHT_CONFIDENCE,
                    tier = if (heightIsTyped) null else tree.heightConfidence?.raw,
                ) { explaining = TierExplainerKind.HEIGHT }
            }

            // MARK: 3 — Computed, from the measurements above — from the
            // EDITED ones, so a tape reading typed over the row above is the
            // diameter this section works from, before Save and without it.
            TreeFormComputedSection(
                dbhCm = editedDbhCm.takeIf { it > 0f },
                heightM = editedHeightM,
                speciesCode = speciesCode,
            )

            // MARK: 4 — Recorded. Three rows, always all three, in the order
            // the field log's record sheet draws them. A row that is present
            // on one of the two forms and absent from the other is how they
            // start reading as two screens again, so a field a cruise tree
            // does not carry keeps its row and says "—".
            TreeFormSection(header = TreeFormWords.RECORDED) {
                TreeFormRow(
                    label = TreeFormWords.POSITION,
                    // Said out loud rather than left blank: a tree with no fix
                    // is a different thing from one whose fix was not shown.
                    // Same sentence the record sheet uses for the same absence.
                    value = tree.latitude?.let { lat ->
                        tree.longitude?.let { lon -> CoordinateInput.text(lat, lon) }
                    } ?: TreeFormWords.NO_POSITION,
                )
                // WHERE THE COORDINATE CAME FROM. A cruise `Tree` has no column
                // for it: its fix is stamped once, at Accept, from the device —
                // so this row is the em dash's own case, a field this kind of
                // tree does not carry. A quick reading DOES carry one (it can
                // be typed by hand from the record sheet) and prints it there.
                TreeFormRow(label = TreeFormWords.POSITION_SOURCE, value = null)
                TreeFormRow(
                    label = TreeFormWords.CAPTURE,
                    value = tree.dbhCaptureMode?.let(TreeFormWords::captureText),
                )
            }

            // MARK: Raw metadata — developer mode only (F6). Raw internals
            // are a developer/audit surface, not a cruiser one.
            if (settings.developerMode) {
                TreeFormSection(header = "Raw metadata (read-only)") {
                    MetaRow("dbhMethod", tree.dbhMethod.raw)
                    MetaRow("dbhIsIrregular", if (tree.dbhIsIrregular) "true" else "false")
                    MetaRow("dbhSigmaMm", tree.dbhSigmaMm?.let { String.format(Locale.US, "%.2f", it) })
                    MetaRow("dbhRmseMm", tree.dbhRmseMm?.let { String.format(Locale.US, "%.2f", it) })
                    MetaRow("dbhCoverageDeg", tree.dbhCoverageDeg?.let { String.format(Locale.US, "%.1f", it) })
                    MetaRow("dbhNInliers", tree.dbhNInliers?.toString())
                    MetaRow("heightSource", tree.heightSource)
                    MetaRow("heightSigmaM", tree.heightSigmaM?.let { String.format(Locale.US, "%.2f", it) })
                    MetaRow("heightDHM", tree.heightDHM?.let { String.format(Locale.US, "%.2f", it) })
                    MetaRow("heightAlphaTopDeg", tree.heightAlphaTopDeg?.let { String.format(Locale.US, "%.2f", it) })
                    MetaRow("heightAlphaBaseDeg", tree.heightAlphaBaseDeg?.let { String.format(Locale.US, "%.2f", it) })
                    // Placement lost its editor (F8) but not its data —
                    // surface it here so an auditor can still read what was
                    // recorded.
                    MetaRow("bearingFromCenterDeg", tree.bearingFromCenterDeg?.let { String.format(Locale.US, "%.1f", it) })
                    MetaRow("distanceFromCenterM", tree.distanceFromCenterM?.let { String.format(Locale.US, "%.2f", it) })
                    MetaRow("createdAt", Instant.ofEpochMilli(tree.createdAt).toString())
                    MetaRow("updatedAt", Instant.ofEpochMilli(tree.updatedAt).toString())
                }
            }

            // MARK: Actions
            ForestixProminentButton(
                label = "Save changes",
                enabled = dirty && !isSaving && dbhEntryValid && heightEntryValid,
                modifier = Modifier.fillMaxWidth(),
            ) {
                scope.launch {
                    viewModel.save()
                    if (viewModel.errorMessage.value == null && !viewModel.dirty.value) {
                        nav.popBackStack()
                    }
                }
            }

            if (isDeleted) {
                ForestixBorderedButton(
                    label = "Put back in tally",
                    icon = Icons.AutoMirrored.Filled.Undo,
                    modifier = Modifier.fillMaxWidth(),
                ) { scope.launch { viewModel.undelete() } }
            } else {
                // iOS `role: .destructive` bordered button — red label.
                ForestixBorderedButton(
                    label = "Remove from tally",
                    icon = Icons.Filled.Delete,
                    tint = colors.confidenceBad,
                    modifier = Modifier.fillMaxWidth(),
                ) { scope.launch { viewModel.softDelete() } }
            }
        }
    }

    // Tier explainer — opened by tapping a confidence chip (F8).
    explaining?.let { kind ->
        TierExplainerSheet(kind) { explaining = null }
    }

    // The tree's own time. The same editor the field log's record sheet
    // opens, on the one instant a cruise tree has.
    if (settingTime) {
        MeasuredTimeEditorDialog(
            epochMs = measuredAt,
            subject = tree.displayTitle,
            sourceText = null,
            footer = TreeFormWords.TIME_FOOTER,
            onDismiss = { settingTime = false },
            onSave = {
                viewModel.measuredAt.value = it
                viewModel.markDirty()
                settingTime = false
            },
        )
    }

    // The plot picker the Plot row opens. Projects are the headings, plots
    // are the rows — the same picker the field log's bulk move uses, because
    // re-parenting one tree and re-parenting twelve are the same act.
    if (pickingPlot) {
        TreeMovePicker(
            projects = cruiseData?.projects.orEmpty(),
            plotsByProject = cruiseData?.plotsByProject.orEmpty(),
            onPick = { destination ->
                planMove(
                    scope, viewModel, env, destination,
                    onPlanned = { pendingMove = it },
                    onReport = { moveReport = it },
                    onDone = { pickingPlot = false })
            },
            onDismiss = { pickingPlot = false },
        )
    }

    // Names what moves and where BEFORE anything is written: the destination
    // may already be using this tree's number, and the confirmation is where
    // the cruiser is told the stem will be renumbered.
    pendingMove?.let { plan ->
        AlertDialog(
            onDismissRequest = { pendingMove = null },
            title = { Text(TreeMoveWords.CONFIRM_TITLE) },
            text = {
                Text(
                    TreeMoveWords.confirmMessage(
                        destination = plan.destination.label,
                        movers = plan.movers.size,
                        alreadyThere = plan.alreadyThere,
                        renumbered = plan.renumbered.size,
                        destinationClosed = plan.destination.isClosed))
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingMove = null
                    scope.launch {
                        val outcome = TreeMover.apply(plan, env)
                        // Re-read both: the row now names a different plot,
                        // and the heading the picker draws it under comes
                        // from the cruise data beside it.
                        viewModel.reload()
                        cruiseData = loadFieldLogCruiseData(env)
                        outcome.failures.firstOrNull()?.let {
                            moveReport = TreeMoveWords.FAILURE_TITLE to it.line
                        }
                    }
                }) { Text(TreeMoveWords.confirmAction(plan.movers.size)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingMove = null }) { Text(TreeMoveWords.CANCEL) }
            },
            containerColor = colors.surface,
        )
    }

    moveReport?.let { (title, message) ->
        AlertDialog(
            onDismissRequest = { moveReport = null },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { moveReport = null }) { Text("OK") }
            },
            containerColor = colors.surface,
        )
    }

    // Error alert (iOS `.alert("Error", ...)`).
    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            confirmButton = { TextButton(onClick = { viewModel.clearError() }) { Text("OK") } },
            title = { Text("Error") },
            text = { Text(message) },
        )
    }
}

/// Works out the move without writing anything, and hands the plan back for
/// confirmation. A destination that cannot be read stops the move rather than
/// letting it proceed on a guessed set of free tree numbers.
private fun planMove(
    scope: kotlinx.coroutines.CoroutineScope,
    viewModel: TreeDetailViewModel,
    env: com.hcjeong.forestix.AppEnvironment,
    destination: TreeMoveDestination,
    onPlanned: (TreeMovePlan) -> Unit,
    onReport: (Pair<String, String>) -> Unit,
    onDone: () -> Unit,
) {
    onDone()
    val id = viewModel.tree.value.id
    scope.launch {
        try {
            val plan = TreeMover.plan(listOf(id), destination, env)
            if (plan.hasWork) {
                onPlanned(plan)
            } else {
                // The tree is already in that plot. Nothing is written, and
                // the cruiser is told that rather than shown a confirmation
                // for a move of nothing.
                onReport(TreeMoveWords.CONFIRM_TITLE to
                    TreeMoveWords.nothingToMove(destination.label))
            }
        } catch (e: Exception) {
            onReport(TreeMoveWords.PLAN_FAILED_TITLE to
                TreeMoveWords.planFailedMessage(e.message ?: e.toString()))
        }
    }
}

// MARK: - Status row

private val TreeStatusOptions = listOf(
    "Live" to TreeStatus.LIVE,
    "Dead standing" to TreeStatus.DEAD_STANDING,
    "Dead down" to TreeStatus.DEAD_DOWN,
    "Cull" to TreeStatus.CULL,
)

/// The four tally statuses as one form row rather than a strip of chips: the
/// row reads like every other row in the section, and what it currently says
/// is legible without decoding which chip is filled.
@Composable
private fun TreeFormStatusRow(status: TreeStatus, onPick: (TreeStatus) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        TreeFormRow(
            label = TreeFormWords.STATUS,
            value = TreeStatusOptions.firstOrNull { it.second == status }?.first,
            onTap = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TreeStatusOptions.forEach { (label, value) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        open = false
                        onPick(value)
                    })
            }
        }
    }
}

@Composable
private fun MetaRow(label: String, value: String?) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = type.dataSmall, color = colors.textPrimary)
        Spacer(Modifier.weight(1f))
        Text(value ?: TreeFormWords.EMPTY, style = type.dataSmall, color = colors.textSecondary)
    }
}

// MARK: - THE FORM -------------------------------------------------------
//
// Everything below is shared with the field log's record sheet. A cruiser
// learns one screen, so there is one set of section names, one set of row
// labels and one em dash.

object TreeFormWords {
    const val DETAIL = "Detail"
    const val MEASUREMENT = "Measurement"
    const val COMPUTED = "Computed"

    const val PLOT = "Plot"
    const val SPECIES = "Species"
    /// The common name the picked code resolves to. The picker's own label is
    /// name-first ("Douglas-fir · DF"), but a code the list does not carry
    /// shows as the bare code — this row is where the form says whether the
    /// app knows what that code is.
    const val SPECIES_NAME = "Name"
    const val TIME = "Time"
    const val TREE = "Tree"
    const val STATUS = "Status"
    const val DAMAGE = "Damage"
    const val NOTES = "Notes"

    const val DBH = "DBH"
    const val DBH_CONFIDENCE = "DBH confidence"
    const val HEIGHT = "Height"
    const val HEIGHT_CONFIDENCE = "Height confidence"

    const val BASAL_AREA = "Basal area"
    const val VOLUME = "Volume"

    /// Every reading behind one row, in the order they were taken. Quick-only
    /// — a cruise `Tree` holds one diameter and one height, not a history of
    /// re-measurements — but drawn through the same section block as the rest,
    /// because a sheet that changes shape halfway down is the "two different
    /// forms" complaint in its most literal form.
    const val READINGS = "Readings"

    // Recorded rows. Both forms draw all three, in this order.
    const val RECORDED = "Recorded"
    const val POSITION = "Position"
    const val POSITION_SOURCE = "Position source"
    const val CAPTURE = "Capture"

    /// A tree with no fix. Said out loud — "not recorded" is a fact, an empty
    /// row is not. Same sentence on both forms and on iOS.
    const val NO_POSITION = "not recorded"

    /// What a row shows when the tree does not carry that field. The row
    /// stays; only the value is absent.
    ///
    /// IT MEANS THAT AND NOTHING ELSE. A plot that was deleted, or a store
    /// that would not open, are facts about this tree rather than fields it
    /// does not have, and they say so — see [UNRESOLVED_SPECIES] and
    /// [PLOT_UNAVAILABLE], and `FieldLogWords.UNKNOWN_PLOT` for the deleted
    /// plot. The em dash is left to mean "not applicable", plus the moment
    /// before a read has come back, where nothing is known yet.
    const val EMPTY = "—"

    /// The cruise store could not be read, so which plot this tree is filed
    /// under is not known — as opposed to known and gone, which is
    /// `FieldLogWords.UNKNOWN_PLOT`. Same words on the quick form and on iOS.
    const val PLOT_UNAVAILABLE = "Plot unavailable"

    /// A species code the active country/region list does not carry: imported
    /// data, or a code typed while another region was set. The code is kept
    /// verbatim — it is what the cruiser recorded — and this is the form
    /// saying the app cannot name it, rather than leaving a bare "ABCO" that
    /// reads like a species the app recognised.
    const val UNRESOLVED_SPECIES = "Not in this region's list"

    /// Said while editing a cruise tree's time. `Tree` has one instant and no
    /// column that records where it came from, so — unlike a quick reading —
    /// nothing downstream can mark a time set here as hand-set.
    const val TIME_FOOTER =
        "This is when the stem was tallied. The tree's row keeps no separate " +
            "record of where its time came from, so a time set here reads " +
            "like the one the tally stamped."

    /// The stand summary's own sentence for a region whose volume
    /// coefficients are not bundled. Byte-identical, because two screens
    /// refusing the same number for the same reason must say it the same way.
    const val VOLUME_PENDING =
        "Volume isn't available for this region yet. Every other metric is " +
            "unaffected."

    /// How a measurement was captured, in words. "typed" is its own answer:
    /// folding it into "Automatic" would tell the cruiser the sensors produced
    /// a number nobody ever pointed a camera at.
    fun captureText(raw: String): String = when (raw) {
        "manual" -> "Adjusted by hand"
        "typed" -> "Typed by hand"
        else -> "Automatic"
    }
}

/// One grouped section of the form — uppercase header, surface card body.
@Composable
internal fun TreeFormSection(header: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.xs)) {
        header?.let {
            Text(
                it.uppercase(Locale.US),
                style = type.sectionHead,
                color = colors.textTertiary,
                modifier = Modifier.padding(start = ForestixSpace.xs),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(ForestixRadius.card)
                .background(colors.surface)
                .padding(ForestixSpace.md),
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
            content = content,
        )
    }
}

/// One row of the form: label left, value right, and a chevron when tapping
/// it does something. [value] is null for a field this tree does not carry —
/// the row still draws, reading [TreeFormWords.EMPTY].
@Composable
internal fun TreeFormRow(
    label: String,
    value: String?,
    onTap: (() -> Unit)? = null,
    emphasised: Boolean = false,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    TreeFormSlotRow(label = label, onTap = onTap) {
        Text(
            value ?: TreeFormWords.EMPTY,
            style = if (emphasised) type.data else type.body,
            color = if (value == null) colors.textTertiary else colors.textPrimary,
            textAlign = TextAlign.End,
        )
    }
}

/// The same row with the value slot handed to the caller — a picker, a chip,
/// anything that is not a string.
@Composable
internal fun TreeFormSlotRow(
    label: String,
    onTap: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onTap == null) Modifier else Modifier.clickableNoRipple(onTap))
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = type.body, color = colors.textSecondary)
        Spacer(Modifier.weight(1f))
        trailing()
        if (onTap != null) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/// A measured quantity: the number, the ± band it carries as a sub-line, and
/// the tap that opens the field which sets it.
///
/// THE BAND BELONGS TO THE MEASUREMENT, not to a row of its own. It is that
/// number's own precision, so it is read with it — and a form that spent two
/// whole rows on "DBH ± band" and "Height ± band" was two rows longer than the
/// same form on the other phone, which is how one screen becomes two.
@Composable
internal fun TreeFormMeasurementRow(
    label: String,
    value: String?,
    sigma: String?,
    onTap: (() -> Unit)? = null,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onTap == null) Modifier else Modifier.clickableNoRipple(onTap))
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(label, style = type.body, color = colors.textSecondary)
            Spacer(Modifier.weight(1f))
            Text(
                value ?: TreeFormWords.EMPTY,
                style = type.data,
                color = if (value == null) colors.textTertiary else colors.textPrimary,
                textAlign = TextAlign.End,
            )
            if (onTap != null) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        sigma?.let {
            Text(it, style = type.dataSmall, color = colors.textTertiary)
        }
    }
}

/// The species rows both kinds of tree carry: the picker, and under it the
/// common name the picked code resolves to.
///
/// The NAME ROW is the point. The picker labels its own options name-first, so
/// a code chosen from the list is already legible — but a code the list does
/// not carry is shown verbatim, and imported data is full of those. Without
/// this row a cruiser reads "ABCO" and has no way to tell whether the app knows
/// what it is; with it, the app either names the species or says it cannot.
@Composable
internal fun TreeFormSpeciesRows(code: String?, onCode: (String?) -> Unit) {
    val trimmed = code?.trim()?.takeIf { it.isNotEmpty() }
    TreeFormSlotRow(label = TreeFormWords.SPECIES) {
        // THE species control, the same one the scan sheet and the new-tree
        // sheet use, so the list, the ordering and the typed-code escape
        // cannot drift apart.
        SpeciesPickerField(
            speciesCode = trimmed,
            onSpeciesCode = onCode,
            bordered = true,
        )
    }
    TreeFormRow(
        label = TreeFormWords.SPECIES_NAME,
        // No code at all is a field this tree does not carry, so it keeps the
        // em dash; a code nothing answers to is a fact about the code.
        value = trimmed?.let { treeSpeciesName(it) ?: TreeFormWords.UNRESOLVED_SPECIES },
    )
}

/// The common name for a species code, or null when no list carries it.
/// `RegionalSpecies.nameForCode` echoes the code back for an unknown, which is
/// how "unknown" is told from "named" — the same test iOS applies.
internal fun treeSpeciesName(code: String): String? {
    val name = RegionalSpecies.nameForCode(code)
    return name.takeIf { !it.equals(code, ignoreCase = true) }
}

/// Confidence row. The chip is a BUTTON: tapping it explains the tiers and
/// what drives them for this measurement (F8). A measurement that carries no
/// tier reads as an em dash and explains nothing — there is nothing to
/// explain.
@Composable
internal fun TreeFormConfidenceRow(
    label: String,
    tier: String?,
    onExplain: () -> Unit,
) {
    val colors = Forestix.colors
    if (tier == null) {
        TreeFormRow(label = label, value = null)
        return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickableNoRipple(onExplain)
            // Names the words the chip actually shows, so what a screen reader
            // announces matches what a sighted cruiser taps.
            .semantics { contentDescription = "$label. Explains what Good, Fair and Check mean" }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = Forestix.type.body, color = colors.textSecondary)
        Spacer(Modifier.weight(1f))
        TreeFormTierBadge(tier)
        // The app's standard "there is more behind this" affordance.
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(16.dp),
        )
    }
}

/// The chip's WORD is the one word the app uses for that grade
/// everywhere else — Good / Fair / Check, from `confidenceDescriptor`.
/// It used to print the stored enum ("Green" / "Yellow" / "Red"), so this
/// chip, the field log's quality column and the sheet this chip opens
/// were three different vocabularies for one reading. The colour still
/// carries the grade too; only the naming is unified.
@Composable
private fun TreeFormTierBadge(tier: String) {
    val descriptor = confidenceDescriptor(tier)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(descriptor.color))
        Text(
            descriptor.label,
            style = Forestix.type.caption,
            color = descriptor.color,
        )
    }
}

/// Why a number cannot be saved. Same sentences on both kinds of tree.
@Composable
internal fun TreeFormWarning(text: String) {
    Text(text, style = Forestix.type.caption, color = Forestix.colors.confidenceBad)
}

/// The number field a measurement row opens. It owns nothing: the text comes
/// in and goes straight back out, which is what lets the caller tell "the
/// cruiser retyped this" from "this is still the prefill" — the whole defence
/// against a rounded prefill overwriting the measurement behind it.
@Composable
internal fun TreeFormNumberField(
    value: String,
    placeholder: String,
    unit: String,
    onValue: (String) -> Unit,
) {
    val type = Forestix.type
    val colors = Forestix.colors
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            placeholder = {
                Text(placeholder, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = type.data.copy(textAlign = TextAlign.End),
            modifier = Modifier.weight(1f),
        )
        Text(unit, style = type.body, color = colors.textSecondary, modifier = Modifier.width(26.dp))
    }
}

// MARK: - Computed

/// Basal area and volume, worked out from the two measurements above.
///
/// VOLUME IS THE STAND SUMMARY'S VOLUME, treated the way that screen treats
/// it: cubic metres from the species' own configured equation, and nothing at
/// all for a region whose coefficients are not bundled (see
/// `Country.volumeStandardPending`) — it prints an em dash and the stand
/// summary's own sentence rather than a fabricated figure. A species with no
/// equation, a tree with no height and a tree with no diameter each read the
/// same em dash: there is no number to show, and 0 m³ is not one — which is
/// why the figure an equation returns is checked too. A coefficient set that
/// integrates a real stem to nothing has not measured that stem.
///
/// THE PENDING-REGION SENTENCE IS THIS PLATFORM'S, DELIBERATELY. iOS puts its
/// stand summary's wording in the row itself; here the row is an em dash and
/// the sentence sits under the section, because that is what THIS app's stand
/// summary does. Agreeing with the screen a cruiser compares this against
/// outranks agreeing with the other phone on this one string — do not "fix"
/// the two into one wording. Everything else in this form is byte-identical
/// across the two platforms on purpose.
@Composable
internal fun TreeFormComputedSection(
    dbhCm: Float?,
    heightM: Float?,
    speciesCode: String?,
) {
    val env = LocalAppEnvironment.current
    val settings by env.settings.state.collectAsStateWithLifecycle()
    val code = speciesCode?.trim()?.takeIf { it.isNotEmpty() }

    // Resolved the way the stand summary resolves it: species config names
    // the equation record, the factory turns the record into the engine form.
    var equation by remember(code) {
        mutableStateOf<com.hcjeong.forestix.inventory.VolumeEquation?>(null)
    }
    LaunchedEffect(code) {
        equation = try {
            code?.let { env.speciesConfigRepository.read(it) }
                ?.let { env.volumeEquationRepository.read(it.volumeEquationId) }
                ?.let(VolumeEquationFactory::make)
        } catch (e: Exception) {
            // An unreadable store is not an equation. The row says "—".
            null
        }
    }

    val pending = settings.country.volumeStandardPending
    val eq = equation
    val volumeText = when {
        pending -> null
        dbhCm == null || dbhCm <= 0f -> null
        // The same floor the plot statistics use before they ask an equation
        // for a volume: below breast height there is no stem to integrate.
        heightM == null || heightM <= 1.3f -> null
        eq == null -> null
        else -> eq.totalVolumeM3(dbhCm = dbhCm, heightM = heightM)
            // A stem that has a diameter and a height has a volume. Anything
            // else is the equation failing — a coefficient set that does not
            // fit this species, a form the factory built wrong — and "0.000 m³"
            // is that failure printed as a measurement of the tree.
            .takeIf { it > 0f }
            ?.let { String.format(Locale.US, "%.3f m³", it) }
    }

    TreeFormSection(header = TreeFormWords.COMPUTED) {
        TreeFormRow(
            label = TreeFormWords.BASAL_AREA,
            value = treeBasalAreaText(dbhCm, settings.unitSystem),
        )
        TreeFormRow(label = TreeFormWords.VOLUME, value = volumeText)
        if (pending) {
            Text(
                TreeFormWords.VOLUME_PENDING,
                style = Forestix.type.caption,
                color = Forestix.colors.textSecondary,
            )
        }
    }
}

/// One tree's basal area, in the unit the cruiser's areal basis is expressed
/// in: square feet for a per-acre cruise, square metres for a per-hectare one.
/// Computing ft² and printing it as m² would misread a stem by ~10.76×.
///
/// The area itself comes from the engine's own `basalAreaM2` — the function
/// every tally, expansion factor and stand total already goes through — so a
/// stem's basal area on this row and in the plot summary cannot disagree.
/// Null when there is no usable diameter; the row prints an em dash for it.
internal fun treeBasalAreaText(dbhCm: Float?, system: UnitSystem): String? {
    if (dbhCm == null || dbhCm <= 0f) return null
    val m2 = basalAreaM2(dbhCm = dbhCm).toDouble()
    // Three decimals metric, two imperial: one stem's cross-section is
    // ~0.03 m² / ~0.3 ft², so both carry the same significant figures. The
    // pair is fixed across the two platforms — a fourth metric decimal read
    // as a disagreement about the number rather than about its precision.
    return if (system == UnitSystem.IMPERIAL) {
        String.format(Locale.US, "%.2f ft²", Units.squareMetersToSquareFeet(m2))
    } else {
        String.format(Locale.US, "%.3f m²", m2)
    }
}

// MARK: - Unit-aware entry helpers
//
// The measurement fields are typed in the cruiser's OWN unit — the same unit
// the row above them prints — so an imperial cruiser never reads inches and
// then types centimetres into the field they opened by tapping them.

/// A stored diameter (cm) in the unit the field shows.
internal fun treeDisplayDiameter(cm: Double, system: UnitSystem): Double =
    if (system == UnitSystem.IMPERIAL) Units.cmToInches(cm) else cm

/// The way back: what a typed diameter stores as.
internal fun treeStoredDiameterCm(typed: Double, system: UnitSystem): Double =
    if (system == UnitSystem.IMPERIAL) Units.inchesToCm(typed) else typed

/// A stored height (m) in the unit the field shows.
internal fun treeDisplayHeight(m: Double, system: UnitSystem): Double =
    if (system == UnitSystem.IMPERIAL) Units.metersToFeet(m) else m

/// The way back: what a typed height stores as.
internal fun treeStoredHeightM(typed: Double, system: UnitSystem): Double =
    if (system == UnitSystem.IMPERIAL) Units.feetToMeters(typed) else typed

/// What a measurement ROW prints while its editor holds something: the number
/// in the field, so a row whose editor is collapsed cannot go on claiming the
/// value the cruiser has just typed over. Text that is not a number falls back
/// to [stored] — the measurement is still there, and the warning under the row
/// is what says the field cannot be saved.
///
/// [blankClears] is the one place the two measurements differ. Emptying the
/// HEIGHT field is how a cruiser says this tree has no height, so the row goes
/// to the em dash with it; emptying the DIAMETER field is not a measurement at
/// all, so the row goes on showing the diameter the tree actually has.
internal fun treeEnteredText(
    text: String,
    unit: String,
    stored: String?,
    blankClears: Boolean,
): String? {
    if (TruthInput.normalized(text).isEmpty()) return if (blankClears) null else stored
    val typed = TruthInput.parsePositive(text) ?: return stored
    return "${MeasurementFormatter.entryText(typed, 1)} $unit"
}
