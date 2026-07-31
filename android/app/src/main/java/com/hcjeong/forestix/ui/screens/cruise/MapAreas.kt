// AREAS ON THE HOME MAP — the outline a cruise is laid out inside, drawn
// and edited IN PLACE instead of on a screen of its own.
//
// WHAT AN AREA IS. A `Stratum`: the project-scoped polygon the cruise
// design has always generated plots inside (`SamplingGenerator.StratumInput`,
// `PlannedPlot.stratumId`). Drawing one on the map used to produce a survey
// boundary instead — a map decoration that drove nothing, so a cruiser who
// outlined their stand still had to go and draw it a second time inside
// Cruise setup before any plot could be laid in it. Making the drawn
// outline BE the stratum is what gives every plot the area laid a
// permanent, exact link back to it (`stratumId`), which is what "delete the
// area, delete its plots" and "resize the area, re-lay its plots" both
// stand on. A geometric after-the-fact "which plots are inside this shape"
// test would sweep up plots a cruiser placed by hand, and a resize would
// then silently destroy them.
//
// The imported survey boundary is untouched and still lives in Map settings
// ("Draw the boundary"): a stand somebody surveyed into a file and an
// outline dragged across a satellite tile are different evidence, and only
// the second one is an area you can cruise from here.
//
// THE MAP KEEPS THE MAP. Every gesture here happens over the home map's own
// camera — "Draw an area" no longer pushes an editor that throws away the
// view the cruiser had framed. The gesture vocabulary is the one
// BoundaryDrawScreen established and a cruiser may already know:
//   • a CORNER is a draggable handle
//   • every EDGE carries a midpoint handle; dragging it adds a corner
//   • LONG-PRESSING a corner deletes it
// and, as there, nothing autosaves and undo is a full-shape stack pushed
// before each gesture.
//
// iOS twin: TimberCruisingApp/Screens/MapHomeScreen+Area.swift.

package com.hcjeong.forestix.ui.screens.cruise

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hcjeong.forestix.AppEnvironment
import com.hcjeong.forestix.basemap.MapAreaOverlay
import com.hcjeong.forestix.basemap.MapCameraState
import com.hcjeong.forestix.data.SettingsSnapshot
import com.hcjeong.forestix.common.areaUnit
import com.hcjeong.forestix.data.cruise.PlannedPlot
import com.hcjeong.forestix.data.cruise.Stratum
import com.hcjeong.forestix.geo.BoundaryDraft
import com.hcjeong.forestix.geo.CoordinateConversions
import com.hcjeong.forestix.geo.GeoJSONImporter
import com.hcjeong.forestix.geo.SamplingGenerator
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixBorderedButton
import com.hcjeong.forestix.ui.theme.ForestixProminentButton
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/// Visible handle diameter and the invisible target around it — 30 dp of
/// ink inside a 48 dp target, bigger than the platform minimum because the
/// target is also MOVING under a gloved thumb.
private val HandleDiameter = 30.dp
private val HandleTarget = 48.dp
private val MidpointDiameter = 20.dp

// MARK: - State

/// Area UI state, owned by MapHomeScreen and remembered across the mode
/// toggle. Separate from CruiseModeState because areas are drawn in BOTH
/// modes: an outline the cruiser dragged onto their stand does not stop
/// existing because they flipped to quick measure, and one that vanished on
/// the toggle would read as lost.
internal class MapAreaState {
    /// The project the areas on screen belong to, resolved by
    /// `MapAreaEffects` in both modes. Null until one exists.
    var projectId by mutableStateOf<UUID?>(null)
    var areas by mutableStateOf<List<Stratum>>(emptyList())
    /// Unvisited plans laid inside each area. Counted here rather than read
    /// off the cruise snapshot because measure mode never loads that, and a
    /// callout that says "no plots planned" because nothing was loaded is a
    /// lie about the cruiser's own work.
    var planCounts by mutableStateOf<Map<UUID, Int>>(emptyMap())
    /// Bump to reload after every mutating action.
    var refresh by mutableIntStateOf(0)

