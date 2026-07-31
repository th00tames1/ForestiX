// RE-DERIVING CRUISE DIAMETERS FROM THE CAPTURES THAT PRODUCED THEM.
//
// CROSS-PLATFORM: the selection rule, the match tolerance, the skip reasons and
// every user-visible string are byte-identical to the iOS sibling
// (App/DBHEpochRecompute.swift).
//
// WHAT MOVED. `DBHEstimator.ESTIMATOR_EPOCH` counts what the estimators
// compute. Epoch 3 solves the silhouette as a CYLINDER — the bracket edges are
// tangent points, and the depth median sits behind the near face — where epoch
// 2 solved it as a chord through the axis. That is a few per cent on every
// stem, and a diameter measured before it is a different quantity from one
// measured after.
//
// WHY THE ROW CANNOT BE FIXED IN PLACE. A cruise `Tree` stores `dbhCm` and
// nothing the identity needs: no span, no depth, no focal length. There is no
// arithmetic that turns an epoch-2 diameter into an epoch-3 one. The
// raw-capture bundle, however, holds the depth frames, the intrinsics, the
// bracket and the calibration, so the ONLY honest recomputation is to run the
// current estimator over the stored bytes — which is what `RawCaptureReplay`
// already does, and what this calls. No maths lives in this file.
//
// WHAT THAT MEANS FOR A NUMBER THAT DOES NOT MOVE. The live diameter is an
// aggregate over the burst's sub-samples; the bundle keeps at most five frames
// of that window and the replay takes the estimator's median over those. Two
// honest computations of one capture, so they land about a per cent apart even
// when the estimator has not changed at all. A tree already stamped at the
// current epoch is therefore SKIPPED rather than re-derived: re-running it
// would replace a burst aggregate with a five-frame median and call the
// difference a correction.
//
// HEIGHTS ARE NOT TOUCHED. Nothing here reads a height bundle.

package com.hcjeong.forestix.data.cruise

import android.content.Context
import com.hcjeong.forestix.sensors.DBHEstimator
import com.hcjeong.forestix.sensors.DBHMethod
import com.hcjeong.forestix.sensors.RawCaptureReplay
import com.hcjeong.forestix.sensors.RawCaptureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

object DBHEpochRecompute {

    // MARK: - Which bundle produced the stored number

    /// How close a bundle's recorded live result must sit to the tree's stored
    /// diameter before that bundle is accepted as the capture the number came
    /// from.
    ///
    /// It cannot be exact. `result_live` is the estimator over the five frames
    /// the bundle keeps; the stored diameter is the aggregate over the whole
    /// burst window (see the DBH scan screen's sampling loop). The two land "a
    /// percent or so apart" on the validation corpus, and 2 % is that with room
    /// for the burst noise on top.
    ///
    /// It is deliberately NOT wide. The job of this number is to separate the
    /// capture that produced the reading from the RETAKES sitting beside it,
    /// and a window wide enough to admit two of them admits the discarded one.
    /// When it does admit two, that is reported as ambiguous and the tree is
    /// left alone — never resolved by taking the newest.
    const val RELATIVE_MATCH_TOLERANCE: Double = 0.02

    /// Two copies of one stored diameter are the same value inside this band.
    /// `dbhCm` is a Float that round-trips through Room untouched, so the only
    /// difference between the planned value and the one on disk is the
    /// Double↔Float conversion — a thousandth of a centimetre is orders of
    /// magnitude more than that and orders of magnitude less than any edit.
    const val STORED_VALUE_EPSILON: Double = 0.001

    // MARK: - Why a tree was left alone

    /// Every tree the sweep saw is either a [Change] or carries one of these,
    /// so the counts on screen add up to the trees on the device.
    sealed class SkipReason(val groupOrder: Int) {

        /// What the console prints. Byte-identical to iOS.
        abstract val text: String

