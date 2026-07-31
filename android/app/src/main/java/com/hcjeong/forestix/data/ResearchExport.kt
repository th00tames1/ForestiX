// RESEARCH-LOG EXPORT — split the append-only research CSV into what the
// FIELD LOG still shows and what it no longer does, without destroying either.
//
// 1:1 port of iOS App/ResearchExport.swift: every user-visible string, every
// file name inside the archive, the `export_status` vocabulary and the column
// order are BYTE-IDENTICAL, so the two platforms' exports concatenate for the
// cross-platform accuracy analysis exactly as the log itself does.
//
// WHY THIS EXISTS. [ResearchLog] is append-only by design and says so in its
// own header: it writes a row on every accepted reading and nothing removes
// that row when the reading is later deleted or replaced by a retake. That is
// the right shape for a research corpus — the retake ITSELF is data, and this
// corpus has already been mined for within-burst jitter and base-to-top drift
// from rows nobody was looking at when they were written — but it is the wrong
// shape for a file handed straight to an analysis, where a superseded reading
// sitting quietly beside its replacement is how one tree gets counted twice.
//
// SO NOTHING IS DELETED AND NOTHING IS HIDDEN. One export produces TWO CSVs in
// one archive: `research_log.csv`, which is what the field log shows, and
// `research_log_superseded.csv`, which is everything held back from it. Both
// ship, both carry the `export_status` column that says which rule put the row
// where it is, and the on-disk log is not touched by any of it.
//
// THE JOIN, AND WHY IT IS A HEURISTIC. A research row identifies itself by
// `timestamp_iso`, `measure_type`, `tree_id` (a tree NUMBER, not a reading id)
// and `measured_value`. There is NO reading id in the schema and no plot
// column, so a row cannot be joined to the reading it was written for; it can
// only be matched to the set of readings that share its kind and tree number.
// That set is ambiguous in ways this file refuses to guess through:
//
//   * A cruise measurement writes a research row but NO quick-measure reading
//     (the cruise flow stores a cruise Tree), and cruise and quick trees share
//     one tree-number namespace.
//   * The quick log holds the last 500 readings; an older reading is evicted
//     by capacity, not by the cruiser.
//   * A tree number that exists in two plots names two different stems, and
//     the row records no plot — the same wall [TruthBackfill.plan] stops at.
//   * Distance rows deliberately carry no tree_id at all.
//
// So `superseded` is asserted ONLY on positive evidence (below), everything
// else that cannot be matched is `unclassified`, and an unclassified row stays
// in the DEFAULT file — withholding it would be the wrong error, because a
// wrong join here drops good data. Being in the wrong one of two shipped files
// is a recoverable mistake; being absent is not.

package com.hcjeong.forestix.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.hcjeong.forestix.common.TruthInput
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

object ResearchExport {

    // MARK: - Vocabulary

    /// The column the exporter appends to every exported row. It exists only
    /// in the EXPORT, never in the stored log: "is this row still what the
    /// field log shows" is a fact about the log as it is right now, and it
    /// changes every time a reading is deleted or retaken. Freezing that
    /// judgement into an append-only row at write time would make it wrong the
    /// moment the cruiser retook the tree.
    ///
    /// Appended LAST, after `tracking_dropped`, by the same rule the log's own
    /// schema follows: new columns go at the end so an existing reader still
    /// lines up.
    const val STATUS_COLUMN = "export_status"

    /// What one exported row is, relative to the field log.
    enum class Status(val raw: String) {
        /// A live reading of this kind, tree and value is in the field log,
        /// and its ground truth agrees with the row's.
        LIVE("live"),

        /// Matched to a live reading whose GROUND TRUTH differs — the cruiser
        /// typed a truth, then corrected it. This row carries the corrected
        /// value; the as-recorded original is in the held-back file as
        /// `superseded-truth`.
        CORRECTED("corrected"),

        /// The tree still has a live reading of this kind, a LATER one, and
        /// this row's measured value is not any of them: a retake, or a
        /// delete-and-remeasure. The only positive evidence available.
        SUPERSEDED("superseded"),

        /// The as-recorded copy of a `corrected` row — held back, never
        /// discarded, so the truth the cruiser first typed is still in the
        /// export beside the one they replaced it with.
        SUPERSEDED_TRUTH("superseded-truth"),

