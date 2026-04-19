// Spec §7.3.1 GPS Averaging. REQ-CTR-001.
//
// Pure function: takes a buffer of CLLocation samples (1 Hz, from a
// CLLocationManager subscription) and returns a PlotCenterResult or
// nil. The caller (LocationService / PlotCenterViewModel) owns the
// CoreLocation delegate and the 60 s wall clock — this module only
// does the ENU-plane median + tier decision so the math is testable
// with synthetic CLLocations.
//
// ENU conversion rationale: lat/lon median at high latitudes distorts
// east-west distances. We project every sample to a local east/north
// frame centered on sample 0 using a small-angle linear approximation
// (WGS-84 semi-major / degree: 111_320 m/°), take medians there, then
// invert the projection. Across a 50 m averaging area this is
// sub-millimetre vs. a full geodesic.

import Foundation
import Models

#if canImport(CoreLocation)
import CoreLocation
#endif

public enum GPSAveraging {

    public struct Input: Sendable {
        public let samples: [CLLocationSnapshot]
        public let maxAcceptableAccuracyM: Float

        public init(
            samples: [CLLocationSnapshot],
            maxAcceptableAccuracyM: Float = 20
        ) {
            self.samples = samples
            self.maxAcceptableAccuracyM = maxAcceptableAccuracyM
        }
    }

    /// §7.3.1 algorithm. Returns nil if fewer than 30 samples survive
    /// the accuracy filter (spec requires ≥ 30 for any tier).
    public static func compute(input: Input) -> PlotCenterResult? {
        let accepted = input.samples.filter {
            $0.horizontalAccuracyM > 0 &&
            Float($0.horizontalAccuracyM) <= input.maxAcceptableAccuracyM
        }
        guard accepted.count >= 30 else { return nil }

        // Project every sample to local ENU centered on the first
        // accepted sample. Small-angle approximation good to sub-mm
        // over a 50 m averaging radius.
        let origin = accepted[0]
        let metersPerDegLat: Double = 111_320
        let metersPerDegLon: Double = 111_320 *
            cos(origin.latitude * .pi / 180)

        var easts: [Double] = []
        var norths: [Double] = []
        easts.reserveCapacity(accepted.count)
        norths.reserveCapacity(accepted.count)
        for s in accepted {
            easts.append((s.longitude - origin.longitude) * metersPerDegLon)
            norths.append((s.latitude - origin.latitude) * metersPerDegLat)
        }

        let medianE = median(easts)
        let medianN = median(norths)

        // Sample standard deviation in the XY plane, pooled across
        // east + north (spec: sqrt(var_east + var_north)).
        let meanE = easts.reduce(0, +) / Double(easts.count)
        let meanN = norths.reduce(0, +) / Double(norths.count)
        var varE: Double = 0
        var varN: Double = 0
        for i in 0..<easts.count {
            let de = easts[i] - meanE
            let dn = norths[i] - meanN
            varE += de * de
            varN += dn * dn
        }
        let n = Double(easts.count)
        let sampleStdXY = sqrt(varE / n + varN / n)

        // Median horizontal accuracy over accepted samples.
        let medianHAcc = Float(median(accepted.map { $0.horizontalAccuracyM }))

        // Invert back to lat/lon.
        let resultLat = origin.latitude + medianN / metersPerDegLat
        let resultLon = origin.longitude + medianE / metersPerDegLon

        let tier = classify(
            medianHAccuracyM: medianHAcc,
            sampleStdXyM: Float(sampleStdXY))

        return PlotCenterResult(
            lat: resultLat,
            lon: resultLon,
            source: .gpsAveraged,
            tier: tier,
            nSamples: accepted.count,
            medianHAccuracyM: medianHAcc,
            sampleStdXyM: Float(sampleStdXY),
            offsetWalkM: nil)
    }

    /// §7.3.1 tier table. Exposed so OffsetFromOpening can inherit +
    /// demote the tier without reinventing thresholds.
    public static func classify(
        medianHAccuracyM mAcc: Float,
        sampleStdXyM stdXY: Float
    ) -> PositionTier {
        if mAcc < 5  && stdXY < 3 { return .A }
        if mAcc < 10 && stdXY < 5 { return .B }
        if mAcc < 20              { return .C }
        return .D
    }

    // MARK: - Median helper

    @inlinable
    static func median(_ xs: [Double]) -> Double {
        precondition(!xs.isEmpty)
        let sorted = xs.sorted()
        let n = sorted.count
        if n % 2 == 1 { return sorted[n / 2] }
        return (sorted[n / 2 - 1] + sorted[n / 2]) * 0.5
    }
}

// MARK: - CLLocationSnapshot

/// POD snapshot of the fields we read out of `CLLocation`. Lets the
/// pure fn test on macOS with synthetic inputs (CLLocation is awkward
/// to construct cross-platform). The iOS LocationService maps
/// `CLLocation` → this.
public struct CLLocationSnapshot: Sendable, Equatable {
    public let latitude: Double
    public let longitude: Double
    public let horizontalAccuracyM: Double
    public let timestamp: Date

    public init(
        latitude: Double,
        longitude: Double,
        horizontalAccuracyM: Double,
        timestamp: Date
    ) {
        self.latitude = latitude
        self.longitude = longitude
        self.horizontalAccuracyM = horizontalAccuracyM
        self.timestamp = timestamp
    }
}

#if canImport(CoreLocation)
public extension CLLocationSnapshot {
    init(_ loc: CLLocation) {
        self.init(
            latitude: loc.coordinate.latitude,
            longitude: loc.coordinate.longitude,
            horizontalAccuracyM: loc.horizontalAccuracy,
            timestamp: loc.timestamp)
    }
}
#endif
