// Ground-mesh sampling math: triangle-inclusion test with barycentric
// interpolation, plus nearest-vertex fallback when no triangle contains
// the query column.

import XCTest
import simd
@testable import AR

final class GroundMeshSamplerTests: XCTestCase {

    // MARK: - Fixtures

    /// Flat quad on y=2 split into two triangles, covering x∈[-1,1], z∈[-1,1].
    private func flatQuad() -> GroundMeshSnapshot {
        let vs: [SIMD3<Float>] = [
            SIMD3(-1, 2, -1),
            SIMD3( 1, 2, -1),
            SIMD3( 1, 2,  1),
            SIMD3(-1, 2,  1)
        ]
        let tris: [UInt32] = [0, 1, 2, 0, 2, 3]
        return GroundMeshSnapshot(vertices: vs, triangles: tris)
    }

    /// Sloped quad rising +Y with +X at 1 m/m slope, still covering
    /// x∈[-1,1], z∈[-1,1].
    private func slopedQuad() -> GroundMeshSnapshot {
        let vs: [SIMD3<Float>] = [
            SIMD3(-1, -1, -1),
            SIMD3( 1,  1, -1),
            SIMD3( 1,  1,  1),
            SIMD3(-1, -1,  1)
        ]
        let tris: [UInt32] = [0, 1, 2, 0, 2, 3]
        return GroundMeshSnapshot(vertices: vs, triangles: tris)
    }

    // MARK: - Flat mesh

    func testFlatMeshReturnsConstantY() {
        let m = flatQuad()
        for (x, z): (Float, Float) in [(0, 0), (0.5, 0.5), (-0.8, 0.3)] {
            let y = GroundMeshSampler.sampleGroundY(atX: x, z: z, snapshot: m)
            XCTAssertNotNil(y, "sample at (\(x),\(z)) returned nil")
            XCTAssertEqual(y!, 2.0, accuracy: 1e-5)
        }
    }

    // MARK: - Sloped mesh

    func testSlopedMeshInterpolatesLinearly() {
        let m = slopedQuad()
        // Slope: y = x. Verify at three points across the quad.
        for x: Float in [-0.5, 0.0, 0.5] {
            let y = GroundMeshSampler.sampleGroundY(atX: x, z: 0, snapshot: m)
            XCTAssertNotNil(y)
            XCTAssertEqual(y!, x, accuracy: 1e-4)
        }
    }

    // MARK: - Outside the mesh → nearest-vertex fallback

    func testOutsideMeshReturnsNearestVertexY() {
        let m = flatQuad()
        // (x,z) well outside the quad → no triangle contains it; fall back
        // to nearest vertex (all vertices at y=2).
        let y = GroundMeshSampler.sampleGroundY(atX: 5, z: 5, snapshot: m)
        XCTAssertNotNil(y)
        XCTAssertEqual(y!, 2.0, accuracy: 1e-5)
    }

    func testRayCastReturnsNilOutsideMesh() {
        let m = flatQuad()
        XCTAssertNil(GroundMeshSampler.rayCastY(x: 5, z: 5, snapshot: m))
        XCTAssertNil(GroundMeshSampler.rayCastY(x: 1.001, z: 0, snapshot: m))
    }

    func testNearestVertexPicksLowest2DDistance() {
        let m = slopedQuad()
        // Query very close to vertex (1,1,1); nearest Y = 1.
        let y = GroundMeshSampler.nearestVertexY(atX: 0.99, z: 0.99, snapshot: m)
        XCTAssertNotNil(y)
        XCTAssertEqual(y!, 1.0, accuracy: 1e-5)
    }

    func testEmptyMeshReturnsNil() {
        let empty = GroundMeshSnapshot.empty
        XCTAssertNil(GroundMeshSampler.sampleGroundY(atX: 0, z: 0, snapshot: empty))
    }

    func testDegenerateTriangleDoesNotCrash() {
        // Two colinear vertices → zero-area triangle; sampler should skip
        // it and try the next, or fall back to nearest-vertex.
        let vs: [SIMD3<Float>] = [
            SIMD3(0, 0, 0),
            SIMD3(1, 0, 0),
            SIMD3(2, 0, 0)
        ]
        let m = GroundMeshSnapshot(vertices: vs, triangles: [0, 1, 2])
        let y = GroundMeshSampler.sampleGroundY(atX: 0.5, z: 0, snapshot: m)
        XCTAssertNotNil(y)
        XCTAssertEqual(y!, 0, accuracy: 1e-5)
    }
}