        /// No live reading this row can be matched to, or nothing to match on.
        /// NOT a claim that the reading is gone — see the file header.
        UNCLASSIFIED("unclassified"),
    }

    /// The file the analysis reads: what the field log shows.
    const val DEFAULT_FILE_NAME = "research_log.csv"

    /// Everything held back from it. Ships in the same archive.
    const val HELD_BACK_FILE_NAME = "research_log_superseded.csv"

    /// The counts and the rule, so the analyst reads them too and not only the
    /// cruiser who tapped Export.
    const val NOTES_FILE_NAME = "research_log_export.txt"

    const val ARCHIVE_FILE_NAME = "forestix_research_export.zip"

    /// A logged `measured_value` and a stored reading value are the SAME
    /// measurement inside this band. The log rounds to two decimals ("%.2f" on
    /// the scan screens), so the band is half of the last recorded digit —
    /// anything tighter would fail to match a row against the very reading
    /// that wrote it. Deliberately NOT the log's truth epsilon (0.001), which
    /// compares two full-precision copies of the same stored number and has no
    /// rounding to absorb.
    const val VALUE_EPSILON = 0.005

    /// Same reasoning for `true_value`, which the screens also write at two
    /// decimals.
    const val TRUTH_EPSILON = 0.005

    // MARK: - What the classification reads

    /// A live reading, reduced to the fields the join needs. A projection
    /// rather than the entry itself so the classification is pure data.
    data class Reading(
        val kind: String,
        val treeNumber: Int?,
        val value: Double,
        val createdAt: Long,
        val truth: Double?,
        val truthUnit: String?,
    )

    /// Every row is in exactly one of the first four counts, so [total] is the
    /// number of rows in the log and the cruiser can reconcile it against the
    /// row count Settings shows. [supersededTruth] is NOT in the total: those
    /// rows are copies, not rows of their own.
    data class Counts(
        var live: Int = 0,
        var corrected: Int = 0,
        var unclassified: Int = 0,
        var superseded: Int = 0,
        var supersededTruth: Int = 0,
    ) {
        val total: Int get() = live + corrected + unclassified + superseded
    }

    /// The two files, as rows, plus what went where.
    data class Classified(
        val header: List<String>,
        val kept: List<List<String>>,
        val heldBack: List<List<String>>,
        val counts: Counts,
    )

    // MARK: - Classification

    /// Split the log's records into the two files. Pure: reads nothing, writes
    /// nothing, invents no value.
    ///
    /// [records] is the log exactly as [ResearchLog] parses it — header first.
    /// Returns null when there is no header, which is the only shape that
    /// cannot be classified at all.
    fun classify(records: List<List<String>>, readings: List<Reading>): Classified? {
        val header = records.firstOrNull() ?: return null
        val idx = Index(header)
        // Live readings that name a tree, grouped by the only key a row can
        // offer. A reading with no tree number can never be matched to a row
        // and is simply not in the index. Readings are held by INDEX, not by
        // value, because the pairing below has to be able to say "this reading
        // is taken" — see [claim].
        val groups = HashMap<GroupKey, MutableList<Int>>()
        readings.forEachIndexed { i, r ->
            val tree = r.treeNumber ?: return@forEachIndexed
            groups.getOrPut(GroupKey(r.kind, tree)) { ArrayList() }.add(i)
        }

        // Each row parsed ONCE. A short row (written by a build with fewer
        // columns and never re-emitted) is padded rather than skipped, so the
        // cells this reads land where the header says they are — the same rule
        // the log's own repair pass follows.
        val rows = ArrayList<Row>()
        records.drop(1).forEachIndexed { i, raw ->
            val cells = ArrayList(raw)
            while (cells.size < header.size) cells.add("")
            val tree = cell(cells, idx.tree).toIntOrNull()
            rows.add(
                Row(
                    index = i,
                    cells = cells,
                    key = tree?.let { GroupKey(cell(cells, idx.type), it) },
                    measured = TruthInput.parse(cell(cells, idx.measured)),
                    stamp = TruthBackfill.parseIso(cell(cells, idx.timestamp)),
                )
            )
        }

        val claimed = claim(rows, readings, groups)

        val kept = ArrayList<List<String>>()
        val heldBack = ArrayList<List<String>>()
        val counts = Counts()
        for (row in rows) {
            when (val v = verdict(row, claimed, readings, groups, idx)) {
                is Verdict.Plain -> {
                    // Plain is only ever LIVE or UNCLASSIFIED — the other three
                    // statuses have their own verdicts because they decide
                    // WHICH file the row goes in, not just what it is labelled.
                    kept.add(row.cells + v.status.raw)
                    if (v.status == Status.LIVE) counts.live++ else counts.unclassified++
                }
                is Verdict.Superseded -> {
                    heldBack.add(row.cells + Status.SUPERSEDED.raw)
                    counts.superseded++
                }
                is Verdict.Corrected -> {
                    kept.add(v.rebased + Status.CORRECTED.raw)
                    heldBack.add(row.cells + Status.SUPERSEDED_TRUTH.raw)
                    counts.corrected++
                    counts.supersededTruth++
                }
            }
        }
        return Classified(header + STATUS_COLUMN, kept, heldBack, counts)
    }

