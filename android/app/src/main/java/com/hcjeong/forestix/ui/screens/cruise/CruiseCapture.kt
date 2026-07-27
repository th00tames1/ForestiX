// Cruise tally-loop handoff — the cruise-mode analogue of PendingTreeNumber
// (v3 redesign, design/forestix-redesign-v3-cruise.html screen ③).
//
// The map home's cruise mode `begin()`s a session before launching the
// SHARED DBH screen ("dbh?chain=true"); while a session is active the scan
// screens route their Accept through `recordDbh` / `recordHeight` INSTEAD
// of QuickMeasureHistory.append — the accepted reading (value + σ +
// metadata + GPS + auto-photo) lands on a cruise `Tree` row in the active
// plot, and the quick-measure world never sees it (the two data worlds
// stay separate; no quick pin is dropped).
//
// QUICK-TALLY LOOP (field-benchmark batch): the cruise DBH screen is a
// DIAMETER LOOP — each Accept `recordDbh`s, then `advanceTally()` bumps
// the target to the plot's next tree number and the screen resets to
// aiming. `undoLastTally()` backs the just-saved row out (hard delete; the
// screen deletes its photo) and steps the auto number back — the Undo
// toast's action. Per-tree height on demand runs through `beginHeight()`
// (tree peek / plot heights sheet): a HEIGHT-ONLY session on an EXISTING
// Tree row, so the Height screen's cruise branch folds the reading into
// that row and pops back to the map.
//
// DIAMETER → HEIGHT CHAIN (field report F10): with
// `AppSettings.measureHeightAfterDiameter` on (the DEFAULT) the tally's
// Accept also PUSHES Height for the tree it just wrote, on top of the DBH
// screen. `savedTreeId` still points at that row — `advanceTally()` only
// moves the target NUMBER — so `recordHeight` folds into the right tree
// even though the tally has already moved on. Height's Accept and its Skip
// both pop straight back onto the tally, already aiming at the next tree.
// With the setting off the loop behaves exactly as it did.
//
// MapHomeScreen calls `end()` on every (re)entry (every cruise capture
// flow pops back to it), which also restores the identity scan calibration
// `begin()` replaced with the project's real ProjectCalibration (the
// publish/restore contract the retired add-tree flow established).

package com.hcjeong.forestix.ui.screens.cruise

import com.hcjeong.forestix.AppEnvironment
import com.hcjeong.forestix.data.cruise.Plot
import com.hcjeong.forestix.data.cruise.Project
import com.hcjeong.forestix.data.cruise.Tree
import com.hcjeong.forestix.data.cruise.TreeStatus
import com.hcjeong.forestix.positioning.CLLocationSnapshot
import com.hcjeong.forestix.positioning.GeoMath
import com.hcjeong.forestix.sensors.DBHResult
import com.hcjeong.forestix.sensors.HeightResult
import com.hcjeong.forestix.sensors.ProjectCalibration
import com.hcjeong.forestix.ui.PendingTreeNumber
import java.util.UUID
import kotlin.math.PI

object CruiseCapture {

    /// What the (+) promised: which plot the chain is scoped to and the
    /// auto tree number already shown on the button ("Add tree · Plot N").
    data class Target(
        val projectId: UUID,
        val plotId: UUID,
        val plotNumber: Int,
        val treeNumber: Int,
        val plotCenterLat: Double,
        val plotCenterLon: Double,
    )

    @Volatile
    var target: Target? = null
        private set

    /// The Tree row the DBH leg created — the Height leg updates it.
    @Volatile
    private var savedTreeId: UUID? = null

    val isActive: Boolean get() = target != null

