// Bidirectional Swift struct ↔ NSManagedObject mapping for all §6 entities.
// Each mapper has two static functions:
//   - `from(struct:into:)` writes Swift struct fields onto an existing entity.
//   - `toStruct(_:)` reads entity fields into a Swift struct.
//
// JSON-encoded fields (`[String]` damageCodes, `[String: Float]` coefficients)
// are serialized here rather than via Core Data transformers for portability.

import Foundation
import CoreData
import Models
import Common

// MARK: - JSON helpers

enum JSONFields {
    static let encoder = JSONEncoder()
    static let decoder = JSONDecoder()

    static func encode<T: Encodable>(_ value: T) -> String {
        (try? String(data: encoder.encode(value), encoding: .utf8)) ?? ""
    }

    static func decode<T: Decodable>(_ type: T.Type, from json: String, fallback: T) -> T {
        guard let data = json.data(using: .utf8),
              let v = try? decoder.decode(type, from: data) else {
            return fallback
        }
        return v
    }
}

// MARK: - ProjectEntity ↔ Project

public enum ProjectMapper {
    public static func apply(_ s: Project, to e: ProjectEntity) {
        e.id = s.id
        e.name = s.name
        e.projectDescription = s.description
        e.owner = s.owner
        e.createdAt = s.createdAt
        e.updatedAt = s.updatedAt
        e.units = s.units.rawValue
        e.breastHeightConvention = s.breastHeightConvention.rawValue
        e.slopeCorrection = s.slopeCorrection
        e.lidarBiasMm = s.lidarBiasMm
        e.depthNoiseMm = s.depthNoiseMm
        e.dbhCorrectionAlpha = s.dbhCorrectionAlpha
        e.dbhCorrectionBeta = s.dbhCorrectionBeta
        e.vioDriftFraction = s.vioDriftFraction
    }

    public static func toStruct(_ e: ProjectEntity) throws -> Project {
        guard let units = UnitSystem(rawValue: e.units) else {
            throw CoreDataError.mappingFailed("Project.units '\(e.units)'")
        }
        guard let bhc = BreastHeightConvention(rawValue: e.breastHeightConvention) else {
            throw CoreDataError.mappingFailed("Project.breastHeightConvention '\(e.breastHeightConvention)'")
        }
        return Project(
            id: e.id,
            name: e.name,
            description: e.projectDescription,
            owner: e.owner,
            createdAt: e.createdAt,
            updatedAt: e.updatedAt,
            units: units,
            breastHeightConvention: bhc,
            slopeCorrection: e.slopeCorrection,
            lidarBiasMm: e.lidarBiasMm,
            depthNoiseMm: e.depthNoiseMm,
            dbhCorrectionAlpha: e.dbhCorrectionAlpha,
            dbhCorrectionBeta: e.dbhCorrectionBeta,
            vioDriftFraction: e.vioDriftFraction
        )
    }
}

// MARK: - StratumEntity ↔ Stratum

public enum StratumMapper {
    public static func apply(_ s: Stratum, to e: StratumEntity) {
        e.id = s.id
        e.projectId = s.projectId
        e.name = s.name
        e.areaAcres = s.areaAcres
        e.polygonGeoJSON = s.polygonGeoJSON
    }

    public static func toStruct(_ e: StratumEntity) -> Stratum {
        Stratum(
            id: e.id,
            projectId: e.projectId,
            name: e.name,
            areaAcres: e.areaAcres,
            polygonGeoJSON: e.polygonGeoJSON
        )
    }
}

// MARK: - CruiseDesignEntity ↔ CruiseDesign

public enum CruiseDesignMapper {
    public static func apply(_ s: CruiseDesign, to e: CruiseDesignEntity) {
        e.id = s.id
        e.projectId = s.projectId
        e.plotType = s.plotType.rawValue
        e.plotAreaAcres = s.plotAreaAcres.map(NSNumber.init(value:))
        e.baf = s.baf.map(NSNumber.init(value:))
        e.samplingScheme = s.samplingScheme.rawValue
        e.gridSpacingMeters = s.gridSpacingMeters.map(NSNumber.init(value:))
        e.heightSubsampleRuleJSON = (try? encodeRule(s.heightSubsampleRule))
            ?? defaultRuleJSON
    }