    /// One row of the log, parsed once: the cells, and the three things the
    /// join reads out of them.
    private class Row(
        val index: Int,
        val cells: List<String>,
        val key: GroupKey?,
        val measured: Double?,
        val stamp: Long?,
    )

    /// One row's fate. [Verdict.Corrected] carries the REBASED row, so the
    /// caller writes the corrected copy to the default file and the row it was
    /// handed to the held-back file.
    private sealed class Verdict {
        class Plain(val status: Status) : Verdict()
        object Superseded : Verdict()
        class Corrected(val rebased: List<String>) : Verdict()
    }

    private data class GroupKey(val kind: String, val tree: Int)

    /// Column positions, resolved once against the header on disk rather than
    /// against [ResearchLog.COLUMNS]: a log this build has not migrated yet may
    /// legitimately be missing a column, and reading by name is what keeps that
    /// from shifting every cell.
    private class Index(header: List<String>) {
        val type = header.indexOf("measure_type")
        val tree = header.indexOf("tree_id")
        val measured = header.indexOf("measured_value")
        val truth = header.indexOf("true_value")
        val error = header.indexOf("error")
        val unit = header.indexOf("truth_unit")
        val timestamp = header.indexOf("timestamp_iso")
    }

    private fun cell(row: List<String>, i: Int): String =
        if (i in row.indices) row[i] else ""

    /// Row index -> reading index, one reading to at most one row.
    ///
    /// ONE READING, ONE ROW is the whole point. A tree measured three times
    /// leaves three rows, and if the retake happened to land on the same
    /// diameter as the keeper then two of them match the surviving reading by
    /// value — and both would be called `live`, which is exactly the
    /// counted-twice the cruiser reported. Claiming globally smallest-dt first
    /// gives the reading to the row that actually wrote it and pushes the
    /// others onto the supersession test, and it resolves identically on every
    /// run and on both platforms because the ordering is total. Same rule and
    /// same reason as [TruthBackfill.plan].
    ///
    /// A row this build cannot place in time sorts LAST (its dt is unknown, not
    /// zero) rather than being dropped: it can still claim a reading no dated
    /// row wanted.
    private fun claim(
        rows: List<Row>,
        readings: List<Reading>,
        groups: Map<GroupKey, List<Int>>,
    ): Map<Int, Int> {
        class Pair(val row: Int, val reading: Int, val delta: Double)
        val pairs = ArrayList<Pair>()
        for (row in rows) {
            val key = row.key ?: continue
            val measured = row.measured ?: continue
            val candidates = groups[key] ?: continue
            // Rows carry the measurement at two (sometimes three) decimals, so
            // the match is a band, not equality — see [VALUE_EPSILON].
            for (ri in candidates) {
                if (abs(readings[ri].value - measured) > VALUE_EPSILON) continue
                val delta = row.stamp
                    ?.let { abs(readings[ri].createdAt - it).toDouble() }
                    ?: Double.MAX_VALUE
                pairs.add(Pair(row.index, ri, delta))
            }
        }
        pairs.sortWith(
            compareBy<Pair> { it.delta }.thenBy { it.row }.thenBy { it.reading })
        val byRow = HashMap<Int, Int>()
        val takenReadings = HashSet<Int>()
        for (p in pairs) {
            if (byRow.containsKey(p.row) || takenReadings.contains(p.reading)) continue
            byRow[p.row] = p.reading
            takenReadings.add(p.reading)
        }
        return byRow
    }

