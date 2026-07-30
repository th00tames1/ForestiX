// DRAW AN AREA — the stand boundary, sketched on the map instead of
// imported from a file. 1:1 port of iOS Screens/BoundaryDrawScreen.swift;
// every user-visible sentence here is byte-identical to the sibling's.
//
// The cruiser's reference is a drone mission planner: a rectangle appears,
// you pull its corners onto the stand. In their own words:
//   • a CORNER is a draggable handle
//   • every EDGE carries a midpoint handle; dragging it adds a corner there
//   • LONG-PRESSING a corner deletes it
//
// Three deliberate choices, all of them about a cold thumb in a glove:
//   • The handles are 30 dp of ink inside a 48 dp touch target — the target
//     is also MOVING under the finger, so it gets the full minimum and not
//     the visible size.
//   • Nothing autosaves. Every edit is live on screen and none of it
//     reaches the store until "Save boundary" is pressed, so a half-dragged
//     shape can never become the boundary the cruise is planned against.
//     Leaving without saving throws the sketch away, and says so first.
//   • Undo is a full-shape stack, pushed before each gesture rather than
//     after. A dragged corner therefore undoes to where the drag STARTED,
//     not to some point in the middle of it.
//
// What it produces is the SAME BoundaryGeometry an import produces, so plot
// generation, the area readout, the in/out verdict and the exports need no
// change at all — with BoundaryOrigin.DRAWN stamped on it forever. It never
// reads the GPS: this screen is about where the cruiser INTENDS the stand
// to be, and a drawn corner is not a measured one.

package com.hcjeong.forestix.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.basemap.MapBoundaryOverlay
import com.hcjeong.forestix.basemap.MapView
import com.hcjeong.forestix.basemap.rememberMapCameraState
import com.hcjeong.forestix.common.areaUnit
import com.hcjeong.forestix.geo.BoundaryDraft
import com.hcjeong.forestix.geo.BoundaryOrigin
import com.hcjeong.forestix.geo.CoordinateConversions
import com.hcjeong.forestix.geo.StoredBoundary
import com.hcjeong.forestix.geo.SurveyBoundaryStore
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/// Visible handle diameter, and the invisible target around it.
private val HandleDiameter = 30.dp
private val HandleTarget = 48.dp
private val MidpointDiameter = 20.dp

/// Ink of the handles and of the outline being drawn — deliberately the
/// same saturated orange the imported boundary uses on the map (MapView's
/// `boundaryTint`). This IS a survey boundary, and colouring it differently
/// would suggest it is a different kind of object. Provenance is carried by
/// words and by the stored stamp, never by a colour a cruiser has to
/// remember.
private val HandleInk = Color(0xFFFFB454)

