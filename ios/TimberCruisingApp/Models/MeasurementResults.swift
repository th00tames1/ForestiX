// Spec §6.3 (Measurement result types — transient, passed up from sensor
// layer). DBHResult (§7.1), HeightResult (§7.2), PlotCenterResult (§7.3).
//
// These are value types that flow from Sensors/Positioning back up to the
// view-model layer; they are not persisted directly (persisted state lives on
// Tree and Plot). Kept in Models/ so both the engine and sensor layers can
// reference them without depending on each other.

import Foundation
import Common

// MARK: - §7.1 DBHResult

public struct DBHResult: Sendable {
    public let diameterCm: Float
    public let centerXZ: SIMD2<Float>           // in ARKit world frame
    public let arcCoverageDeg: Float
    public let rmseMm: Float
    public let sigmaRmm: Float
    public let nInliers: Int
    public let confidence: ConfidenceTier
    public let method: DBHMethod
    public let rawPointsPath: String?
    public let rejectionReason: String?         // non-nil if confidence == .red

    public init(
        diameterCm: Float,
        centerXZ: SIMD2<Float>,
        arcCoverageDeg: Float,
        rmseMm: Float,
        sigmaRmm: Float,
        nInliers: Int,
        confidence: ConfidenceTier,
        method: DBHMethod,
        rawPointsPath: String?,
        rejectionReason: String?
    ) {
        self.diameterCm = diameterCm
        self.centerXZ = centerXZ
        self.arcCoverageDeg = arcCoverageDeg
        self.rmseMm = rmseMm
        self.sigmaRmm = sigmaRmm
        self.nInliers = nInliers
        self.confidence = confidence
        self.method = method
        self.rawPointsPath = rawPointsPath
        self.rejectionReason = rejectionReason
    }
}

// MARK: - §7.2 HeightResult

public struct HeightResult: Sendable {
    public let heightM: Float
    public let dHm: Float
    public let alphaTopRad: Float
    public let alphaBaseRad: Float
    public let sigmaHm: Float
    public let confidence: ConfidenceTier
    public let method: HeightMethod
    public let rejectionReason: String?

    public init(
        heightM: Float,
        dHm: Float,
        alphaTopRad: Float,
        alphaBaseRad: Float,
        sigmaHm: Float,
        confidence: ConfidenceTier,
        method: HeightMethod,
        rejectionReason: String?
    ) {
        self.heightM = heightM
        self.dHm = dHm
        self.alphaTopRad = alphaTopRad
        self.alphaBaseRad = alphaBaseRad
        self.sigmaHm = sigmaHm
        self.confidence = confidence
        self.method = method
        self.rejectionReason = rejectionReason
    }
}

// MARK: - §7.3 PlotCenterResult

public struct PlotCenterResult: Sendable {
    public let lat: Double
    public let lon: Double
    public let source: PositionSource
    public let tier: PositionTier
    public let nSamples: Int
    public let medianHAccuracyM: Float
    public let sampleStdXyM: Float
    public let offsetWalkM: Float?

    public init(
        lat: Double,
        lon: Double,
        source: PositionSource,
        tier: PositionTier,
        nSamples: Int,
        medianHAccuracyM: Float,
        sampleStdXyM: Float,
        offsetWalkM: Float?
    ) {
        self.lat = lat
        self.lon = lon
        self.source = source
        self.tier = tier
        self.nSamples = nSamples
        self.medianHAccuracyM = medianHAccuracyM
        self.sampleStdXyM = sampleStdXyM
        self.offsetWalkM = offsetWalkM
    }
}
