import XCTest
import simd
import Models
@testable import Sensors

final class ManualBracketMappingTests: XCTestCase {
    private let view = CGSize(width: 1032, height: 1376)
    private let portrait = DepthViewMapping(a: 0, b: 256.0 / 1376, tx: 0,
                                           c: -192.0 / 1032, d: 0, ty: 192)
    private func frame(_ mapping: DepthViewMapping?) -> ARDepthFrame {
        // An off-centre foreground band: reflecting a bracket changes depth.
        var depth = [Float](repeating: 2, count: 256 * 192)
        for y in 100...150 { for x in 0..<256 { depth[y * 256 + x] = 1 } }
        let k = simd_float3x3(columns: (SIMD3(210, 0, 0), SIMD3(0, 210, 0), SIMD3(128, 96, 1)))
        return ARDepthFrame(width: 256, height: 192, depth: depth,
            confidence: .init(repeating: 2, count: depth.count), intrinsics: k,
            cameraPoseWorld: matrix_identity_float4x4, timestamp: 0, viewMapping: mapping)
    }

    func testPortraitAsymmetricBracketSamplesForegroundInsteadOfReflectedBackground() throws {
        let f = frame(portrait)
        let g = try XCTUnwrap(DBHEstimator.bracketDepthGeometry(frame: f,
            leftFraction: 0.25, rightFraction: 0.45, viewSize: view))
        XCTAssertEqual(g.axis, .col(x: 128))
        XCTAssertEqual(g.left, 0.55, accuracy: 1e-12)
        XCTAssertEqual(g.right, 0.75, accuracy: 1e-12)
        let mapped = try XCTUnwrap(DBHEstimator.bracketChordFit(frame: f,
            guideAxis: g.axis, leftFraction: g.left, rightFraction: g.right))
        let unmapped = try XCTUnwrap(DBHEstimator.bracketChordFit(frame: f,
            guideAxis: g.axis, leftFraction: 0.25, rightFraction: 0.45))
        XCTAssertEqual(unmapped.diameterCm, 2 * mapped.diameterCm, accuracy: 1e-8)
    }

    func testPortraitCropChangesWidthAndFixedCoordinate() throws {
        let f = frame(.init(a: 0, b: 256.0 / 844, tx: 5,
                            c: -0.6 * 192 / 390, d: 0, ty: 0.8 * 192))
        let g = try XCTUnwrap(DBHEstimator.bracketDepthGeometry(frame: f,
            leftFraction: 0.25, rightFraction: 0.45, viewSize: .init(width: 390, height: 844)))
        XCTAssertEqual(g.axis, .col(x: 133))
        XCTAssertEqual(g.right - g.left, 0.12, accuracy: 1e-12)
    }

    func testAllFourOrientationsAndSwappedHandles() throws {
        let cases: [(DepthViewMapping, GuideAxis, Double, Double)] = [
            (.init(a: 256.0/1032, b: 0, tx: 0, c: 0, d: 192.0/1376, ty: 0), .row(y: 96), 0.25, 0.45),
            (portrait, .col(x: 128), 0.55, 0.75),
            (.init(a: -256.0/1032, b: 0, tx: 256, c: 0, d: -192.0/1376, ty: 192), .row(y: 96), 0.55, 0.75),
            (.init(a: 0, b: -256.0/1376, tx: 256, c: 192.0/1032, d: 0, ty: 0), .col(x: 128), 0.25, 0.45)
        ]
        for (m, axis, lo, hi) in cases {
            for (l,r) in [(0.25,0.45),(0.45,0.25)] {
                let g = try XCTUnwrap(DBHEstimator.bracketDepthGeometry(frame: frame(m),
                    leftFraction: l, rightFraction: r, viewSize: view))
                XCTAssertEqual(g.axis, axis)
                XCTAssertEqual(g.left, lo, accuracy: 1e-12)
                XCTAssertEqual(g.right, hi, accuracy: 1e-12)
            }
        }
    }

