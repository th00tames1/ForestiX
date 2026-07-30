// GROUND-TRUTH UNIT REPAIR — re-base tape values that were typed in imperial
// and stored as if the digits were already the metric base.
//
// CROSS-PLATFORM: selection rule, provenance stamps, persisted keys and every
// user-visible string are byte-identical to the iOS sibling
// (App/TruthUnitRepair.swift).
//
// WHAT WENT WRONG. The ground-truth field had no unit toggle. The cruiser was
// working from an imperial tape — inches on the diameter, feet on the height —
// and typed those digits into a field the app labelled and stored in metric.
// A stem taped at 27 in went into the corpus as 27 cm.
//
// HOW AN AFFECTED VALUE IS RECOGNISED. By the ABSENCE OF A RECORDED UNIT, and
// by nothing else. The commit that gave the truth field its per-entry unit
// toggle is the same commit that started recording `truth_unit` on the
// manifest, `truth_unit` in the research CSV, and a truth on the reading at
// all. So:
//
//   * A truth carrying a unit was typed with the toggle on screen. The cruiser
//     said what they meant and the stored base is already right. Never touched.
//   * A truth carrying NO unit was typed before the toggle existed. Every
//     writer that can record a unit does record one — RawCaptureStore.setTruth
//     writes `truth_unit` whenever it writes a value, and the scan screens and
//     the raw-captures console are its only callers — so an unstamped truth
//     cannot have come from a build that had the toggle.
//   * On a READING the same absence is not enough on its own, because the
//     reading has never carried a unit for a hand-typed truth. The extra
//     condition is `truth_source = capture`: a truth the cruiser typed onto a
//     reading was necessarily typed in a build that had the toggle (the field
//     and the toggle landed together), so only a value COPIED from a manifest
//     can be carrying pre-toggle digits. That manifest is then read directly
//     rather than assumed — see [plan].
//
// WHAT IS DELIBERATELY NOT PART OF THE RULE. `settings.units` on the manifest,
// which says which system the app was DISPLAYING. Measured against the
// cruiser's own corpus it carries no information about what was typed: in the
// session of 2026-07-27 the app was in metric for every capture, and the
// diameter truths there still sit at about 1/2.54 of what the app measured and
// the heights at about 1/0.3048. What the app displayed and what the cruiser
// wrote on the tape were simply different things.
//
// THE VALUE IS NEVER DOUBLED. 27 cm and 68.58 cm are both ordinary stems, so
// the correction cannot be read back out of the number. Every repair therefore
// WRITES THE UNIT it re-based into — `truth_unit` on the manifest and in the
// research CSV, `truthUnit` on the reading — which is exactly the marker the
// rule selects on. A repaired value no longer matches the rule that chose it,
// on any device, in any order, without a version flag.

package com.hcjeong.forestix.data

import android.content.Context
import com.hcjeong.forestix.common.TruthInput
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

object TruthUnitRepair {

    // MARK: - What is being re-based

    /// The two quantities the defect was confirmed for. Distance truths are
    /// NOT here: the cruiser's corpus has no evidence about what unit a
    /// distance was typed in, and a repair with no evidence behind it is a
    /// guess written into research data.
    enum class Quantity(val raw: String, val rawKind: String) {
        DIAMETER("diameter", "dbh"),
        HEIGHT("height", "height");

        /// The unit the digits were really in.
        val typedUnit: TruthInput.Unit
            get() = if (this == DIAMETER) TruthInput.Unit.INCHES else TruthInput.Unit.FEET

        /// The app's metric base for this quantity — what the digits were
        /// wrongly stored as.
        val baseUnitText: String
            get() = if (this == DIAMETER) "cm" else "m"

        companion object {
            fun of(kind: MeasureKind): Quantity? = when (kind) {
                MeasureKind.DBH -> DIAMETER
                MeasureKind.HEIGHT -> HEIGHT
                else -> null
            }

            fun ofRawKind(raw: String): Quantity? = when (raw) {
                "dbh" -> DIAMETER
                "height" -> HEIGHT
                else -> null
            }
        }
    }

    /// The corrected base for digits that were typed in [quantity]'s imperial
    /// unit. Routed through [TruthInput.toBase] rather than multiplying here,
    /// because that is the ONE place a typed truth becomes a base value and a
    /// second copy of 2.54 is how the two would eventually disagree.
    fun repaired(typed: Double, quantity: Quantity): Double =
        TruthInput.toBase(typed, quantity.typedUnit)

