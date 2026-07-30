// Bidirectional model <-> Room row mapping — direct port of iOS
// Persistence/Mapping/Mappers.swift.
//
// Each mapper keeps the iOS function names:
//   - `apply(s)` builds the row from the model. (On iOS `apply` writes onto
//     an existing NSManagedObject; Room rows are immutable data classes, so
//     the Kotlin version returns a fresh row that the DAO upserts.)
//   - `toStruct(e)` reads the row back into the model, throwing
//     CruiseDataError.MappingFailed on an unrecognized enum raw value —
//     byte-for-byte the same failure contract as iOS CoreDataError.
//
// JSON-encoded fields ([String] damageCodes, [String: Float] coefficients)
// are serialized here rather than via Room converters for portability,
// matching the iOS decision to serialize in Mappers.swift.

package com.hcjeong.forestix.data.cruise

import com.hcjeong.forestix.sensors.ConfidenceTier
import com.hcjeong.forestix.sensors.DBHMethod
import com.hcjeong.forestix.sensors.HeightMethod
import org.json.JSONArray
import org.json.JSONObject

// MARK: - JSON helpers

/// Port of the iOS `JSONFields` enum. Swift uses generic Codable; Kotlin
/// (no serialization plugin in this module) ships the two shapes we need.
object JSONFields {

    /// `["a","b"]` — same shape Swift's JSONEncoder emits for `[String]`.
    fun encodeStringList(value: List<String>): String {
        val arr = JSONArray()
        for (v in value) arr.put(v)
        return arr.toString()
    }

    fun decodeStringList(json: String, fallback: List<String> = emptyList()): List<String> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            fallback
        }
    }

    /// `{"a":0.1,"b":0.05}` — same shape Swift emits for `[String: Float]`.
    fun encodeFloatMap(value: Map<String, Float>): String {
        val obj = JSONObject()
        for ((k, v) in value) obj.put(k, v.toDouble())
        return obj.toString()
    }

    fun decodeFloatMap(json: String, fallback: Map<String, Float> = emptyMap()): Map<String, Float> {
        return try {
            val obj = JSONObject(json)
            buildMap {
                for (key in obj.keys()) put(key, obj.getDouble(key).toFloat())
            }
        } catch (_: Exception) {
            fallback
        }
    }
}

// MARK: - ProjectEntity <-> Project

object ProjectMapper {
    fun apply(s: Project) = ProjectEntity(
        id = s.id,
        name = s.name,
        projectDescription = s.description,
        owner = s.owner,
        createdAt = s.createdAt,
        updatedAt = s.updatedAt,
        units = s.units.raw,
        breastHeightConvention = s.breastHeightConvention.raw,
        slopeCorrection = s.slopeCorrection,
        lidarBiasMm = s.lidarBiasMm,
        depthNoiseMm = s.depthNoiseMm,
        dbhCorrectionAlpha = s.dbhCorrectionAlpha,
        dbhCorrectionBeta = s.dbhCorrectionBeta,
        vioDriftFraction = s.vioDriftFraction,
    )

    @Throws(CruiseDataError::class)
    fun toStruct(e: ProjectEntity): Project {
        val units = UnitSystem.fromRaw(e.units)
            ?: throw CruiseDataError.MappingFailed("Project.units '${e.units}'")
        val bhc = BreastHeightConvention.fromRaw(e.breastHeightConvention)
            ?: throw CruiseDataError.MappingFailed(
                "Project.breastHeightConvention '${e.breastHeightConvention}'")
        return Project(
            id = e.id,
            name = e.name,
            description = e.projectDescription,
            owner = e.owner,
            createdAt = e.createdAt,
            updatedAt = e.updatedAt,
            units = units,
            breastHeightConvention = bhc,
            slopeCorrection = e.slopeCorrection,
            lidarBiasMm = e.lidarBiasMm,
            depthNoiseMm = e.depthNoiseMm,
            dbhCorrectionAlpha = e.dbhCorrectionAlpha,
            dbhCorrectionBeta = e.dbhCorrectionBeta,
            vioDriftFraction = e.vioDriftFraction,
        )
    }
}

// MARK: - StratumEntity <-> Stratum

object StratumMapper {
    fun apply(s: Stratum) = StratumEntity(
        id = s.id,
        projectId = s.projectId,
        name = s.name,
        areaAcres = s.areaAcres,
        polygonGeoJSON = s.polygonGeoJSON,
    )