    /// The area whose callout is up. Mutually exclusive with a pin peek and
    /// with the plot menu — one selection on the map at a time.
    var selectedId by mutableStateOf<UUID?>(null)
    /// The outline being dragged. Non-null is the whole "editing an area"
    /// mode: the bottom cluster becomes the draft bar, the handles appear,
    /// and press-and-hold planning is suppressed.
    var draft by mutableStateOf<BoundaryDraft?>(null)
    /// Full-shape undo, pushed BEFORE each gesture rather than after, so a
    /// dragged corner undoes to where the drag started.
    var undoStack by mutableStateOf<List<BoundaryDraft>>(emptyList())
    var draftName by mutableStateOf("")
    /// The stored area the draft is replacing. null = the draft is new.
    var editingId by mutableStateOf<UUID?>(null)
    /// Set when a long-press deleted a corner mid-touch — the drag
    /// recogniser on that same finger is then ignored until it lifts,
    /// because the index it holds now names a DIFFERENT vertex.
    var suppressCornerDrag by mutableStateOf(false)
    /// Why an area could not be saved or deleted. Its own channel: neither
    /// the plot nor the plan refusal names an area, and a refusal that names
    /// the wrong thing sends the cruiser looking for the wrong problem.
    var saveRefusal by mutableStateOf<String?>(null)
    var deleteCandidate by mutableStateOf<Stratum?>(null)
    /// The overlap toggle, announced the first time a tap hits both an area
    /// and a plot. `stackHintSeen` keeps it to once a session — a hint that
    /// reappears forever is noise, and one that never appears is a gesture
    /// nobody finds.
    var stackHint by mutableStateOf<String?>(null)
    var stackHintSeen = false
    /// The area Cruise setup was opened FROM, so generation lays plots into
    /// that area and leaves every other area's plan alone.
    var cruiseSetupArea by mutableStateOf<Stratum?>(null)

    val selected: Stratum? get() = areas.firstOrNull { it.id == selectedId }

    fun pushUndo() {
        val current = draft ?: return
        // A field session drags a lot; the stack is for mistakes, not for
        // history, and an unbounded one is just a leak.
        undoStack = (undoStack + current).takeLast(30)
    }

    fun cancelDraft() {
        draft = null
        editingId = null
        undoStack = emptyList()
        saveRefusal = null
        suppressCornerDrag = false
    }

    /// "Area 1", "Area 2", … — one past the highest number already spoken
    /// for, so re-using a name a deleted area had is never a surprise.
    fun defaultName(): String {
        val used = areas.mapNotNull { s ->
            s.name.removePrefix("Area ").takeIf { s.name.startsWith("Area ") }?.toIntOrNull()
        }
        return "Area ${(used.maxOrNull() ?: 0) + 1}"
    }
}

/// Loads the project's areas and the size of the plan inside each.
///
/// Runs in BOTH modes and resolves the project ITSELF rather than reading
/// the cruise snapshot: measure mode never loads that snapshot, and areas
/// that appeared only in cruise mode would read as lost.
@Composable
internal fun MapAreaEffects(
    state: MapAreaState,
    env: AppEnvironment,
    cruiseProjectId: String?,
) {
    LaunchedEffect(state.refresh, cruiseProjectId) {
        val project = try {
            resolveCurrentProject(env, cruiseProjectId)
        } catch (_: Exception) {
            null
        }
        state.projectId = project?.id
        val projectId = project?.id
        if (projectId == null) {
            state.areas = emptyList()
            state.planCounts = emptyMap()
            state.selectedId = null
            return@LaunchedEffect
        }
        state.areas = try {
            env.stratumRepository.listByProject(projectId)
        } catch (_: Exception) {
            emptyList()
        }
        state.planCounts = try {
            env.plannedPlotRepository.listByProject(projectId)
                .filter { !it.visited && it.stratumId != null }
                .groupingBy { it.stratumId!! }
                .eachCount()
        } catch (_: Exception) {
            emptyMap()
        }
        if (state.areas.none { it.id == state.selectedId }) state.selectedId = null
    }
}

// MARK: - What the map draws

/// The draft, while one is open, is drawn through the SAME layer as the
/// stored areas — one renderer, so a half-dragged outline can never look
/// like a different kind of object from the one it is about to become. The
/// area being edited drops out: its stored shape is exactly what the draft
/// is replacing, and drawing both would show the cruiser two answers to one
/// question.
internal const val AREA_DRAFT_OVERLAY_ID = "area-draft"

