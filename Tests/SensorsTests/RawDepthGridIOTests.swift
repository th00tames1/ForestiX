import XCTest
import simd
import Models
@testable import Sensors

/// Synthetic application inputs only; no field observations.
final class RawDepthGridIOTests: XCTestCase {
    private let depth: [Float] = [-0.0,Float(bitPattern: 0x7fc01234),.infinity,1.2345678,0.001,8,9,-1]
    private let confidence: [UInt8] = [0,0,0,1,2,2,0,0]
    private func floats(_ values: [Float]) -> Data {
        Data(values.flatMap { v -> [UInt8] in let b = v.bitPattern; return (0..<4).map { UInt8(truncatingIfNeeded: b >> ($0 * 8)) } })
    }
    private func withDirectory(_ body: (URL) throws -> Void) throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-grid-"+UUID().uuidString)
        try FileManager.default.createDirectory(at: dir,withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        try body(dir)
    }
    private func read(_ dir: URL, format: String = "f32m", file: String = "depth.bin", conf: String? = "depth.conf") throws -> RawDepthGridIO.Grid {
        try RawDepthGridIO.read(directory: dir,width: 4,height: 2,format: format,depthFile: file,confidenceFile: conf)
    }
    func testF32BitsAndRecordedConfidenceRemainExact() throws {
        try withDirectory { dir in
            try floats(depth).write(to: dir.appendingPathComponent("depth.bin"))
            try Data(confidence).write(to: dir.appendingPathComponent("depth.conf"))
            let grid = try read(dir)
            XCTAssertEqual(grid.depth.map(\.bitPattern),depth.map(\.bitPattern)); XCTAssertEqual(grid.confidence,confidence)
        }
    }
    func testLegacyF32WithoutConfidenceKeepsHistoricalDerivation() throws {
        try withDirectory { dir in
            try floats(depth).write(to: dir.appendingPathComponent("depth.bin"))
            let grid = try read(dir,conf: nil)
            XCTAssertEqual(grid.depth.map(\.bitPattern),depth.map(\.bitPattern))
            XCTAssertEqual(grid.confidence,[0,0,0,2,2,2,0,0])
        }
    }
    func testAndroidU16LegacyIngestIncludingHoleValues() throws {
        try withDirectory { dir in
            let mm: [UInt16] = [0,1,999,1000,1250,8000,8001,65535]
            try Data(mm.flatMap { [UInt8(truncatingIfNeeded: $0),UInt8(truncatingIfNeeded: $0 >> 8)] }).write(to: dir.appendingPathComponent("depth.bin"))
            let grid = try read(dir,format: "u16mm",conf: nil)
            XCTAssertEqual(grid.depth.map(\.bitPattern),[Float(0),0.001,0.999,1,1.25,8,0,0].map(\.bitPattern))
            XCTAssertEqual(grid.confidence,[0,2,2,2,2,2,0,0])
        }
    }
    func testExplicitCorruptOrMissingConfidenceNeverBecomesDerivedConfidence() throws {
        try withDirectory { dir in
            try floats(depth).write(to: dir.appendingPathComponent("depth.bin"))
            for bytes in [Data([2]),Data(repeating: 3,count: 8),Data(repeating: 2,count: 9)] {
                try bytes.write(to: dir.appendingPathComponent("depth.conf"))
                XCTAssertThrowsError(try read(dir))
            }
            XCTAssertThrowsError(try read(dir,conf: "missing.conf"))
        }
    }
    func testBoundsTruncationUnknownFormatAndEscapedPathsAreRejected() throws {
        try withDirectory { dir in
            for count in [31,33] {
                try Data(repeating: 0,count: count).write(to: dir.appendingPathComponent("depth.bin"))
                XCTAssertThrowsError(try read(dir,conf: nil))
            }
            try floats(depth).write(to: dir.appendingPathComponent("depth.bin"))
            for file in ["../depth.bin",dir.appendingPathComponent("depth.bin").path,"sub/depth.bin","sub\\depth.bin",""] {
                XCTAssertThrowsError(try read(dir,file: file,conf: nil))
            }
            try FileManager.default.createSymbolicLink(at: dir.appendingPathComponent("link.bin"),withDestinationURL: dir.appendingPathComponent("depth.bin"))
            XCTAssertThrowsError(try read(dir,file: "link.bin",conf: nil))
            XCTAssertThrowsError(try read(dir,format: "unknown",conf: nil))
            for size in [(0,2),(Int.max,2),(4096,4096)] {
                XCTAssertThrowsError(try RawDepthGridIO.read(directory: dir,width: size.0,height: size.1,format: "f32m",depthFile: "depth.bin",confidenceFile: nil))
            }
        }
    }
    func testOptionalConfidenceKeysDecodeHistoricalAndAndroidDescriptors() throws {
        let meta = RawCaptureManifest.DBHBundle.Frame(depthFile: "d.bin",width: 4,height: 2,format: "u16mm",
            fx: 100,fy: 100,cx: 2,cy: 1,tapPx: [2,1],axis: "row",cameraPose: RawCaptureMatrix.flat(matrix_identity_float4x4),
            viewToDepth: [1,0,0,0,1,0],confidenceFile: "d.conf")
        let data = try JSONEncoder().encode(meta)
        XCTAssertEqual(try JSONDecoder().decode(RawCaptureManifest.DBHBundle.Frame.self,from: data).confidenceFile,"d.conf")
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        object.removeValue(forKey: "confidence_file")
        XCTAssertNil(try JSONDecoder().decode(RawCaptureManifest.DBHBundle.Frame.self,from: JSONSerialization.data(withJSONObject: object)).confidenceFile)
        let aim = RawCaptureManifest.HeightBundle.Aim(pitchDeg: 1,cameraPose: meta.cameraPose,confidenceFile: "base.conf")
        XCTAssertEqual(try JSONDecoder().decode(RawCaptureManifest.HeightBundle.Aim.self,from: JSONEncoder().encode(aim)).confidenceFile,"base.conf")
    }
    func testHeightRGBWithoutDepthIsStoredAndDoesNotInflateFrameCount() throws {
        let id = UUID().uuidString, dir = RawCaptureStore.bundleDirectory(id: id)
        defer { try? FileManager.default.removeItem(at: dir) }
        let bytes = Data([0xff,0xd8,0xff,0xd9])
        let outcome = RawCaptureRecorder.recordHeight(id: id,anchorWorld: .zero,anchorHitType: "synthetic",anchorDistanceM: 10,
            anchorPose: matrix_identity_float4x4,basePitchRad: -0.1,baseStanding: SIMD3(10,1.5,0),
            baseRotationPose: matrix_identity_float4x4,topPitchRad: 0.6,topPose: matrix_identity_float4x4,dHM: 10,
            poseSamples: [],trackingDropped: false,calibration: .identity,context: .quick,gps: nil,
            baseJPEG: bytes,topJPEG: bytes)
        guard case .saved = outcome else { return XCTFail("Synthetic height recording failed") }
        let m = try XCTUnwrap(RawCaptureStore.loadManifest(id: id))
        XCTAssertEqual(m.resultLive.frameCount,0)
        XCTAssertNil(m.height?.base.depthFile); XCTAssertNil(m.height?.top.depthFile)
        XCTAssertNil(m.height?.base.format); XCTAssertNil(m.height?.top.width)
        XCTAssertEqual(m.height?.base.rgbFile,"rgb_base.jpg"); XCTAssertEqual(m.height?.top.rgbFile,"rgb_top.jpg")
        XCTAssertEqual(try Data(contentsOf: dir.appendingPathComponent("rgb_base.jpg")),bytes)
        XCTAssertEqual(try Data(contentsOf: dir.appendingPathComponent("rgb_top.jpg")),bytes)
        XCTAssertEqual(m.replaySelfcheck.status,"pass")
    }
    func testMixedBundleKeepsFrameOrderAndRejectsPartialLegacyReplay() throws {
        let id = UUID().uuidString, dir = RawCaptureStore.bundleDirectory(id: id)
        defer { try? FileManager.default.removeItem(at: dir) }
        let f = ARDepthFrame(width: 128,height: 96,depth: .init(repeating: 1.25,count: 128*96),
            confidence: .init(repeating: 2,count: 128*96),intrinsics: RawCaptureMatrix.intrinsics(fx: 100,fy: 100,cx: 64,cy: 48),
            cameraPoseWorld: matrix_identity_float4x4,timestamp: 1,viewMapping: .init(a: 1,b: 0,tx: 0,c: 0,d: 1,ty: 0))
        let evidence = RawCaptureEvidence(measurementId: id,resultSeconds: 2,
            displayedResult: .init(value: 25,sigma: nil,tier: "green",method: "synthetic",valid: true))
        let f2 = ARDepthFrame(width: f.width,height: f.height,depth: .init(repeating: 1.26,count: f.depth.count),
            confidence: f.confidence,intrinsics: f.intrinsics,cameraPoseWorld: f.cameraPoseWorld,timestamp: 1.1,viewMapping: f.viewMapping)
        let outcome = RawCaptureRecorder.recordDBH(id: id,frames: [f,f2,f,f2,f],tapPixel: SIMD2(64,48),calibration: .identity,
            algorithm: .chord,bracket: .init(enabled: true,left: 0.25,right: 0.75),guideAxis: .row(y: 48),captureManual: true,
            context: .quick,referenceJPEG: nil,gps: nil,captureEvidence: evidence)
        guard case .saved = outcome else { return XCTFail("Synthetic recording failed") }
        var manifest = try XCTUnwrap(RawCaptureStore.loadManifest(id: id))
        let originalReplay = try XCTUnwrap(RawCaptureReplay.rerunCapturedDBH(manifest: manifest,id: id))
        XCTAssertGreaterThan(originalReplay,0)
        manifest.dbh!.frames[0].format = "u16mm"
        manifest.dbh!.frames[1].confidenceFile = "depth_1.conf"
        try Data(repeating: 2,count: 128*96).write(to: dir.appendingPathComponent("depth_1.conf"))
        try Data((0..<128*96).flatMap { _ in [UInt8(0xe2),0x04] }).write(to: dir.appendingPathComponent("depth_0.bin"))
        let inputs = try XCTUnwrap(RawCaptureReplay.reconstructDBHInputs(manifest: manifest,id: id))
        XCTAssertEqual(inputs.frames.count,5); XCTAssertEqual(inputs.frames[0].depth[0],1.25)
        XCTAssertEqual(inputs.frames[1].depth[0],1.26); XCTAssertEqual(inputs.frames[2].depth[0],1.25)
        XCTAssertEqual(RawCaptureReplay.rerunDBH(manifest: manifest,id: id)?.diameterCm,Float(originalReplay))
        // Removing one owned synthetic legacy payload cannot silently drop a frame.
        try FileManager.default.removeItem(at: dir.appendingPathComponent("depth_0.bin"))
        XCTAssertNil(RawCaptureReplay.reconstructDBHInputs(manifest: manifest,id: id))
        // The independent checksummed modern input remains sufficient.
        XCTAssertEqual(try RawCaptureReplay.rerunCapturedDBH(manifest: manifest,id: id),originalReplay)
    }
}
