// On-device log of one-off measurements — port of the iOS
// QuickMeasureHistory ObservableObject. Exposes StateFlows the Compose UI
// collects (entries / plots / activePlotID / isNearCapacity), the same
// mutation surface (append / delete / clearAll + plot management), tree-
// identity helpers, and CSV / bundle export.

package com.hcjeong.forestix.data

import android.content.Context
import androidx.core.content.FileProvider
import android.net.Uri
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

    fun clearAll() {
        scope.launch {
            dao.clearEntries()
            _entries.value = emptyList()
            recomputeCapacity()
        }
    }

    // MARK: Plot management -------------------------------------------------

    fun createPlot(
        name: String,
        unitName: String = "",
        acres: Double? = null,
        typeRaw: String = "fixed",
        baf: Double? = null,
        radiusFt: Double? = null,
        parentPlotID: UUID? = null,
        nestedKind: String? = null,
    ): QuickMeasurePlot {
        val plot = QuickMeasurePlot(
            name = name, unitName = unitName, acres = acres, typeRaw = typeRaw,
            baf = baf, radiusFt = radiusFt, parentPlotID = parentPlotID,
            nestedKind = nestedKind, isDefault = false,
        )
        scope.launch {
            dao.upsertPlot(PlotRow.from(plot))
            _plots.value = dao.allPlots().map { it.toDomain() }.sortedByDescending { it.createdAt }
        }
        _activePlotID.value = plot.id
        return plot
    }

    fun renamePlot(id: UUID, newName: String) {
        scope.launch {
            val p = dao.allPlots().firstOrNull { it.id == id.toString() } ?: return@launch
            dao.upsertPlot(p.copy(name = newName))
            _plots.value = dao.allPlots().map { it.toDomain() }.sortedByDescending { it.createdAt }
        }
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

    fun summary(forTreeNumber: Int): String? {
        val n = forTreeNumber
        val owned = _entries.value.filter { it.treeNumber == n }
        if (owned.isEmpty()) return null
        val parts = mutableListOf<String>()
        owned.firstOrNull { it.kind == MeasureKind.DBH }?.let {
            parts.add(String.format(Locale.US, "DBH %.1f cm", it.value))
        }
        owned.firstOrNull { it.kind == MeasureKind.HEIGHT }?.let {
            parts.add(String.format(Locale.US, "Height %.1f m", it.value))
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