internal fun areaOverlays(state: MapAreaState): List<MapAreaOverlay> {
    val out = state.areas.mapNotNull { stratum ->
        if (stratum.id == state.editingId) return@mapNotNull null
        val rings = try {
            parseRings(stratum.polygonGeoJSON)
        } catch (_: Exception) {
            return@mapNotNull null
        }
        if (rings.isEmpty()) return@mapNotNull null
        MapAreaOverlay(
            id = stratum.id.toString(),
            rings = rings,
            selected = stratum.id == state.selectedId,
        )
    }.toMutableList()
    state.draft?.takeIf { it.vertices.size >= 3 }?.let {
        out.add(MapAreaOverlay(AREA_DRAFT_OVERLAY_ID, listOf(it.closedRing), selected = true))
    }
    return out
}

// MARK: - Tap routing

/// Where a tap that missed every pin lands. The map reports what was under
/// the finger and takes no view; the rule for the OVERLAP is here, because
/// only the host knows what is selected right now.
///
/// THE OVERLAP RULE: a plot is laid inside an area, so their targets
/// coincide by construction. A tap on the pair TOGGLES — whichever of the
/// two is not currently selected is the one the cruiser is asking for.
/// Without it the area, being the whole interior, would swallow every tap
/// and the plot underneath would be unreachable. It is also announced
/// (`stackHint`) the first time it happens in a session: a toggle nobody
/// knows about is the same as no toggle at all.
internal fun handleOverlayTap(
    state: MapAreaState,
    cruise: CruiseModeState,
    plotId: String?,
    areaIdRaw: String?,
) {
    // A tap while drafting belongs to the draft, not to what is under it:
    // the outline being dragged is the only thing on this map the cruiser is
    // currently talking to.
    if (state.draft != null) return
    val areaId = areaIdRaw?.let { raw -> try { UUID.fromString(raw) } catch (_: Exception) { null } }
    when {
        plotId != null && areaId != null -> {
            if (state.selectedId == areaId) {
                state.selectedId = null
                cruise.openPlotMenu(plotId)
                announceStack(state, showingArea = false)
            } else {
                selectArea(state, cruise, areaId)
                announceStack(state, showingArea = true)
            }
        }
        plotId != null -> {
            state.selectedId = null
            cruise.openPlotMenu(plotId)
        }
        // Tapping the selected area again lets it go — otherwise an area
        // covering the viewport would be a selection with no way out except
        // a pin tap.
        areaId != null ->
            if (state.selectedId == areaId) state.selectedId = null
            else selectArea(state, cruise, areaId)
    }
}

internal fun selectArea(state: MapAreaState, cruise: CruiseModeState, id: UUID) {
    cruise.selectedId = null
    cruise.plotMenuFor = null
    state.selectedId = id
}

/// Names the thing the NEXT tap will reach, because that is the sentence a
/// cruiser can act on straight away.
private fun announceStack(state: MapAreaState, showingArea: Boolean) {
    if (state.stackHintSeen) return
    state.stackHintSeen = true
    state.stackHint = if (showingArea) {
        "Tap again for the plot under this area."
    } else {
        "Tap again for the area around this plot."
    }
}

// MARK: - Drawing and editing in place

/// "Draw an area" from the pressed-point callout. The starting rectangle is
/// sized from the VISIBLE viewport (half of its short side) rather than
/// from a fixed ground distance: at zoom 12 a 200 m box is a dot and at
/// zoom 20 it is off-screen, and a cruiser who cannot see what they were
/// given assumes nothing happened. Centred on the press, so it lands on the
/// ground under the finger.
internal fun beginAreaDraw(
    state: MapAreaState,
    camera: MapCameraState,
    at: CoordinateConversions.LatLon,
) {
    val centre = camera.screenPoint(at) ?: return
    val size = camera.viewportSizePx
    if (size.width <= 0 || size.height <= 0) return
    val half = minOf(size.width, size.height) * 0.25f
    val a = camera.coordinateAt(Offset(centre.x - half, centre.y - half)) ?: return
    val b = camera.coordinateAt(Offset(centre.x + half, centre.y + half)) ?: return
    state.editingId = null
    state.draftName = state.defaultName()
    state.undoStack = emptyList()
    state.saveRefusal = null
    state.selectedId = null
    state.draft = BoundaryDraft.rectangle(a, b)
}

/// EDIT — the same in-place editor, seeded with the stored outline.
internal fun beginAreaEdit(state: MapAreaState, stratum: Stratum) {
    val outer = try {
        parseRings(stratum.polygonGeoJSON).firstOrNull()
    } catch (_: Exception) {
        null
    }
    if (outer == null || outer.size < 4) {
        state.saveRefusal = "This area's outline could not be read, so it can't be edited."
        return
    }
    state.editingId = stratum.id
    state.draftName = stratum.name
    state.undoStack = emptyList()
    state.saveRefusal = null
    state.draft = BoundaryDraft.fromClosedRing(outer)
}