    private fun verdict(
        row: Row,
        claimed: Map<Int, Int>,
        readings: List<Reading>,
        groups: Map<GroupKey, List<Int>>,
        idx: Index,
    ): Verdict {
        val readingIndex = claimed[row.index]
        if (readingIndex == null) {
            // Unmatched. SUPERSEDED needs positive evidence: the tree must
            // still have a live reading OF THIS KIND, and it must have been
            // taken LATER than this row. Without a tree number, without a
            // parseable timestamp, or with no later reading, the row says
            // nothing — a cruise measurement, a reading past the 500-row cap, a
            // cleared log and a deletion all look identical from here, and the
            // log cannot tell them apart.
            val key = row.key ?: return Verdict.Plain(Status.UNCLASSIFIED)
            val stamp = row.stamp ?: return Verdict.Plain(Status.UNCLASSIFIED)
            val candidates = groups[key] ?: return Verdict.Plain(Status.UNCLASSIFIED)
            if (candidates.any { readings[it].createdAt > stamp }) return Verdict.Superseded
            return Verdict.Plain(Status.UNCLASSIFIED)
        }
        val reading = readings[readingIndex]
        // Matched. The remaining question is the GROUND TRUTH: a truth typed
        // wrong and corrected in the field log leaves the wrong number here.
        val logged = TruthInput.parsePositive(cell(row.cells, idx.truth))
        val stored = reading.truth
        if (logged == null && stored == null) return Verdict.Plain(Status.LIVE)
        if (logged != null && stored != null && abs(logged - stored) <= TRUTH_EPSILON) {
            return Verdict.Plain(Status.LIVE)
        }
        // `measured` is non-null on any claimed row — a row with no measured
        // value can never have matched one.
        return Verdict.Corrected(rebased(row.cells, idx, reading, row.measured ?: 0.0))
    }

    /// This row with its ground truth taken from the READING — the value the
    /// field log shows — and `error` recomputed from the row's own frozen
    /// `measured_value`.
    ///
    /// Nothing is invented. Both numbers are the cruiser's own: the truth is
    /// the one they corrected to, and the error is arithmetic over a
    /// measurement this pass does not touch (the estimator is frozen), exactly
    /// as [ResearchLog.repairImperialTruths] recomputes it. A cleared truth
    /// clears the error and the unit with it rather than leaving a number
    /// computed against a truth that is no longer there.
    private fun rebased(
        row: List<String>,
        idx: Index,
        reading: Reading,
        measured: Double,
    ): List<String> {
        val out = ArrayList(row)
        fun put(i: Int, value: String) {
            if (i in out.indices) out[i] = value
        }
        val truth = reading.truth
        if (truth != null) {
            put(idx.truth, String.format(Locale.US, "%.2f", truth))
            put(idx.error, String.format(Locale.US, "%.2f", measured - truth))
            // The unit comes from the reading too: carrying the old row's unit
            // beside a new value would stamp a number with a unit nobody typed
            // it in. Empty means NOT STATED, the same as everywhere else.
            put(idx.unit, reading.truthUnit ?: "")
        } else {
            put(idx.truth, "")
            put(idx.error, "")
            put(idx.unit, "")
        }
        return out
    }

    // MARK: - Wording (byte-identical to iOS)

    /// The line the cruiser reads at the moment of export. Every count is
    /// named: a quiet filter is how someone later concludes data went missing.
    ///
    /// No plural branching anywhere — the two platforms have to emit the same
    /// bytes, and "1 rows" is a smaller cost than two pluralisation rules
    /// drifting apart. Same rule as [TruthBackfill.summary].
    fun summary(c: Counts): String =
        "Exported ${c.total} research rows: ${c.live} live, " +
            "${c.corrected} truth-corrected, ${c.unclassified} unclassifiable, " +
            "${c.superseded} superseded. The first three are in " +
            "$DEFAULT_FILE_NAME; the superseded ones, plus ${c.supersededTruth} " +
            "as-recorded copies of the truth-corrected rows, are in " +
            "$HELD_BACK_FILE_NAME. Nothing was deleted from this device."