    /// Two truth values are the SAME value inside this band — the same number
    /// and reasoning as [TruthBackfill.VALUE_EPSILON]: every writer puts the
    /// metric base through one parser, so the only difference between two
    /// copies of a truth is float round-trip.
    const val VALUE_EPSILON = 0.001

    // MARK: - One planned change

    data class Change(
        val quantity: Quantity,
        /// The digits as the cruiser typed them (and as they are stored now).
        val before: Double,
        /// The metric base those digits meant.
        val after: Double,
        /// Which store this row lives in — "reading" | "capture" | "log".
        val store: String,
        /// Reading id, capture id, or the research row's tree id. Empty when
        /// the row names nothing.
        val key: String,
    ) {
        /// "27 -> 68.6 cm (27.0 in)". The digits on the left are rendered the
        /// way the field would have shown them back (trailing zeros trimmed);
        /// both converted numbers are one decimal, which is the precision the
        /// cruiser reads a tape to.
        val exampleLine: String
            get() {
                val base = String.format(Locale.US, "%.1f", after)
                val typed = String.format(Locale.US, "%.1f", before)
                return TruthInput.text(before) + " -> " + base + " " +
                    quantity.baseUnitText + " (" + typed + " " +
                    quantity.typedUnit.raw + ")"
            }
    }

    /// Why a truth the sweep SAW is being left alone. Every truth in every
    /// store lands in exactly one of these or in the change list, so the
    /// numbers the cruiser is shown add up to what is on the device.
    data class Kept(
        /// A unit was recorded with it — entered knowingly, already right.
        var unitRecorded: Int = 0,
        /// Typed onto the reading itself, which was only possible from the
        /// build that had the unit toggle.
        var typedOnReading: Int = 0,
        /// Capture-recovered, but no unit-less capture on this device carries
        /// that value — so there is nothing to prove it predates the toggle.
        var unverifiable: Int = 0,
        /// A distance row. The defect was confirmed for diameter and height.
        var otherQuantity: Int = 0,
    ) {
        val total: Int get() = unitRecorded + typedOnReading + unverifiable + otherQuantity
    }

    /// What a run WOULD do, computed before anything is written.
    data class Plan(
        val readings: MutableMap<UUID, Change> = LinkedHashMap(),
        val captures: MutableMap<String, Change> = LinkedHashMap(),
        /// Research-log rows, in file order. Applied by re-deriving them inside
        /// the log's own lock, not by index.
        var logRows: List<Change> = emptyList(),
        var kept: Kept = Kept(),
    ) {
        val readingDiameters: Int get() = count(readings.values, Quantity.DIAMETER)
        val readingHeights: Int get() = count(readings.values, Quantity.HEIGHT)
        val captureDiameters: Int get() = count(captures.values, Quantity.DIAMETER)
        val captureHeights: Int get() = count(captures.values, Quantity.HEIGHT)
        val logDiameters: Int get() = count(logRows, Quantity.DIAMETER)
        val logHeights: Int get() = count(logRows, Quantity.HEIGHT)

        val isEmpty: Boolean
            get() = readings.isEmpty() && captures.isEmpty() && logRows.isEmpty()

        private fun count(xs: Collection<Change>, q: Quantity) = xs.count { it.quantity == q }

        /// Every planned change in a stable order — what the report writes and
        /// what the examples are drawn from. Captures first because every
        /// affected value has one.
        val ordered: List<Change>
            get() = captures.entries.sortedBy { it.key }.map { it.value } +
                readings.entries.sortedBy { it.key.toString() }.map { it.value } +
                logRows

        /// Up to four worked examples — two diameters and two heights, taken in
        /// that stable order so the same corpus previews the same lines every
        /// time.
        val examples: List<String>
            get() {
                val all = ordered
                val out = ArrayList<String>()
                for (q in listOf(Quantity.DIAMETER, Quantity.HEIGHT)) {
                    var taken = 0
                    for (c in all) {
                        if (c.quantity != q || taken >= 2) continue
                        val line = c.exampleLine
                        if (line in out) continue
                        out.add(line)
                        taken++
                    }
                }
                return out
            }
    }

    // MARK: - The rule

    /// One kind on one tree — how a reading is tied back to the captures that
    /// could have supplied its truth.
    private data class GroupKey(val quantity: Quantity, val tree: Int)

