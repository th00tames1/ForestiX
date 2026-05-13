package edu.oregonstate.forestrix.inventory

import edu.oregonstate.forestrix.models.CruiseDesign
import edu.oregonstate.forestrix.models.Plot
import edu.oregonstate.forestrix.models.PlotType
import edu.oregonstate.forestrix.models.SpeciesCatalog
import edu.oregonstate.forestrix.models.Tree
import edu.oregonstate.forestrix.models.TreeStatus
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

fun basalAreaM2(dbhCm: Float): Float {
    val dM = dbhCm / 100f
    return (PI.toFloat() * dM * dM) / 4f
}

fun treeFactorBaf(tree: Tree, baf: Float): Float =
    baf / basalAreaM2(tree.dbhCm)

data class PlotStats(
    val liveTreeCount: Int,
    val tpa: Float,
    val baPerAcreM2: Float,
    val qmdCm: Float,
    val grossVolumePerAcreM3: Float,
    val merchVolumePerAcreM3: Float,
    val bySpecies: Map<String, SpeciesStat>
) {
    data class SpeciesStat(
        val count: Int,
        val tpa: Float,
        val baPerAcreM2: Float,
        val grossVolumePerAcreM3: Float
    )

    companion object {
        val Empty = PlotStats(
            liveTreeCount = 0,
            tpa = 0f,
            baPerAcreM2 = 0f,
            qmdCm = 0f,
            grossVolumePerAcreM3 = 0f,
            merchVolumePerAcreM3 = 0f,
            bySpecies = emptyMap()
        )
    }
}

object PlotStatsCalculator {
    fun compute(plot: Plot, cruiseDesign: CruiseDesign, trees: List<Tree>): PlotStats {
        val live = trees.filter { it.deletedAtMillis == null && it.status == TreeStatus.LIVE }
        if (live.isEmpty()) return PlotStats.Empty

        val fixedArea = cruiseDesign.plotType == PlotType.FIXED_AREA
        val fixedEf = if (fixedArea) 1f / plot.plotAreaAcres else 0f

        var totalTpa = 0f
        var totalBa = 0f
        var sumDbhSq = 0f
        var totalGrossVolume = 0f
        var totalMerchVolume = 0f
        val species = linkedMapOf<String, MutableSpeciesStat>()

        for (tree in live) {
            val ba = basalAreaM2(tree.dbhCm)
            val ef = if (fixedArea) {
                fixedEf
            } else {
                cruiseDesign.baf?.let { if (ba > 0f) it / ba else 0f } ?: 0f
            }

            totalTpa += ef
            totalBa += ba * ef
            sumDbhSq += tree.dbhCm * tree.dbhCm

            val equation = VolumeEquations.forSpecies(tree.speciesCode)
            val height = tree.heightM ?: 0f
            var grossVolume = 0f
            if (height > 1.3f && equation != null) {
                val perTreeGross = equation.totalVolumeM3(tree.dbhCm, height)
                val perTreeMerch = SpeciesCatalog.byCode[tree.speciesCode]?.let { speciesConfig ->
                    equation.merchantableVolumeM3(
                        dbhCm = tree.dbhCm,
                        heightM = height,
                        topDibCm = speciesConfig.merchTopDibCm,
                        stumpHeightCm = speciesConfig.stumpHeightCm
                    )
                } ?: 0f
                grossVolume = perTreeGross * ef
                totalGrossVolume += grossVolume
                totalMerchVolume += perTreeMerch * ef
            }

            val bucket = species.getOrPut(tree.speciesCode) { MutableSpeciesStat() }
            bucket.count += 1
            bucket.tpa += ef
            bucket.ba += ba * ef
            bucket.grossVolume += grossVolume
        }

        val bySpecies = species.mapValues {
            PlotStats.SpeciesStat(
                count = it.value.count,
                tpa = it.value.tpa,
                baPerAcreM2 = it.value.ba,
                grossVolumePerAcreM3 = it.value.grossVolume
            )
        }

        return PlotStats(
            liveTreeCount = live.size,
            tpa = totalTpa,
            baPerAcreM2 = totalBa,
            qmdCm = sqrt(sumDbhSq / live.size),
            grossVolumePerAcreM3 = totalGrossVolume,
            merchVolumePerAcreM3 = totalMerchVolume,
            bySpecies = bySpecies
        )
    }

    private data class MutableSpeciesStat(
        var count: Int = 0,
        var tpa: Float = 0f,
        var ba: Float = 0f,
        var grossVolume: Float = 0f
    )
}

private interface VolumeEquation {
    fun totalVolumeM3(dbhCm: Float, heightM: Float): Float
    fun merchantableVolumeM3(
        dbhCm: Float,
        heightM: Float,
        topDibCm: Float,
        stumpHeightCm: Float
    ): Float
}

private object VolumeEquations {
    private val douglasFir = ImperialLogLinearVolumeEquation(
        b0 = -2.60f,
        b1 = 1.80f,
        b2 = 1.10f,
        merchFraction = 0.85f
    )
    private val westernHemlock = ImperialLogLinearVolumeEquation(
        b0 = -2.50f,
        b1 = 1.85f,
        b2 = 1.05f,
        merchFraction = 0.85f
    )
    private val westernRedcedar = SchumacherHallVolumeEquation(
        a = 0.0001f,
        b = 2.0f,
        c = 1.0f,
        merchFraction = 0.85f
    )
    private val redAlder = SchumacherHallVolumeEquation(
        a = 0.00008f,
        b = 2.0f,
        c = 1.0f,
        merchFraction = 0.85f
    )

    fun forSpecies(speciesCode: String): VolumeEquation? = when (speciesCode) {
        "DF" -> douglasFir
        "WH" -> westernHemlock
        "RC" -> westernRedcedar
        "RA" -> redAlder
        else -> null
    }
}

private data class ImperialLogLinearVolumeEquation(
    private val b0: Float,
    private val b1: Float,
    private val b2: Float,
    private val merchFraction: Float
) : VolumeEquation {
    override fun totalVolumeM3(dbhCm: Float, heightM: Float): Float {
        if (dbhCm <= 0f || heightM <= 0f) return 0f
        val dIn = dbhCm / 2.54f
        val hFt = heightM / 0.3048f
        val logV = b0 + b1 * log10(dIn.toDouble()).toFloat() + b2 * log10(hFt.toDouble()).toFloat()
        return 10.0.pow(logV.toDouble()).toFloat() * 0.0283168466f
    }

    override fun merchantableVolumeM3(
        dbhCm: Float,
        heightM: Float,
        topDibCm: Float,
        stumpHeightCm: Float
    ): Float = totalVolumeM3(dbhCm, heightM) * merchFraction
}

private data class SchumacherHallVolumeEquation(
    private val a: Float,
    private val b: Float,
    private val c: Float,
    private val merchFraction: Float
) : VolumeEquation {
    override fun totalVolumeM3(dbhCm: Float, heightM: Float): Float {
        if (dbhCm <= 0f || heightM <= 0f) return 0f
        return a * dbhCm.toDouble().pow(b.toDouble()).toFloat() *
            heightM.toDouble().pow(c.toDouble()).toFloat()
    }

    override fun merchantableVolumeM3(
        dbhCm: Float,
        heightM: Float,
        topDibCm: Float,
        stumpHeightCm: Float
    ): Float = totalVolumeM3(dbhCm, heightM) * merchFraction
}
