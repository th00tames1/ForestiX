// Room row classes — direct port of iOS Persistence/Mapping/Entities.swift
// (spec §6 entities as NSManagedObject subclasses).
//
// Each @Entity mirrors the attribute set of its Core Data counterpart 1:1
// (same class name, same property names) so cross-platform diffs line up.
// Swift UUID -> java.util.UUID via CruiseConverters, Swift Date -> Long
// epoch-millis, Int32 -> Int, optional NSNumber -> nullable Kotlin numeric.
// JSON-encoded columns (`damageCodesJSON`, `coefficientsJSON`,
// `heightSubsampleRuleJSON`) stay String here; the JSON round-trip lives in
// Mappers.kt exactly like iOS.

package com.hcjeong.forestix.data.cruise

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import java.util.UUID

/// UUID <-> String column converter shared by every cruise entity.
class CruiseConverters {
    @TypeConverter fun uuidToString(u: UUID?): String? = u?.toString()
    @TypeConverter fun stringToUuid(s: String?): UUID? = s?.let(UUID::fromString)
}

@Entity(tableName = "ProjectEntity")
data class ProjectEntity(
    @PrimaryKey val id: UUID,
    val name: String,
    val projectDescription: String,
    val owner: String,
    val createdAt: Long,
    val updatedAt: Long,
    val units: String,
    val breastHeightConvention: String,
    val slopeCorrection: Boolean,
    val lidarBiasMm: Float,
    val depthNoiseMm: Float,
    val dbhCorrectionAlpha: Float,
    val dbhCorrectionBeta: Float,
    val vioDriftFraction: Float,
)

@Entity(tableName = "StratumEntity", indices = [Index("projectId")])
data class StratumEntity(
    @PrimaryKey val id: UUID,
    val projectId: UUID,
    val name: String,
    val areaAcres: Float,
    val polygonGeoJSON: String,
)

@Entity(tableName = "CruiseDesignEntity", indices = [Index("projectId")])
data class CruiseDesignEntity(
    @PrimaryKey val id: UUID,
    val projectId: UUID,
    val plotType: String,
    val plotAreaAcres: Float?,
    val baf: Float?,
    val samplingScheme: String,
    val gridSpacingMeters: Float?,
    val heightSubsampleRuleJSON: String,
)

@Entity(tableName = "PlannedPlotEntity", indices = [Index("projectId")])
data class PlannedPlotEntity(
    @PrimaryKey val id: UUID,
    val projectId: UUID,
    val stratumId: UUID?,
    val plotNumber: Int,
    val plannedLat: Double,
    val plannedLon: Double,
    val visited: Boolean,
    val skipped: Boolean,
    /// `PositionSource.raw`, or null for a plot the generator laid / a row
    /// written before the column existed — see `PlannedPlot.plannedSource`.
    val plannedSource: String? = null,
)

@Entity(tableName = "PlotEntity", indices = [Index("projectId")])
data class PlotEntity(
    @PrimaryKey val id: UUID,
    val projectId: UUID,
    val plannedPlotId: UUID?,
    val plotNumber: Int,
    val centerLat: Double,
    val centerLon: Double,
    val positionSource: String,
    val positionTier: String,
    val gpsNSamples: Int,
    val gpsMedianHAccuracyM: Float,
    val gpsSampleStdXyM: Float,
    val offsetWalkM: Float?,
    val slopeDeg: Float,
    val aspectDeg: Float,
    val plotAreaAcres: Float,
    val startedAt: Long,
    val closedAt: Long?,
    val closedBy: String?,
    val notes: String,
    val coverPhotoPath: String?,
    val panoramaPath: String?,
)

@Entity(tableName = "TreeEntity", indices = [Index("plotId")])
data class TreeEntity(
    @PrimaryKey val id: UUID,
    val plotId: UUID,
    val treeNumber: Int,
    /// The cruiser's own name for the tree. Nullable; added in schema v4
    /// (CruiseDatabase.MIGRATION_3_4). Null reads as "#$treeNumber".
    val treeName: String? = null,
    val speciesCode: String,
    val status: String,

    val dbhCm: Float,
    val dbhMethod: String,
    val dbhSigmaMm: Float?,
    val dbhRmseMm: Float?,
    val dbhCoverageDeg: Float?,
    val dbhNInliers: Int?,
    val dbhConfidence: String,
    /// Which estimator found the diameter's edges: "auto", "manual" (the
    /// ADJUST bracket), or "typed". Nullable; added in schema v5
    /// (CruiseDatabase.MIGRATION_4_5). Null on rows written before the
    /// column existed. `dbhMethod` cannot stand in for this — a bracket
    /// and an auto fit record the same method.
    val dbhCaptureMode: String? = null,
    val dbhIsIrregular: Boolean,

    val heightM: Float?,
    val heightMethod: String?,
    val heightSource: String?,
    val heightSigmaM: Float?,
    val heightDHM: Float?,
    val heightAlphaTopDeg: Float?,
    val heightAlphaBaseDeg: Float?,
    val heightConfidence: String?,

    val bearingFromCenterDeg: Float?,
    val distanceFromCenterM: Float?,
    val boundaryCall: String?,

    val crownClass: String?,
    val damageCodesJSON: String,
    val isMultistem: Boolean,
    val parentTreeId: UUID?,

    val notes: String,
    val photoPath: String?,
    val rawScanPath: String?,

    /// Cruise-mode map pin (v3 redesign): the GPS fix captured at Accept.
    /// Nullable — trees recorded without a fix simply don't pin. Added in
    /// schema v2 (CruiseDatabase.MIGRATION_1_2).
    val latitude: Double? = null,
    val longitude: Double? = null,

    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

@Entity(tableName = "SpeciesConfigEntity")
data class SpeciesConfigEntity(
    @PrimaryKey val code: String,
    val commonName: String,
    val scientificName: String,
    val volumeEquationId: String,
    val merchTopDibCm: Float,
    val stumpHeightCm: Float,
    val expectedDbhMinCm: Float,
    val expectedDbhMaxCm: Float,
    val expectedHeightMinM: Float,
    val expectedHeightMaxM: Float,
)

@Entity(tableName = "VolumeEquationEntity")
data class VolumeEquationEntity(
    @PrimaryKey val id: String,
    val form: String,
    val coefficientsJSON: String,
    val unitsIn: String,
    val unitsOut: String,
    val sourceCitation: String,
)

@Entity(tableName = "HeightDiameterFitEntity", indices = [Index("projectId")])
data class HeightDiameterFitEntity(
    @PrimaryKey val id: UUID,
    val projectId: UUID,
    val speciesCode: String,
    val modelForm: String,
    val coefficientsJSON: String,
    val nObs: Int,
    val rmse: Float,
    val updatedAt: Long,
)