// MARK: - Saving

/// Write the draft. A NEW area is created; an EDITED one is updated in
/// place, keeping its id — which is what keeps the plots it already laid
/// attached to it — and its plots are then re-laid on the new outline (see
/// `relayPlots`). Returns the saved area, or null when it refused (the
/// reason is on `state.saveRefusal`).
internal suspend fun saveAreaDraft(
    state: MapAreaState,
    env: AppEnvironment,
    settings: SettingsSnapshot,
): Stratum? {
    val draft = state.draft ?: return null
    val failure = draft.validationFailure
    if (failure != null) {
        state.saveRefusal = failure.message
        return null
    }
    // An area needs a project to live in. Same door the rest of the map
    // uses when a cruiser acts before naming anything.
    val projectId = state.projectId ?: try {
        createDefaultProject(env, settings).id
    } catch (e: Exception) {
        state.saveRefusal =
            "Couldn't save the area: there is no project to put it in (${e.message ?: e})."
        return null
    }
    val name = state.draftName.trim().ifEmpty { state.defaultName() }
    val stratum = Stratum(
        id = state.editingId ?: UUID.randomUUID(),
        projectId = projectId,
        name = name,
        areaAcres = draft.areaAcres.toFloat(),
        polygonGeoJSON = GeoJSONImporter.serialise(listOf(draft.closedRing)),
    )
    try {
        if (state.editingId != null) {
            env.stratumRepository.update(stratum)
            relayPlots(env, stratum)
        } else {
            env.stratumRepository.create(stratum)
        }
    } catch (e: Exception) {
        state.saveRefusal =
            "Storage error: ${e.message ?: e}. The area was not saved — try again."
        return null
    }
    state.editingId = null
    state.undoStack = emptyList()
    state.saveRefusal = null
    state.draft = null
    state.selectedId = stratum.id
    state.refresh++
    return stratum
}

/// RESIZING AN AREA MOVES ITS PLOTS. An area whose outline changed but
/// whose plots stayed put is the worst of both: a plan that no longer
/// matches the ground it claims to sample, with plots sitting outside the
/// stand.
///
/// The plots are re-laid with the SAME design the cruiser generated them
/// with — the project's stored CruiseDesign, the one Cruise setup wrote —
/// so a resize changes where the plots are and nothing else. Only UNVISITED
/// plans are touched: a planned plot the cruiser has already opened has
/// become a measurement, and no outline drag may move or destroy that.
///
/// An area with no plans yet, or a project with no design yet, has nothing
/// to re-lay and this does nothing.
private suspend fun relayPlots(env: AppEnvironment, stratum: Stratum) {
    val existing = env.plannedPlotRepository.listByProject(stratum.projectId)
        .filter { it.stratumId == stratum.id && !it.visited }
    if (existing.isEmpty()) return
    val design = env.cruiseDesignRepository.forProject(stratum.projectId).firstOrNull() ?: return
    val relaid: List<PlannedPlot> = SamplingGenerator.generate(
        strata = listOf(
            SamplingGenerator.StratumInput(
                stratumId = stratum.id,
                rings = parseRings(stratum.polygonGeoJSON),
            ),
        ),
        options = SamplingGenerator.GenerationOptions(
            projectId = stratum.projectId,
            scheme = design.samplingScheme,
            gridSpacingMeters = design.gridSpacingMeters?.toDouble(),
            // Count-based designs re-lay the number of plots the area is
            // holding right now, which is the number the cruiser asked for
            // the last time they generated into it.
            nPerStratum = existing.size,
            seed = 1uL,
        ),
        startingPlotNumber = existing.minOf { it.plotNumber },
    )
    if (relaid.isEmpty()) return
    for (p in existing) env.plannedPlotRepository.delete(p.id)
    for (p in relaid) env.plannedPlotRepository.create(p)
}

// MARK: - Deleting

internal fun areaDeletionTitle(stratum: Stratum): String = "Delete ${stratum.name}?"

