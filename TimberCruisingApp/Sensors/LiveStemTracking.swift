import Foundation

/// A small screen-space mask. Runs preserve the silhouette, not its bounding box.
/// The camera/depth display transform is applied before sampling this grid.
public struct LiveStemMask: Sendable {
    public struct Run: Sendable {
        public let row: Int, start: Int, end: Int
    }
    public let width: Int, height: Int
    public let runs: [Run]

    /// Project a depth-space point into the oriented RGB mask. This is also
    /// exercised with column-walk/portrait fixtures to prevent a rotated overlay.
    public static func cameraPoint(depthX: Double, depthY: Double, depthWidth: Int, depthHeight: Int,
                                   cameraWidth: Int, cameraHeight: Int, quarterTurns: Int) -> (Int, Int)? {
        guard depthX.isFinite, depthY.isFinite, depthWidth > 0, depthHeight > 0,
              cameraWidth > 0, cameraHeight > 0, (0...3).contains(quarterTurns) else { return nil }
        let scale = min(Double(cameraWidth) / Double(depthWidth), Double(cameraHeight) / Double(depthHeight))
        let rx = floor((depthX + 0.5) * scale + (Double(cameraWidth) - Double(depthWidth) * scale) / 2 + 0.5)
        let ry = floor((depthY + 0.5) * scale + (Double(cameraHeight) - Double(depthHeight) * scale) / 2 + 0.5)
        guard rx >= 0, rx < Double(cameraWidth), ry >= 0, ry < Double(cameraHeight) else { return nil }
        var x = Int(rx), y = Int(ry), w = cameraWidth, h = cameraHeight
        for _ in 0..<quarterTurns { let old = x; x = h - 1 - y; y = old; swap(&w, &h) }
        return (x, y)
    }

    public static func sample(viewWidth: Double, viewHeight: Double,
                              contains: (Double, Double) -> Bool) -> LiveStemMask? {
        guard viewWidth.isFinite, viewHeight.isFinite, viewWidth > 1, viewHeight > 1 else { return nil }
        let width = 96, height = max(48, min(192, Int(96 * viewHeight / viewWidth)))
        var runs: [Run] = []
        for y in 0..<height {
            var start: Int?
            for x in 0...width {
                let inside = x < width && contains((Double(x) + 0.5) / Double(width),
                                                    (Double(y) + 0.5) / Double(height))
                if inside { if start == nil { start = x } }
                else if let s = start { runs.append(Run(row: y, start: s, end: x)); start = nil }
            }
        }
        return runs.isEmpty ? nil : LiveStemMask(width: width, height: height, runs: runs)
    }

    /// Measure the selected instance on the horizontal screen guide. Five nearby
    /// scan lines must agree; holes, clipped trunks and disconnected objects fail closed.
    public static func centreExtent(score: Float, contains: (Double, Double) -> Bool) -> StemExtent? {
        guard score.isFinite, score >= 0.25 else { return nil }
        let samples = 512
        var bounds: [(Double, Double)] = []
        for y in [0.48, 0.49, 0.5, 0.51, 0.52] {
            let centre = samples / 2
            guard contains((Double(centre) + 0.5) / Double(samples), y) else { return nil }
            var left = centre, right = centre
            while left > 0 && contains((Double(left - 1) + 0.5) / Double(samples), y) { left -= 1 }
            while right + 1 < samples && contains((Double(right + 1) + 0.5) / Double(samples), y) { right += 1 }
            guard left > 0, right < samples - 1 else { return nil }
            bounds.append((Double(left) / Double(samples), Double(right + 1) / Double(samples)))
        }
        let lefts = bounds.map(\.0).sorted(), rights = bounds.map(\.1).sorted()
        let left = lefts[2], right = rights[2], span = right - left
        let tolerance = max(0.015, span * 0.08)
        guard span >= 0.02, span <= 0.95,
              lefts.last! - lefts.first! <= tolerance,
              rights.last! - rights.first! <= tolerance else { return nil }
        return StemExtent(leftFraction: left, rightFraction: right, score: score, maskPixels: 1)
    }
}

public struct LiveStemObservation: Sendable {
    public let extent: StemExtent?
    public let mask: LiveStemMask?
    public init(extent: StemExtent?, mask: LiveStemMask?) { self.extent = extent; self.mask = mask }
}

/// Identical temporal policy on both platforms. It does NOT repeatedly feed a
/// half-corrected bracket back into the one-shot paper alignment algorithm.
public struct LiveStemTracker: Sendable {
    public static let holdSeconds = 0.6
    public private(set) var current: StemExtent?
    private var pending: StemExtent?
    public private(set) var lastGood = -Double.infinity
    private var pendingTime = -Double.infinity

    public init() {}
    public mutating func reset() { current = nil; pending = nil; lastGood = -.infinity; pendingTime = -.infinity }
    public func isFresh(at time: Double) -> Bool {
        current != nil && time.isFinite && time >= lastGood && time - lastGood <= Self.holdSeconds
    }
    @discardableResult
    public mutating func update(_ candidate: StemExtent?, at time: Double) -> StemExtent? {
        guard time.isFinite else { reset(); return nil }
        if !isFresh(at: time) { current = nil }
        if time < pendingTime || time - pendingTime > Self.holdSeconds { pending = nil }
        guard let candidate, candidate.leftFraction.isFinite, candidate.rightFraction.isFinite,
              candidate.leftFraction >= 0, candidate.rightFraction <= 1,
              candidate.widthFraction >= 0.02, candidate.widthFraction <= 0.95,
              candidate.score.isFinite, candidate.score >= 0.25 else {
            pending = nil
            return current
        }
        func close(_ a: StemExtent, _ b: StemExtent) -> Bool {
            let limit = max(0.025, a.widthFraction * 0.15)
            return abs(a.leftFraction - b.leftFraction) <= limit && abs(a.rightFraction - b.rightFraction) <= limit
        }
        if let old = current, close(old, candidate) {
            current = StemExtent(leftFraction: old.leftFraction + 0.45 * (candidate.leftFraction - old.leftFraction),
                                 rightFraction: old.rightFraction + 0.45 * (candidate.rightFraction - old.rightFraction),
                                 score: candidate.score, maskPixels: candidate.maskPixels)
        } else {
            guard let prior = pending, close(prior, candidate) else { pending = candidate; pendingTime = time; return current }
            current = candidate
        }
        pending = nil; lastGood = time
        return current
    }
}