    fun toStruct(e: StratumEntity) = Stratum(
        id = e.id,
        projectId = e.projectId,
        name = e.name,
        areaAcres = e.areaAcres,
        polygonGeoJSON = e.polygonGeoJSON,
    )
}

// MARK: - CruiseDesignEntity <-> CruiseDesign

object CruiseDesignMapper {
    fun apply(s: CruiseDesign) = CruiseDesignEntity(
        id = s.id,
        projectId = s.projectId,
        plotType = s.plotType.raw,
        plotAreaAcres = s.plotAreaAcres,
        baf = s.baf,
        samplingScheme = s.samplingScheme.raw,
        gridSpacingMeters = s.gridSpacingMeters,
        heightSubsampleRuleJSON = s.heightSubsampleRule.toJsonString(),
    )

    @Throws(CruiseDataError::class)
    fun toStruct(e: CruiseDesignEntity): CruiseDesign {
        val pt = PlotType.fromRaw(e.plotType)
            ?: throw CruiseDataError.MappingFailed("CruiseDesign.plotType '${e.plotType}'")
        val ss = SamplingScheme.fromRaw(e.samplingScheme)
            ?: throw CruiseDataError.MappingFailed(
                "CruiseDesign.samplingScheme '${e.samplingScheme}'")
        // iOS falls back to .everyKth(k: 5) on any undecodable rule JSON.
        val rule = HeightSubsampleRule.fromJsonString(e.heightSubsampleRuleJSON)
            ?: HeightSubsampleRule.EveryKth(k = 5)
        return CruiseDesign(
            id = e.id,
            projectId = e.projectId,
            plotType = pt,
            plotAreaAcres = e.plotAreaAcres,
            baf = e.baf,
            samplingScheme = ss,
            gridSpacingMeters = e.gridSpacingMeters,
            heightSubsampleRule = rule,
        )
    }
}

// MARK: - PlannedPlotEntity <-> PlannedPlot

object PlannedPlotMapper {
    fun apply(s: PlannedPlot) = PlannedPlotEntity(
        id = s.id,
        projectId = s.projectId,
        stratumId = s.stratumId,
        plotNumber = s.plotNumber,
        plannedLat = s.plannedLat,
        plannedLon = s.plannedLon,
        visited = s.visited,
        skipped = s.skipped,
        plannedSource = s.plannedSource?.raw,
    )

    fun toStruct(e: PlannedPlotEntity) = PlannedPlot(
        id = e.id,
        projectId = e.projectId,
        stratumId = e.stratumId,
        plotNumber = e.plotNumber,
        plannedLat = e.plannedLat,
        plannedLon = e.plannedLon,
        visited = e.visited,
        skipped = e.skipped,
        // An unrecognised string decodes to null rather than to a borrowed
        // case: "I don't know how this point was placed" is the honest
        // reading of a value this build cannot name.
        plannedSource = PositionSource.fromRaw(e.plannedSource),
    )
}

// MARK: - PlotEntity <-> Plot

object PlotMapper {
    fun apply(s: Plot) = PlotEntity(
        id = s.id,
        projectId = s.projectId,
        plannedPlotId = s.plannedPlotId,
        plotNumber = s.plotNumber,
        centerLat = s.centerLat,
        centerLon = s.centerLon,
        positionSource = s.positionSource.raw,
        positionTier = s.positionTier.raw,
        gpsNSamples = s.gpsNSamples,
        gpsMedianHAccuracyM = s.gpsMedianHAccuracyM,
        gpsSampleStdXyM = s.gpsSampleStdXyM,
        offsetWalkM = s.offsetWalkM,
        slopeDeg = s.slopeDeg,
        aspectDeg = s.aspectDeg,
        plotAreaAcres = s.plotAreaAcres,
        startedAt = s.startedAt,
        closedAt = s.closedAt,
        closedBy = s.closedBy,
        notes = s.notes,
        coverPhotoPath = s.coverPhotoPath,
        panoramaPath = s.panoramaPath,
    )