@Composable
fun BoundaryDrawScreen(
    /// Where the map opens — the camera the cruiser was already looking at,
    /// so "Draw an area" drops the rectangle on the ground on screen and
    /// not on a remembered default.
    initialCenter: CoordinateConversions.LatLon,
    initialZoom: Double,
    onDismiss: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val settings by LocalAppEnvironment.current.settings.state.collectAsStateWithLifecycle()
    val stored by SurveyBoundaryStore.state.collectAsStateWithLifecycle()

    val camera = rememberMapCameraState()
    val targetPx = with(density) { HandleTarget.toPx() }

    /// null until the map has laid out and the starting rectangle has been
    /// derived from the viewport.
    var draft by remember { mutableStateOf<BoundaryDraft?>(null) }
    var undoStack by remember { mutableStateOf<List<BoundaryDraft>>(emptyList()) }
    var name by remember { mutableStateOf(BoundaryDraft.DEFAULT_NAME) }
    var confirmingReplace by remember { mutableStateOf(false) }
    var confirmingDiscard by remember { mutableStateOf(false) }
    /// A save the store refused. Stays on screen: a boundary that did not
    /// persist must never look like one that did.
    var saveFailure by remember { mutableStateOf<String?>(null) }
    /// Set when a long-press deleted a corner mid-touch — the drag
    /// recogniser on that same finger is then ignored until it lifts,
    /// because the index it holds now names a DIFFERENT vertex.
    var suppressCornerDrag by remember { mutableStateOf(false) }

    fun pushUndo() {
        val current = draft ?: return
        // A field session drags a lot; the stack is for mistakes, not for
        // history, and an unbounded one is just a leak.
        undoStack = (undoStack + current).takeLast(30)
    }

    fun attemptDismiss() {
        // Any edit at all counts as unsaved work: the starting rectangle
        // alone is not worth a prompt, but a moved corner is.
        if (undoStack.isEmpty()) onDismiss() else confirmingDiscard = true
    }

    fun commitSave() {
        val current = draft ?: return
        val label = name.trim().ifEmpty { BoundaryDraft.DEFAULT_NAME }
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                try {
                    SurveyBoundaryStore.saveDrawn(
                        context = context,
                        geometry = current.toGeometry(label),
                        displayName = label,
                    )
                    null
                } catch (e: Exception) {
                    e.message ?: "The boundary could not be saved."
                }
            }
            if (failure == null) onDismiss() else saveFailure = failure
        }
    }

    fun attemptSave() {
        saveFailure = null
        val current = draft ?: return
        if (!current.isValid) return
        // REPLACEMENT IS NEVER SILENT. An imported boundary was surveyed by
        // someone with instruments; overwriting it with a sketch because a
        // button was in the way is the worst thing this screen could do.
        if (stored != null) confirmingReplace = true else commitSave()
    }

    BackHandler { attemptDismiss() }

    // The starting rectangle, sized from the VISIBLE viewport (its middle
    // half) rather than from a fixed ground distance: at zoom 12 a 200 m
    // box is a dot and at zoom 20 it is off-screen, and a cruiser who
    // cannot see what they were just given assumes nothing happened. Fired
    // once, on the first layout that produces a camera — never again, so a
    // pan can't reset a shape the cruiser has started pulling about.
    val viewport = camera.viewportSizePx
    LaunchedEffect(viewport) {
        if (draft != null || viewport.width <= 0 || viewport.height <= 0) return@LaunchedEffect
        val a = camera.coordinateAt(Offset(viewport.width * 0.25f, viewport.height * 0.25f))
        val b = camera.coordinateAt(Offset(viewport.width * 0.75f, viewport.height * 0.75f))
        if (a != null && b != null) draft = BoundaryDraft.rectangle(a, b)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.canvas)
    ) {
        // MARK: - Top bar
        Box(
            Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .statusBarsPadding()
                .padding(vertical = ForestixSpace.xs)
        ) {
            Text(
                "Draw an area",
                style = type.bodyBold.copy(fontSize = 17.sp),
                color = colors.textPrimary,
                modifier = Modifier.align(Alignment.Center),
            )
            TextButton(
                onClick = { attemptDismiss() },
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Text("Cancel", style = type.body, color = colors.primary)
            }
        }

        Text(
            "Drag a corner to move it. Drag the small handle on an edge to add a corner. " +
                "Press and hold a corner to delete it.",
            style = type.caption,
            color = colors.textSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = ForestixSpace.md, vertical = ForestixSpace.sm),
        )

        // MARK: - Map + handles
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            MapView(
                center = initialCenter,
                modifier = Modifier.fillMaxSize(),
                initialZoom = initialZoom,
                mapType = settings.mapType,
                overlayURLTemplate = if (settings.overlayEnabled &&
                    settings.providerUsageAcknowledged
                ) {
                    settings.tileURLTemplate
                } else {
                    null
                },
                // The outline under construction is drawn through the map's
                // OWN boundary layer, so it sits exactly where a saved
                // boundary will sit — no second renderer to disagree with
                // the first about what the shape looks like.
                boundary = draft?.let { d ->
                    listOf(
                        MapBoundaryOverlay(
                            rings = listOf(d.vertices + d.vertices.first()),
                            closed = true,
                        )
                    )
                } ?: emptyList(),
                cameraState = camera,
            )

            // Handles are separate small children of a Box that has NO
            // pointerInput of its own, so every touch that misses one falls
            // straight through to the map underneath and pans it — which is
            // what a cruiser does far more often than they drag a corner.
            draft?.let { current ->

                // EDGE MIDPOINTS — drawn first, so a corner handle sitting
                // on top of one always wins the touch.
                current.edgeMidpoints.forEachIndexed { index, coordinate ->
                    val point = camera.screenPoint(coordinate) ?: return@forEachIndexed
                    Box(
                        Modifier
                            .offset {
                                IntOffset(
                                    (point.x - targetPx / 2).roundToInt(),
                                    (point.y - targetPx / 2).roundToInt(),
                                )
                            }
                            .size(HandleTarget)
                            .drawBehind {
                                val r = MidpointDiameter.toPx() / 2
                                drawCircle(Color.White, radius = r, center = center)
                                drawCircle(
                                    HandleInk,
                                    radius = r,
                                    center = center,
                                    style = Stroke(width = 3.dp.toPx()),
                                )
                            }
                            .pointerInput(index) {
                                // Map-space position, accumulated from
                                // DELTAS after being seeded at the gesture's
                                // start from the LIVE geometry. Deriving it
                                // from the element's own origin every frame
                                // would chase a handle that is moving under
                                // the finger; a delta is the same number in
                                // either space.
                                var cursor = Offset.Zero
                                var insertedIndex = -1
                                detectDragGestures(
                                    onDragStart = { start ->
                                        insertedIndex = -1
                                        val mid = draft?.edgeMidpoints?.getOrNull(index)
                                        val here = mid?.let { camera.screenPoint(it) }
                                        cursor = if (here == null) {
                                            Offset.Zero
                                        } else {
                                            here + start - Offset(targetPx / 2, targetPx / 2)
                                        }
                                    },
                                    onDragEnd = { insertedIndex = -1 },
                                    onDragCancel = { insertedIndex = -1 },
                                ) { change, amount ->
                                    change.consume()
                                    if (cursor == Offset.Zero) return@detectDragGestures
                                    cursor += amount
                                    val at = camera.coordinateAt(cursor)
                                        ?: return@detectDragGestures
                                    val base = draft ?: return@detectDragGestures
                                    if (insertedIndex >= 0) {
                                        draft = base.moving(insertedIndex, at)
                                    } else {
                                        // The handle becomes a real corner
                                        // only now, on the first movement —
                                        // a stray touch on an edge must not
                                        // silently add a vertex the cruiser
                                        // never asked for.
                                        pushUndo()
                                        val (next, created) = base.insertingOnEdge(index, at)
                                        draft = next
                                        insertedIndex = created
                                    }
                                }
                            }
                    )
                }

                // CORNERS.
                current.vertices.forEachIndexed { index, coordinate ->
                    val point = camera.screenPoint(coordinate) ?: return@forEachIndexed
                    Box(
                        Modifier
                            .offset {
                                IntOffset(
                                    (point.x - targetPx / 2).roundToInt(),
                                    (point.y - targetPx / 2).roundToInt(),
                                )
                            }
                            .size(HandleTarget)
                            .drawBehind {
                                val r = HandleDiameter.toPx() / 2
                                drawCircle(HandleInk, radius = r, center = center)
                                drawCircle(
                                    Color.White,
                                    radius = r,
                                    center = center,
                                    style = Stroke(width = 3.dp.toPx()),
                                )
                            }
                            .pointerInput(index) {
                                detectTapGestures(
                                    onLongPress = {
                                        val base = draft ?: return@detectTapGestures
                                        val next = base.removing(index)
                                        // Refused at three corners: the
                                        // deletion that would leave a line
                                        // is the one a gloved thumb makes
                                        // by accident. Nothing is pushed
                                        // onto the undo stack for a
                                        // deletion that did not happen.
                                        if (next != null) {
                                            pushUndo()
                                            draft = next
                                            suppressCornerDrag = true
                                        }
                                    }
                                )
                            }
                            .pointerInput(index) {
                                var cursor = Offset.Zero
                                var started = false
                                detectDragGestures(
                                    onDragStart = { start ->
                                        started = false
                                        val here = draft?.vertices?.getOrNull(index)
                                            ?.let { camera.screenPoint(it) }
                                        cursor = if (here == null) {
                                            Offset.Zero
                                        } else {
                                            here + start - Offset(targetPx / 2, targetPx / 2)
                                        }
                                    },
                                    onDragEnd = { suppressCornerDrag = false },
                                    onDragCancel = { suppressCornerDrag = false },
                                ) { change, amount ->
                                    change.consume()
                                    if (suppressCornerDrag) return@detectDragGestures
                                    if (cursor == Offset.Zero) return@detectDragGestures
                                    cursor += amount
                                    val at = camera.coordinateAt(cursor)
                                        ?: return@detectDragGestures
                                    val base = draft ?: return@detectDragGestures
                                    if (!started) {
                                        // One undo entry per gesture,
                                        // captured before the first
                                        // millimetre is committed.
                                        pushUndo()
                                        started = true
                                    }
                                    draft = base.moving(index, at)
                                }
                            }
                    )
                }
            }
        }

        // MARK: - Control panel
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .navigationBarsPadding()
                .padding(ForestixSpace.md),
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    // Live area in the cruiser's OWN unit system — they are
                    // laying out a stand, so the number they need is acres
                    // or hectares, never m². A dash rather than "0.00 ac"
                    // while the shape has no defined interior: a number
                    // computed from a self-crossing outline is a number
                    // that means nothing.
                    val unit = settings.unitSystem.areaUnit
                    val d = draft
                    Text(
                        if (d != null && d.isValid) {
                            String.format(
                                Locale.US,
                                "Area %.2f %s",
                                unit.fromAcres(d.areaAcres),
                                unit.abbreviation,
                            )
                        } else {
                            "Area —"
                        },
                        style = type.bodyBold,
                        color = colors.textPrimary,
                    )
                    val count = d?.vertices?.size ?: 0
                    Text(
                        if (count == 1) "1 corner" else "$count corners",
                        style = type.caption,
                        color = colors.textSecondary,
                    )
                }
                Text(
                    "Undo",
                    style = type.body,
                    color = if (undoStack.isEmpty()) colors.textTertiary else colors.primary,
                    modifier = Modifier
                        .clickableNoRipple {
                            val previous = undoStack.lastOrNull() ?: return@clickableNoRipple
                            undoStack = undoStack.dropLast(1)
                            draft = previous
                            saveFailure = null
                        }
                        .padding(horizontal = ForestixSpace.xs, vertical = ForestixSpace.xxs),
                )
            }

            // A refusal is shown the moment the shape becomes invalid, not
            // at the moment Save is pressed — the corner that caused it is
            // still under the thumb.
            val refusal = draft?.validationFailure?.message ?: saveFailure
            if (refusal != null) {
                Text(refusal, style = type.caption, color = colors.confidenceBad)
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("Boundary name", color = colors.textTertiary) },
                modifier = Modifier.fillMaxWidth(),
                // Explicit ink on an explicit surface: the default M3 scheme
                // has put black text on this app's dark surfaces before, and
                // a name field a cruiser cannot read is a name field they
                // will not set.
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    cursorColor = colors.primary,
                    focusedBorderColor = colors.primary,
                    unfocusedBorderColor = colors.divider,
                ),
            )

            val canSave = draft?.isValid == true
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(ForestixRadius.card)
                    .background(if (canSave) colors.primary else colors.divider)
                    .clickableNoRipple { if (canSave) attemptSave() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Save boundary",
                    style = type.bodyBold,
                    color = if (canSave) colors.primaryInk else colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    if (confirmingReplace) {
        AlertDialog(
            onDismissRequest = { confirmingReplace = false },
            title = { Text("Replace the boundary?") },
            text = { Text(replacementMessage(stored)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingReplace = false
                    commitSave()
                }) { Text("Replace") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingReplace = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmingDiscard) {
        AlertDialog(
            onDismissRequest = { confirmingDiscard = false },
            title = { Text("Discard this outline?") },
            text = { Text("The outline has not been saved. Leaving now loses it.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDiscard = false
                    onDismiss()
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDiscard = false }) { Text("Keep editing") }
            },
        )
    }
}

/// The "Replace the boundary?" body, shared with the import path in
/// MapSettingsSheet so both replacements are described in the same words.
/// Byte-identical to iOS BoundaryDrawScreen.replacementMessage(for:).
internal fun replacementMessage(existing: StoredBoundary?): String {
    if (existing == null) return ""
    val origin = if (existing.origin == BoundaryOrigin.DRAWN) {
        "drawn on the map"
    } else {
        "imported from a file"
    }
    return "${existing.displayName} is loaded now — $origin. Forestix keeps one boundary at a " +
        "time, so saving replaces it. The replaced boundary is not recoverable in the app."
}