    func testMissingNonfiniteObliqueAndOutsideMappingsFailClosed() {
        let invalid: [DepthViewMapping?] = [nil,
            .init(a: 0, b: 0, tx: 0, c: 0, d: 0, ty: 0),
            .init(a: .nan, b: 0, tx: 0, c: 0, d: 1, ty: 0),
            .init(a: 0.1, b: 0, tx: 0, c: 0.1, d: 0.1, ty: 0),
            .init(a: 0, b: 256.0/1376, tx: 500, c: -192.0/1032, d: 0, ty: 192)]
        for m in invalid {
            XCTAssertNil(DBHEstimator.bracketDepthGeometry(frame: frame(m),
                leftFraction: 0.25, rightFraction: 0.45, viewSize: view))
        }
        for l in [Double.nan, Double.infinity, -0.1, 1.1, 0.45] {
            XCTAssertNil(DBHEstimator.bracketDepthGeometry(frame: frame(portrait),
                leftFraction: l, rightFraction: 0.45, viewSize: view))
        }
    }

    func testDepthBracketMetadataRoundTripsWithoutSecondTransformation() throws {
        let g = try XCTUnwrap(DBHEstimator.bracketDepthGeometry(frame: frame(portrait),
            leftFraction: 0.25, rightFraction: 0.45, viewSize: view))
        let b = RawCaptureManifest.DBHBundle.Bracket(enabled: true, left: g.left, right: g.right,
            coordinateSpace: "depth_axis_fraction_v1", screenFractions: [0.25,0.45],
            viewportSize: [1032,1376])
        let data = try JSONEncoder().encode(b)
        let decoded = try JSONDecoder().decode(RawCaptureManifest.DBHBundle.Bracket.self, from: data)
        XCTAssertEqual(decoded.left, 0.55, accuracy: 1e-12)
        XCTAssertEqual(decoded.coordinateSpace, "depth_axis_fraction_v1")
        XCTAssertEqual(decoded.viewportSize, [1032,1376])
        let legacy = try JSONDecoder().decode(RawCaptureManifest.DBHBundle.Bracket.self,
            from: Data(#"{"enabled":true,"left":0.25,"right":0.45}"#.utf8))
        XCTAssertNil(legacy.coordinateSpace)
        XCTAssertEqual(legacy.left, 0.25)
    }

    func testRecorderAndReplayPreserveMappingAndDiameter() throws {
        let f = frame(portrait)
        XCTAssertEqual(RawCaptureFrame.canonicalized(f).viewMapping, portrait)
        let g = try XCTUnwrap(DBHEstimator.bracketDepthGeometry(frame: f,
            leftFraction: 0.25, rightFraction: 0.45, viewSize: view))
        let b = RawCaptureManifest.DBHBundle.Bracket(enabled: true, left: g.left, right: g.right,
            coordinateSpace: "depth_axis_fraction_v1", screenFractions: [0.25,0.45],
            viewportSize: [1032,1376])
        let id = "coordinate-regression-" + UUID().uuidString
        let dir = RawCaptureStore.bundleDirectory(id: id)
        defer { try? FileManager.default.removeItem(at: dir) }
        let cal = ProjectCalibration(depthNoiseMm: 1, dbhCorrectionAlpha: 0, dbhCorrectionBeta: 1)
        RawCaptureRecorder.recordDBH(id: id, frames: Array(repeating: f, count: 5),
            tapPixel: SIMD2(128,96), calibration: cal, algorithm: .chord,
            bracket: b, guideAxis: g.axis, captureManual: true,
            context: .quick, referenceJPEG: nil, gps: nil)
        let manifest = try XCTUnwrap(RawCaptureStore.loadManifest(id: id))
        XCTAssertEqual(manifest.dbh?.frames.first?.viewToDepth, portrait.flattened)
        XCTAssertEqual(manifest.dbh?.bracket.coordinateSpace, "depth_axis_fraction_v1")
        let inputs = try XCTUnwrap(RawCaptureReplay.reconstructDBHInputs(manifest: manifest, id: id))
        XCTAssertEqual(inputs.frames.first?.viewMapping, portrait)
        let result = try XCTUnwrap(RawCaptureReplay.rerunDBH(manifest: manifest, id: id))
        XCTAssertEqual(Double(result.diameterCm), manifest.resultLive.value, accuracy: 1e-6)
    }
}