    @Throws(CruiseDataError::class)
    fun toStruct(e: PlotEntity): Plot {
        val src = PositionSource.fromRaw(e.positionSource)
            ?: throw CruiseDataError.MappingFailed("Plot.positionSource '${e.positionSource}'")
        val tier = PositionTier.fromRaw(e.positionTier)
            ?: throw CruiseDataError.MappingFailed("Plot.positionTier '${e.positionTier}'")
        return Plot(
            id = e.id,
            projectId = e.projectId,
            plannedPlotId = e.plannedPlotId,
            plotNumber = e.plotNumber,
            centerLat = e.centerLat,
            centerLon = e.centerLon,
            positionSource = src,
            positionTier = tier,
            gpsNSamples = e.gpsNSamples,
            gpsMedianHAccuracyM = e.gpsMedianHAccuracyM,
            gpsSampleStdXyM = e.gpsSampleStdXyM,
            offsetWalkM = e.offsetWalkM,
            slopeDeg = e.slopeDeg,
            aspectDeg = e.aspectDeg,
            plotAreaAcres = e.plotAreaAcres,
            startedAt = e.startedAt,
            closedAt = e.closedAt,
            closedBy = e.closedBy,
            notes = e.notes,
            coverPhotoPath = e.coverPhotoPath,
            panoramaPath = e.panoramaPath,
        )
    }
}

// MARK: - TreeEntity <-> Tree

object TreeMapper {
    fun apply(s: Tree) = TreeEntity(
        id = s.id,
        plotId = s.plotId,
        treeNumber = s.treeNumber,
        treeName = s.treeName,
        speciesCode = s.speciesCode,
        status = s.status.raw,

        dbhCm = s.dbhCm,
        dbhMethod = s.dbhMethod.raw,
        dbhSigmaMm = s.dbhSigmaMm,
        dbhRmseMm = s.dbhRmseMm,
        dbhCoverageDeg = s.dbhCoverageDeg,
        dbhNInliers = s.dbhNInliers,
        dbhConfidence = s.dbhConfidence.raw,
        dbhCaptureMode = s.dbhCaptureMode,
        dbhIsIrregular = s.dbhIsIrregular,

        heightM = s.heightM,
        heightMethod = s.heightMethod?.raw,
        heightSource = s.heightSource,
        heightSigmaM = s.heightSigmaM,
        heightDHM = s.heightDHM,
        heightAlphaTopDeg = s.heightAlphaTopDeg,
        heightAlphaBaseDeg = s.heightAlphaBaseDeg,
        heightConfidence = s.heightConfidence?.raw,

        bearingFromCenterDeg = s.bearingFromCenterDeg,
        distanceFromCenterM = s.distanceFromCenterM,
        boundaryCall = s.boundaryCall,

        crownClass = s.crownClass,
        damageCodesJSON = JSONFields.encodeStringList(s.damageCodes),
        isMultistem = s.isMultistem,
        parentTreeId = s.parentTreeId,

        notes = s.notes,
        photoPath = s.photoPath,
        rawScanPath = s.rawScanPath,

        latitude = s.latitude,
        longitude = s.longitude,

        createdAt = s.createdAt,
        updatedAt = s.updatedAt,
        deletedAt = s.deletedAt,
    )

    @Throws(CruiseDataError::class)
    fun toStruct(e: TreeEntity): Tree {
        val status = TreeStatus.fromRaw(e.status)
            ?: throw CruiseDataError.MappingFailed("Tree.status '${e.status}'")
        val method = DBHMethod.entries.firstOrNull { it.raw == e.dbhMethod }
            ?: throw CruiseDataError.MappingFailed("Tree.dbhMethod '${e.dbhMethod}'")
        val dbhConf = ConfidenceTier.entries.firstOrNull { it.raw == e.dbhConfidence }
            ?: throw CruiseDataError.MappingFailed("Tree.dbhConfidence '${e.dbhConfidence}'")
        val heightMethod: HeightMethod? = e.heightMethod?.let { raw ->
            HeightMethod.entries.firstOrNull { it.raw == raw }
                ?: throw CruiseDataError.MappingFailed("Tree.heightMethod '$raw'")
        }
        val heightConf: ConfidenceTier? = e.heightConfidence?.let { raw ->
            ConfidenceTier.entries.firstOrNull { it.raw == raw }
                ?: throw CruiseDataError.MappingFailed("Tree.heightConfidence '$raw'")
        }
        return Tree(
            id = e.id,
            plotId = e.plotId,
            treeNumber = e.treeNumber,
            treeName = e.treeName,
            speciesCode = e.speciesCode,
            status = status,
            dbhCm = e.dbhCm,
            dbhMethod = method,
            dbhSigmaMm = e.dbhSigmaMm,
            dbhRmseMm = e.dbhRmseMm,
            dbhCoverageDeg = e.dbhCoverageDeg,
            dbhNInliers = e.dbhNInliers,
            dbhConfidence = dbhConf,
            dbhCaptureMode = e.dbhCaptureMode,
            dbhIsIrregular = e.dbhIsIrregular,
            heightM = e.heightM,
            heightMethod = heightMethod,
            heightSource = e.heightSource,
            heightSigmaM = e.heightSigmaM,
            heightDHM = e.heightDHM,
            heightAlphaTopDeg = e.heightAlphaTopDeg,
            heightAlphaBaseDeg = e.heightAlphaBaseDeg,
            heightConfidence = heightConf,
            bearingFromCenterDeg = e.bearingFromCenterDeg,
            distanceFromCenterM = e.distanceFromCenterM,
            boundaryCall = e.boundaryCall,
            crownClass = e.crownClass,
            damageCodes = JSONFields.decodeStringList(e.damageCodesJSON),
            isMultistem = e.isMultistem,
            parentTreeId = e.parentTreeId,
            notes = e.notes,
            photoPath = e.photoPath,
            rawScanPath = e.rawScanPath,
            latitude = e.latitude,
            longitude = e.longitude,
            createdAt = e.createdAt,
            updatedAt = e.updatedAt,
            deletedAt = e.deletedAt,
        )
    }
}

