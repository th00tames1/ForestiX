package edu.oregonstate.forestrix.storage

import android.content.Context
import edu.oregonstate.forestrix.measurement.ConfidenceTier
import edu.oregonstate.forestrix.models.BreastHeightConvention
import edu.oregonstate.forestrix.models.CruiseDesign
import edu.oregonstate.forestrix.models.DbhMethod
import edu.oregonstate.forestrix.models.HeightMethod
import edu.oregonstate.forestrix.models.Plot
import edu.oregonstate.forestrix.models.PositionSource
import edu.oregonstate.forestrix.models.PositionTier
import edu.oregonstate.forestrix.models.Project
import edu.oregonstate.forestrix.models.Tree
import edu.oregonstate.forestrix.models.TreeStatus
import edu.oregonstate.forestrix.models.UnitSystem
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class FieldSession(
    val project: Project,
    val plot: Plot,
    val design: CruiseDesign
)

class ForestrixStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadSession(): FieldSession {
        val projectId = getOrCreateUuid(KEY_PROJECT_ID)
        val plotId = getOrCreateUuid(KEY_PLOT_ID)
        val project = Project(
            id = projectId,
            name = prefs.getString(KEY_PROJECT_NAME, null) ?: "McDonald-Dunn pilot",
            owner = "ForestiX Android",
            units = UnitSystem.IMPERIAL,
            breastHeightConvention = BreastHeightConvention.UPHILL
        )
        val plot = Plot(
            id = plotId,
            projectId = projectId,
            plotNumber = prefs.getInt(KEY_PLOT_NUMBER, 1),
            centerLat = java.lang.Double.longBitsToDouble(
                prefs.getLong(KEY_PLOT_LAT, java.lang.Double.doubleToRawLongBits(44.5638))
            ),
            centerLon = java.lang.Double.longBitsToDouble(
                prefs.getLong(KEY_PLOT_LON, java.lang.Double.doubleToRawLongBits(-123.2815))
            ),
            positionSource = PositionSource.MANUAL,
            positionTier = PositionTier.D,
            plotAreaAcres = 0.1f
        )
        return FieldSession(
            project = project,
            plot = plot,
            design = CruiseDesign(projectId = projectId)
        )
    }

    fun loadTrees(plotId: UUID, includeDeleted: Boolean = true): List<Tree> =
        loadAllTrees()
            .filter { it.plotId == plotId && (includeDeleted || it.deletedAtMillis == null) }
            .sortedBy { it.treeNumber }

    fun nextTreeNumber(plotId: UUID): Int {
        val maxLive = loadTrees(plotId, includeDeleted = false)
            .maxOfOrNull { it.treeNumber } ?: 0
        return maxLive + 1
    }

    fun upsertTree(tree: Tree) {
        val trees = loadAllTrees().toMutableList()
        val index = trees.indexOfFirst { it.id == tree.id }
        if (index >= 0) {
            trees[index] = tree.copy(updatedAtMillis = System.currentTimeMillis())
        } else {
            trees += tree
        }
        saveAllTrees(trees)
    }

    fun softDeleteTree(treeId: UUID) {
        val now = System.currentTimeMillis()
        saveAllTrees(loadAllTrees().map {
            if (it.id == treeId) it.copy(deletedAtMillis = now, updatedAtMillis = now) else it
        })
    }

    fun undeleteTree(treeId: UUID) {
        val now = System.currentTimeMillis()
        saveAllTrees(loadAllTrees().map {
            if (it.id == treeId) it.copy(deletedAtMillis = null, updatedAtMillis = now) else it
        })
    }

    private fun loadAllTrees(): List<Tree> {
        val raw = prefs.getString(KEY_TREES, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val json = array.optJSONObject(i) ?: continue
                json.toTreeOrNull()?.let { add(it) }
            }
        }
    }

    private fun saveAllTrees(trees: List<Tree>) {
        val array = JSONArray()
        trees.sortedWith(compareBy<Tree> { it.plotId.toString() }.thenBy { it.treeNumber })
            .forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_TREES, array.toString()).commit()
    }

    private fun getOrCreateUuid(key: String): UUID {
        val existing = prefs.getString(key, null)
        if (existing != null) {
            runCatching { return UUID.fromString(existing) }
        }
        val created = UUID.randomUUID()
        prefs.edit().putString(key, created.toString()).commit()
        return created
    }

    private fun Tree.toJson(): JSONObject = JSONObject()
        .put("id", id.toString())
        .put("plotId", plotId.toString())
        .put("treeNumber", treeNumber)
        .put("speciesCode", speciesCode)
        .put("status", status.name)
        .put("dbhCm", dbhCm.toDouble())
        .put("dbhMethod", dbhMethod.name)
        .putNullable("dbhSigmaMm", dbhSigmaMm)
        .putNullable("dbhRmseMm", dbhRmseMm)
        .putNullable("dbhCoverageDeg", dbhCoverageDeg)
        .putNullable("dbhNInliers", dbhNInliers)
        .put("dbhConfidence", dbhConfidence.name)
        .put("dbhIsIrregular", dbhIsIrregular)
        .putNullable("heightM", heightM)
        .putNullable("heightMethod", heightMethod?.name)
        .putNullable("heightSource", heightSource)
        .putNullable("heightSigmaM", heightSigmaM)
        .putNullable("heightDHM", heightDHM)
        .putNullable("heightAlphaTopDeg", heightAlphaTopDeg)
        .putNullable("heightAlphaBaseDeg", heightAlphaBaseDeg)
        .putNullable("heightConfidence", heightConfidence?.name)
        .putNullable("bearingFromCenterDeg", bearingFromCenterDeg)
        .putNullable("distanceFromCenterM", distanceFromCenterM)
        .putNullable("boundaryCall", boundaryCall)
        .putNullable("crownClass", crownClass)
        .put("damageCodes", JSONArray(damageCodes))
        .put("isMultistem", isMultistem)
        .putNullable("parentTreeId", parentTreeId?.toString())
        .put("notes", notes)
        .putNullable("photoPath", photoPath)
        .putNullable("rawScanPath", rawScanPath)
        .put("createdAtMillis", createdAtMillis)
        .put("updatedAtMillis", updatedAtMillis)
        .putNullable("deletedAtMillis", deletedAtMillis)

    private fun JSONObject.toTreeOrNull(): Tree? {
        return runCatching {
            Tree(
                id = UUID.fromString(getString("id")),
                plotId = UUID.fromString(getString("plotId")),
                treeNumber = getInt("treeNumber"),
                speciesCode = getString("speciesCode"),
                status = enumOrDefault(optString("status"), TreeStatus.LIVE),
                dbhCm = getDouble("dbhCm").toFloat(),
                dbhMethod = enumOrDefault(optString("dbhMethod"), DbhMethod.MANUAL_CALIPER),
                dbhSigmaMm = optFloat("dbhSigmaMm"),
                dbhRmseMm = optFloat("dbhRmseMm"),
                dbhCoverageDeg = optFloat("dbhCoverageDeg"),
                dbhNInliers = optIntOrNull("dbhNInliers"),
                dbhConfidence = enumOrDefault(optString("dbhConfidence"), ConfidenceTier.YELLOW),
                dbhIsIrregular = optBoolean("dbhIsIrregular", false),
                heightM = optFloat("heightM"),
                heightMethod = optStringOrNull("heightMethod")?.let {
                    enumOrDefault(it, HeightMethod.MANUAL_ENTRY)
                },
                heightSource = optStringOrNull("heightSource"),
                heightSigmaM = optFloat("heightSigmaM"),
                heightDHM = optFloat("heightDHM"),
                heightAlphaTopDeg = optFloat("heightAlphaTopDeg"),
                heightAlphaBaseDeg = optFloat("heightAlphaBaseDeg"),
                heightConfidence = optStringOrNull("heightConfidence")?.let {
                    enumOrDefault(it, ConfidenceTier.YELLOW)
                },
                bearingFromCenterDeg = optFloat("bearingFromCenterDeg"),
                distanceFromCenterM = optFloat("distanceFromCenterM"),
                boundaryCall = optStringOrNull("boundaryCall"),
                crownClass = optStringOrNull("crownClass"),
                damageCodes = optStringArray("damageCodes"),
                isMultistem = optBoolean("isMultistem", false),
                parentTreeId = optStringOrNull("parentTreeId")?.let { UUID.fromString(it) },
                notes = optString("notes", ""),
                photoPath = optStringOrNull("photoPath"),
                rawScanPath = optStringOrNull("rawScanPath"),
                createdAtMillis = optLong("createdAtMillis", System.currentTimeMillis()),
                updatedAtMillis = optLong("updatedAtMillis", System.currentTimeMillis()),
                deletedAtMillis = optLongOrNull("deletedAtMillis")
            )
        }.getOrNull()
    }

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject {
        put(key, value ?: JSONObject.NULL)
        return this
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

    private fun JSONObject.optFloat(key: String): Float? =
        if (has(key) && !isNull(key)) optDouble(key).toFloat() else null

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null

    private fun JSONObject.optStringArray(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                array.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String, default: T): T =
        runCatching { enumValueOf<T>(value) }.getOrDefault(default)

    companion object {
        private const val PREFS_NAME = "forestrix_field_store"
        private const val KEY_PROJECT_ID = "project_id"
        private const val KEY_PROJECT_NAME = "project_name"
        private const val KEY_PLOT_ID = "plot_id"
        private const val KEY_PLOT_NUMBER = "plot_number"
        private const val KEY_PLOT_LAT = "plot_lat"
        private const val KEY_PLOT_LON = "plot_lon"
        private const val KEY_TREES = "trees"
    }
}
