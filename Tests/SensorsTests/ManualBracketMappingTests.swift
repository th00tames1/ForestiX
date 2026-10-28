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
        let id = UUID().uuidString
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
    func testActualInputReplayUsesOriginalConfidenceRatherThanCanonicalConfidence() throws {
        let original = frame(portrait)
        let g = try XCTUnwrap(DBHEstimator.bracketDepthGeometry(frame: original,leftFraction: 0.25,rightFraction: 0.45,viewSize: view))
        let b = RawCaptureManifest.DBHBundle.Bracket(enabled: true,left: g.left,right: g.right)
        for confidence: UInt8 in [2,0] {
            let f = ARDepthFrame(width: original.width,height: original.height,depth: original.depth,
                confidence: .init(repeating: confidence,count: original.depth.count),intrinsics: original.intrinsics,
                cameraPoseWorld: original.cameraPoseWorld,timestamp: 123.25,viewMapping: portrait,viewportRevision: 4)
            let cal = ProjectCalibration.identity
            let actual = DBHEstimator.bracketChordEstimate(frames: [f],guideAxis: g.axis,leftFraction: g.left,rightFraction: g.right,calibration: cal)
            let id = UUID().uuidString; let dir = RawCaptureStore.bundleDirectory(id: id)
            defer { try? FileManager.default.removeItem(at: dir) }
            let evidence = RawCaptureEvidence(measurementId: id,resultSeconds: 124,
                displayedResult: .init(value: actual.map { Double($0.diameterCm) },sigma: actual.map { Double($0.sigmaRmm) },
                    tier: actual?.confidence.rawValue,method: actual?.method.rawValue,valid: actual?.confidence != nil && actual?.confidence != .red))
            let outcome = RawCaptureRecorder.recordDBH(id: id,frames: [f],tapPixel: SIMD2(128,96),calibration: cal,
                algorithm: .chord,bracket: b,guideAxis: g.axis,captureManual: true,context: .quick,referenceJPEG: nil,gps: nil,captureEvidence: evidence)
            guard case .saved = outcome else { return XCTFail("Raw evidence was not saved: \(outcome)") }
            let m = try XCTUnwrap(RawCaptureStore.loadManifest(id: id)); let e = try XCTUnwrap(m.captureEvidence)
            XCTAssertEqual(m.measurementId,id); XCTAssertEqual(e.displayedResult,evidence.displayedResult)
            XCTAssertEqual(try RawCaptureReplay.rerunCapturedDBH(manifest: m,id: id),actual.map { Double($0.diameterCm) })
            let restored = try RawCaptureEvidenceIO.readFrame(try XCTUnwrap(e.frames.first),directory: dir)
            XCTAssertEqual(restored.confidence,f.confidence); XCTAssertEqual(restored.viewMapping,portrait)
            XCTAssertEqual(restored.timestamp,123.25); XCTAssertEqual(restored.viewportRevision,4)
            XCTAssertGreaterThan(m.resultLive.value,0) // Legacy canonical analysis is retained separately.
            // The bracket estimator's confidence policy is unchanged; preserve
            // its actual result rather than inventing a new confidence gate.
            var bytes = try Data(contentsOf: dir.appendingPathComponent(e.frames[0].confidence.file)); bytes[0] ^= 1
            try bytes.write(to: dir.appendingPathComponent(e.frames[0].confidence.file))
            XCTAssertThrowsError(try RawCaptureReplay.rerunCapturedDBH(manifest: m,id: id))
        }
    }
    func testHeightRetainsActualRadiansAndSeparateDisplayedResult() throws {
        let id = UUID().uuidString; let dir = RawCaptureStore.bundleDirectory(id: id)
        defer { try? FileManager.default.removeItem(at: dir) }
        let anchor = SIMD3<Float>(0,0,0), standing = SIMD3<Float>(10,1.5,0)
        let top: Float = 0.600000084, base: Float = -0.100000017
        let r = HeightEstimator.estimate(input: .init(anchorPointWorld: anchor,standingPointWorld: standing,
            alphaTopRad: top,alphaBaseRad: base,trackingStateWasNormalThroughout: true,projectCalibration: .identity))
        let evidence = RawCaptureEvidence(measurementId: id,resultSeconds: 124,displayedResult: .init(value: Double(r.heightM),
            sigma: r.sigmaHm.map(Double.init),tier: r.confidence.rawValue,method: r.method.rawValue,valid: HeightEstimator.canAccept(r)))
        // A fast Accept/GT precedes the writer. Both must survive its two
        // manifest flushes without changing the measured height or radians.
        XCTAssertEqual(RawCaptureStore.applyTruth(id: id,value: 19.125,unit: .meters),.pending)
        XCTAssertTrue(RawCaptureStore.markAccepted(id: id))
        func record() -> RawCaptureOutcome { RawCaptureRecorder.recordHeight(id: id,anchorWorld: anchor,anchorHitType: "synthetic",anchorDistanceM: 1,
            anchorPose: matrix_identity_float4x4,basePitchRad: base,baseStanding: standing,baseRotationPose: matrix_identity_float4x4,
            topPitchRad: top,topPose: matrix_identity_float4x4,dHM: r.dHm,poseSamples: [],trackingDropped: false,
            calibration: .identity,context: .quick,gps: nil,captureEvidence: evidence) }
        let outcome = record()
        guard case .saved = outcome else { return XCTFail("Height evidence not saved: \(outcome)") }
        let m = try XCTUnwrap(RawCaptureStore.loadManifest(id: id)); let e = try XCTUnwrap(m.captureEvidence)
        XCTAssertEqual(e.scalars["alpha_top_rad"],Double(top)); XCTAssertEqual(e.scalars["alpha_base_rad"],Double(base))
        XCTAssertEqual(try RawCaptureReplay.rerunCapturedHeight(manifest: m),Double(r.heightM)); XCTAssertEqual(e.replayStatus,"pass")
        XCTAssertEqual(e.displayedResult.value,Double(r.heightM)); XCTAssertEqual(m.truth.value,19.125); XCTAssertEqual(m.truth.truthUnit,"m")
        XCTAssertTrue(m.resultLive.operatorAccepted)
        XCTAssertFalse(FileManager.default.fileExists(atPath: dir.appendingPathComponent("pending_patch.json").path))
        let names = try FileManager.default.contentsOfDirectory(atPath: dir.path).sorted()
        let before = try names.map { try Data(contentsOf: dir.appendingPathComponent($0)) }
        guard case .failed(let reason) = record() else { return XCTFail("Duplicate height writer replaced an existing capture") }
        XCTAssertTrue(reason.contains("existing raw capture preserved"))
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: dir.path).sorted(),names)
        XCTAssertEqual(try names.map { try Data(contentsOf: dir.appendingPathComponent($0)) },before)
    }
}
