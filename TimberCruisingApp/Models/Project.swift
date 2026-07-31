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

/// Spec REQ-HGT-007 + §7.4. Rule for when a tree gets a measured height vs.
/// imputed from the H–D model. Stored on `CruiseDesign`.
///
/// Cases:
///   - `.allTrees`  — measure every live tree.
///   - `.none`      — impute every tree (historical datasets, dense stands).
///   - `.everyKth(k:)` — measure one in every k live trees, indexed by
///     `treeNumber` within the plot. First tree of a plot always measured.
///   - `.perSpeciesCount(minPerSpeciesOnPlot:)` — keep measuring until at
///     least N live trees of that species on the plot have a measured
///     height; subsequent trees of that species get imputed.
public enum HeightSubsampleRule: Codable, Sendable, Equatable {
    case allTrees
    case none
    case everyKth(k: Int)
    case perSpeciesCount(minPerSpeciesOnPlot: Int)
}

/// How a plot centre was obtained.
///
/// `gpsSingle` is the ONE-SHOT fix: the coordinate the receiver happened to
/// be reporting at the instant the cruiser dropped the plot, with no window
/// and no median behind it. It exists because the app has always been able
/// to record such a centre (the AR "Start plot" path, and now the planned
/// plot's "Start plot now") and used to file it under `gpsAveraged` with
/// `gpsNSamples = 1` — a label that says a 60 s median was computed when
/// nothing of the sort happened. Anyone reading the corpus later has to be
/// able to separate the two populations, and `gpsNSamples` is not where a
/// reader looks first. One fix and thirty are different data; they get
/// different names.
public enum PositionSource: String, Codable, Sendable {
    case gpsAveraged, gpsSingle, vioOffset, vioChain, externalRTK, manual
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
    /// Which estimator the two coefficients above were fitted against — see
    /// `ProjectCalibration.dbhCalibrationEpoch`. 0 = never calibrated, which
    /// is safe at any epoch because the identity correction always is.
    public var dbhCalibrationEpoch: Int     // default 0
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
        dbhCalibrationEpoch: Int = 0,
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
        self.dbhCalibrationEpoch = dbhCalibrationEpoch
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
    public var heightSubsampleRule: HeightSubsampleRule

    public init(
        id: UUID,
        projectId: UUID,
        plotType: PlotType,
        plotAreaAcres: Float?,
        baf: Float?,
        samplingScheme: SamplingScheme,
        gridSpacingMeters: Float?,
        heightSubsampleRule: HeightSubsampleRule = .everyKth(k: 5)
    ) {
        self.id = id
        self.projectId = projectId
        self.plotType = plotType
        self.plotAreaAcres = plotAreaAcres
        self.baf = baf
        self.samplingScheme = samplingScheme
        self.gridSpacingMeters = gridSpacingMeters
        self.heightSubsampleRule = heightSubsampleRule
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
    /// Intentionally skipped by the cruiser (inaccessible — cliff, water,
    /// private land). Distinct from `visited`: a skipped plot is a documented
    /// decision, so the (+) "nearest unvisited" navigation passes over it and
    /// exports mark it skipped rather than merely pending.
    public var skipped: Bool
    /// How `plannedLat`/`plannedLon` came to exist.
    ///
    /// `.manual` — the cruiser DREW this point with a finger on the map. It
    /// is an intention, not an observation: nothing measured it, and its
    /// error is however far the finger was from the tree the cruiser meant.
    /// The one rule this field exists to keep is that a drawn coordinate and
    /// a GPS-measured one are never stored the same way — the plot that
    /// eventually opens here still takes its `centerLat`/`centerLon` from
    /// the ARRIVAL FIX and stamps that fix's own `positionSource`, so the
    /// drawn point never becomes a measured one. Keeping both is the point:
    /// their difference is the canopy GPS error, and it can only be read if
    /// the plan says it was drawn.
    ///
    /// `nil` — NOT hand-placed. Every planned plot that existed before this
    /// field, and every one `SamplingGenerator` lays down, is derived from a
    /// boundary and a design rather than placed by a person, and there is no
    /// `PositionSource` case that says "computed from a design". Inventing
    /// one, or borrowing `.manual` for the generator, would make the field
    /// say nothing. Absent means absent.
    public var plannedSource: PositionSource?

    public init(
        id: UUID,
        projectId: UUID,
        stratumId: UUID?,
        plotNumber: Int,
        plannedLat: Double,
        plannedLon: Double,
        visited: Bool,
        skipped: Bool = false,
        plannedSource: PositionSource? = nil
    ) {
        self.id = id
        self.projectId = projectId
        self.stratumId = stratumId
        self.plotNumber = plotNumber
        self.plannedLat = plannedLat
        self.plannedLon = plannedLon
        self.visited = visited
        self.skipped = skipped
        self.plannedSource = plannedSource
    }
}
