import Foundation

/// Versioned DBH input selection only. Other AR tools and old recordings keep
/// their established behaviour. These support limits are application heuristics,
/// not a manufacturer's guarantee of absolute range accuracy.
public enum DBHMeasurementDepth {
    public static let legacy = "legacy_full_depth_v1"
    public static let sensorConfidence = "arkit_confidence_c80_v1"
    public static func calibrationEpoch(_ policy:String) -> Int {
        policy == sensorConfidence ? 10 : 9
    }
    public static func recognized(_ policy: String) -> Bool {
        policy == legacy || policy == sensorConfidence
    }
    public static func acceptsWindow(_ frames: [ARDepthFrame]) -> Bool {
        guard let first = frames.first, recognized(first.dbhDepthInputPolicy) else { return false }
        return frames.allSatisfy { $0.dbhDepthInputPolicy == first.dbhDepthInputPolicy }
    }
    public static func sample(frame: ARDepthFrame, axis: GuideAxis,
                              left: Double, right: Double, focal: Double) -> (Double,Double)? {
        let column: Bool, fixed: Int
        switch axis { case .row(let y): column = false; fixed = y
        case .col(let x): column = true; fixed = x }
        let extent = column ? frame.height : frame.width
        let orthogonal = column ? frame.width : frame.height
        guard recognized(frame.dbhDepthInputPolicy), fixed >= 0, fixed < orthogonal,
              left.isFinite, right.isFinite, left >= 0, right < Double(extent), right > left else { return nil }
        func value(_ i: Int) -> Double {
            let x = column ? fixed : i, y = column ? i : fixed
            if frame.dbhDepthInputPolicy == sensorConfidence && frame.confidence(atX:x,y:y) < 1 { return .nan }
            return Double(frame.depth(atX:x,y:y))
        }
        if frame.dbhDepthInputPolicy == sensorConfidence {
            let margin = (right-left)*(1-BoundaryAlignment.samplingFraction)/2
            let epsilon = 8*Double.ulpOfOne*max(1,max(abs(left),abs(right)))
            let a = max(0,Int(ceil(left+margin-epsilon)))
            let b = min(extent-1,Int(floor(right-margin+epsilon)))
            guard b >= a else { return nil }
            var bins = [0,0,0], valid = 0
            for i in a...b {
                let z = value(i)
                if z.isFinite && z >= 0.001 && z <= 8 {
                    valid += 1; bins[min(2,(i-a)*3/(b-a+1))] += 1
                }
            }
            guard valid >= 3, Double(valid)/Double(b-a+1) >= 0.6,
                  bins.allSatisfy({ $0 > 0 }) else { return nil }
        }
        return BoundaryAlignment.sampledDepth(width:extent,left:left,right:right,
            focal:focal,depthAt:value)
    }
}