        /// Already carries the epoch this recompute would stamp. Makes the
        /// action idempotent, and keeps a burst aggregate from being replaced
        /// by a five-frame median for no gain (see the file header).
        object AlreadyCurrentEpoch : SkipReason(0) {
            override val text: String
                get() = "already at estimator epoch ${DBHEstimator.ESTIMATOR_EPOCH}"
        }

        /// Hand-entered. The cruiser looked at the stem and typed a number; no
        /// capture stands behind it and replaying one cannot overturn it.
        object TypedDiameter : SkipReason(1) {
            override val text = "typed diameter — no capture behind it"
        }

        /// Another row in the same plot carries the same tree number, so the
        /// triple a bundle names does not pick out one tree. Both rows would
        /// otherwise be rewritten from the one capture.
        object DuplicateTreeNumber : SkipReason(2) {
            override val text = "ambiguous: two trees share this plot and tree number"
        }

        /// Nothing on disk carries this tree's (project, plot, tree number).
        object NoBundle : SkipReason(3) {
            override val text = "no raw capture for this tree"
        }

        /// Bundles exist, but none holds the value the row holds — so which
        /// capture the row came from is not known.
        data class AmbiguousNoneMatches(val bundles: Int) : SkipReason(4) {
            override val text = "ambiguous: $bundles bundles, none matches the stored value"
        }

        /// More than one bundle holds the row's value. A retake that agrees
        /// with the reading it replaced is exactly the case where guessing
        /// would be invisible.
        data class AmbiguousManyMatch(val matches: Int) : SkipReason(5) {
            override val text = "ambiguous: $matches bundles match the stored value"
        }

        /// The bytes would not reconstruct — a missing depth file, a truncated
        /// write, an estimator that returns nothing.
        object ReplayFailed : SkipReason(6) {
            override val text = "bundle could not be replayed"
        }

        /// The replay ran and the current estimator REJECTED the fit. A
        /// rejected fit is not a diameter and must not be written as one.
        object ReplayRejected : SkipReason(7) {
            override val text = "replayed fit was rejected"
        }
    }

    // MARK: - One planned rewrite

    data class Change(
        val treeId: UUID,
        /// What every cruise surface calls this tree — `TreeLabel.title`.
        val treeLabel: String,
        val treeNumber: Int,
        /// The bundle the stored number was matched to, and the one replayed.
        val bundleId: String,
        val storedCm: Double,
        val recomputedCm: Double,
    ) {
        val deltaCm: Double get() = recomputedCm - storedCm
    }

    data class Skip(
        val treeId: UUID,
        val treeLabel: String,
        val treeNumber: Int,
        val reason: SkipReason,
    )

    /// One line of the grouped skip list.
    data class SkipGroup(val reason: SkipReason, val count: Int)

    /// What a run WOULD do, computed before anything is written.
    data class Plan(
        val changes: List<Change> = emptyList(),
        val skips: List<Skip> = emptyList(),
    ) {
        val isEmpty: Boolean get() = changes.isEmpty()
        val treesSeen: Int get() = changes.size + skips.size

        /// Signed mean of the diameter changes, cm. Zero when nothing moves.
        val meanDeltaCm: Double
            get() = if (changes.isEmpty()) 0.0 else changes.sumOf { it.deltaCm } / changes.size

        /// The single largest move, by magnitude — reported SIGNED, because
        /// the direction is the whole point of looking at it.
        val largestChange: Change? get() = changes.maxByOrNull { abs(it.deltaCm) }

        /// Skips grouped by reason, in `SkipReason.groupOrder`. The counted
        /// forms (`ambiguous: N bundles…`) group by their whole text, so two
        /// trees with different bundle counts are two lines.
        val skipGroups: List<SkipGroup>
            get() = skips.groupBy { it.reason.text }
                .map { (_, rows) -> SkipGroup(rows.first().reason, rows.size) }
                .sortedWith(compareBy({ it.reason.groupOrder }, { it.reason.text }))

        /// Fold another project's plan into this one — the console checks every
        /// project in one pass and reads the corpus as a whole.
        fun merged(other: Plan) = Plan(changes + other.changes, skips + other.skips)
    }

    // MARK: - One project's trees