/// The count is the whole point of this sentence: an area and the plan laid
/// inside it go together, and a cruiser who is about to lose a morning's
/// planning must be told how much before they answer.
internal fun areaDeletionMessage(state: MapAreaState, stratum: Stratum): String {
    val n = state.planCounts[stratum.id] ?: 0
    if (n == 0) {
        return "No plots are planned inside it. The outline is removed and nothing else changes."
    }
    val noun = if (n == 1) "plot" else "plots"
    val verb = if (n == 1) "is" else "are"
    return "The $n planned $noun laid inside it $verb deleted with it. " +
        "Plots you have already opened are kept — this only removes the plan."
}

/// UNVISITED plans only. The moment a planned plot is opened it has a real
/// Plot standing on it with a tally inside, and nothing on this screen may
/// take that away.
internal suspend fun deleteArea(
    state: MapAreaState,
    env: AppEnvironment,
    stratum: Stratum,
) {
    try {
        env.plannedPlotRepository.listByProject(stratum.projectId)
            .filter { it.stratumId == stratum.id && !it.visited }
            .forEach { env.plannedPlotRepository.delete(it.id) }
        env.stratumRepository.delete(stratum.id)
    } catch (e: Exception) {
        state.saveRefusal =
            "Storage error: ${e.message ?: e}. The area was not deleted — try again."
        return
    }
    if (state.editingId == stratum.id) state.cancelDraft()
    state.selectedId = null
    state.deleteCandidate = null
    state.refresh++
}

// MARK: - Handles over the map

/// Handles are separate small children of a Box that has NO pointerInput of
/// its own, so every touch that misses one falls straight through to the
/// map underneath and pans it — which is what a cruiser does far more often
/// than they drag a corner.
@Composable
internal fun MapAreaHandles(state: MapAreaState, camera: MapCameraState) {
    val current = state.draft ?: return
    val colors = Forestix.colors
    val targetPx = with(LocalDensity.current) { HandleTarget.toPx() }

    // EDGE MIDPOINTS — drawn first, so a corner handle sitting on top of one
    // always wins the touch.
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
                        colors.cruiseAccent,
                        radius = r,
                        center = center,
                        style = Stroke(width = 3.dp.toPx()),
                    )
                }
                .pointerInput(index) {
                    // Map-space position, accumulated from DELTAS after
                    // being seeded at the gesture's start from the LIVE
                    // geometry. Deriving it from the element's own origin
                    // every frame would chase a handle that is moving under
                    // the finger; a delta is the same number in either space.
                    var cursor = Offset.Zero
                    var insertedIndex = -1
                    detectDragGestures(
                        onDragStart = { start ->
                            insertedIndex = -1
                            val mid = state.draft?.edgeMidpoints?.getOrNull(index)
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
                        val at = camera.coordinateAt(cursor) ?: return@detectDragGestures
                        val base = state.draft ?: return@detectDragGestures
                        if (insertedIndex >= 0) {
                            state.draft = base.moving(insertedIndex, at)
                        } else {
                            // The handle becomes a real corner only now, on
                            // the first movement — a stray touch on an edge
                            // must not silently add a vertex the cruiser
                            // never asked for.
                            state.pushUndo()
                            val (next, created) = base.insertingOnEdge(index, at)
                            state.draft = next
                            insertedIndex = created
                        }
                    }
                },
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
                    drawCircle(colors.cruiseAccent, radius = r, center = center)
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
                            val base = state.draft ?: return@detectTapGestures
                            val next = base.removing(index)
                            // Refused at three corners: the deletion that
                            // would leave a line is the one a gloved thumb
                            // makes by accident. Nothing is pushed onto the
                            // undo stack for a deletion that did not happen.
                            if (next != null) {
                                state.pushUndo()
                                state.draft = next
                                state.suppressCornerDrag = true
                            }
                        },
                    )
                }
                .pointerInput(index) {
                    var cursor = Offset.Zero
                    var started = false
                    detectDragGestures(
                        onDragStart = { start ->
                            started = false
                            val here = state.draft?.vertices?.getOrNull(index)
                                ?.let { camera.screenPoint(it) }
                            cursor = if (here == null) {
                                Offset.Zero
                            } else {
                                here + start - Offset(targetPx / 2, targetPx / 2)
                            }
                        },
                        onDragEnd = { state.suppressCornerDrag = false },
                        onDragCancel = { state.suppressCornerDrag = false },
                    ) { change, amount ->
                        change.consume()
                        if (state.suppressCornerDrag) return@detectDragGestures
                        if (cursor == Offset.Zero) return@detectDragGestures
                        cursor += amount
                        val at = camera.coordinateAt(cursor) ?: return@detectDragGestures
                        val base = state.draft ?: return@detectDragGestures
                        if (!started) {
                            // One undo entry per gesture, captured before
                            // the first millimetre is committed.
                            state.pushUndo()
                            started = true
                        }
                        state.draft = base.moving(index, at)
                    }
                },
        )
    }
}

