// Cruise tally-loop handoff — the cruise-mode analogue of PendingTreeNumber
// (v3 redesign, design/forestix-redesign-v3-cruise.html screen ③).
//
// CruiseMapScreen `begin()`s a session before launching the SHARED
// DBH→Height full-measurement chain ("dbh?chain=true"); while a session is
// active the scan screens route their Accept through `recordDbh` /
// `recordHeight` INSTEAD of QuickMeasureHistory.append — the accepted
// reading (value + σ + metadata + GPS + auto-photo) lands on a cruise
// `Tree` row in the active plot, and the quick-measure world never sees it
// (the two data worlds stay separate; no quick pin is dropped).
//
// The DBH leg CREATES the Tree row (height nullable), the Height leg
// UPDATES it — so a cruiser who backs out of the height leg still keeps a
// valid DBH-only tree. CruiseMapScreen calls `end()` on every (re)entry,
// which also restores the identity scan calibration `begin()` replaced
// with the project's real ProjectCalibration (same publish/restore
// contract as AddTreeFlowRetained).

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
    suspend fun recordDbh(
        env: AppEnvironment,
        r: DBHResult,
        speciesCode: String?,
        damageCodes: List<String>,
        note: String,
        photoPath: String?,
        fix: CLLocationSnapshot?,
    ) {
        val t = target ?: return
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
    }

    /// Height Accept (cruise session): fold the height leg into the Tree
    /// row the DBH leg created. No-op when that row doesn't exist (the
    /// chain always runs DBH first).
    suspend fun recordHeight(
        env: AppEnvironment,
        r: HeightResult,
        photoPath: String?,
        fix: CLLocationSnapshot?,
    ) {
        if (target == null) return
        val tree = savedTreeId?.let { env.treeRepository.read(it) } ?: return
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
        env.treeRepository.update(tree)
    }
}
