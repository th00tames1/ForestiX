// GROUND-TRUTH RECOVERY — attach a truth typed for a raw CAPTURE to the
// READING it belongs to.
//
// CROSS-PLATFORM: matching rule, provenance stamp, persisted keys and every
// user-visible string are byte-identical to the iOS sibling
// (App/TruthBackfill.swift).
//
// WHY THIS EXISTS. A ground truth used to live only in the raw-capture
// manifest, keyed on tree number. Both scan screens began writing it onto the
// reading as well in one commit, so captures taken since are symmetric — but
// everything recorded before that has its tape numbers in manifests and
// nowhere else, and the read-only sheet section that used to display them is
// gone. The values were never destroyed (they are still in the manifest and
// still ship in the raw-capture ZIP), but the field log shows a blank True
// field for trees the cruiser measured with a tape, which is indistinguishable
// on screen from never having measured them.
//
// There is a SECOND, still-live source of the same shape: the raw-captures
// console lets a truth be typed against a stored bundle long after the fact,
// and that path writes the manifest only. So this is not a one-time migration
// of historic data — it is a repair that can find new work at any time, which
// is why it is an action the cruiser triggers rather than a launch pass, and
// why it must be idempotent rather than version-flagged.
//
// WHAT IT WILL NOT DO
//   * It never overwrites a truth already on a reading. The typed field wins.
//   * It never creates a reading. A manifest truth whose reading was deleted
//     stays in the manifest; inventing a measurement to hang it from would put
//     a number into the corpus that nothing measured.
//   * It never edits a manifest. The manifest copy is the capture's own record
//     and still ships in the ZIP exactly as before. (TruthUnitRepair does edit
//     one — but it decides nothing: it corrects the SCALE of the bundle's own
//     truth where the bundle itself records no unit, and it re-bases the
//     reading to match, so the two stay equal and this pass keeps reporting
//     them as already home rather than as newly stranded.)
//   * It never touches the estimator, the value, sigma, or anything but
//     `truth` and its provenance stamp.

package com.hcjeong.forestix.data

import android.content.Context
import com.hcjeong.forestix.sensors.RawCaptureStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.abs

object TruthBackfill {

    // MARK: - What a manifest contributes

    /// One recoverable truth, lifted out of a raw-capture manifest.
    ///
    /// [treeNumber] is required, not nullable: a truth with no tree number
    /// names no stem and can never be matched to one. [value] is the metric
    /// base the manifest stores — cm for a diameter, m for a height, the same
    /// unit as [QuickMeasureEntry.truth], so nothing on this path converts.
    data class ManifestTruth(
        val captureId: String,
        val kind: MeasureKind,
        val treeNumber: Int,
        val value: Double,
        val createdAtMs: Long,
        /// Kept verbatim so the report records the manifest's own stamp rather
        /// than a re-rendering of the parsed instant.
        val createdAt: String,
    )

    /// What a run WOULD do, computed before anything is written.
    data class Plan(
        /// reading id -> truth value to attach.
        val attachments: Map<UUID, Double>,
        /// Manifest truths whose value ALREADY sits on a reading of that kind
        /// and tree. Nothing to do and nothing to report: the manifest and the
        /// reading simply agree, which is the normal state of every capture
        /// taken since the scan screens started writing both.
        val alreadyPresent: Int,
        /// Manifest truths with no reading to sit on. Reported, not discarded
        /// — they stay in the manifest and in the ZIP.
        val unmatched: List<ManifestTruth>,
        /// Bundles on disk, parseable or not — the denominator the cruiser
        /// reconciles against their own backup.
        val scanned: Int,
    )

    /// A stored truth and a manifest truth are the SAME value inside this
    /// band. Both are written to the metric base from the same parser, so the
    /// only difference between them is float round-trip — the same reasoning
    /// and the same number as the field log's FIELD_LOG_VALUE_EPSILON.
    const val VALUE_EPSILON = 0.001

    // MARK: - Reading the corpus