    public static func toStruct(_ e: CruiseDesignEntity) throws -> CruiseDesign {
        guard let pt = PlotType(rawValue: e.plotType) else {
            throw CoreDataError.mappingFailed("CruiseDesign.plotType '\(e.plotType)'")
        }
        guard let ss = SamplingScheme(rawValue: e.samplingScheme) else {
            throw CoreDataError.mappingFailed("CruiseDesign.samplingScheme '\(e.samplingScheme)'")
        }
        let rule = (try? decodeRule(e.heightSubsampleRuleJSON)) ?? .everyKth(k: 5)
        return CruiseDesign(
            id: e.id,
            projectId: e.projectId,
            plotType: pt,
            plotAreaAcres: e.plotAreaAcres?.floatValue,
            baf: e.baf?.floatValue,
            samplingScheme: ss,
            gridSpacingMeters: e.gridSpacingMeters?.floatValue,
            heightSubsampleRule: rule
        )
    }

    private static let defaultRuleJSON = #"{"everyKth":{"k":5}}"#

    private static func encodeRule(_ r: HeightSubsampleRule) throws -> String {
        let data = try JSONEncoder().encode(r)
        return String(data: data, encoding: .utf8) ?? defaultRuleJSON
    }

    private static func decodeRule(_ s: String) throws -> HeightSubsampleRule {
        guard let data = s.data(using: .utf8) else {
            throw CoreDataError.mappingFailed("CruiseDesign.heightSubsampleRuleJSON utf8")
        }
        return try JSONDecoder().decode(HeightSubsampleRule.self, from: data)
    }
}

// MARK: - PlannedPlotEntity ↔ PlannedPlot

public enum PlannedPlotMapper {
    public static func apply(_ s: PlannedPlot, to e: PlannedPlotEntity) {
        e.id = s.id
        e.projectId = s.projectId
        e.stratumId = s.stratumId
        e.plotNumber = Int32(s.plotNumber)
        e.plannedLat = s.plannedLat
        e.plannedLon = s.plannedLon
        e.visited = s.visited
    }

    public static func toStruct(_ e: PlannedPlotEntity) -> PlannedPlot {
        PlannedPlot(
            id: e.id,
            projectId: e.projectId,
            stratumId: e.stratumId,
            plotNumber: Int(e.plotNumber),
            plannedLat: e.plannedLat,
            plannedLon: e.plannedLon,
            visited: e.visited
        )
    }
}

// MARK: - PlotEntity ↔ Plot

public enum PlotMapper {
    public static func apply(_ s: Plot, to e: PlotEntity) {
        e.id = s.id
        e.projectId = s.projectId
        e.plannedPlotId = s.plannedPlotId
        e.plotNumber = Int32(s.plotNumber)
        e.centerLat = s.centerLat
        e.centerLon = s.centerLon
        e.positionSource = s.positionSource.rawValue
        e.positionTier = s.positionTier.rawValue
        e.gpsNSamples = Int32(s.gpsNSamples)
        e.gpsMedianHAccuracyM = s.gpsMedianHAccuracyM
        e.gpsSampleStdXyM = s.gpsSampleStdXyM
        e.offsetWalkM = s.offsetWalkM.map(NSNumber.init(value:))
        e.slopeDeg = s.slopeDeg
        e.aspectDeg = s.aspectDeg
        e.plotAreaAcres = s.plotAreaAcres
        e.startedAt = s.startedAt
        e.closedAt = s.closedAt
        e.closedBy = s.closedBy
        e.notes = s.notes
        e.coverPhotoPath = s.coverPhotoPath
        e.panoramaPath = s.panoramaPath
    }

    public static func toStruct(_ e: PlotEntity) throws -> Plot {
        guard let src = PositionSource(rawValue: e.positionSource) else {
            throw CoreDataError.mappingFailed("Plot.positionSource '\(e.positionSource)'")
        }
        guard let tier = PositionTier(rawValue: e.positionTier) else {
            throw CoreDataError.mappingFailed("Plot.positionTier '\(e.positionTier)'")
        }
        return Plot(
            id: e.id,
            projectId: e.projectId,
            plannedPlotId: e.plannedPlotId,
            plotNumber: Int(e.plotNumber),
            centerLat: e.centerLat,
            centerLon: e.centerLon,
            positionSource: src,
            positionTier: tier,
            gpsNSamples: Int(e.gpsNSamples),
            gpsMedianHAccuracyM: e.gpsMedianHAccuracyM,
            gpsSampleStdXyM: e.gpsSampleStdXyM,
            offsetWalkM: e.offsetWalkM?.floatValue,
            slopeDeg: e.slopeDeg,
            aspectDeg: e.aspectDeg,
            plotAreaAcres: e.plotAreaAcres,
            startedAt: e.startedAt,
            closedAt: e.closedAt,
            closedBy: e.closedBy,
            notes: e.notes,
            coverPhotoPath: e.coverPhotoPath,
            panoramaPath: e.panoramaPath
        )
    }
}

