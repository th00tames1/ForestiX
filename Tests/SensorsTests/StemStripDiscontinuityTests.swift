// Phase 9 Option B — depth-discontinuity split inside the stem-strip
// extractor. These tests directly exercise `extractGuideRowStemStrip`
// with hand-crafted depth profiles so the split logic is verified in
// isolation, separate from the synthetic-cylinder pipeline tests in
// DBHEstimatorTests.swift.
//
// Each test builds a one-row depth map with:
//   • Several "trunk" pixels at known depths
//   • Maybe a discontinuity (jump) between two trunks
//   • Maybe a noise spike or step inside one trunk
// And asserts that the strip the extractor returns is the EXPECTED
// subset — proving:
//   1. Clean trunks are NOT split (per-pixel gradient stays under threshold)
//   2. Two adjacent trunks at slightly different depths ARE split
//   3. Mid-trunk noise spikes split (acceptable false positive)
//   4. Buttress-style step features split (acceptable, as those trees
//      can't be measured cleanly anyway)

import XCTest
import simd
@testable import Sensors

final class StemStripDiscontinuityTests: XCTestCase {

    // MARK: - Fixtures

    /// Minimal one-row depth map. The width is 64 columns, height 1
    /// (we only set guideRowY = 0). Confidence is 2 (high) for every
    /// pixel that has a positive depth, 0 elsewhere — matches Apple's
    /// LiDAR convention where invalid pixels carry confidence 0.
    private func makeFrame(depths: [Float]) -> ARDepthFrame {
        let width = depths.count
        let conf: [UInt8] = depths.map { $0 > 0 ? 2 : 0 }
        // Identity pose + dummy intrinsics — we never back-project here.
        let K = simd_float3x3(
            SIMD3<Float>(200, 0, 0),
            SIMD3<Float>(0, 200, 0),
            SIMD3<Float>(Float(width) / 2, 0, 1))
        return ARDepthFrame(
            width: width, height: 1,
            depth: depths, confidence: conf,
            intrinsics: K,
            cameraPoseWorld: matrix_identity_float4x4,
            timestamp: 0)
    }

    // MARK: - Test 1 — clean trunk is not split

    /// A 21-column trunk with a smooth depth profile (1.5 m centre,
    /// edges curving back to ~1.51 m) must extract entirely. The
    /// per-pixel gradient stays well under 4 cm so the discontinuity
    /// gate never fires.
    func testCleanTrunkExtractsEntireStrip() {
        let centre = 32
        var depths = [Float](repeating: 0, count: 64)
        // Smooth parabolic profile: depth = 1.5 + 0.0001 · (col − centre)²
        // → depth at edges ≈ 1.51, max per-pixel jump ≈ 0.4 mm.
        for col in (centre - 10)...(centre + 10) {
            let dx = Float(col - centre)
            depths[col] = 1.5 + 0.0001 * dx * dx
        }
        let frame = makeFrame(depths: depths)

        let strip = DBHEstimator.extractGuideRowStemStrip(
            frame: frame,
            guideRowY: 0,
            tapColumn: centre,
            dTap: 1.5,
            deltaDepth: 0.15,
            discontinuityThresholdM: 0.04)

        XCTAssertEqual(strip.count, 21,
            "Clean smooth trunk must extract all 21 columns; got \(strip.count). "
            + "Strip: \(strip)")
        XCTAssertEqual(strip.first, centre - 10)
        XCTAssertEqual(strip.last, centre + 10)
    }

    // MARK: - Test 2 — two trunks at slightly different depths split

    /// Trunk A at 1.50 m (columns 22…32) and Trunk B at 1.55 m
    /// (columns 33…43) sit visually adjacent — both inside the
    /// ±15 cm absolute window so the OLD walk would absorb both.
    /// With Option B's 4 cm adjacent threshold, the walk must stop
    /// at the boundary and return only Trunk A.
    func testTwoAdjacentTrunksAtDifferentDepthsSplit() {
        var depths = [Float](repeating: 0, count: 64)
        // Trunk A: smooth profile around col 27, depth 1.50 m
        for col in 22...32 {
            let dx = Float(col - 27)
            depths[col] = 1.50 + 0.0001 * dx * dx
        }
        // Trunk B: smooth profile around col 38, depth 1.55 m
        // (boundary jump between col 32 and col 33 is ~5 cm, > threshold)
        for col in 33...43 {
            let dx = Float(col - 38)
            depths[col] = 1.55 + 0.0001 * dx * dx
        }
        let frame = makeFrame(depths: depths)

        let strip = DBHEstimator.extractGuideRowStemStrip(
            frame: frame,
            guideRowY: 0,
            tapColumn: 27,             // tap on Trunk A
            dTap: 1.50,
            deltaDepth: 0.15,
            discontinuityThresholdM: 0.04)

        XCTAssertFalse(strip.contains(where: { $0 >= 33 }),
            "Strip leaked into Trunk B columns: \(strip)")
        XCTAssertEqual(strip.count, 11,
            "Strip should cover Trunk A's 11 columns only; got \(strip.count). "
            + "Strip: \(strip)")
        XCTAssertEqual(strip.first, 22)
        XCTAssertEqual(strip.last,  32)
    }