// MARK: - SpeciesConfigEntity <-> SpeciesConfig

object SpeciesConfigMapper {
    fun apply(s: SpeciesConfig) = SpeciesConfigEntity(
        code = s.code,
        commonName = s.commonName,
        scientificName = s.scientificName,
        volumeEquationId = s.volumeEquationId,
        merchTopDibCm = s.merchTopDibCm,
        stumpHeightCm = s.stumpHeightCm,
        expectedDbhMinCm = s.expectedDbhMinCm,
        expectedDbhMaxCm = s.expectedDbhMaxCm,
        expectedHeightMinM = s.expectedHeightMinM,
        expectedHeightMaxM = s.expectedHeightMaxM,
    )

    fun toStruct(e: SpeciesConfigEntity) = SpeciesConfig(
        code = e.code,
        commonName = e.commonName,
        scientificName = e.scientificName,
        volumeEquationId = e.volumeEquationId,
        merchTopDibCm = e.merchTopDibCm,
        stumpHeightCm = e.stumpHeightCm,
        expectedDbhMinCm = e.expectedDbhMinCm,
        expectedDbhMaxCm = e.expectedDbhMaxCm,
        expectedHeightMinM = e.expectedHeightMinM,
        expectedHeightMaxM = e.expectedHeightMaxM,
    )
}

// MARK: - VolumeEquationEntity <-> VolumeEquation (record)

object VolumeEquationMapper {
    fun apply(s: VolumeEquation) = VolumeEquationEntity(
        id = s.id,
        form = s.form,
        coefficientsJSON = JSONFields.encodeFloatMap(s.coefficients),
        unitsIn = s.unitsIn,
        unitsOut = s.unitsOut,
        sourceCitation = s.sourceCitation,
    )

    fun toStruct(e: VolumeEquationEntity) = VolumeEquation(
        id = e.id,
        form = e.form,
        coefficients = JSONFields.decodeFloatMap(e.coefficientsJSON),
        unitsIn = e.unitsIn,
        unitsOut = e.unitsOut,
        sourceCitation = e.sourceCitation,
    )
}

// MARK: - HeightDiameterFitEntity <-> HeightDiameterFit

object HeightDiameterFitMapper {
    fun apply(s: HeightDiameterFit) = HeightDiameterFitEntity(
        id = s.id,
        projectId = s.projectId,
        speciesCode = s.speciesCode,
        modelForm = s.modelForm,
        coefficientsJSON = JSONFields.encodeFloatMap(s.coefficients),
        nObs = s.nObs,
        rmse = s.rmse,
        updatedAt = s.updatedAt,
    )

    fun toStruct(e: HeightDiameterFitEntity) = HeightDiameterFit(
        id = e.id,
        projectId = e.projectId,
        speciesCode = e.speciesCode,
        modelForm = e.modelForm,
        coefficients = JSONFields.decodeFloatMap(e.coefficientsJSON),
        nObs = e.nObs,
        rmse = e.rmse,
        updatedAt = e.updatedAt,
    )
}
