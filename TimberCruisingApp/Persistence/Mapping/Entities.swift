// Spec §6 entities as NSManagedObject subclasses (manual codegen).
// Each subclass mirrors the attributes declared in
// TimberCruising.xcdatamodeld/contents. Mapping to/from Swift structs lives
// in Mappers.swift.

import Foundation
import CoreData

@objc(ProjectEntity)
public final class ProjectEntity: NSManagedObject {
    @NSManaged public var id: UUID
    @NSManaged public var name: String
    @NSManaged public var projectDescription: String
    @NSManaged public var owner: String
    @NSManaged public var createdAt: Date
    @NSManaged public var updatedAt: Date
    @NSManaged public var units: String
    @NSManaged public var breastHeightConvention: String
    @NSManaged public var slopeCorrection: Bool
    @NSManaged public var lidarBiasMm: Float
    @NSManaged public var depthNoiseMm: Float
    @NSManaged public var dbhCorrectionAlpha: Float
    @NSManaged public var dbhCorrectionBeta: Float
    @NSManaged public var vioDriftFraction: Float
}

@objc(StratumEntity)
public final class StratumEntity: NSManagedObject {
    @NSManaged public var id: UUID
    @NSManaged public var projectId: UUID
    @NSManaged public var name: String
    @NSManaged public var areaAcres: Float
    @NSManaged public var polygonGeoJSON: String
}

@objc(CruiseDesignEntity)
public final class CruiseDesignEntity: NSManagedObject {
    @NSManaged public var id: UUID
    @NSManaged public var projectId: UUID
    @NSManaged public var plotType: String
    @NSManaged public var plotAreaAcres: NSNumber?
    @NSManaged public var baf: NSNumber?
    @NSManaged public var samplingScheme: String
    @NSManaged public var gridSpacingMeters: NSNumber?
    @NSManaged public var heightSubsampleRuleJSON: String
}

@objc(PlannedPlotEntity)
public final class PlannedPlotEntity: NSManagedObject {
    @NSManaged public var id: UUID
    @NSManaged public var projectId: UUID
    @NSManaged public var stratumId: UUID?
    @NSManaged public var plotNumber: Int32
    @NSManaged public var plannedLat: Double
    @NSManaged public var plannedLon: Double
    @NSManaged public var visited: Bool
}

@objc(PlotEntity)
public final class PlotEntity: NSManagedObject {
    @NSManaged public var id: UUID
    @NSManaged public var projectId: UUID
    @NSManaged public var plannedPlotId: UUID?
    @NSManaged public var plotNumber: Int32
    @NSManaged public var centerLat: Double
    @NSManaged public var centerLon: Double
    @NSManaged public var positionSource: String
    @NSManaged public var positionTier: String
    @NSManaged public var gpsNSamples: Int32
    @NSManaged public var gpsMedianHAccuracyM: Float
    @NSManaged public var gpsSampleStdXyM: Float
    @NSManaged public var offsetWalkM: NSNumber?
    @NSManaged public var slopeDeg: Float
    @NSManaged public var aspectDeg: Float
    @NSManaged public var plotAreaAcres: Float
    @NSManaged public var startedAt: Date
    @NSManaged public var closedAt: Date?
    @NSManaged public var closedBy: String?
    @NSManaged public var notes: String
    @NSManaged public var coverPhotoPath: String?
    @NSManaged public var panoramaPath: String?
}

@objc(TreeEntity)
public final class TreeEntity: NSManagedObject {
    @NSManaged public var id: UUID
    @NSManaged public var plotId: UUID
    @NSManaged public var treeNumber: Int32
    @NSManaged public var speciesCode: String
    @NSManaged public var status: String

    @NSManaged public var dbhCm: Float
    @NSManaged public var dbhMethod: String
    @NSManaged public var dbhSigmaMm: NSNumber?
    @NSManaged public var dbhRmseMm: NSNumber?
    @NSManaged public var dbhCoverageDeg: NSNumber?
    @NSManaged public var dbhNInliers: NSNumber?
    @NSManaged public var dbhConfidence: String
    @NSManaged public var dbhIsIrregular: Bool

    @NSManaged public var heightM: NSNumber?
    @NSManaged public var heightMethod: String?
    @NSManaged public var heightSource: String?
    @NSManaged public var heightSigmaM: NSNumber?
    @NSManaged public var heightDHM: NSNumber?
    @NSManaged public var heightAlphaTopDeg: NSNumber?
    @NSManaged public var heightAlphaBaseDeg: NSNumber?
    @NSManaged public var heightConfidence: String?

    @NSManaged public var bearingFromCenterDeg: NSNumber?
    @NSManaged public var distanceFromCenterM: NSNumber?
    @NSManaged public var boundaryCall: String?

    @NSManaged public var crownClass: String?
    @NSManaged public var damageCodesJSON: String
    @NSManaged public var isMultistem: Bool
    @NSManaged public var parentTreeId: UUID?

    @NSManaged public var notes: String
    @NSManaged public var photoPath: String?
    @NSManaged public var rawScanPath: String?

    @NSManaged public var createdAt: Date
    @NSManaged public var updatedAt: Date
    @NSManaged public var deletedAt: Date?
}

@objc(SpeciesConfigEntity)
public final class SpeciesConfigEntity: NSManagedObject {
    @NSManaged public var code: String
    @NSManaged public var commonName: String
    @NSManaged public var scientificName: String
    @NSManaged public var volumeEquationId: String
    @NSManaged public var merchTopDibCm: Float
    @NSManaged public var stumpHeightCm: Float
    @NSManaged public var expectedDbhMinCm: Float
    @NSManaged public var expectedDbhMaxCm: Float
    @NSManaged public var expectedHeightMinM: Float
    @NSManaged public var expectedHeightMaxM: Float
}

@objc(VolumeEquationEntity)
public final class VolumeEquationEntity: NSManagedObject {
    @NSManaged public var id: String
    @NSManaged public var form: String
    @NSManaged public var coefficientsJSON: String
    @NSManaged public var unitsIn: String
    @NSManaged public var unitsOut: String
    @NSManaged public var sourceCitation: String
}

@objc(HeightDiameterFitEntity)
public final class HeightDiameterFitEntity: NSManagedObject {
    @NSManaged public var id: UUID
    @NSManaged public var projectId: UUID
    @NSManaged public var speciesCode: String
    @NSManaged public var modelForm: String
    @NSManaged public var coefficientsJSON: String
    @NSManaged public var nObs: Int32
    @NSManaged public var rmse: Float
    @NSManaged public var updatedAt: Date
}