    /// A project and the trees under it. Reading the store and replaying depth
    /// bundles are separate jobs, so the read is a separate, explicit step.
    data class ProjectTrees(
        val projectId: UUID,
        val projectName: String,
        val trees: List<Tree>,
    )

    /// Every project on the device with its (non-deleted) trees. A soft-deleted
    /// tree is not offered: it is not in the cruise any more, and rewriting a
    /// row nothing reads is churn with a risk attached.
    suspend fun loadProjects(
        projectRepository: ProjectRepository,
        plotRepository: PlotRepository,
        treeRepository: TreeRepository,
    ): List<ProjectTrees> {
        val projects = runCatching { projectRepository.list() }.getOrNull() ?: return emptyList()
        val out = ArrayList<ProjectTrees>(projects.size)
        for (project in projects) {
            val plots = runCatching { plotRepository.listByProject(project.id) }.getOrNull()
                ?: continue
            val trees = ArrayList<Tree>()
            for (plot in plots) {
                trees += runCatching { treeRepository.listByPlot(plot.id) }.getOrNull() ?: continue
            }
            if (trees.isEmpty()) continue
            out.add(ProjectTrees(project.id, project.name, trees))
        }
        return out
    }

    // MARK: - The rule

    /// Work out what would change for one project, changing nothing.
    ///
    /// MATCHING. A bundle names the tree it was taken on: `context` carries the
    /// project, the plot and the tree number, and that triple is the join. A
    /// tree can hold SEVERAL bundles — a retake is a real thing, and the
    /// discarded attempt stays on disk — so the triple alone is not enough to
    /// say which capture the stored number came from. The bundle's own record
    /// of what the app published at capture time is: the one whose
    /// `result_live` still sits on the row's diameter is the one the row was
    /// written from. Anything else is a skip with a reason.
    suspend fun plan(
        bundles: BundleIndex,
        project: ProjectTrees,
    ): Plan = withContext(Dispatchers.IO) {
        val changes = ArrayList<Change>()
        val skips = ArrayList<Skip>()
        val byTree = bundles.byTree

        // How many ROWS each triple picks out. The triple is the only thing a
        // bundle records about which tree it was taken on, so if two rows share
        // one, no bundle under it identifies either — and matching per tree
        // would rewrite both rows from the same capture.
        val rowsPerKey = HashMap<TreeKey, Int>()
        for (tree in project.trees) {
            val key = TreeKey(project.projectId, tree.plotId, tree.treeNumber)
            rowsPerKey[key] = (rowsPerKey[key] ?: 0) + 1
        }

        // Trees in tally order, ties broken on the id so two runs over the same
        // corpus list them the same way.
        val ordered = project.trees.sortedWith(
            compareBy({ it.treeNumber }, { it.id.toString() }))

        for (tree in ordered) {
            val label = tree.displayTitle
            fun skip(reason: SkipReason) {
                skips.add(Skip(tree.id, label, tree.treeNumber, reason))
            }

            // Idempotence first: a tree already at this epoch is finished,
            // whatever else is true of it.
            if (tree.dbhEstimatorEpoch == DBHEstimator.ESTIMATOR_EPOCH) {
                skip(SkipReason.AlreadyCurrentEpoch); continue
            }
            // `dbhCaptureMode` is the provenance field, and it only exists on
            // rows written since it landed. `dbhMethod` has always recorded a
            // hand-entered diameter, so an older typed row is recognised by
            // that instead of falling through to the bundle match and being
            // rewritten because the cruiser happened to type close to what the
            // screen was showing.
            if (tree.dbhCaptureMode == "typed" ||
                tree.dbhMethod == DBHMethod.MANUAL_VISUAL ||
                tree.dbhMethod == DBHMethod.MANUAL_CALIPER
            ) {
                skip(SkipReason.TypedDiameter); continue
            }
            val stored = tree.dbhCm.toDouble()
            val key = TreeKey(project.projectId, tree.plotId, tree.treeNumber)
            if (rowsPerKey[key] != 1) {
                skip(SkipReason.DuplicateTreeNumber); continue
            }
            val candidates = byTree[key].orEmpty()
            if (candidates.isEmpty()) {
                skip(SkipReason.NoBundle); continue
            }
            // A stored diameter of zero has no ratio to match on, and no
            // capture produces one — treat it as unmatchable rather than
            // dividing by it.
            val matches = if (stored > 0.0) {
                candidates.filter {
                    abs(it.liveValueCm - stored) <= RELATIVE_MATCH_TOLERANCE * stored
                }
            } else {
                emptyList()
            }
            if (matches.size != 1) {
                skip(
                    if (matches.isEmpty()) SkipReason.AmbiguousNoneMatches(candidates.size)
                    else SkipReason.AmbiguousManyMatch(matches.size),
                )
                continue
            }
            val match = matches.first()
            val result = try {
                RawCaptureReplay.rerunDbh(match.dir, match.manifest)
            } catch (_: Throwable) {
                null
            }
            if (result == null) {
                skip(SkipReason.ReplayFailed); continue
            }
            if (result.tier == "red") {
                skip(SkipReason.ReplayRejected); continue
            }
            if (!result.value.isFinite() || result.value <= 0.0) {
                skip(SkipReason.ReplayFailed); continue
            }
            changes.add(
                Change(
                    treeId = tree.id, treeLabel = label, treeNumber = tree.treeNumber,
                    bundleId = match.id, storedCm = stored, recomputedCm = result.value,
                ),
            )
        }
        Plan(changes, skips)
    }

