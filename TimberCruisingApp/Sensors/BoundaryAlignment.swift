import Foundation

/// Shared manual, depth-walk and AI-assisted diameter geometry. No reference diameter
/// or learned correction parameters are used. Coordinates are depth pixels;
/// width is horizontal in this grid regardless of device orientation.
public enum BoundaryAlignment {
    public struct Grid: Sendable {
        public let width: Int, height: Int, row: Int
        public let depth: [Double], left: Double, right: Double, focal: Double
        public init(width: Int, height: Int, row: Int, depth: [Double], left: Double, right: Double, focal: Double) {
            self.width = width; self.height = height; self.row = row; self.depth = depth
            self.left = left; self.right = right; self.focal = focal
        }
        public func at(_ x: Int, _ y: Int) -> Double { depth[y * width + x] }
    }
    public struct Result: Sendable {
        public let left: Double, right: Double, diameterCm: Double
        public let leftReason: String, rightReason: String
        public var changed: Bool { leftReason == "accepted" || rightReason == "accepted" }
    }
    static func median(_ a: [Double]) -> Double {
        let a = a.filter { $0.isFinite }.sorted(); guard !a.isEmpty else { return .nan }
        return (a[(a.count - 1) / 2] + a[a.count / 2]) / 2
    }
    static func mad(_ a: [Double]) -> Double { let m = median(a); return median(a.map { abs($0 - m) }) }
    static func valid(_ a: [Double]) -> [Double] { a.filter { $0.isFinite && $0 > 0.001 && $0 <= 8 } }
    /// Surface-corrected tangent inversion for a full-span spatial median.
    /// c = 1 - sqrt(3)/2 is derived from the uniform-lateral circular model,
    /// not a fitted sensor multiplier. Raw depth pixels are never changed.
    public static let fullSpanDepthOffsetFactor = 1.0 - sqrt(0.75)
    public static func fullSpanDiameterCm(spanPx: Double, medianDepthM: Double,
                                          focalPx: Double) -> Double? {
        guard spanPx.isFinite, medianDepthM.isFinite, focalPx.isFinite,
              spanPx > 0, medianDepthM > 0, focalPx > 1 else { return nil }
        let k = spanPx / (2 * focalPx)
        let q = k * (k + sqrt(1 + k * k))
        let cm = 200 * medianDepthM * q / (1 + fullSpanDepthOffsetFactor * q)
        return cm.isFinite && cm > 0 ? cm : nil
    }

