// Spec §6.2 (Plot). REQ-CTR-001..005, REQ-AGG-001..003.

import Foundation

public struct Plot: Identifiable, Codable, Sendable {
    public let id: UUID
    public let projectId: UUID
    public var plannedPlotId: UUID?
    public var plotNumber: Int
    public var centerLat: Double
    public var centerLon: Double
    public var positionSource: PositionSource
    public var positionTier: PositionTier
    public var gpsNSamples: Int
    public var gpsMedianHAccuracyM: Float
    public var gpsSampleStdXyM: Float
    public var offsetWalkM: Float?          // non-nil for vioOffset
    public var slopeDeg: Float
    public var aspectDeg: Float
    public var plotAreaAcres: Float         // denormalized from CruiseDesign for robustness
    public var startedAt: Date
    public var closedAt: Date?
    public var closedBy: String?
    public var notes: String
    public var coverPhotoPath: String?
    public var panoramaPath: String?        // for re-navigation

    public init(
        id: UUID,
        projectId: UUID,
        plannedPlotId: UUID?,
        plotNumber: Int,
        centerLat: Double,
        centerLon: Double,
        positionSource: PositionSource,
        positionTier: PositionTier,
        gpsNSamples: Int,
        gpsMedianHAccuracyM: Float,
        gpsSampleStdXyM: Float,
        offsetWalkM: Float?,
        slopeDeg: Float,
        aspectDeg: Float,
        plotAreaAcres: Float,
        startedAt: Date,
        closedAt: Date?,
        closedBy: String?,
        notes: String,
        coverPhotoPath: String?,
        panoramaPath: String?
    ) {
        self.id = id
        self.projectId = projectId
        self.plannedPlotId = plannedPlotId
        self.plotNumber = plotNumber
        self.centerLat = centerLat
        self.centerLon = centerLon
        self.positionSource = positionSource
        self.positionTier = positionTier
        self.gpsNSamples = gpsNSamples
        self.gpsMedianHAccuracyM = gpsMedianHAccuracyM
        self.gpsSampleStdXyM = gpsSampleStdXyM
        self.offsetWalkM = offsetWalkM
        self.slopeDeg = slopeDeg
        self.aspectDeg = aspectDeg
        self.plotAreaAcres = plotAreaAcres
        self.startedAt = startedAt
        self.closedAt = closedAt
        self.closedBy = closedBy
        self.notes = notes
        self.coverPhotoPath = coverPhotoPath
        self.panoramaPath = panoramaPath
    }
}
