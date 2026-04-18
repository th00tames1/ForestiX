// Spec §6.1 (project-related enums) + §6.2 (Project, Stratum, CruiseDesign,
// PlannedPlot). REQ-PRJ-001..006, REQ-CAL-001..005.

import Foundation

// MARK: - §6.1 Enumerations (project/design/position-related)

public enum UnitSystem: String, Codable, Sendable { case imperial, metric }

public enum PlotType: String, Codable, Sendable { case fixedArea, variableRadius }

public enum SamplingScheme: String, Codable, Sendable {
    case systematicGrid, stratifiedRandom, manual
}

public enum BreastHeightConvention: String, Codable, Sendable {
    case uphill, mid, any, custom
}

public enum PositionSource: String, Codable, Sendable {
    case gpsAveraged, vioOffset, vioChain, externalRTK, manual
}

public enum PositionTier: String, Codable, Sendable { case A, B, C, D }

// MARK: - §6.2 Project

public struct Project: Identifiable, Codable, Sendable {
    public let id: UUID
    public var name: String
    public var description: String
    public var owner: String
    public var createdAt: Date
    public var updatedAt: Date
    public var units: UnitSystem
    public var breastHeightConvention: BreastHeightConvention
    public var slopeCorrection: Bool
    // Calibration
    public var lidarBiasMm: Float
    public var depthNoiseMm: Float
    public var dbhCorrectionAlpha: Float    // from cylinder calibration; default 0
    public var dbhCorrectionBeta: Float     // default 1
    public var vioDriftFraction: Float      // default 0.02

    public init(
        id: UUID,
        name: String,
        description: String,
        owner: String,
        createdAt: Date,
        updatedAt: Date,
        units: UnitSystem,
        breastHeightConvention: BreastHeightConvention,
        slopeCorrection: Bool,
        lidarBiasMm: Float,
        depthNoiseMm: Float,
        dbhCorrectionAlpha: Float,
        dbhCorrectionBeta: Float,
        vioDriftFraction: Float
    ) {
        self.id = id
        self.name = name
        self.description = description
        self.owner = owner
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.units = units
        self.breastHeightConvention = breastHeightConvention
        self.slopeCorrection = slopeCorrection
        self.lidarBiasMm = lidarBiasMm
        self.depthNoiseMm = depthNoiseMm
        self.dbhCorrectionAlpha = dbhCorrectionAlpha
        self.dbhCorrectionBeta = dbhCorrectionBeta
        self.vioDriftFraction = vioDriftFraction
    }
}

// MARK: - §6.2 Stratum

public struct Stratum: Identifiable, Codable, Sendable {
    public let id: UUID
    public let projectId: UUID
    public var name: String
    public var areaAcres: Float
    public var polygonGeoJSON: String       // WGS84

    public init(id: UUID, projectId: UUID, name: String, areaAcres: Float, polygonGeoJSON: String) {
        self.id = id
        self.projectId = projectId
        self.name = name
        self.areaAcres = areaAcres
        self.polygonGeoJSON = polygonGeoJSON
    }
}

// MARK: - §6.2 CruiseDesign

public struct CruiseDesign: Identifiable, Codable, Sendable {
    public let id: UUID
    public let projectId: UUID
    public var plotType: PlotType
    public var plotAreaAcres: Float?        // required if fixedArea
    public var baf: Float?                  // required if variableRadius
    public var samplingScheme: SamplingScheme
    public var gridSpacingMeters: Float?    // required if systematicGrid

    public init(
        id: UUID,
        projectId: UUID,
        plotType: PlotType,
        plotAreaAcres: Float?,
        baf: Float?,
        samplingScheme: SamplingScheme,
        gridSpacingMeters: Float?
    ) {
        self.id = id
        self.projectId = projectId
        self.plotType = plotType
        self.plotAreaAcres = plotAreaAcres
        self.baf = baf
        self.samplingScheme = samplingScheme
        self.gridSpacingMeters = gridSpacingMeters
    }
}

// MARK: - §6.2 PlannedPlot

public struct PlannedPlot: Identifiable, Codable, Sendable {
    public let id: UUID
    public let projectId: UUID
    public var stratumId: UUID?
    public var plotNumber: Int
    public var plannedLat: Double
    public var plannedLon: Double
    public var visited: Bool

    public init(
        id: UUID,
        projectId: UUID,
        stratumId: UUID?,
        plotNumber: Int,
        plannedLat: Double,
        plannedLon: Double,
        visited: Bool
    ) {
        self.id = id
        self.projectId = projectId
        self.stratumId = stratumId
        self.plotNumber = plotNumber
        self.plannedLat = plannedLat
        self.plannedLon = plannedLon
        self.visited = visited
    }
}