    /// Pixel centres inside the continuous guide interval, including its ends.
    /// Read only the measurement row, not an allocated copy of the entire map.
    public static func fullSpanSample(width: Int, left: Double, right: Double,
                                      focal: Double, depthAt: (Int) -> Double) -> (Double, Double)? {
        guard width > 0, left.isFinite, right.isFinite,
              left >= 0, right < Double(width), right > left else { return nil }
        let lo = Int(ceil(left)), hi = Int(floor(right))
        guard hi >= lo else { return nil }
        let samples = valid((lo...hi).map(depthAt))
        guard samples.count >= 3 else { return nil }
        let z = median(samples)
        guard z >= 0.3, z <= 5,
              let cm = fullSpanDiameterCm(spanPx: right - left, medianDepthM: z, focalPx: focal)
        else { return nil }
        return (cm, z)
    }
    public static func diameter(_ g: Grid, left: Double, right: Double) -> (Double, Double)? {
        guard g.width > 0, g.height > 0, g.depth.count == g.width * g.height,
              g.row >= 0, g.row < g.height else { return nil }
        return fullSpanSample(width: g.width, left: left, right: right, focal: g.focal) {
            g.at($0, g.row)
        }
    }
    /// Three foreground prompts plus an expanded box. The returned points
    /// are in the same oriented depth grid as the boundary calculation.
    public static func prompts(_ g: Grid) -> [(Double,Double)]? {
        let span = g.right-g.left
        let core = (0..<g.width).filter { Double($0) >= g.left+0.25*span && Double($0) <= g.right-0.25*span }
        let ys = [-0.15,0,0.15].map { min(g.height-1,max(0,Int(Double(g.row)+$0*Double(g.height)))) }
        let adjacent = valid([ys[0],ys[2]].flatMap { y in (max(0,y-2)..<min(g.height,y+3)).flatMap { yy in core.map { g.at($0,yy) } } })
        let z = median(adjacent); guard z.isFinite else { return nil }
        let spread = max(0.05,3*1.4826*mad(adjacent))
        var points: [(Double,Double)] = []
        for y in ys {
            let xs = core.filter { let v = g.at($0,y); return v.isFinite && v > 0.001 && v <= 8 }
            guard let x = xs.min(by: { abs(g.at($0,y)-z)/spread+abs(Double($0)-(g.left+g.right)/2)/max(span,1) < abs(g.at($1,y)-z)/spread+abs(Double($1)-(g.left+g.right)/2)/max(span,1) }) else { return nil }
            points.append((Double(x),Double(y)))
        }
        points += [(max(0,g.left-0.25*span),max(0,Double(g.row)-0.25*Double(g.height))),
                   (min(Double(g.width-1),g.right+0.25*span),min(Double(g.height-1),Double(g.row)+0.25*Double(g.height)))]
        return points
    }
    /// Mask closure samples the full-resolution RGB mask in depth coordinates.
    public static func correct(_ g: Grid, rgbPixelsPerDepthPixel scale: Double,
                               mask: (Int,Int) -> Bool,
                               rowSegments: (Int) -> [(Double,Double)]) -> Result? {
        guard let (base,zold) = diameter(g,left:g.left,right:g.right), scale > 0 else { return nil }
        func retained(_ why: String) -> Result { Result(left:g.left,right:g.right,diameterCm:base,leftReason:why,rightReason:why) }
        let span = g.right-g.left
        let core = (0..<g.width).filter { Double($0) >= g.left+0.25*span && Double($0) <= g.right-0.25*span }
        guard !core.isEmpty else { return retained("empty_core") }
        let near = Array(max(0,g.row-2)..<min(g.height,g.row+3))
        let ys = Array(max(0,g.row-Int(Double(g.height)*0.08))..<min(g.height,g.row+Int(Double(g.height)*0.08)+1))
        func vals(_ rows: [Int]) -> [Double] { valid(rows.flatMap { y in core.map { g.at($0,y) } }) }
        let ay = [-0.15,0.15].map { min(g.height-1,max(0,Int(Double(g.row)+$0*Double(g.height)))) }
        let adj = vals(ay.flatMap { Array(max(0,$0-2)..<min(g.height,$0+3)) }), center = vals(near)
        let za = median(adj), lim = max(0.05,3*1.4826*mad(adj))
        if !center.isEmpty && za.isFinite && Double(center.filter { $0 < za-lim }.count)/Double(center.count) >= 0.1 { return retained("depth_occlusion") }
        let v = vals(ys), zc = median(v)
        guard !v.isEmpty, !center.isEmpty else { return retained("depth_support") }
        let support = Double(v.filter { abs($0/zc-1) <= 0.2 }.count)/Double(v.count)
        let ls = Double(center.filter { abs($0/zc-1) <= 0.2 }.count)/Double(center.count)
        guard near.allSatisfy({ y in core.allSatisfy { mask($0,y) } }) else { return retained("mask_core_gap") }
        guard support >= 0.6, ls >= 0.6 else { return retained("depth_support") }
        let diffs = ys.flatMap { y in zip(core.dropFirst(),core).map { g.at($0.0,y)-g.at($0.1,y) } }
        let noise = max(1e-6,1.4826*mad(diffs)/sqrt(2))
        var bounds = [[(Double,Double)]](repeating:[],count:2)
        for y in ys {
            let segments = rowSegments(y).compactMap { l,r -> (Double,Int,Double,Double)? in
                let overlap = max(0,min(r,g.right-0.25*span)-max(l,g.left+0.25*span))
                return overlap > 0 ? (overlap,Int((r-l)*scale),l,r) : nil
            }
            if let best = segments.max(by: { $0.0 == $1.0 ? $0.1 < $1.1 : $0.0 < $1.0 }) {
                if best.2 > -0.5 { bounds[0].append((Double(y),best.2)) }
                if best.3 < Double(g.width)-0.5 { bounds[1].append((Double(y),best.3)) }
            }
        }
        var proposed = [g.left,g.right], reasons = ["accepted","accepted"]
        for side in 0..<2 {
            let pts = bounds[side], old = proposed[side]
            if Double(pts.count) < max(3,0.6*Double(ys.count)) { reasons[side] = "insufficient_rows";continue }
            var slopes: [Double] = []
            for i in 0..<pts.count { for j in 0..<i { slopes.append((pts[i].1-pts[j].1)/(pts[i].0-pts[j].0)) } }
            let slope = median(slopes), b = median(pts.map { $0.1-slope*($0.0-Double(g.row)) })
            let residual = mad(pts.map { $0.1-(slope*($0.0-Double(g.row))+b) })
            if residual > 2 { reasons[side] = "nonlinear_boundary" }
            else if abs(b-old) > 0.1*span { reasons[side] = "large_shift" }
            else if abs(b-old) < 1/scale { reasons[side] = "below_rgb_resolution" }
            else {
                let ix = (0..<3).map { side == 0 ? Int(ceil(b))+$0 : Int(floor(b))-$0 }.filter { $0 >= 0 && $0 < g.width }
                let inside = valid(near.flatMap { y in ix.map { g.at($0,y) } })
                if inside.count < 3 || median(inside) > zc+base/200+3*noise { reasons[side] = "incoherent_inside_depth" }
                else { proposed[side] = old+0.5*(b-old) }
            }
        }
        guard let (dia,z) = diameter(g,left:proposed[0],right:proposed[1]), z <= zc+base/200+3*noise else { return retained("incoherent_result") }
        _ = zold
        return Result(left:proposed[0],right:proposed[1],diameterCm:dia,leftReason:reasons[0],rightReason:reasons[1])
    }
}
