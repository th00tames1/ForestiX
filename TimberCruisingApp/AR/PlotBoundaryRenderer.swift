// Spec §7.8 AR Plot Boundary Rendering + REQ-BND-001..004.
//
// Two layers:
//
//   • Math (cross-platform): `ringVertices`, `slopeCorrected`,
//     `membership`, `isDriftedBeyond` — pure functions with unit tests.
//
//   • Rendering (iOS, RealityKit): `makeRingEntity` builds a ModelEntity
//     from the vertices as a chain of thin box segments with an emissive
//     green material. Guarded behind `canImport(RealityKit) && os(iOS)`
//     so the package builds on macOS for `swift test`.

import Foundation
import simd

#if canImport(RealityKit) && os(iOS)
import RealityKit
import UIKit
#endif

public enum PlotBoundaryRenderer {

    /// Default vertex count for the ring polyline. Spec §7.8 uses 72.
    public static let defaultVertexCount: Int = 72

    /// Ring material parameters (spec §7.8 step 3): green emissive,
    /// α = 0.6, line width ≈ 2 cm.
    public struct RingStyle: Sendable, Equatable {
        public var color: SIMD4<Float>   // rgba, 0…1
        public var lineWidthM: Float
        public init(color: SIMD4<Float> = SIMD4(0, 1, 0, 0.6),
                    lineWidthM: Float = 0.02) {
            self.color = color
            self.lineWidthM = lineWidthM
        }
    }

    // MARK: - Math

    /// `count` points on the horizontal circle of radius R centered at
    /// `center` in the gravity-aligned ARKit world frame. The returned
    /// array has `count + 1` entries — the last vertex coincides with
    /// the first so callers can emit a closed line strip.
    public static func ringVertices(
        center: SIMD3<Float>,
        radiusM: Float,
        count: Int = defaultVertexCount
    ) -> [SIMD3<Float>] {
        precondition(count >= 3, "ring needs ≥ 3 vertices")
        var out: [SIMD3<Float>] = []
        out.reserveCapacity(count + 1)
        for i in 0...count {
            let theta = Float(i) * 2 * .pi / Float(count)
            let x = center.x + radiusM * cos(theta)
            let z = center.z + radiusM * sin(theta)
            out.append(SIMD3<Float>(x, center.y, z))
        }
        return out
    }

    /// Project each input vertex onto the ground by asking `sampler` for
    /// Y at (vertex.x, vertex.z). Vertices for which the sampler returns
    /// nil keep their original Y — the ring degrades to a flat circle
    /// when the LiDAR mesh is sparse rather than disappearing.
    public static func slopeCorrected(
        _ vertices: [SIMD3<Float>],
        sampler: (Float, Float) -> Float?
    ) -> [SIMD3<Float>] {
        vertices.map { v in
            if let y = sampler(v.x, v.z) {
                return SIMD3<Float>(v.x, y, v.z)
            }
            return v
        }
    }

    // MARK: - REQ-BND-003 in/out/borderline

    public enum StemMembership: Sendable, Equatable {
        case inside
        case outside
        case borderline
    }

    /// Fixed-area plot membership. Borderline band is ±0.2 m around the
    /// radius so field crews catch ambiguous calls.
    public static func membership(
        stemPositionXZ stem: SIMD2<Float>,
        centerXZ center: SIMD2<Float>,
        radiusM R: Float,
        borderlineBandM band: Float = 0.2
    ) -> StemMembership {
        let dx = stem.x - center.x
        let dz = stem.y - center.y
        let d  = sqrt(dx * dx + dz * dz)
        if abs(d - R) <= band { return .borderline }
        return d < R ? .inside : .outside
    }

    /// Variable-radius-plot membership (REQ-BND-003). Callers compute
    /// the per-stem `limitDistanceM` from DBH and BAF and hand it here
    /// along with the measured `distanceToStemM`.
    public static func membership(
        distanceToStemM d: Float,
        limitDistanceM Lm: Float,
        borderlineBandM band: Float = 0.2
    ) -> StemMembership {
        if abs(d - Lm) <= band { return .borderline }
        return d < Lm ? .inside : .outside
    }

    // MARK: - REQ-BND-004 drift warn

    /// True when the user has walked farther than `driftRadiusM` (spec
    /// default 15 m) from the plot center.
    public static func isDriftedBeyond(
        userXZ user: SIMD2<Float>,
        centerXZ center: SIMD2<Float>,
        driftRadiusM r: Float = 15
    ) -> Bool {
        let dx = user.x - center.x
        let dz = user.y - center.y
        return sqrt(dx * dx + dz * dz) > r
    }
}

// MARK: - RealityKit rendering

#if canImport(RealityKit) && os(iOS)

public extension PlotBoundaryRenderer {

    /// Builds a ModelEntity that renders the ring as a chain of thin box
    /// segments along the given vertex polyline. Caller attaches the
    /// returned entity to an `AnchorEntity(world: center)` so anchor
    /// lifetime stays visible in the Screen layer.
    static func makeRingEntity(
        vertices: [SIMD3<Float>],
        style: RingStyle = RingStyle()
    ) -> ModelEntity {
        let root = ModelEntity()
        let material = makeEmissiveMaterial(style: style)
        for i in 0..<(vertices.count - 1) {
            let a = vertices[i]
            let b = vertices[i + 1]
            let delta = b - a
            let len = simd_length(delta)
            if len < 1e-4 { continue }
            let box = MeshResource.generateBox(
                size: SIMD3<Float>(style.lineWidthM, style.lineWidthM, len))
            let segment = ModelEntity(mesh: box, materials: [material])
            let forward = delta / len
            let up = abs(forward.y) < 0.99 ? SIMD3<Float>(0, 1, 0)
                                           : SIMD3<Float>(1, 0, 0)
            let right = simd_normalize(simd_cross(up, forward))
            let trueUp = simd_cross(forward, right)
            let rot = simd_quatf(
                matrix: matrix_float3x3(columns: (right, trueUp, forward)))
            segment.transform = Transform(
                scale: .one,
                rotation: rot,
                translation: (a + b) * 0.5)
            root.addChild(segment)
        }
        return root
    }

    private static func makeEmissiveMaterial(style: RingStyle) -> Material {
        var m = PhysicallyBasedMaterial()
        let c = style.color
        let tint = UIColor(red:   CGFloat(c.x),
                           green: CGFloat(c.y),
                           blue:  CGFloat(c.z),
                           alpha: CGFloat(c.w))
        m.baseColor      = .init(tint: tint)
        m.emissiveColor  = .init(color: tint)
        m.emissiveIntensity = 1.0
        m.blending = .transparent(opacity: .init(floatLiteral: Float(c.w)))
        return m
    }
}

#endif