    /// The (project, plot, tree number) triple. Built from a manifest context
    /// or from a tree; a context missing any of the three belongs to no cruise
    /// tree (a quick-measure capture carries no plot) and yields null rather
    /// than a partial key that could collide.
    internal data class TreeKey(val projectId: UUID, val plotId: UUID, val treeNumber: Int)

    /// One DBH bundle reduced to what the match needs: the stem it names, the
    /// value the app published for it, and where to replay it from.
    internal data class BundleRef(
        val id: String,
        val dir: File,
        val manifest: JSONObject,
        val liveValueCm: Double,
    )

    /// Every readable DBH bundle on disk, keyed by the stem it names. Built
    /// ONCE per run and handed to each project's plan — the corpus is the same
    /// for all of them, and re-reading every manifest per project would parse
    /// the whole of disk again for each one.
    class BundleIndex internal constructor(
        internal val byTree: Map<TreeKey, List<BundleRef>>,
    )

    suspend fun indexBundles(context: Context): BundleIndex = withContext(Dispatchers.IO) {
        BundleIndex(readBundles(context))
    }

    private fun readBundles(context: Context): Map<TreeKey, List<BundleRef>> {
        val out = HashMap<TreeKey, MutableList<BundleRef>>()
        for (summary in RawCaptureStore.list(context)) {
            if (summary.kind != "dbh") continue
            val manifest = RawCaptureStore.manifestOf(context, summary.id) ?: continue
            val key = treeKey(manifest.optJSONObject("context")) ?: continue
            val live = manifest.optJSONObject("result_live")?.optDouble("value")
                ?.takeIf { it.isFinite() } ?: continue
            out.getOrPut(key) { ArrayList() }.add(
                BundleRef(
                    id = summary.id,
                    dir = RawCaptureStore.dirOf(context, summary.id),
                    manifest = manifest,
                    liveValueCm = live,
                ),
            )
        }
        return out
    }

    /// Parsed as UUIDs rather than compared as strings: the two platforms write
    /// the same identifier with different letter case.
    private fun treeKey(context: JSONObject?): TreeKey? {
        if (context == null) return null
        val project = uuidOrNull(context.optString("project_id")) ?: return null
        val plot = uuidOrNull(context.optString("plot_id")) ?: return null
        val number = context.optInt("tree_number", -1).takeIf { it >= 0 } ?: return null
        return TreeKey(project, plot, number)
    }

