import XCTest
import simd
@testable import Sensors
import Models

final class DepthGuideAxisTests: XCTestCase {
    private func frame(_ mapping: DepthViewMapping?, depth: Float = 1) -> ARDepthFrame {
        ARDepthFrame(width: 256, height: 192,
                     depth: Array(repeating: depth, count: 256 * 192),
                     confidence: Array(repeating: 2, count: 256 * 192),
                     intrinsics: matrix_identity_float3x3,
                     cameraPoseWorld: matrix_identity_float4x4,
                     timestamp: 0, viewMapping: mapping)
    }
    private let center = SIMD2<Double>(128, 96)

    func testPortraitHorizontalIsNativeColumnRegardlessOfDepth() {
        let mapping = DepthViewMapping(a: 0, b: 0.5, tx: 0,
                                       c: -0.5, d: 0, ty: 192)
        for depth: Float in [0, 1, 7] {
            XCTAssertEqual(DBHEstimator.screenHorizontalGuideAxis(
                frame: frame(mapping, depth: depth), tapPixel: center), .col(x: 128))
        }
    }
    func testLandscapeHorizontalIsNativeRow() {
        let mapping = DepthViewMapping(a: 0.5, b: 0, tx: 0, c: 0, d: 0.5, ty: 0)
        XCTAssertEqual(DBHEstimator.screenHorizontalGuideAxis(
            frame: frame(mapping), tapPixel: center), .row(y: 96))
    }
    func testOppositePortraitAndCropDoNotChangeWalkDirection() {
        let mapping = DepthViewMapping(a: 0, b: -0.2, tx: 240,
                                       c: 0.3, d: 0, ty: 15)
        XCTAssertEqual(DBHEstimator.screenHorizontalGuideAxis(
            frame: frame(mapping), tapPixel: center), .col(x: 128))
    }
    func testMissingDegenerateObliqueAndNonfiniteMappingsFailClosed() {
        let mappings: [DepthViewMapping?] = [
            nil,
            .init(a: 0, b: 0, tx: 0, c: 0, d: 0, ty: 0),
            .init(a: 1, b: -1, tx: 0, c: 1, d: 1, ty: 0),
            .init(a: .nan, b: 0, tx: 0, c: 0, d: 1, ty: 0),
        ]
        for mapping in mappings {
            XCTAssertNil(DBHEstimator.screenHorizontalGuideAxis(
                frame: frame(mapping), tapPixel: center))
        }
    }
    func testFlatPortraitDepthCannotSelectTheNativeRow() {
        let mapping = DepthViewMapping(a: 0, b: 0.5, tx: 0,
                                       c: -0.5, d: 0, ty: 192)
        let sample = frame(mapping)
        // The old content vote chooses a screen-vertical slice in this scene.
        XCTAssertEqual(DBHEstimator.pickGuideAxis(
            frame: sample, tapPixel: center,
            calibration: ProjectCalibration(depthNoiseMm: 1,
                dbhCorrectionAlpha: 1, dbhCorrectionBeta: 0)), .row(y: 96))
        XCTAssertEqual(DBHEstimator.screenHorizontalGuideAxis(
            frame: sample, tapPixel: center), .col(x: 128))
    }
}