    /// Work out what will change, changing nothing.
    ///
    /// CAPTURES. A bundle is affected when it holds a truth and records no
    /// `truth_unit`. Cruise-mode bundles are included — unlike [TruthBackfill],
    /// which skips them because it is deciding which READING a truth belongs
    /// to. Nothing is being decided here: the field being corrected is the
    /// bundle's own, and a cruise capture's tape number was typed into the same
    /// unit-less field as every other.
    ///
    /// READINGS. A reading is affected when it holds a truth, records no unit,
    /// and carries `truth_source = capture` — the only way pre-toggle digits
    /// can reach a reading. That is still not taken on trust: at least one
    /// capture of the same kind and tree must hold that same value with NO
    /// unit, and no capture of that kind and tree may hold it WITH one. So a
    /// truth typed into the raw-captures console today (which records a unit)
    /// and matched onto a reading by the recovery pass is recognised as correct
    /// and left alone, and a reading whose capture has been deleted is left
    /// alone as well, because nothing on the device can say what it was.
    ///
    /// RESEARCH LOG. Rows are selected by the log itself (see
    /// [ResearchLog.repairImperialTruths]), which owns its file and its lock;
    /// the rows it reports are folded into the same plan so one preview covers
    /// all three.
    fun plan(
        summaries: List<RawCaptureStore.Summary>,
        entries: List<QuickMeasureEntry>,
        logRows: List<Change>,
        logKept: Kept,
    ): Plan {
        val plan = Plan()
        plan.kept = logKept
        plan.logRows = logRows

        // Captures, and the two lookups the reading rule needs.
        val unitless = HashMap<GroupKey, MutableList<Double>>()
        val stamped = HashMap<GroupKey, MutableList<Double>>()
        for (s in summaries) {
            val value = s.truthValue ?: continue
            if (!value.isFinite() || value <= 0) continue
            val q = Quantity.ofRawKind(s.kind)
            if (q == null) {
                plan.kept.otherQuantity++
                continue
            }
            val tree = s.treeNumber
            if (tree != null) {
                val key = GroupKey(q, tree)
                val bucket = if (s.truthUnit == null) unitless else stamped
                bucket.getOrPut(key) { mutableListOf() }.add(value)
            }
            if (s.truthUnit != null) {
                plan.kept.unitRecorded++
                continue
            }
            plan.captures[s.id] = Change(q, value, repaired(value, q), "capture", s.id)
        }

        for (e in entries) {
            val truth = e.truth ?: continue
            if (!truth.isFinite() || truth <= 0) continue
            val q = Quantity.of(e.kind)
            if (q == null) {
                plan.kept.otherQuantity++
                continue
            }
            if (e.truthUnit != null) {
                plan.kept.unitRecorded++
                continue
            }
            if (e.truthSource != TruthSource.CAPTURE.raw) {
                plan.kept.typedOnReading++
                continue
            }
            val tree = e.treeNumber
            if (tree == null) {
                plan.kept.unverifiable++
                continue
            }
            val key = GroupKey(q, tree)
            val matches = { v: Double -> abs(v - truth) <= VALUE_EPSILON }
            // A stamped capture holding this value is proof the value was
            // entered knowingly. It wins over an unstamped one: the rule may
            // only fire when NOTHING on the device says a unit was given.
            if (stamped[key].orEmpty().any(matches)) {
                plan.kept.unitRecorded++
                continue
            }
            if (!unitless[key].orEmpty().any(matches)) {
                plan.kept.unverifiable++
                continue
            }
            plan.readings[e.id] =
                Change(q, truth, repaired(truth, q), "reading", e.id.toString())
        }
        return plan
    }

    // MARK: - Running it

    /// What actually happened, so the sentence and the report agree with the
    /// disk rather than with the plan.
    data class Result(
        var readings: Int = 0,
        var captures: Int = 0,
        var log: Int = 0,
        /// Captures whose manifest could not be rewritten. Nothing is hidden: a
        /// partial run says which part failed and leaves the rest alone.
        var failedCaptures: Int = 0,
        var logFailed: Boolean = false,
        var kept: Int = 0,
    )

    /// Read all three stores and work out what would change. Writes nothing.
    suspend fun preview(context: Context, history: QuickMeasureHistory): Plan {
        val summaries = RawCaptureStore.list(context)
        val log = ResearchLog.repairImperialTruths(context, apply = false)
        return plan(summaries, history.entries.value, log.rows, log.kept)
    }

