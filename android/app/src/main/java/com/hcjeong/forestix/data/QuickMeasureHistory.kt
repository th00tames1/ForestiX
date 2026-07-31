// On-device log of one-off measurements — port of the iOS
// QuickMeasureHistory ObservableObject. Exposes StateFlows the Compose UI
// collects (entries / plots / activePlotID / isNearCapacity), the same
// mutation surface (append / delete / clearAll + plot management), tree-
// identity helpers, and CSV / bundle export.

package com.hcjeong.forestix.data

import android.content.Context
import androidx.core.content.FileProvider
import android.net.Uri
import com.hcjeong.forestix.common.MeasuredTimeInput
import com.hcjeong.forestix.sensors.LogRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

class QuickMeasureHistory private constructor(
    private val appContext: Context,
    private val dao: QuickMeasureDao,
    private val capacity: Int = 500,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _entries = MutableStateFlow<List<QuickMeasureEntry>>(emptyList())
    val entries: StateFlow<List<QuickMeasureEntry>> = _entries.asStateFlow()

    private val _plots = MutableStateFlow<List<QuickMeasurePlot>>(emptyList())
    val plots: StateFlow<List<QuickMeasurePlot>> = _plots.asStateFlow()

    private val _activePlotID = MutableStateFlow<UUID?>(null)
    val activePlotID: StateFlow<UUID?> = _activePlotID.asStateFlow()

    private val _isNearCapacity = MutableStateFlow(false)
    val isNearCapacity: StateFlow<Boolean> = _isNearCapacity.asStateFlow()

    private suspend fun bootstrap() {
        var plots = dao.allPlots().map { it.toDomain() }.sortedByDescending { it.createdAt }
        var entries = dao.allEntries().map { it.toDomain() }

        // Ensure a permanent default plot exists; re-home orphan entries.
        if (plots.none { it.isDefault }) {
            val def = QuickMeasurePlot(
                name = "Quick measurements",
                createdAt = entries.minByOrNull { it.createdAt }?.createdAt ?: System.currentTimeMillis(),
                isDefault = true,
            )
            dao.upsertPlot(PlotRow.from(def))
            plots = plots + def
        }
        val defaultID = plots.first { it.isDefault }.id
        val orphans = entries.filter { it.plotID == null }
        if (orphans.isNotEmpty()) {
            orphans.forEach { dao.upsertEntry(EntryRow.from(it.copy(plotID = defaultID))) }
            entries = dao.allEntries().map { it.toDomain() }
        }

        _plots.value = plots.sortedByDescending { it.createdAt }
        _entries.value = entries.sortedByDescending { it.createdAt }
        _activePlotID.value = plots.firstOrNull { it.isDefault }?.id
        recomputeCapacity()
    }

    // MARK: Mutations -------------------------------------------------------

    fun append(entry: QuickMeasureEntry) {
        scope.launch {
            dao.upsertEntry(EntryRow.from(entry))
            // Enforce capacity: trim oldest beyond cap.
            val all = dao.allEntries()
            if (all.size > capacity) {
                all.drop(capacity).forEach { dao.deleteEntry(it.id) }
            }
            _entries.value = dao.allEntries().map { it.toDomain() }
            recomputeCapacity()
        }
    }

    fun delete(id: UUID) {
        scope.launch {
            // Remove the entry's auto-captured photo alongside the row.
            _entries.value.firstOrNull { it.id == id }?.photoPath?.let { name ->
                java.io.File(java.io.File(appContext.filesDir, "measure-photos"), name).delete()
            }
            dao.deleteEntry(id.toString())
            _entries.value = dao.allEntries().map { it.toDomain() }
            recomputeCapacity()
        }
    }

    /// Replace an existing entry by id (map-peek "Edit this tree"). Upsert
    /// carries REPLACE semantics, so the same-id row is overwritten in place;
    /// mirror of `delete` for the mutating quick-edit sheet.
    fun update(entry: QuickMeasureEntry) {
        scope.launch {
            dao.upsertEntry(EntryRow.from(entry))
            _entries.value = dao.allEntries().map { it.toDomain() }
            recomputeCapacity()
        }
    }

    /// Re-state WHEN one reading was measured, and put the log back in order.
    ///
    /// ONE READING AT A TIME, deliberately. The cruiser was offered a bulk
    /// offset and declined it: their notebook holds a per-tree time to the
    /// minute, so they will type each one anyway and a shared offset buys
    /// nothing. Keeping it singular also keeps the provenance simple — every
    /// hand-set time is one deliberate act on one reading.
    ///
    /// RE-CHECKS AT THE WRITE, like [repairTruthUnits]: the screen already put
    /// the picked time through [MeasuredTimeInput.resolve], but this is the
    /// call that actually writes, so the rule has to hold here too. A future
    /// time is refused and NOTHING is written.
    ///
    /// The re-sort the iOS sibling has to do by hand comes free here: every
    /// read is `ORDER BY createdAt DESC` at the DAO, so re-reading after the
    /// upsert puts the corrected reading where its new time belongs — which is
    /// what makes the row move in the field log, the point of the feature.
    ///
    /// REFUSES A NO-OP. Opening the editor and saving the minute that was
    /// already there is not an edit, and stamping "typed" on it would claim a
    /// hand-set time for a reading nobody re-timed — and would quietly throw
    /// away the seconds a sensor stamp carries, which are what makes a capture
    /// and its manifest match to better than a minute. The comparison is
    /// minute-to-minute because that is the precision the cruiser picks in.
    ///
    /// Suspends rather than firing into [scope] so the caller can act on the
    /// real outcome; returns true only when a reading actually moved.
    suspend fun setMeasuredTime(id: UUID, newTime: Long): Boolean {
        val resolved = MeasuredTimeInput.resolve(newTime)
        if (resolved !is MeasuredTimeInput.Result.Time) return false
        val existing = dao.allEntries().firstOrNull { it.id == id.toString() }
            ?.toDomain() ?: return false
        if (MeasuredTimeInput.truncatedToMinute(existing.createdAt) == resolved.epochMs) {
            return false
        }
        dao.upsertEntry(EntryRow.from(existing.settingCreatedAt(resolved.epochMs)))
        _entries.value = dao.allEntries().map { it.toDomain() }
        return true
    }

    /// Attach recovered ground truths to readings, and report how many
    /// readings actually changed.
    ///
    /// REFUSES to overwrite: an id whose reading already carries a truth is
    /// skipped here as well as in the planner, because this is the call that
    /// actually writes and the guarantee has to hold at the write. Suspends
    /// rather than firing into [scope] so the caller can show the real count;
    /// a recovery that reported a number it had not yet written would be the
    /// same class of lie as a silent one.
    suspend fun backfillTruths(attachments: Map<UUID, Double>): Int {
        if (attachments.isEmpty()) return 0
        var changed = 0
        for (row in dao.allEntries()) {
            val e = row.toDomain()
            val value = attachments[e.id] ?: continue
            if (e.truth != null) continue
            dao.upsertEntry(
                EntryRow.from(e.settingTruth(value, TruthSource.CAPTURE.raw)))
            changed++
        }
        if (changed > 0) _entries.value = dao.allEntries().map { it.toDomain() }
        return changed
    }

    /// Two truth values are the SAME value inside this band — the same number
    /// and reasoning as [TruthBackfill.VALUE_EPSILON]: every writer puts the
    /// metric base through one parser, so the only difference between two
    /// copies of a truth is float round-trip.
    private val truthValueEpsilon = 0.001

    /// Re-base ground truths that were stored at the wrong scale, and report
    /// how many readings actually changed — TruthUnitRepair's write into the
    /// reading log.
    ///
    /// RE-CHECKS AT THE WRITE. The plan was computed off a snapshot and the log
    /// can have moved since, so each reading must STILL be the one that was
    /// planned for: the truth still the pre-repair number, and still carrying
    /// no unit. A reading the cruiser retyped in between is left exactly as
    /// they left it. Suspends rather than firing into [scope] for the same
    /// reason [backfillTruths] does — a repair that reported a number it had
    /// not yet written would be the same class of lie as a silent one.
    suspend fun repairTruthUnits(repairs: Map<UUID, TruthUnitRepair.Change>): Int {
        if (repairs.isEmpty()) return 0
        var changed = 0
        for (row in dao.allEntries()) {
            val e = row.toDomain()
            val r = repairs[e.id] ?: continue
            if (e.truthUnit != null) continue
            val stored = e.truth ?: continue
            if (abs(stored - r.before) > truthValueEpsilon) continue
            dao.upsertEntry(
                EntryRow.from(e.repairingTruthUnit(r.after, r.quantity.typedUnit.raw)))
            changed++
        }
        if (changed > 0) _entries.value = dao.allEntries().map { it.toDomain() }
        return changed
    }

    /// Saves [entry] as THE reading of its kind for its (plot, tree) — any
    /// earlier reading of the same kind on the same tree is removed rather
    /// than left behind as a second one.
    ///
    /// This is what "measure it again" means from the field log: the log
    /// shows one row per (plot, tree) and picks the newest reading of each
    /// kind, so an appended re-measure left the superseded number invisible
    /// on screen but still in the CSV, where it read as a second tree visit.
    /// Readings with no tree number are never merged (the field log gives
    /// each its own row), so those just append.
    fun replaceReading(entry: QuickMeasureEntry) {
        val tree = entry.treeNumber
        if (tree == null) {
            append(entry)
            return
        }
        scope.launch {
            val default = defaultPlotID()
            val plot = entry.plotID ?: default
            val superseded = dao.allEntries().map { it.toDomain() }.filter {
                it.id != entry.id && it.kind == entry.kind &&
                    it.treeNumber == tree && (it.plotID ?: default) == plot
            }
            superseded.forEach { old ->
                old.photoPath?.let { name ->
                    File(File(appContext.filesDir, "measure-photos"), name).delete()
                }
                dao.deleteEntry(old.id.toString())
            }
            dao.upsertEntry(EntryRow.from(entry))
            // Same cap as `append` — a replacement that adds a row (the tree
            // had no reading of this kind yet) can still push past it.
            val all = dao.allEntries()
            if (all.size > capacity) {
                all.drop(capacity).forEach { dao.deleteEntry(it.id) }
            }
            _entries.value = dao.allEntries().map { it.toDomain() }
            recomputeCapacity()
        }
    }

    fun clearAll() {
        scope.launch {
            dao.clearEntries()
            _entries.value = emptyList()
            recomputeCapacity()
        }
    }

    // MARK: Plot management -------------------------------------------------

    /// [makeActive] is true for every path that creates a plot in order to
    /// measure INTO it, which is all of them but one: the field log's move
    /// picker names a destination for readings already taken, and re-pointing
    /// the next scan because the cruiser tidied up some old ones would be a
    /// silent change to where their next measurement lands. Moving data and
    /// choosing where new data goes are different acts. iOS `createPlot`
    /// carries the same flag for the same reason.
    fun createPlot(
        name: String,
        unitName: String = "",
        acres: Double? = null,
        typeRaw: String = "fixed",
        baf: Double? = null,
        radiusFt: Double? = null,
        parentPlotID: UUID? = null,
        nestedKind: String? = null,
        makeActive: Boolean = true,
    ): QuickMeasurePlot {
        val plot = QuickMeasurePlot(
            name = name, unitName = unitName, acres = acres, typeRaw = typeRaw,
            baf = baf, radiusFt = radiusFt, parentPlotID = parentPlotID,
            nestedKind = nestedKind, isDefault = false,
        )
        // Published BEFORE the write lands, not after it. The DAO round-trip
        // is a coroutine, and a caller that creates a destination and
        // immediately moves readings into it (the field log's move picker,
        // which does exactly that) would otherwise ask a plot list that does
        // not know about the plot yet and be told the destination is gone.
        // iOS inserts into its plot array synchronously for the same reason.
        _plots.value = (_plots.value + plot).sortedByDescending { it.createdAt }
        scope.launch {
            dao.upsertPlot(PlotRow.from(plot))
            _plots.value = dao.allPlots().map { it.toDomain() }.sortedByDescending { it.createdAt }
        }
        if (makeActive) _activePlotID.value = plot.id
        return plot
    }

    /// Re-homes readings into [toPlot], in ONE statement.
    ///
    /// The field log's move (see ui/screens/QuickMove.kt) exists because a
    /// reading's plot could be chosen before the measurement and never after
    /// it. This is the write behind it, and it is deliberately a SET rather
    /// than a per-entry [update]: a tree's diameter and height must not be
    /// two separate writes with a window in between where half the stem has
    /// moved.
    ///
    /// Only plotID changes — the UPDATE names one column, so no other field
    /// can be dropped on the way through. Refuses an unknown destination
    /// outright rather than filing readings under an id no plot answers to.
    /// Suspends rather than firing into [scope] so the caller can report what
    /// actually happened; a move that reported a number it had not yet
    /// written would be the same class of lie as a silent one.
    suspend fun moveEntries(ids: Set<UUID>, toPlot: UUID): Int {
        if (ids.isEmpty()) return 0
        if (_plots.value.none { it.id == toPlot }) return 0
        val changed = dao.moveEntriesToPlot(ids.map { it.toString() }, toPlot.toString())
        if (changed > 0) _entries.value = dao.allEntries().map { it.toDomain() }
        return changed
    }

    fun renamePlot(id: UUID, newName: String) {
        scope.launch {
            val p = dao.allPlots().firstOrNull { it.id == id.toString() } ?: return@launch
            dao.upsertPlot(p.copy(name = newName))
            _plots.value = dao.allPlots().map { it.toDomain() }.sortedByDescending { it.createdAt }
        }
    }

    /// Write the plot's area, or clear it.
    ///
    /// A quick plot with no area on it has its per-acre figures divided by an
    /// assumed tenth of an acre, and the summary card says so. Placing the
    /// sampling ring usually settles it — that entry carries the ring's own
    /// area — but a cruiser who knows the plot's area without having placed a
    /// ring had no way to say so on this platform at all.
    ///
    /// A non-finite or non-positive value CLEARS the area rather than storing
    /// nonsense: 0 ac would divide every density by the floor and read as a
    /// measurement, and NaN compares differently on the two platforms.
    ///
    /// Mirrors iOS `QuickMeasureHistory.setPlotAcres(id:to:)`.
    /// SUSPENDS, unlike its neighbours, because the caller has to show what
    /// LANDED rather than what was typed — the store refuses an area it cannot
    /// divide by, and a fire-and-forget write would have the sheet re-read the
    /// plot before the write finished and redraw the draft as though it had
    /// been accepted.
    suspend fun setPlotAcres(id: UUID, newAcres: Double?): QuickMeasurePlot? {
        val p = dao.allPlots().firstOrNull { it.id == id.toString() } ?: return null
        val clean = newAcres?.takeIf { it.isFinite() && it > 0.0 }
        dao.upsertPlot(p.copy(acres = clean))
        val fresh = dao.allPlots().map { it.toDomain() }.sortedByDescending { it.createdAt }
        _plots.value = fresh
        return fresh.firstOrNull { it.id == id }
    }

    fun deletePlot(id: UUID) {
        scope.launch {
            val plots = dao.allPlots().map { it.toDomain() }
            val target = plots.firstOrNull { it.id == id } ?: return@launch
            if (target.isDefault) return@launch
            val defaultID = plots.firstOrNull { it.isDefault }?.id
            dao.rehomeEntries(id.toString(), defaultID?.toString())
            dao.deletePlot(id.toString())
            if (_activePlotID.value == id) _activePlotID.value = defaultID
            _plots.value = dao.allPlots().map { it.toDomain() }.sortedByDescending { it.createdAt }
            _entries.value = dao.allEntries().map { it.toDomain() }
        }
    }

    fun setActivePlot(id: UUID) {
        if (_plots.value.any { it.id == id }) _activePlotID.value = id
    }

    fun plot(id: UUID): QuickMeasurePlot? = _plots.value.firstOrNull { it.id == id }

    fun defaultPlotID(): UUID? = _plots.value.firstOrNull { it.isDefault }?.id

    fun entriesForPlot(id: UUID?): List<QuickMeasureEntry> {
        if (id == null) return _entries.value
        val def = defaultPlotID()
        return _entries.value.filter { (it.plotID ?: def) == id }
    }

    // MARK: Tree identity ---------------------------------------------------

    val distinctTreeNumbers: List<Int>
        get() {
            val seen = LinkedHashSet<Int>()
            _entries.value.forEach { it.treeNumber?.let(seen::add) }
            return seen.sorted()
        }

    val suggestedNextTreeNumber: Int
        get() = (distinctTreeNumbers.maxOrNull() ?: 0) + 1

    /// The name already recorded against a tree, if it has one. Entries are
    /// newest-first, so a re-measurement or a chained height picks up the
    /// name the first reading on that tree was given instead of arriving
    /// nameless and splitting the tree in two in the export.
    fun treeName(forTreeNumber: Int?, plotID: UUID?): String? {
        if (forTreeNumber == null) return null
        val def = defaultPlotID()
        return _entries.value.lastOrNull {
            it.treeNumber == forTreeNumber &&
                (it.plotID ?: def) == (plotID ?: def) &&
                it.treeName != null
        }?.treeName
    }

    /// The species already recorded against a tree, if any reading carries
    /// one. Entries are newest-first and this takes the FIRST match, which is
    /// the cruiser's latest word on that stem — the same rule the map pin's
    /// peek card already reads a species by, so the chooser and the pin cannot
    /// disagree about what species a tree is.
    ///
    /// Deliberately the opposite end of the log from [treeName], which takes
    /// the tree's FIRST reading. A name is an identifier other surfaces and
    /// the export already join on, so it must not change under them; a
    /// species is an observation, and a correction made later is the better
    /// of the two.
    fun speciesCode(forTreeNumber: Int?, plotID: UUID?): String? {
        if (forTreeNumber == null) return null
        val def = defaultPlotID()
        return _entries.value.firstOrNull {
            it.treeNumber == forTreeNumber &&
                (it.plotID ?: def) == (plotID ?: def) &&
                hasSpecies(it)
        }?.speciesCode
    }

    /// The name to offer for the next tree — the HIGHEST name in the series
    /// the cruiser is currently using, stepped on by [TreeNameSequence]. Null
    /// on a log that has never been named, and then the chooser's field simply
    /// starts empty.
    ///
    /// This used to step on the most recent name, which is not the same thing:
    /// a re-measurement is appended carrying the name it already had, so
    /// re-measuring T01 after T03 made the log's newest name "T01" and the
    /// chooser proposed "T02" — a name a different stem already wears. The
    /// number suggestion beside it is `max + 1` and cannot collide; the name
    /// now matches that rule. Entries are newest-first, which is the order
    /// [TreeNameSequence.nextInSeries] expects.
    val suggestedNextTreeName: String?
        get() = TreeNameSequence.nextInSeries(
            _entries.value.mapNotNull { it.treeName })

    /// The species to offer for the next tree — the code on the most recent
    /// reading that carries one. Null on a log where nothing has been given a
    /// species, and then the picker simply opens unset.
    ///
    /// It lives here beside the name suggestion because it is the same kind of
    /// rule and the two are read together; the chooser and the field log's
    /// new-tree sheet both take it from here rather than each deciding what
    /// "the last species" means.
    ///
    /// Unlike the name, this is NOT a series that steps on — a stand is
    /// usually one species tree after tree, so the last one seen is the
    /// suggestion. Blank and whitespace-only codes are skipped: a reading
    /// saved with the species left unset must not propose "" as a species.
    ///
    /// This is a suggestion for a control, never a recorded observation. What
    /// the caller does with an untouched one is the caller's decision — see
    /// the measure chooser.
    val suggestedNextSpeciesCode: String?
        get() = _entries.value.firstOrNull { hasSpecies(it) }?.speciesCode

    /// A reading carries a species when the code is present AND not blank.
    /// `isNullOrBlank()` matches the iOS sibling's `.whitespacesAndNewlines`
    /// trim, so the same log proposes the same species on both phones.
    ///
    /// Takes the entry as a parameter rather than being an extension on it:
    /// inside an extension, a bare `speciesCode` sits next to this class's own
    /// `speciesCode(forTreeNumber:plotID:)` and reads ambiguously.
    private fun hasSpecies(e: QuickMeasureEntry): Boolean =
        !e.speciesCode.isNullOrBlank()

    fun summary(forTreeNumber: Int): String? {
        val n = forTreeNumber
        val owned = _entries.value.filter { it.treeNumber == n }
        if (owned.isEmpty()) return null
        val parts = mutableListOf<String>()
        owned.firstOrNull { it.kind == MeasureKind.DBH }?.let {
            parts.add(String.format(Locale.US, "DBH %.1f cm", it.value))
        }
        owned.firstOrNull { it.kind == MeasureKind.HEIGHT }?.let {
            parts.add(String.format(Locale.US, "Height %.2f m", it.value))
        }
        return if (parts.isEmpty()) "\u2014" else parts.joinToString(" \u00B7 ")
    }

    // MARK: Export ----------------------------------------------------------

    suspend fun exportCSV(): Uri? = withContext(Dispatchers.IO) {
        val entries = _entries.value
        if (entries.isEmpty()) return@withContext null
        val bytes = QuickMeasureExport.buildCsv(entries, _plots.value)
        writeExport("quick-measure-${stamp()}.csv", bytes)
    }

    suspend fun exportBundle(logRule: LogRule): Uri? = withContext(Dispatchers.IO) {
        val entries = _entries.value
        if (entries.isEmpty()) return@withContext null
        val bytes = QuickMeasureExport.buildBundleZip(entries, _plots.value, logRule)
        writeExport("quick-measure-bundle-${stamp()}.zip", bytes)
    }

    private fun writeExport(name: String, bytes: ByteArray): Uri {
        val dir = File(appContext.cacheDir, "Exports").apply { mkdirs() }
        val file = File(dir, name)
        file.writeBytes(bytes)
        return FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", Locale.US).format(Date())

    // MARK: Capacity --------------------------------------------------------

    private fun recomputeCapacity() {
        _isNearCapacity.value = _entries.value.size >= (capacity * 0.95).toInt()
    }

    companion object {
        @Volatile private var INSTANCE: QuickMeasureHistory? = null

        suspend fun get(context: Context, dao: QuickMeasureDao): QuickMeasureHistory {
            INSTANCE?.let { return it }
            val created = QuickMeasureHistory(context.applicationContext, dao)
            created.bootstrap()
            INSTANCE = created
            return created
        }
    }
}