// MARK: - TreeEntity ↔ Tree

public enum TreeMapper {
    public static func apply(_ s: Tree, to e: TreeEntity) {
        e.id = s.id
        e.plotId = s.plotId
        e.treeNumber = Int32(s.treeNumber)
        e.speciesCode = s.speciesCode
        e.status = s.status.rawValue

        e.dbhCm = s.dbhCm
        e.dbhMethod = s.dbhMethod.rawValue
        e.dbhSigmaMm = s.dbhSigmaMm.map(NSNumber.init(value:))
        e.dbhRmseMm = s.dbhRmseMm.map(NSNumber.init(value:))
        e.dbhCoverageDeg = s.dbhCoverageDeg.map(NSNumber.init(value:))
        e.dbhNInliers = s.dbhNInliers.map { NSNumber(value: Int32($0)) }
        e.dbhConfidence = s.dbhConfidence.rawValue
        e.dbhIsIrregular = s.dbhIsIrregular

        e.heightM = s.heightM.map(NSNumber.init(value:))
        e.heightMethod = s.heightMethod?.rawValue
        e.heightSource = s.heightSource
        e.heightSigmaM = s.heightSigmaM.map(NSNumber.init(value:))
        e.heightDHM = s.heightDHM.map(NSNumber.init(value:))
        e.heightAlphaTopDeg = s.heightAlphaTopDeg.map(NSNumber.init(value:))
        e.heightAlphaBaseDeg = s.heightAlphaBaseDeg.map(NSNumber.init(value:))
        e.heightConfidence = s.heightConfidence?.rawValue

        e.bearingFromCenterDeg = s.bearingFromCenterDeg.map(NSNumber.init(value:))
        e.distanceFromCenterM = s.distanceFromCenterM.map(NSNumber.init(value:))
        e.boundaryCall = s.boundaryCall

        e.crownClass = s.crownClass
        e.damageCodesJSON = JSONFields.encode(s.damageCodes)
        e.isMultistem = s.isMultistem
        e.parentTreeId = s.parentTreeId

        e.notes = s.notes
        e.photoPath = s.photoPath
        e.rawScanPath = s.rawScanPath

        e.createdAt = s.createdAt
        e.updatedAt = s.updatedAt
        e.deletedAt = s.deletedAt
    }

    public static func toStruct(_ e: TreeEntity) throws -> Tree {
        guard let status = TreeStatus(rawValue: e.status) else {
            throw CoreDataError.mappingFailed("Tree.status '\(e.status)'")
        }
        guard let method = DBHMethod(rawValue: e.dbhMethod) else {
            throw CoreDataError.mappingFailed("Tree.dbhMethod '\(e.dbhMethod)'")
        }
        guard let dbhConf = ConfidenceTier(rawValue: e.dbhConfidence) else {
            throw CoreDataError.mappingFailed("Tree.dbhConfidence '\(e.dbhConfidence)'")
        }
        let heightMethod: HeightMethod? = try e.heightMethod.flatMap {
            guard let v = HeightMethod(rawValue: $0) else {
                throw CoreDataError.mappingFailed("Tree.heightMethod '\($0)'")
            }
            return v
        }
        let heightConf: ConfidenceTier? = try e.heightConfidence.flatMap {
            guard let v = ConfidenceTier(rawValue: $0) else {
                throw CoreDataError.mappingFailed("Tree.heightConfidence '\($0)'")
            }
            return v
        }
        return Tree(
            id: e.id,
            plotId: e.plotId,
            treeNumber: Int(e.treeNumber),
            speciesCode: e.speciesCode,
            status: status,
            dbhCm: e.dbhCm,
            dbhMethod: method,
            dbhSigmaMm: e.dbhSigmaMm?.floatValue,
            dbhRmseMm: e.dbhRmseMm?.floatValue,
            dbhCoverageDeg: e.dbhCoverageDeg?.floatValue,
            dbhNInliers: e.dbhNInliers?.intValue,
            dbhConfidence: dbhConf,
            dbhIsIrregular: e.dbhIsIrregular,
            heightM: e.heightM?.floatValue,
            heightMethod: heightMethod,
            heightSource: e.heightSource,
            heightSigmaM: e.heightSigmaM?.floatValue,
            heightDHM: e.heightDHM?.floatValue,
            heightAlphaTopDeg: e.heightAlphaTopDeg?.floatValue,
            heightAlphaBaseDeg: e.heightAlphaBaseDeg?.floatValue,
            heightConfidence: heightConf,
            bearingFromCenterDeg: e.bearingFromCenterDeg?.floatValue,
            distanceFromCenterM: e.distanceFromCenterM?.floatValue,
            boundaryCall: e.boundaryCall,
            crownClass: e.crownClass,
            damageCodes: JSONFields.decode([String].self, from: e.damageCodesJSON, fallback: []),
            isMultistem: e.isMultistem,
            parentTreeId: e.parentTreeId,
            notes: e.notes,
            photoPath: e.photoPath,
            rawScanPath: e.rawScanPath,
            createdAt: e.createdAt,
            updatedAt: e.updatedAt,
            deletedAt: e.deletedAt
        )
    }
}

