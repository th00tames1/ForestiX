// Spec §6.1 (tree-related enums) + §6.2 (Tree). REQ-TAL-001..006,
// REQ-DBH-001..009, REQ-HGT-001..007.

import Foundation
import Common

// MARK: - §6.1 Enumerations (tree/DBH/height)

public enum TreeStatus: String, Codable, Sendable {
    case live
    case deadStanding
    case deadDown
    case cull
}

public enum DBHMethod: String, Codable, Sendable {
    case lidarPartialArcSingleView
    case lidarPartialArcDualView
    case lidarIrregular
    case manualCaliper
    case manualVisual
}

public enum HeightMethod: String, Codable, Sendable {
    case vioWalkoffTangent
    case tapeTangent        // manual tape distance + tangent
    case manualEntry
    case imputedHD
}

// MARK: - §6.2 Tree

public struct Tree: Identifiable, Codable, Sendable {
    public let id: UUID
    public let plotId: UUID
    public var treeNumber: Int
    public var speciesCode: String
    public var status: TreeStatus

    // DBH
    public var dbhCm: Float
    public var dbhMethod: DBHMethod
    public var dbhSigmaMm: Float?           // uncertainty
    public var dbhRmseMm: Float?
    public var dbhCoverageDeg: Float?
    public var dbhNInliers: Int?
    public var dbhConfidence: ConfidenceTier
    public var dbhIsIrregular: Bool

    // Height
    public var heightM: Float?
    public var heightMethod: HeightMethod?
    public var heightSource: String?        // "measured" or "imputed"
    public var heightSigmaM: Float?
    public var heightDHM: Float?
    public var heightAlphaTopDeg: Float?
    public var heightAlphaBaseDeg: Float?
    public var heightConfidence: ConfidenceTier?

    // Geometry within plot
    public var bearingFromCenterDeg: Float?
    public var distanceFromCenterM: Float?
    public var boundaryCall: String?        // "in" / "borderline" / "out" for BAF plots

    // Attributes
    public var crownClass: String?
    public var damageCodes: [String]
    public var isMultistem: Bool
    public var parentTreeId: UUID?

    public var notes: String
    public var photoPath: String?
    public var rawScanPath: String?         // optional .ply

    public var createdAt: Date
    public var updatedAt: Date
    public var deletedAt: Date?             // soft-delete

    public init(
        id: UUID,
        plotId: UUID,
        treeNumber: Int,
        speciesCode: String,
        status: TreeStatus,
        dbhCm: Float,
        dbhMethod: DBHMethod,
        dbhSigmaMm: Float?,
        dbhRmseMm: Float?,
        dbhCoverageDeg: Float?,
        dbhNInliers: Int?,
        dbhConfidence: ConfidenceTier,
        dbhIsIrregular: Bool,
        heightM: Float?,
        heightMethod: HeightMethod?,
        heightSource: String?,
        heightSigmaM: Float?,
        heightDHM: Float?,
        heightAlphaTopDeg: Float?,
        heightAlphaBaseDeg: Float?,
        heightConfidence: ConfidenceTier?,
        bearingFromCenterDeg: Float?,
        distanceFromCenterM: Float?,
        boundaryCall: String?,
        crownClass: String?,
        damageCodes: [String],
        isMultistem: Bool,
        parentTreeId: UUID?,
        notes: String,
        photoPath: String?,
        rawScanPath: String?,
        createdAt: Date,
        updatedAt: Date,
        deletedAt: Date?
    ) {
        self.id = id
        self.plotId = plotId
        self.treeNumber = treeNumber
        self.speciesCode = speciesCode
        self.status = status
        self.dbhCm = dbhCm
        self.dbhMethod = dbhMethod
        self.dbhSigmaMm = dbhSigmaMm
        self.dbhRmseMm = dbhRmseMm
        self.dbhCoverageDeg = dbhCoverageDeg
        self.dbhNInliers = dbhNInliers
        self.dbhConfidence = dbhConfidence
        self.dbhIsIrregular = dbhIsIrregular
        self.heightM = heightM
        self.heightMethod = heightMethod
        self.heightSource = heightSource
        self.heightSigmaM = heightSigmaM
        self.heightDHM = heightDHM
        self.heightAlphaTopDeg = heightAlphaTopDeg
        self.heightAlphaBaseDeg = heightAlphaBaseDeg
        self.heightConfidence = heightConfidence
        self.bearingFromCenterDeg = bearingFromCenterDeg
        self.distanceFromCenterM = distanceFromCenterM
        self.boundaryCall = boundaryCall
        self.crownClass = crownClass
        self.damageCodes = damageCodes
        self.isMultistem = isMultistem
        self.parentTreeId = parentTreeId
        self.notes = notes
        self.photoPath = photoPath
        self.rawScanPath = rawScanPath
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.deletedAt = deletedAt
    }
}
