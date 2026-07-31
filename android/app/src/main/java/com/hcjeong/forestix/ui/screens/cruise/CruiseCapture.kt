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
import com.hcjeong.forestix.data.TreeNameSequence
import com.hcjeong.forestix.data.cruise.Plot
import com.hcjeong.forestix.data.cruise.Project
import com.hcjeong.forestix.data.cruise.Tree
import com.hcjeong.forestix.data.cruise.TreeLabel
import com.hcjeong.forestix.data.cruise.TreeStatus
import com.hcjeong.forestix.positioning.CLLocationSnapshot
import com.hcjeong.forestix.positioning.GeoMath
import com.hcjeong.forestix.sensors.DBHResult
import com.hcjeong.forestix.sensors.HeightResult
import com.hcjeong.forestix.sensors.ProjectCalibration
import com.hcjeong.forestix.ui.PendingTreeNumber
import com.hcjeong.forestix.sensors.DBHMethod
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
        /// The name the NEXT tallied tree will be saved under, stepped on by
        /// [TreeNameSequence] after every save exactly as the number is. Null
        /// on a plot nobody has named — the loop then stays zero-typing and
        /// the trees are labelled by number, which is what cruise mode has
        /// always done. The tally pill is where the cruiser sets or clears it.
        val treeName: String? = null,
        val plotCenterLat: Double,
        val plotCenterLon: Double,
    ) {
        /// What the tally chrome calls the tree it is ABOUT to write. One
        /// rule, shared with every saved tree's `displayTitle`.
        val treeTitle: String get() = TreeLabel.title(treeName, treeNumber)
    }

    @Volatile
    var target: Target? = null
        private set

    /// The Tree row the DBH leg created — the Height leg updates it.
    @Volatile
    private var savedTreeId: UUID? = null

    /// What that row is CALLED. Tracked beside the id rather than read back
    /// off `target`, because in the diameter → height chain (F10) the tally
    /// has already advanced its target to the next tree: `target.treeName` is
    /// the name of the tree not yet measured, and labelling the height leg
    /// with it would name the wrong stem. Null when the row was never named,
    /// or when there is no row. iOS reads the same thing off the scoped row
    /// (`chainHeightTreeName`).
    @Volatile
    var savedTreeName: String? = null
        private set

    val isActive: Boolean get() = target != null

    /// Arm the session and publish the project's REAL calibration into the
    /// shared scan screens (quick measure keeps identity — iOS parity).
    fun begin(
        env: AppEnvironment,
        project: Project,
        plot: Plot,
        treeNumber: Int,
        treeName: String? = null,
    ) {
        target = Target(
            projectId = project.id,
            plotId = plot.id,
            plotNumber = plot.plotNumber,
            treeNumber = treeNumber,
            treeName = treeName,
            plotCenterLat = plot.centerLat,
            plotCenterLon = plot.centerLon,
        )
        savedTreeId = null
        savedTreeName = null
        // Number only, still: the cruise tree's name is written straight onto
        // the Tree row by [recordDbh] below, and the PendingTreeNumber lock is
        // the QUICK-MEASURE handoff — a cruise reading never becomes a
        // QuickMeasureEntry, so a name left in that lock would only be there
        // to leak into an unrelated quick-measure capture later.
        PendingTreeNumber.set(number = treeNumber)
        env.activeScanCalibration.value = ProjectCalibration(
            depthNoiseMm = project.depthNoiseMm,
            dbhCorrectionAlpha = project.dbhCorrectionAlpha,
            dbhCorrectionBeta = project.dbhCorrectionBeta,
            dbhCalibrationEpoch = project.dbhCalibrationEpoch,
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
            // The EXISTING row's name — this session measures a tree that is
            // already on the books, so the height screen has to call it what
            // the map and the tree peek call it.
            treeName = tree.treeName,
            plotCenterLat = plot.centerLat,
            plotCenterLon = plot.centerLon,
        )
        savedTreeId = tree.id
        savedTreeName = tree.treeName
        PendingTreeNumber.set(number = tree.treeNumber)
        env.activeScanCalibration.value = ProjectCalibration(
            depthNoiseMm = project.depthNoiseMm,
            dbhCorrectionAlpha = project.dbhCorrectionAlpha,
            dbhCorrectionBeta = project.dbhCorrectionBeta,
            dbhCalibrationEpoch = project.dbhCalibrationEpoch,
            vioDriftFraction = project.vioDriftFraction,
        )
    }

    /// Quick-tally loop: after a cruise DBH Accept the session STAYS armed
    /// for the plot's next tree — bump the target number in place (the
    /// just-saved row stays remembered for `undoLastTally`). Returns the
    /// new target number, or null when no session is active.
    ///
    /// [saved] is whether the Accept actually wrote a row. The NUMBER advances
    /// either way (it always has), but the NAME advances only on a save that
    /// landed: re-deriving it from a plot the row never reached would resolve
    /// the series WITHOUT the name the cruiser typed and quietly throw it
    /// away. Left alone, the next Accept reuses it — which is what the pill is
    /// still promising on screen. iOS gates it the same way.
    suspend fun advanceTally(env: AppEnvironment, saved: Boolean): Int? {
        val t = target ?: return null
        val next = t.treeNumber + 1
        target = t.copy(
            treeNumber = next,
            treeName = if (saved) nextTreeName(env, t.plotId) else t.treeName,
        )
        return next
    }

    /// The name to offer for the next tree in this plot — the HIGHEST name in
    /// the series the cruiser is using here, stepped on by [TreeNameSequence].
    /// Null on a plot whose trees have never been named, and the tally then
    /// stays zero-typing and labels by number, exactly as cruise always has.
    ///
    /// The SAME rule the quick-measure chooser offers
    /// (`QuickMeasureHistory.suggestedNextTreeName`), through the same helper
    /// rather than a second copy of it: the two worlds have to agree on what
    /// follows "Plot3-T07", because a split cruise joins on the name.
    /// [TreeNameSequence.nextInSeries] wants the names newest-first, so the
    /// plot's trees are ordered by creation before their names are read —
    /// repository order is not a promise. Soft-deleted rows are excluded, so
    /// a deleted tree never holds a name hostage.
    suspend fun nextTreeName(env: AppEnvironment, plotId: UUID): String? =
        nextTreeName(
            runCatching { env.treeRepository.listByPlot(plotId) }
                .getOrDefault(emptyList()))

    /// The same rule against a plot's trees already in hand — the plot peek
    /// labels its "Add tree" button from its own list rather than going back
    /// to the repository for one string.
    fun nextTreeName(trees: List<Tree>): String? {
        val live = trees.filter { it.deletedAt == null }
            .sortedByDescending { it.createdAt }
        val proposed =
            TreeNameSequence.nextInSeries(live.mapNotNull { it.treeName })
                ?: return null
        // NEVER propose a name a stem in this plot already wears.
        //
        // [TreeNameSequence.next] deliberately hands a name with no trailing
        // number back UNCHANGED rather than inventing "Big oak2" — the app
        // cannot tell whether "Big oak" starts a series, so the quick-measure
        // chooser shows it again and the cruiser retypes it. The tally loop
        // never stops to ask: it would accept "Big oak", "Big oak", "Big oak"
        // down the whole plot, and tree_name is the column a cruise split
        // across two phones joins on. Dropping back to null labels the next
        // stem "Tree #<n>" instead, and the pill is one tap away.
        if (live.any { it.treeName == proposed }) return null
        return proposed
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
        savedTreeName = null
        target = t.copy(
            treeNumber = tree.treeNumber,
            // Stepped back with the number, and re-derived from the plot for
            // the same reason: the undone row is gone, so the series resolves
            // to the name that row had. Reusing the name the loop had already
            // advanced to would leave a gap nothing ever fills.
            treeName = nextTreeName(env, t.plotId),
        )
        return tree
    }

    /// Tally pill → rename, committed. An emptied field CLEARS the name rather
    /// than storing "": the tree then falls back to "Tree #<n>", which is the
    /// only way back to an unnamed tally once a series has been started.
    ///
    /// Trimmed before it is stored, exactly as `PendingTreeNumber.set` and
    /// iOS's `lockChooserTree()` do — the name is a join key for a split
    /// cruise, and a gloved thumb must not create " Plot3-T07" here and
    /// "Plot3-T07" there.
    fun renameTarget(name: String?) {
        val t = target ?: return
        target = t.copy(treeName = name?.trim()?.takeIf { it.isNotEmpty() })
    }

    /// Disarm (chain finished OR abandoned) and restore the identity
    /// calibration for the quick-measure world. Idempotent.
    fun end(env: AppEnvironment) {
        if (target == null && savedTreeId == null) return
        target = null
        savedTreeId = null
        savedTreeName = null
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
        /// "auto" | "manual" | "typed" — which estimator found the edges.
        captureMode: String? = null,
    ): UUID? {
        val t = target ?: return null
        savedTreeId = null
        savedTreeName = null
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
            // The name the pill was showing when the cruiser accepted. Null is
            // the norm and stays the norm — an unnamed cruise tree is labelled
            // by number and exports an empty tree_name, as it always has.
            treeName = t.treeName,
            speciesCode = species,
            status = TreeStatus.LIVE,
            dbhCm = r.diameterCm,
            dbhMethod = r.method,
            // A TYPED diameter has no propagated uncertainty — there is no
            // geometry to propagate. Recording 0 would claim a perfect
            // measurement and poison the sigma column the accuracy work
            // reads. Same rule as the height path.
            dbhSigmaMm = if (r.method == DBHMethod.MANUAL_VISUAL) null else r.sigmaRmm,
            dbhRmseMm = r.rmseMm,
            dbhCoverageDeg = r.arcCoverageDeg,
            dbhNInliers = r.nInliers,
            dbhConfidence = r.confidence,
            dbhIsIrregular = false,
            // Which estimator produced this diameter. Without it a corpus
            // mixing bracket and auto fits cannot be split at analysis time,
            // and the bracket is now the default path.
            dbhCaptureMode = captureMode,
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
        savedTreeName = tree.treeName
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