// MARK: - SpeciesConfigEntity ↔ SpeciesConfig

public enum SpeciesConfigMapper {
    public static func apply(_ s: SpeciesConfig, to e: SpeciesConfigEntity) {
        e.code = s.code
        e.commonName = s.commonName
        e.scientificName = s.scientificName
        e.volumeEquationId = s.volumeEquationId
        e.merchTopDibCm = s.merchTopDibCm
        e.stumpHeightCm = s.stumpHeightCm
        e.expectedDbhMinCm = s.expectedDbhMinCm
        e.expectedDbhMaxCm = s.expectedDbhMaxCm
        e.expectedHeightMinM = s.expectedHeightMinM
        e.expectedHeightMaxM = s.expectedHeightMaxM
    }

    public static func toStruct(_ e: SpeciesConfigEntity) -> SpeciesConfig {
        SpeciesConfig(
            code: e.code,
            commonName: e.commonName,
            scientificName: e.scientificName,
            volumeEquationId: e.volumeEquationId,
            merchTopDibCm: e.merchTopDibCm,
            stumpHeightCm: e.stumpHeightCm,
            expectedDbhMinCm: e.expectedDbhMinCm,
            expectedDbhMaxCm: e.expectedDbhMaxCm,
            expectedHeightMinM: e.expectedHeightMinM,
            expectedHeightMaxM: e.expectedHeightMaxM
        )
    }
}

// MARK: - VolumeEquationEntity ↔ VolumeEquation (record)

public enum VolumeEquationMapper {
    public static func apply(_ s: VolumeEquation, to e: VolumeEquationEntity) {
        e.id = s.id
        e.form = s.form
        e.coefficientsJSON = JSONFields.encode(s.coefficients)
        e.unitsIn = s.unitsIn
        e.unitsOut = s.unitsOut
        e.sourceCitation = s.sourceCitation
    }

    public static func toStruct(_ e: VolumeEquationEntity) -> VolumeEquation {
        VolumeEquation(
            id: e.id,
            form: e.form,
            coefficients: JSONFields.decode([String: Float].self, from: e.coefficientsJSON, fallback: [:]),
            unitsIn: e.unitsIn,
            unitsOut: e.unitsOut,
            sourceCitation: e.sourceCitation
        )
    }
}

// MARK: - HeightDiameterFitEntity ↔ HeightDiameterFit

public enum HeightDiameterFitMapper {
    public static func apply(_ s: HeightDiameterFit, to e: HeightDiameterFitEntity) {
        e.id = s.id
        e.projectId = s.projectId
        e.speciesCode = s.speciesCode
        e.modelForm = s.modelForm
        e.coefficientsJSON = JSONFields.encode(s.coefficients)
        e.nObs = Int32(s.nObs)
        e.rmse = s.rmse
        e.updatedAt = s.updatedAt
    }

    public static func toStruct(_ e: HeightDiameterFitEntity) -> HeightDiameterFit {
        HeightDiameterFit(
            id: e.id,
            projectId: e.projectId,
            speciesCode: e.speciesCode,
            modelForm: e.modelForm,
            coefficients: JSONFields.decode([String: Float].self, from: e.coefficientsJSON, fallback: [:]),
            nObs: Int(e.nObs),
            rmse: e.rmse,
            updatedAt: e.updatedAt
        )
    }
}