// MARK: - Anchored callouts

/// THE PIN THE CRUISER PRESSED, with the choices in a bubble over it.
///
/// This replaces a dialog in the middle of the screen. A dialog asks "plan a
/// plot here" while pointing at nothing — the cruiser has to hold the spot
/// in their head and trust that the app held it too. Anchored to the press,
/// the question and the ground it is about are the same object.
///
/// BOTH MODES, but not the same menu in both. "Draw an area" is always here.
/// "Plan a plot here" plans a CRUISE plot, and measure mode's plots — the
/// project-less quick-measure ones — hold no coordinate to plan
/// (`QuickMeasurePlot` has no lat/lon at all), so there is nothing for it to
/// write there. It is left OUT of the measure-mode menu rather than shown
/// and refused: an item that cannot work is the defect this menu was fixed
/// for.
@Composable
internal fun MapPlanCallout(
    cruise: CruiseModeState,
    camera: MapCameraState,
    inCruiseMode: Boolean,
    onDrawArea: (CoordinateConversions.LatLon) -> Unit,
) {
    val at = cruise.planMenuAt ?: return
    val anchor = camera.screenPoint(at) ?: return
    val colors = Forestix.colors
    val dismiss = {
        cruise.planMenuAt = null
        cruise.awaitingPlanPress = false
    }
    val pinPx = with(LocalDensity.current) { 34.dp.toPx() }
    Box(
        Modifier
            .offset {
                IntOffset(
                    (anchor.x - pinPx / 2).roundToInt(),
                    (anchor.y - pinPx).roundToInt(),
                )
            }
            .size(34.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Place,
            contentDescription = "Planning here",
            tint = colors.cruiseAccent,
            modifier = Modifier.size(34.dp),
        )
    }
    MapCallout(anchor = anchor, viewport = camera.viewportSizePx, title = "Plan here") {
        if (inCruiseMode) {
            ForestixProminentButton(
                label = "Plan a plot here",
                modifier = Modifier.fillMaxWidth(),
            ) {
                dismiss()
                cruise.planPlot(at)
            }
        }
        if (inCruiseMode) {
            ForestixBorderedButton(
                label = "Draw an area",
                modifier = Modifier.fillMaxWidth(),
            ) {
                dismiss()
                onDrawArea(at)
            }
        } else {
            ForestixProminentButton(
                label = "Draw an area",
                modifier = Modifier.fillMaxWidth(),
            ) {
                dismiss()
                onDrawArea(at)
            }
        }
        ForestixBorderedButton(label = "Cancel", modifier = Modifier.fillMaxWidth()) { dismiss() }
    }
}

/// What a selected area offers: CRUISE (Cruise setup seeded with this
/// outline), EDIT (back into the in-place editor) and DELETE. Anchored on
/// the area's own centre for the same reason the plan menu is anchored on
/// the press — several areas can be on screen at once, and a card in the
/// middle of the screen would not say which one.
@Composable
internal fun MapAreaCallout(
    state: MapAreaState,
    camera: MapCameraState,
    settings: SettingsSnapshot,
    onCruise: (Stratum) -> Unit,
) {
    val stratum = state.selected ?: return
    val centre = areaCentre(stratum) ?: return
    val anchor = camera.screenPoint(centre) ?: return
    val colors = Forestix.colors
    MapCallout(
        anchor = anchor,
        viewport = camera.viewportSizePx,
        title = stratum.name,
        subtitle = areaSubtitle(state, stratum, settings),
    ) {
        ForestixProminentButton(label = "Cruise this area", modifier = Modifier.fillMaxWidth()) {
            onCruise(stratum)
        }
        ForestixBorderedButton(label = "Edit outline", modifier = Modifier.fillMaxWidth()) {
            beginAreaEdit(state, stratum)
        }
        ForestixBorderedButton(
            label = "Delete area",
            modifier = Modifier.fillMaxWidth(),
            tint = colors.confidenceBad,
        ) {
            state.deleteCandidate = stratum
        }
    }
}