    /// Arm the session and publish the project's REAL calibration into the
    /// shared scan screens (quick measure keeps identity — iOS parity).
    fun begin(env: AppEnvironment, project: Project, plot: Plot, treeNumber: Int) {
        target = Target(
            projectId = project.id,
            plotId = plot.id,
            plotNumber = plot.plotNumber,
            treeNumber = treeNumber,
            plotCenterLat = plot.centerLat,
            plotCenterLon = plot.centerLon,
        )
        savedTreeId = null
        PendingTreeNumber.value = treeNumber
        env.activeScanCalibration.value = ProjectCalibration(
            depthNoiseMm = project.depthNoiseMm,
            dbhCorrectionAlpha = project.dbhCorrectionAlpha,
            dbhCorrectionBeta = project.dbhCorrectionBeta,
            vioDriftFraction = project.vioDriftFraction,
        )
    }

    /// Height-on-demand (tree peek "Measure height" / plot heights sheet):
    /// arm a HEIGHT-ONLY session on an EXISTING Tree row. `recordHeight`
    /// then folds the accepted reading into that row; the Height screen's
    /// cruise branch pops straight back to the map, whose (re)entry runs
    /// `end()`. Same calibration publish as `begin()`.
    fun beginHeight(env: AppEnvironment, project: Project, plot: Plot, tree: Tree) {
        target = Target(
            projectId = project.id,
            plotId = plot.id,
            plotNumber = plot.plotNumber,
            treeNumber = tree.treeNumber,
            plotCenterLat = plot.centerLat,
            plotCenterLon = plot.centerLon,
        )
        savedTreeId = tree.id
        PendingTreeNumber.value = tree.treeNumber
        env.activeScanCalibration.value = ProjectCalibration(
            depthNoiseMm = project.depthNoiseMm,
            dbhCorrectionAlpha = project.dbhCorrectionAlpha,
            dbhCorrectionBeta = project.dbhCorrectionBeta,
            vioDriftFraction = project.vioDriftFraction,
        )
    }

    /// Quick-tally loop: after a cruise DBH Accept the session STAYS armed
    /// for the plot's next tree — bump the target number in place (the
    /// just-saved row stays remembered for `undoLastTally`). Returns the
    /// new target number, or null when no session is active.
    fun advanceTally(): Int? {
        val t = target ?: return null
        val next = t.treeNumber + 1
        target = t.copy(treeNumber = next)
        return next
    }

    /// Undo toast action: HARD-delete the just-saved tally row and step the
    /// auto number back to it. Returns the deleted Tree (the caller removes
    /// its photo — MeasurePhotoStore needs an Activity), or null when there
    /// is nothing to undo / the delete failed.
    suspend fun undoLastTally(env: AppEnvironment): Tree? {
        val t = target ?: return null
        val id = savedTreeId ?: return null
        val tree = runCatching { env.treeRepository.read(id) }.getOrNull() ?: return null
        runCatching { env.treeRepository.hardDelete(id) }.getOrElse { return null }
        savedTreeId = null
        target = t.copy(treeNumber = tree.treeNumber)
        return tree
    }

    /// Disarm (chain finished OR abandoned) and restore the identity
    /// calibration for the quick-measure world. Idempotent.
    fun end(env: AppEnvironment) {
        if (target == null && savedTreeId == null) return
        target = null
        savedTreeId = null
        env.activeScanCalibration.value = ProjectCalibration.identity
    }

