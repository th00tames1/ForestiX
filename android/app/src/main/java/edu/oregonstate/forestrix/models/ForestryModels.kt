package edu.oregonstate.forestrix.models

import edu.oregonstate.forestrix.measurement.ConfidenceTier
import java.util.UUID

enum class UnitSystem { IMPERIAL, METRIC }
enum class PlotType { FIXED_AREA, VARIABLE_RADIUS }
enum class SamplingScheme { SYSTEMATIC_GRID, STRATIFIED_RANDOM, MANUAL }
enum class BreastHeightConvention { UPHILL, MID, ANY, CUSTOM }
enum class PositionSource { GPS_AVERAGED, VIO_OFFSET, VIO_CHAIN, EXTERNAL_RTK, MANUAL }
enum class PositionTier { A, B, C, D }
enum class TreeStatus { LIVE, DEAD_STANDING, DEAD_DOWN, CULL }

enum class DbhMethod {
    LIDAR_PARTIAL_ARC_SINGLE_VIEW,
    LIDAR_PARTIAL_ARC_DUAL_VIEW,
    LIDAR_IRREGULAR,
    RAW_DEPTH_CHORD_SILHOUETTE,
    MANUAL_CALIPER,
    MANUAL_VISUAL
}

enum class HeightMethod {
    ARCORE_VIO_WALKOFF_TANGENT,
    TAPE_TANGENT,
    MANUAL_ENTRY,
    IMPUTED_HD
}

sealed interface HeightSubsampleRule {
    data object AllTrees : HeightSubsampleRule
    data object None : HeightSubsampleRule
    data class EveryKth(val k: Int) : HeightSubsampleRule
    data class PerSpeciesCount(val minPerSpeciesOnPlot: Int) : HeightSubsampleRule
}

data class Project(
    val id: UUID = UUID.randomUUID(),
    val name: String,
    val description: String = "",
    val owner: String = "",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val units: UnitSystem = UnitSystem.IMPERIAL,
    val breastHeightConvention: BreastHeightConvention = BreastHeightConvention.UPHILL,
    val slopeCorrection: Boolean = true,
    val lidarBiasMm: Float = 0f,
    val depthNoiseMm: Float = 5f,
    val dbhCorrectionAlpha: Float = 0f,
    val dbhCorrectionBeta: Float = 1f,
    val vioDriftFraction: Float = 0.02f
)

data class Stratum(
    val id: UUID = UUID.randomUUID(),
    val projectId: UUID,
    val name: String,
    val areaAcres: Float,
    val polygonGeoJson: String
)

data class CruiseDesign(
    val id: UUID = UUID.randomUUID(),
    val projectId: UUID,
    val plotType: PlotType = PlotType.FIXED_AREA,
    val plotAreaAcres: Float? = 0.1f,
    val baf: Float? = null,
    val samplingScheme: SamplingScheme = SamplingScheme.SYSTEMATIC_GRID,
    val gridSpacingMeters: Float? = null,
    val heightSubsampleRule: HeightSubsampleRule = HeightSubsampleRule.EveryKth(5)
)

data class PlannedPlot(
    val id: UUID = UUID.randomUUID(),
    val projectId: UUID,
    val stratumId: UUID? = null,
    val plotNumber: Int,
    val plannedLat: Double,
    val plannedLon: Double,
    val visited: Boolean = false
)

data class Plot(
    val id: UUID = UUID.randomUUID(),
    val projectId: UUID,
    val plannedPlotId: UUID? = null,
    val plotNumber: Int,
    val centerLat: Double,
    val centerLon: Double,
    val positionSource: PositionSource = PositionSource.MANUAL,
    val positionTier: PositionTier = PositionTier.D,
    val gpsNSamples: Int = 0,
    val gpsMedianHAccuracyM: Float = 0f,
    val gpsSampleStdXyM: Float = 0f,
    val offsetWalkM: Float? = null,
    val slopeDeg: Float = 0f,
    val aspectDeg: Float = 0f,
    val plotAreaAcres: Float = 0.1f,
    val startedAtMillis: Long = System.currentTimeMillis(),
    val closedAtMillis: Long? = null,
    val notes: String = ""
)

data class Tree(
    val id: UUID = UUID.randomUUID(),
    val plotId: UUID,
    val treeNumber: Int,
    val speciesCode: String,
    val status: TreeStatus = TreeStatus.LIVE,
    val dbhCm: Float,
    val dbhMethod: DbhMethod,
    val dbhSigmaMm: Float? = null,
    val dbhRmseMm: Float? = null,
    val dbhCoverageDeg: Float? = null,
    val dbhNInliers: Int? = null,
    val dbhConfidence: ConfidenceTier = ConfidenceTier.GREEN,
    val dbhIsIrregular: Boolean = false,
    val heightM: Float? = null,
    val heightMethod: HeightMethod? = null,
    val heightSource: String? = null,
    val heightSigmaM: Float? = null,
    val heightDHM: Float? = null,
    val heightAlphaTopDeg: Float? = null,
    val heightAlphaBaseDeg: Float? = null,
    val heightConfidence: ConfidenceTier? = null,
    val bearingFromCenterDeg: Float? = null,
    val distanceFromCenterM: Float? = null,
    val boundaryCall: String? = null,
    val crownClass: String? = null,
    val damageCodes: List<String> = emptyList(),
    val isMultistem: Boolean = false,
    val parentTreeId: UUID? = null,
    val notes: String = "",
    val photoPath: String? = null,
    val rawScanPath: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val deletedAtMillis: Long? = null
)

data class SpeciesConfig(
    val code: String,
    val commonName: String,
    val scientificName: String = "",
    val merchTopDibCm: Float = 15.24f,
    val stumpHeightCm: Float = 30.48f,
    val expectedDbhMinCm: Float = 5f,
    val expectedDbhMaxCm: Float = 200f,
    val expectedHeightMinM: Float = 5f,
    val expectedHeightMaxM: Float = 80f
)