/// Size in the cruiser's own unit, and how much of the plan sits in here —
/// the two numbers that decide whether this is the area they meant to tap.
private fun areaSubtitle(
    state: MapAreaState,
    stratum: Stratum,
    settings: SettingsSnapshot,
): String {
    val unit = settings.unitSystem.areaUnit
    val size = String.format(
        Locale.US, "%.2f %s",
        unit.fromAcres(stratum.areaAcres.toDouble()), unit.abbreviation,
    )
    val n = state.planCounts[stratum.id] ?: 0
    if (n == 0) return "$size · no plots planned"
    return "$size · $n plot${if (n == 1) "" else "s"} planned"
}

/// Mean of the outer ring's corners, in ENU about the first corner so the
/// anchor lands inside the shape rather than wherever naive lon/lat
/// averaging would put it.
private fun areaCentre(stratum: Stratum): CoordinateConversions.LatLon? {
    val outer = try {
        parseRings(stratum.polygonGeoJSON).firstOrNull()
    } catch (_: Exception) {
        null
    } ?: return null
    if (outer.size < 3) return null
    val origin = outer.first()
    val plane = outer.map { CoordinateConversions.toENU(it, origin) }
    return CoordinateConversions.toLatLon(
        CoordinateConversions.ENU(
            east = plane.sumOf { it.east } / plane.size,
            north = plane.sumOf { it.north } / plane.size,
        ),
        origin,
    )
}

/// A bubble anchored ABOVE a point on the map, with a pointer aimed back at
/// it — the shape a cruiser expects from a mission planner, and the one
/// thing a centred dialog cannot be.
///
/// It clamps itself into the viewport horizontally, and flips BELOW the
/// anchor when there is not enough room above, so a press near the top of
/// the screen still gets a readable menu. Both are ordinary in the field:
/// the ground a cruiser wants is very often at the edge of what is framed.
@Composable
private fun MapCallout(
    anchor: Offset,
    viewport: IntSize,
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val density = LocalDensity.current
    val widthPx = with(density) { 240.dp.toPx() }
    val gapPx = with(density) { 26.dp.toPx() }
    val marginPx = with(density) { 12.dp.toPx() }
    var measured by remember { mutableStateOf(IntSize.Zero) }
    val below = anchor.y - measured.height - gapPx < marginPx
    val clampedX = anchor.x.coerceIn(
        widthPx / 2 + marginPx,
        maxOf(viewport.width - widthPx / 2 - marginPx, widthPx / 2 + marginPx),
    )
    Column(
        Modifier
            .offset {
                IntOffset(
                    (clampedX - widthPx / 2).roundToInt(),
                    if (below) {
                        (anchor.y + gapPx).roundToInt()
                    } else {
                        (anchor.y - gapPx - measured.height).roundToInt()
                    },
                )
            }
            .width(240.dp)
            .onSizeChanged { measured = it }
            .clip(ForestixRadius.card)
            .background(colors.surface)
            .border(1.dp, colors.divider, ForestixRadius.card)
            .padding(ForestixSpace.sm),
        verticalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
    ) {
        Text(title, style = type.bodyBold, color = colors.textPrimary)
        if (subtitle != null) {
            Text(subtitle, style = type.caption, color = colors.textSecondary)
        }
        content()
    }
}

// MARK: - The draft's control bar