    /// Apply a plan the cruiser has just confirmed, then record what happened.
    ///
    /// ORDER MATTERS. Readings first, then captures, then the log — so if the
    /// run dies part-way, what is left behind is a reading whose unit-less
    /// capture still backs it, which is a state the rule reads correctly on the
    /// next run. Doing captures first would strip the evidence the reading rule
    /// depends on and strand the reading unrepairable.
    suspend fun applyPlan(context: Context, plan: Plan, history: QuickMeasureHistory): Result {
        val result = Result()
        result.kept = plan.kept.total
        result.readings = history.repairTruthUnits(plan.readings)
        for ((id, c) in plan.captures) {
            val ok = RawCaptureStore.repairTruthUnit(
                context, id, c.before, c.after, c.quantity.typedUnit)
            if (ok) result.captures++ else result.failedCaptures++
        }
        val log = ResearchLog.repairImperialTruths(context, apply = true)
        result.log = log.written
        result.logFailed = log.failed
        TruthUnitRepairReport.save(context, plan, result)
        return result
    }

    // MARK: - Wording (byte-identical to iOS)

    /// The preview the cruiser confirms against. Three lines of counts (one per
    /// store, because the same tape value lives in all three and adding them
    /// would claim three times the work), then the worked examples, then what
    /// is being left alone and why.
    ///
    /// No plural branching anywhere: the two platforms have to emit the same
    /// bytes, and "1 diameters" is a smaller cost than two pluralisation rules
    /// drifting apart.
    fun previewText(plan: Plan): String {
        if (plan.isEmpty) return "Nothing to repair. " + keptText(plan.kept)
        val sb = StringBuilder()
        sb.append("Readings: ${plan.readingDiameters} diameters, ")
        sb.append("${plan.readingHeights} heights.\n")
        sb.append("Captures: ${plan.captureDiameters} diameters, ")
        sb.append("${plan.captureHeights} heights.\n")
        sb.append("Research log: ${plan.logDiameters} diameters, ")
        sb.append("${plan.logHeights} heights.\n\n")
        for (line in plan.examples) sb.append(line).append('\n')
        sb.append('\n').append(keptText(plan.kept))
        sb.append("\nNothing is written until you confirm.")
        return sb.toString()
    }

    /// The "and here is what it will not touch" sentence, so the arithmetic
    /// closes: everything the sweep saw is either changing or in one of these.
    fun keptText(kept: Kept): String =
        "Leaving ${kept.total} alone: ${kept.unitRecorded} already record the " +
            "unit they were typed in, ${kept.typedOnReading} were typed onto " +
            "a reading after the unit toggle existed, ${kept.unverifiable} " +
            "have no unit-less capture to check against, and " +
            "${kept.otherQuantity} are distance readings the defect was never " +
            "confirmed for."

    /// The result line. Counts what reached the disk, not what was planned.
    fun resultText(r: Result): String {
        val sb = StringBuilder()
        sb.append("Repaired ${r.readings} readings, ${r.captures} captures and ")
        sb.append("${r.log} research-log rows. ")
        if (r.failedCaptures > 0) {
            sb.append("${r.failedCaptures} captures could not be rewritten and are ")
            sb.append("unchanged. ")
        }
        if (r.logFailed) {
            sb.append("The research log could not be rewritten and is unchanged. ")
        }
        sb.append("${r.kept} were left alone. ")
        sb.append("Every before-and-after is in truth-repair.json.")
        return sb.toString()
    }
}

/// EVERY change the run made, written where the cruiser can pull it off the
/// device and diff it against their backup. That is the whole point of a repair
/// to research data being loud: the counts on screen say how much moved, and
/// this says exactly which numbers.
///
/// CROSS-PLATFORM: file name and every JSON key byte-identical to iOS.
object TruthUnitRepairReport {

    private const val FILE_NAME = "truth-repair.json"
    private const val SCHEMA = 1

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun save(
        context: Context,
        plan: TruthUnitRepair.Plan,
        result: TruthUnitRepair.Result,
    ) {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        val arr = JSONArray()
        for (c in plan.ordered) {
            arr.put(
                JSONObject().apply {
                    put("store", c.store)
                    put("key", c.key)
                    put("quantity", c.quantity.raw)
                    put("before", c.before)
                    put("after", c.after)
                    put("unit", c.quantity.typedUnit.raw)
                }
            )
        }
        val root = JSONObject().apply {
            put("schema", SCHEMA)
            put("ran_at", fmt.format(Date()))
            put("readings", result.readings)
            put("captures", result.captures)
            put("log", result.log)
            put("failed_captures", result.failedCaptures)
            put("log_failed", result.logFailed)
            put("kept", result.kept)
            put("changes", arr)
        }
        try {
            file(context).writeText(root.toString(2))
        } catch (_: Throwable) {
            // A lost report costs the reconciliation list and nothing else; the
            // stores were already written, and the counts on screen still say
            // how much moved.
        }
    }
}