    const val EMPTY_MESSAGE = "No research rows on this device."
    const val FAILURE_MESSAGE =
        "Research export failed — the log could not be read or written."

    /// Shipped inside the archive so the analyst gets the same accounting and
    /// the same caveat the cruiser did, rather than inferring a filter from two
    /// files with different row counts.
    fun notes(c: Counts): String {
        val sb = StringBuilder(summary(c)).append("\n\n")
        sb.append("export_status values\n")
        sb.append("  live              a reading with this kind, tree and value is in the field log\n")
        sb.append("  corrected         matched, but the field log's ground truth differs; true_value,\n")
        sb.append("                    error and truth_unit here are the field log's, and the row as\n")
        sb.append("                    recorded is in $HELD_BACK_FILE_NAME as superseded-truth\n")
        sb.append("  unclassified      no live reading to match — a cruise measurement, a reading past\n")
        sb.append("                    the 500-row cap, a cleared log, or a deletion. The log cannot\n")
        sb.append("                    say which, so it does not guess, and the row is kept here\n")
        sb.append("  superseded        the tree still has a LATER live reading of this kind and this\n")
        sb.append("                    value is not it: a retake or a delete-and-remeasure\n")
        sb.append("  superseded-truth  the as-recorded copy of a corrected row\n\n")
        sb.append("The research log carries no reading id and no plot, so rows are matched on\n")
        sb.append("measure_type, tree_id, measured_value and timestamp_iso only. That join is a\n")
        sb.append("heuristic: superseded is asserted only on the positive evidence above, and\n")
        sb.append("everything else stays unclassified rather than being guessed into a bucket.\n")
        return sb.toString()
    }

    // MARK: - Building the archive

    /// The two CSVs and the notes, as one archive. Rows are re-emitted through
    /// the log's own escaping so a note containing a comma survives the round
    /// trip.
    fun archiveBytes(c: Classified): ByteArray {
        fun csv(rows: List<List<String>>): ByteArray {
            val sb = StringBuilder()
            sb.append(c.header.joinToString(",") { ResearchLog.csvEscape(it) }).append('\n')
            for (row in rows) {
                sb.append(row.joinToString(",") { ResearchLog.csvEscape(it) }).append('\n')
            }
            return sb.toString().toByteArray(Charsets.UTF_8)
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            listOf(
                DEFAULT_FILE_NAME to csv(c.kept),
                HELD_BACK_FILE_NAME to csv(c.heldBack),
                NOTES_FILE_NAME to notes(c.counts).toByteArray(Charsets.UTF_8),
            ).forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    // MARK: - Running it

    /// The readings the classification is judged against.
    fun readings(entries: List<QuickMeasureEntry>): List<Reading> =
        entries.map {
            Reading(it.kind.raw, it.treeNumber, it.value, it.createdAt,
                it.truth, it.truthUnit)
        }

    /// What one export produced: the file to share (null when nothing was
    /// written) and the sentence the cruiser reads.
    data class Outcome(val uri: Uri?, val message: String)

    /// Read the log, classify it against the field log, write the archive, and
    /// return the file to share plus the sentence the cruiser reads.
    ///
    /// Parses the whole research CSV, so callers run it off the main thread —
    /// the same shape as the ground-truth recovery beside it in Settings.
    fun run(context: Context, entries: List<QuickMeasureEntry>): Outcome {
        val records = ResearchLog.snapshotRecords(context)
        if (records == null || records.size < 2) return Outcome(null, EMPTY_MESSAGE)
        val classified = classify(records, readings(entries))
            ?: return Outcome(null, FAILURE_MESSAGE)
        val uri = write(context, archiveBytes(classified))
            ?: return Outcome(null, FAILURE_MESSAGE)
        return Outcome(uri, summary(classified.counts))
    }

    /// Beside the quick-measure exports, under a FIXED name: the previous
    /// export is replaced rather than accumulating a folder of near-identical
    /// archives the cruiser then has to tell apart by timestamp.
    private fun write(context: Context, bytes: ByteArray): Uri? = try {
        val dir = File(context.cacheDir, "Exports").apply { mkdirs() }
        val file = File(dir, ARCHIVE_FILE_NAME)
        file.writeBytes(bytes)
        FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file)
    } catch (_: Exception) {
        null
    }
}