/// Replaces the action cluster while an outline is being dragged. It
/// carries what BoundaryDrawScreen's panel carries — live area, the corner
/// count, undo, the name, and a refusal the moment the shape becomes
/// invalid rather than at the moment Save is pressed — because the shape is
/// still under the thumb that caused it.
@Composable
internal fun MapAreaDraftBar(
    state: MapAreaState,
    settings: SettingsSnapshot,
    onSave: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val draft = state.draft ?: return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = ForestixSpace.sm)
            .clip(ForestixRadius.card)
            .background(colors.surface)
            .padding(ForestixSpace.md),
        verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
    ) {
        Text(
            "Drag a corner to move it. Drag the small handle on an edge to add a corner. " +
                "Press and hold a corner to delete it.",
            style = type.caption,
            color = colors.textSecondary,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                // Live area in the cruiser's OWN unit system. A dash rather
                // than "0.00 ac" while the shape has no defined interior: a
                // number computed from a self-crossing outline is a number
                // that means nothing.
                val unit = settings.unitSystem.areaUnit
                Text(
                    if (draft.isValid) {
                        String.format(
                            Locale.US, "Area %.2f %s",
                            unit.fromAcres(draft.areaAcres), unit.abbreviation,
                        )
                    } else {
                        "Area —"
                    },
                    style = type.bodyBold,
                    color = colors.textPrimary,
                )
                val count = draft.vertices.size
                Text(
                    if (count == 1) "1 corner" else "$count corners",
                    style = type.caption,
                    color = colors.textSecondary,
                )
            }
            TextButton(
                onClick = {
                    val previous = state.undoStack.lastOrNull() ?: return@TextButton
                    state.undoStack = state.undoStack.dropLast(1)
                    state.draft = previous
                    state.saveRefusal = null
                },
                enabled = state.undoStack.isNotEmpty(),
            ) {
                Text("Undo", style = type.body, color = colors.primary)
            }
        }
        val refusal = draft.validationFailure?.message ?: state.saveRefusal
        if (refusal != null) {
            Text(refusal, style = type.caption, color = colors.confidenceBad)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(ForestixRadius.control)
                .background(colors.surfaceRaised)
                .border(1.dp, colors.divider, ForestixRadius.control)
                .padding(horizontal = 11.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = state.draftName,
                onValueChange = { state.draftName = it },
                singleLine = true,
                textStyle = type.body.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ForestixSpace.sm)) {
            ForestixBorderedButton(label = "Cancel", modifier = Modifier.weight(1f)) {
                state.cancelDraft()
            }
            ForestixProminentButton(
                label = if (state.editingId == null) "Save area" else "Save changes",
                modifier = Modifier.weight(1f),
                enabled = draft.isValid,
                onClick = onSave,
            )
        }
    }
}

/// The overlap toggle, said once. Sits where the plan prompt sits and is
/// dismissed by the same tap that reads it.
@Composable
internal fun MapAreaStackHintBanner(state: MapAreaState, topPaddingDp: androidx.compose.ui.unit.Dp) {
    val hint = state.stackHint ?: return
    val colors = Forestix.colors
    val type = Forestix.type
    Row(
        Modifier
            .padding(top = topPaddingDp, start = ForestixSpace.sm, end = ForestixSpace.sm)
            .fillMaxWidth()
            .clip(ForestixRadius.control)
            .background(colors.surface)
            .border(1.dp, colors.divider, ForestixRadius.control)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
    ) {
        Icon(
            Icons.Filled.Layers,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            hint,
            style = type.caption.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { state.stackHint = null }) {
            Text("Got it", style = type.body, color = colors.primary)
        }
    }
}

/// AREA DELETED, step two. Never silent and never vague — see
/// `areaDeletionMessage`.
@Composable
internal fun MapAreaDeleteDialog(state: MapAreaState, onConfirm: (Stratum) -> Unit) {
    val stratum = state.deleteCandidate ?: return
    val colors = Forestix.colors
    val type = Forestix.type
    AlertDialog(
        onDismissRequest = { state.deleteCandidate = null },
        containerColor = colors.surface,
        title = {
            Text(areaDeletionTitle(stratum), style = type.bodyBold, color = colors.textPrimary)
        },
        text = {
            Text(
                areaDeletionMessage(state, stratum),
                style = type.caption,
                color = colors.textSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(stratum) }) {
                Text("Delete area", style = type.body, color = colors.confidenceBad)
            }
        },
        dismissButton = {
            TextButton(onClick = { state.deleteCandidate = null }) {
                Text("Cancel", style = type.body, color = colors.textPrimary)
            }
        },
    )
}

/// A refusal the cruiser has to see: an area that did not persist must
/// never look like one that did. Only while no draft is open — mid-drag the
/// draft bar already carries the sentence.
@Composable
internal fun MapAreaRefusalDialog(state: MapAreaState) {
    val message = state.saveRefusal ?: return
    if (state.draft != null) return
    val colors = Forestix.colors
    val type = Forestix.type
    AlertDialog(
        onDismissRequest = { state.saveRefusal = null },
        containerColor = colors.surface,
        title = {
            Text("Couldn't save the area", style = type.bodyBold, color = colors.textPrimary)
        },
        text = { Text(message, style = type.caption, color = colors.textSecondary) },
        confirmButton = {
            TextButton(onClick = { state.saveRefusal = null }) {
                Text("OK", style = type.body, color = colors.primary)
            }
        },
    )
}