    /// DBH Accept (cruise session): create the cruise Tree row with the
    /// full scan pedigree, the Accept snapshot, and the GPS fix. Species
    /// defaults to the plot's most recent tree (mock rule "species = last
    /// used"); bearing/distance from the plot centre are auto-computed
    /// from the fix (replaces the retired Extras step's manual fields).
    ///
    /// Returns the id of the row that was written, or null when there is no
    /// session. The DIAMETER → HEIGHT chain (field report F10) uses this:
    /// there is no point opening Height when no row exists for the height to
    /// land on. `savedTreeId` is CLEARED before the write attempt, so a
    /// throwing `create` can never leave the previous tree armed for the
    /// height leg or for Undo.
    suspend fun recordDbh(
        env: AppEnvironment,
        r: DBHResult,
        speciesCode: String?,
        damageCodes: List<String>,
        note: String,
        photoPath: String?,
        fix: CLLocationSnapshot?,
    ): UUID? {
        val t = target ?: return null
        savedTreeId = null
        val now = System.currentTimeMillis()
        val species = speciesCode
            ?: env.treeRepository.listByPlot(t.plotId)
                .maxByOrNull { it.createdAt }?.speciesCode
            ?: ""
        var bearing: Float? = null
        var dist: Float? = null
        if (fix != null && (t.plotCenterLat != 0.0 || t.plotCenterLon != 0.0)) {
            dist = GeoMath.distanceM(
                t.plotCenterLat, t.plotCenterLon, fix.latitude, fix.longitude).toFloat()
            bearing = GeoMath.bearingDeg(
                t.plotCenterLat, t.plotCenterLon, fix.latitude, fix.longitude).toFloat()
        }
        val tree = Tree(
            id = UUID.randomUUID(),
            plotId = t.plotId,
            treeNumber = t.treeNumber,
            speciesCode = species,
            status = TreeStatus.LIVE,
            dbhCm = r.diameterCm,
            dbhMethod = r.method,
            dbhSigmaMm = r.sigmaRmm,
            dbhRmseMm = r.rmseMm,
            dbhCoverageDeg = r.arcCoverageDeg,
            dbhNInliers = r.nInliers,
            dbhConfidence = r.confidence,
            dbhIsIrregular = false,
            heightM = null,
            heightMethod = null,
            heightSource = null,
            heightSigmaM = null,
            heightDHM = null,
            heightAlphaTopDeg = null,
            heightAlphaBaseDeg = null,
            heightConfidence = null,
            bearingFromCenterDeg = bearing,
            distanceFromCenterM = dist,
            boundaryCall = null,
            crownClass = null,
            damageCodes = damageCodes,
            isMultistem = false,
            parentTreeId = null,
            notes = note,
            photoPath = photoPath,
            rawScanPath = null,
            latitude = fix?.latitude,
            longitude = fix?.longitude,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        )
        env.treeRepository.create(tree)
        savedTreeId = tree.id
        return tree.id
    }

    /// Height Accept (cruise session): fold the height leg into the Tree
    /// row the DBH leg created.
    ///
    /// Returns TRUE only when the reading actually reached the row. Field
    /// fix: this used to return Unit and the caller swallowed every failure
    /// in a `runCatching {}` before popping back to the map — so a missing
    /// session, an unreadable row or a failed update looked exactly like a
    /// success, and the tree peek went on showing DBH alone with nothing
    /// anywhere saying the height had been dropped. The caller now keeps
    /// the result on screen and says so when this returns false.
    suspend fun recordHeight(
        env: AppEnvironment,
        r: HeightResult,
        photoPath: String?,
        fix: CLLocationSnapshot?,
    ): Boolean {
        if (target == null) return false
        val tree = savedTreeId
            ?.let { runCatching { env.treeRepository.read(it) }.getOrNull() }
            ?: return false
        tree.heightM = r.heightM
        tree.heightMethod = r.method
        tree.heightSource = "measured"
        tree.heightSigmaM = r.sigmaHm
        tree.heightDHM = r.dHm
        tree.heightAlphaTopDeg = (r.alphaTopRad * 180.0 / PI).toFloat()
        tree.heightAlphaBaseDeg = (r.alphaBaseRad * 180.0 / PI).toFloat()
        tree.heightConfidence = r.confidence
        if (tree.photoPath == null) tree.photoPath = photoPath
        if (tree.latitude == null && fix != null) {
            tree.latitude = fix.latitude
            tree.longitude = fix.longitude
        }
        tree.updatedAt = System.currentTimeMillis()
        return runCatching { env.treeRepository.update(tree) }.isSuccess
    }
}