    /// Manifest `created_at` is written by both platforms as an ISO-8601
    /// instant with milliseconds; older bundles on either platform may carry
    /// one without. Both shapes are parsed rather than one being assumed,
    /// because a timestamp that silently fails to parse would quietly make
    /// every capture of that vintage unmatchable. Returns null when neither
    /// shape fits, and such a capture is then simply not recoverable.
    fun parseIso(s: String): Long? {
        if (s.isBlank()) return null
        for (pattern in arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
        )) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US)
                fmt.timeZone = TimeZone.getTimeZone("UTC")
                fmt.isLenient = false
                return fmt.parse(s)?.time
            } catch (_: Throwable) {
                // try the next shape
            }
        }
        return null
    }

    /// The recoverable truths in a set of bundle summaries.
    ///
    /// CRUISE CAPTURES ARE SKIPPED. `context.mode` says which world a capture
    /// belongs to, and a cruise capture's reading is a cruise Tree/Stem record,
    /// not a [QuickMeasureEntry] — the cruise flow appends nothing to the quick
    /// log at all. Offering a cruise truth to a quick-measure reading with the
    /// same tree number would attach it to a different stem entirely.
    fun recoverable(summaries: List<RawCaptureStore.Summary>): List<ManifestTruth> =
        summaries.mapNotNull { s ->
            if (s.mode == "cruise") return@mapNotNull null
            val value = s.truthValue ?: return@mapNotNull null
            if (!value.isFinite() || value <= 0) return@mapNotNull null
            val tree = s.treeNumber ?: return@mapNotNull null
            val kind = kindOf(s.kind) ?: return@mapNotNull null
            val ms = parseIso(s.createdAt) ?: return@mapNotNull null
            ManifestTruth(s.id, kind, tree, value, ms, s.createdAt)
        }

    /// Only the two kinds a raw capture can record. CROWN / DISTANCE /
    /// SAMPLING_PLOT have no capture bundle and no truth field.
    fun kindOf(raw: String): MeasureKind? = when (raw) {
        "dbh" -> MeasureKind.DBH
        "height" -> MeasureKind.HEIGHT
        else -> null
    }

    // MARK: - The matching rule

    /// Group key: one kind on one tree. The plot is NOT part of it — see
    /// [plan] for why it cannot be.
    private data class GroupKey(val kind: MeasureKind, val tree: Int)

    /// Work out which truth goes on which reading, changing nothing.
    ///
    /// THE RULE, in order:
    ///   1. Same kind, same tree number.
    ///   2. Same plot — enforced as "the plot must be unambiguous". A quick
    ///      capture's manifest records NO plot (`context.plot_id` is the cruise
    ///      plot and is null for every quick capture, and a quick-measure plot
    ///      id was never written there at all), so the plot cannot be read off
    ///      the manifest and compared. What CAN be checked is whether the tree
    ///      number is ambiguous: if this kind of reading exists for this tree
    ///      number in more than one plot, the manifest cannot say which of them
    ///      it was taped for, and the truth is left unmatched. Anything else
    ///      would be a coin toss between two different stems.
    ///   3. Never overwrite. A reading that already carries a truth is not a
    ///      candidate; the typed field wins.
    ///   4. Nearest in time. A capture and the reading it produced are seconds
    ///      apart, so |dt| identifies which of a tree's several readings a
    ///      truth was typed against — the same time-matching the validation
    ///      analysis used to pair phones. Pairs are taken globally smallest-dt
    ///      first, so two truths competing for one reading resolve the same way
    ///      every run, and neither a truth nor a reading is used twice.
    ///
    /// No maximum dt is imposed. A window would be a threshold nobody measured,
    /// and it would silently strand real tape values whose only rival is "this
    /// one is old" — the whole complaint being repaired here.
    ///
    /// IDEMPOTENT: everything it attaches makes its reading ineligible (rule 3),
    /// so a second run over the same corpus plans zero attachments.
    fun plan(
        manifests: List<ManifestTruth>,
        entries: List<QuickMeasureEntry>,
        defaultPlotID: UUID?,
        scanned: Int,
    ): Plan {
        // Readings of a recoverable kind that belong to a tree, grouped.
        val groups = HashMap<GroupKey, MutableList<QuickMeasureEntry>>()
        for (e in entries) {
            if (e.kind != MeasureKind.DBH && e.kind != MeasureKind.HEIGHT) continue
            val tree = e.treeNumber ?: continue
            groups.getOrPut(GroupKey(e.kind, tree)) { mutableListOf() }.add(e)
        }

        // Rule 2: a group spread across plots cannot be resolved by a manifest
        // that names no plot. Computed over ALL of the group's readings, not
        // just the truthless ones — a plot whose reading already has its own
        // truth still proves the tree number is ambiguous.
        val ambiguous = groups.filterValues { group ->
            group.map { (it.plotID ?: defaultPlotID)?.toString() ?: "" }.toSet().size > 1
        }.keys

        // ALREADY HOME. A manifest whose value is already carried by a reading
        // of that kind and tree needs nothing done and must not be reported as
        // stranded — every capture taken since the scan screens started
        // writing the truth to both places is in exactly this state, and
        // without this the first run would tell the cruiser that a couple of
        // hundred tape values are missing from readings that plainly show them.
        // It is also what keeps a re-measure quiet: the replacement reading
        // carries the truth across, so the manifest is still accounted for.
        val settled = HashSet<Int>()
        manifests.forEachIndexed { i, m ->
            val group = groups[GroupKey(m.kind, m.treeNumber)].orEmpty()
            if (group.any { e -> e.truth?.let { abs(it - m.value) <= VALUE_EPSILON } == true }) {
                settled.add(i)
            }
        }

        // Every legal (truth, reading) pairing, with the distance that ranks it.
        data class Pair2(
            val manifestIndex: Int,
            val entryId: UUID,
            val deltaMs: Long,
            val manifestCreatedAtMs: Long,
            val captureId: String,
            val entryIdText: String,
        )
        val pairs = ArrayList<Pair2>()
        manifests.forEachIndexed { i, m ->
            if (i in settled) return@forEachIndexed
            val key = GroupKey(m.kind, m.treeNumber)
            if (key in ambiguous) return@forEachIndexed
            val group = groups[key] ?: return@forEachIndexed
            for (e in group) {
                if (e.truth != null) continue
                pairs.add(
                    Pair2(
                        manifestIndex = i,
                        entryId = e.id,
                        deltaMs = abs(e.createdAt - m.createdAtMs),
                        manifestCreatedAtMs = m.createdAtMs,
                        captureId = m.captureId,
                        entryIdText = e.id.toString(),
                    )
                )
            }
        }
        // Total order, so two devices (or two runs) resolve a contested reading
        // identically. dt first; the rest are tie-breakers that only exist so
        // the sort can never depend on map iteration order.
        pairs.sortWith(
            compareBy<Pair2> { it.deltaMs }
                .thenBy { it.manifestCreatedAtMs }
                .thenBy { it.captureId }
                .thenBy { it.entryIdText }
        )

        val attachments = LinkedHashMap<UUID, Double>()
        val claimedManifests = HashSet<Int>()
        val claimedEntries = HashSet<UUID>()
        for (p in pairs) {
            if (p.manifestIndex in claimedManifests || p.entryId in claimedEntries) continue
            claimedManifests.add(p.manifestIndex)
            claimedEntries.add(p.entryId)
            attachments[p.entryId] = manifests[p.manifestIndex].value
        }

        val unmatched = manifests.filterIndexed { i, _ ->
            i !in claimedManifests && i !in settled
        }
        return Plan(attachments, settled.size, unmatched, scanned)
    }

    // MARK: - Running it

    /// Read the corpus, attach what matches, record what did not, and return
    /// the sentence the cruiser reads. Reads every manifest on disk, so the
    /// caller runs it off the main thread.
    suspend fun run(context: Context, history: QuickMeasureHistory): String {
        val summaries = RawCaptureStore.list(context)
        // The denominator, counted WITHOUT a second parse of every manifest.
        val scanned = RawCaptureStore.directoryCount(context)
        val manifests = recoverable(summaries)
        val plan = plan(
            manifests = manifests,
            entries = history.entries.value,
            defaultPlotID = history.defaultPlotID(),
            scanned = scanned,
        )
        val attached = history.backfillTruths(plan.attachments)
        // The REPORT records what actually happened, so a reading the history
        // refused between planning and writing is counted as attached by
        // neither the sentence nor the sheet.
        TruthBackfillReport.save(
            context, scanned, attached, plan.alreadyPresent, plan.unmatched)
        return summary(attached, plan.alreadyPresent, plan.unmatched.size, scanned)
    }

    // MARK: - Wording (byte-identical to iOS)

    /// The result line. Every truth the sweep found is in exactly one of the
    /// three counts, so `attached + alreadyPresent + unmatched` is the number
    /// of manifest truths on the device and the cruiser can reconcile it
    /// against their own backup — which is the whole reason this is not silent.
    ///
    /// No plural branching anywhere: the two platforms have to emit the same
    /// bytes, and "1 ground truths" is a smaller cost than two pluralisation
    /// rules drifting apart.
    fun summary(attached: Int, alreadyPresent: Int, unmatched: Int, scanned: Int): String {
        if (scanned <= 0) return "No raw captures on this device."
        return "Attached $attached ground truths to readings. " +
            "$alreadyPresent were already on one. " +
            "$unmatched found no reading and stay in the raw-capture ZIP. " +
            "Scanned $scanned captures."
    }

    /// The quiet line the field log shows where the cruiser would look for a
    /// truth that is in the ZIP and on no reading. [text] is the value rendered
    /// in the unit of the truth field directly above it, so the two never
    /// disagree.
    fun strandedLine(text: String): String =
        "$text was typed for a raw capture of this tree that no reading " +
            "matches — it stays in the raw-capture ZIP."
}