    private fun uuidOrNull(raw: String?): UUID? {
        if (raw.isNullOrEmpty() || raw == "null") return null
        return try { UUID.fromString(raw) } catch (_: IllegalArgumentException) { null }
    }

    // MARK: - Running it

    /// What actually reached the store, so the sentence after the run agrees
    /// with the disk rather than with the plan.
    data class Result(
        /// Rows rewritten and stamped.
        val written: Int = 0,
        /// Rows whose diameter no longer held the value they were matched on,
        /// or which had been stamped in the meantime. Left exactly as found.
        val stale: Int = 0,
        /// Rows the repository refused. Nothing is hidden: a partial run says
        /// which part failed and leaves the rest alone.
        val failed: Int = 0,
    )

    /// Apply a plan the cruiser has just confirmed.
    ///
    /// RE-CHECKS EVERY ROW under the read it is about to write. The preview is
    /// a snapshot, and between it and the confirm the cruiser can have edited a
    /// diameter in Tree detail or measured the stem again. A row that no longer
    /// holds the value it was matched on is no longer the row the bundle was
    /// matched TO, so it is counted stale and skipped — the match evidence is
    /// gone and re-deriving it would be a guess.
    suspend fun apply(
        changes: List<Change>,
        repository: TreeRepository,
        now: Long = System.currentTimeMillis(),
    ): Result = withContext(Dispatchers.IO) {
        var written = 0
        var stale = 0
        var failed = 0
        for (change in changes) {
            // `read` excludes soft-deleted rows, so a tree deleted since the
            // preview counts stale and is not resurrected by a write.
            val tree = runCatching { repository.read(change.treeId) }.getOrNull()
            if (tree == null ||
                tree.dbhEstimatorEpoch == DBHEstimator.ESTIMATOR_EPOCH ||
                abs(tree.dbhCm.toDouble() - change.storedCm) > STORED_VALUE_EPSILON
            ) {
                stale += 1
                continue
            }
            tree.dbhCm = change.recomputedCm.toFloat()
            tree.dbhEstimatorEpoch = DBHEstimator.ESTIMATOR_EPOCH
            tree.updatedAt = now
            if (runCatching { repository.update(tree) }.isSuccess) written += 1 else failed += 1
        }
        Result(written = written, stale = stale, failed = failed)
    }

    // MARK: - Wording (byte-identical to iOS)

    const val EXPLANATION =
        "Re-derives each cruise diameter from the raw capture it was measured " +
            "from, with the estimator this build ships, and records the epoch " +
            "that produced it. Typed diameters, trees with no capture, and trees " +
            "whose capture cannot be identified are left alone. Heights are not " +
            "touched."

    const val NOTHING_TO_DO =
        "Nothing to recompute. Every measured diameter with an identifiable " +
            "capture already carries this estimator epoch."

    const val CONFIRM_MESSAGE =
        "This replaces measured field data. Every change is listed on this " +
            "screen; nothing else is written."

    const val SKIP_FOOTER =
        "A tree is only rewritten when exactly one of its bundles still holds " +
            "the diameter the row holds. Anything less certain is left as the " +
            "cruiser recorded it."

    /// "+1.3 cm" / "-0.4 cm" — signed, because the direction is the point.
    fun signedCm(v: Double): String = String.format(Locale.US, "%+.1f cm", v)

    /// "38.4 → 39.7 cm (+1.3)".
    fun changeLine(c: Change): String = String.format(
        Locale.US, "%.1f → %.1f cm (%+.1f)", c.storedCm, c.recomputedCm, c.deltaCm)

    /// The sentence after a run. Counts what reached the store, not what was
    /// planned — the two differ exactly when a row moved under the preview.
    fun resultText(r: Result): String {
        var s = "Rewrote ${r.written} diameters at estimator epoch "
        s += "${DBHEstimator.ESTIMATOR_EPOCH}. "
        if (r.stale > 0) {
            s += "${r.stale} no longer held the value they were matched on and "
            s += "are unchanged. "
        }
        if (r.failed > 0) {
            s += "${r.failed} could not be written and are unchanged. "
        }
        return s
    }
}