    /// Sanity check — same fixture, but with the discontinuity check
    /// disabled (`Float.infinity`), the OLD behaviour leaks into
    /// Trunk B because both trunks fit in ±15 cm. Proves the new
    /// argument is what does the splitting (not some other change).
    func testWithoutDiscontinuityCheckBothTrunksMerge() {
        var depths = [Float](repeating: 0, count: 64)
        for col in 22...32 {
            let dx = Float(col - 27)
            depths[col] = 1.50 + 0.0001 * dx * dx
        }
        for col in 33...43 {
            let dx = Float(col - 38)
            depths[col] = 1.55 + 0.0001 * dx * dx
        }
        let frame = makeFrame(depths: depths)

        let strip = DBHEstimator.extractGuideRowStemStrip(
            frame: frame,
            guideRowY: 0,
            tapColumn: 27,
            dTap: 1.50,
            deltaDepth: 0.15,
            discontinuityThresholdM: .infinity)

        XCTAssertTrue(strip.contains(43),
            "With check disabled the walk should reach Trunk B's right edge. "
            + "Strip: \(strip)")
        XCTAssertEqual(strip.count, 22)
    }

    // MARK: - Test 3 — mid-trunk noise spike triggers split

    /// A single trunk with one rogue pixel that jumps 6 cm forward
    /// (LiDAR depth-noise spike). Option B treats this as a
    /// boundary and stops at the spike — accepted false positive,
    /// since the strip still contains enough of the trunk to fit.
    func testMidTrunkNoiseSpikeSplits() {
        let centre = 32
        var depths = [Float](repeating: 0, count: 64)
        for col in (centre - 10)...(centre + 10) {
            let dx = Float(col - centre)
            depths[col] = 1.5 + 0.0001 * dx * dx
        }
        // Rogue pixel: 6 cm closer than its neighbours.
        depths[centre + 5] = depths[centre + 5] - 0.06
        let frame = makeFrame(depths: depths)

        let strip = DBHEstimator.extractGuideRowStemStrip(
            frame: frame,
            guideRowY: 0,
            tapColumn: centre,
            dTap: 1.5,
            deltaDepth: 0.15,
            discontinuityThresholdM: 0.04)

        XCTAssertFalse(strip.contains(centre + 5),
            "Spike pixel must not be in strip. Strip: \(strip)")
        XCTAssertFalse(strip.contains(where: { $0 > centre + 4 }),
            "Walk must stop at spike, not absorb pixels beyond. Strip: \(strip)")
        // Left side is unaffected; should reach the full left edge.
        XCTAssertEqual(strip.first, centre - 10)
    }

    // MARK: - Test 4 — buttress-style step splits

    /// Buttress / branch / swelling — a 5 cm step in trunk depth
    /// near the right edge. Option B splits at the step. Accepted
    /// behaviour: such trees fail other quality gates anyway, and
    /// splitting early lets the cruiser see "arc coverage too low"
    /// quickly rather than fitting an inflated combined surface.
    func testButtressStepSplits() {
        let centre = 32
        var depths = [Float](repeating: 0, count: 64)
        // Smooth left half + centre.
        for col in (centre - 10)...(centre + 5) {
            let dx = Float(col - centre)
            depths[col] = 1.5 + 0.0001 * dx * dx
        }
        // Step to a swollen / buttressed surface: 5 cm closer.
        for col in (centre + 6)...(centre + 10) {
            let dx = Float(col - centre)
            depths[col] = 1.5 + 0.0001 * dx * dx - 0.05
        }
        let frame = makeFrame(depths: depths)

        let strip = DBHEstimator.extractGuideRowStemStrip(
            frame: frame,
            guideRowY: 0,
            tapColumn: centre,
            dTap: 1.5,
            deltaDepth: 0.15,
            discontinuityThresholdM: 0.04)

        XCTAssertFalse(strip.contains(where: { $0 >= centre + 6 }),
            "Walk must stop at the buttress step. Strip: \(strip)")
        XCTAssertEqual(strip.first, centre - 10)
        XCTAssertEqual(strip.last, centre + 5)
    }

    // MARK: - Test 5 — gap (background pixel) still stops the walk

    /// Pre-existing behaviour preserved: a pixel whose ABSOLUTE depth
    /// is outside ±deltaDepth of dTap (e.g., a sky / background
    /// pixel between two trunks) still terminates the walk via the
    /// existing absolute-depth gate, regardless of whether the
    /// discontinuity check is present. Catches regressions where the
    /// new logic might accidentally weaken the absolute filter.
    func testBackgroundPixelStopsWalk() {
        let centre = 32
        var depths = [Float](repeating: 0, count: 64)
        for col in (centre - 5)...(centre + 5) {
            let dx = Float(col - centre)
            depths[col] = 1.5 + 0.0001 * dx * dx
        }
        // Background pixel at col centre + 6 (depth = 5.0 m, far beyond
        // ±0.15 of dTap = 1.5).
        depths[centre + 6] = 5.0
        // Another trunk at depth 1.49 starting at col centre + 7 — but
        // walk should never reach it because background pixel breaks
        // contiguity first.
        for col in (centre + 7)...(centre + 12) {
            depths[col] = 1.49
        }
        let frame = makeFrame(depths: depths)

        let strip = DBHEstimator.extractGuideRowStemStrip(
            frame: frame,
            guideRowY: 0,
            tapColumn: centre,
            dTap: 1.5,
            deltaDepth: 0.15,
            discontinuityThresholdM: 0.04)

        XCTAssertFalse(strip.contains(where: { $0 > centre + 5 }),
            "Walk must stop before the background pixel. Strip: \(strip)")
    }
}