/// What the last run did, kept so the field log can name a stranded value
/// without re-reading ~300 manifests to draw one sheet.
///
/// CROSS-PLATFORM: file name and every JSON key are byte-identical to iOS. It
/// is a RECORD, not a source of truth — deleting it loses nothing except the
/// sheet's notice until the next run.
object TruthBackfillReport {

    private const val FILE_NAME = "truth-backfill.json"
    private const val SCHEMA = 1

    data class Stranded(
        val captureId: String,
        val kind: String,
        val treeNumber: Int,
        val value: Double,
        val createdAt: String,
    )

    data class Report(
        val scanned: Int,
        val attached: Int,
        /// Manifest truths the readings already carried. Recorded so
        /// `attached + already_present + unmatched.size` adds up to the number
        /// of manifest truths on the device.
        val alreadyPresent: Int,
        val unmatched: List<Stranded>,
    ) {
        /// Stranded values recorded against one tree and kind — what the field
        /// log section asks for.
        ///
        /// Matched on tree number and kind ONLY, because a quick capture's
        /// manifest records no plot to match on (see [TruthBackfill.plan]). A
        /// tree number that exists in two plots is exactly the case the planner
        /// refuses to attach, so the notice appearing on both of those sheets
        /// is the honest outcome: the value is real, and which stem it belongs
        /// to is unresolved.
        fun stranded(kind: MeasureKind, treeNumber: Int): List<Stranded> =
            unmatched.filter { it.kind == kind.raw && it.treeNumber == treeNumber }
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun save(
        context: Context,
        scanned: Int,
        attached: Int,
        alreadyPresent: Int,
        unmatched: List<TruthBackfill.ManifestTruth>,
    ) {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        val arr = JSONArray()
        for (m in unmatched) {
            arr.put(
                JSONObject().apply {
                    put("capture_id", m.captureId)
                    put("kind", m.kind.raw)
                    put("tree_number", m.treeNumber)
                    put("value", m.value)
                    put("created_at", m.createdAt)
                }
            )
        }
        val root = JSONObject().apply {
            put("schema", SCHEMA)
            put("ran_at", fmt.format(Date()))
            put("scanned", scanned)
            put("attached", attached)
            put("already_present", alreadyPresent)
            put("unmatched", arr)
        }
        try {
            file(context).writeText(root.toString(2))
        } catch (_: Throwable) {
            // A lost report costs the sheet its notice and nothing else; the
            // readings were already written.
        }
    }

    fun load(context: Context): Report? {
        val f = file(context)
        if (!f.exists()) return null
        val root = try { JSONObject(f.readText()) } catch (_: Throwable) { return null }
        val arr = root.optJSONArray("unmatched") ?: JSONArray()
        val out = ArrayList<Stranded>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val tree = o.optInt("tree_number", -1)
            if (tree < 0) continue
            val value = o.optDouble("value")
            if (value.isNaN()) continue
            out.add(
                Stranded(
                    captureId = o.optString("capture_id"),
                    kind = o.optString("kind"),
                    treeNumber = tree,
                    value = value,
                    createdAt = o.optString("created_at"),
                )
            )
        }
        return Report(
            scanned = root.optInt("scanned", 0),
            attached = root.optInt("attached", 0),
            alreadyPresent = root.optInt("already_present", 0),
            unmatched = out,
        )
    }
}
