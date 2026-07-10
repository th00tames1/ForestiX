// Spec §8 Geo/SamplingGenerator. REQ-PRJ-004.

import XCTest
import Models
@testable import Geo

final class SamplingGeneratorTests: XCTestCase {

    // Small rectangle near Seattle: ~1.11 km (N-S) × ~0.76 km (E-W) at 47°N,
    // roughly 84 ha. Large enough to host a 100 m grid with many plots.
    private let ringSeattle: [CoordinateConversions.LatLon] = [
        .init(latitude: 47.60, longitude: -122.30),
        .init(latitude: 47.60, longitude: -122.29),
        .init(latitude: 47.61, longitude: -122.29),
        .init(latitude: 47.61, longitude: -122.30),
        .init(latitude: 47.60, longitude: -122.30)
    ]

    // MARK: - Systematic grid

    func testSystematicGridProducesNonZeroCount() throws {
        let projectId = UUID()
        let stratum = SamplingGenerator.StratumInput(stratumId: UUID(), rings: [ringSeattle])
        let plots = try SamplingGenerator.generate(
            strata: [stratum],
            options: .init(projectId: projectId, scheme: .systematicGrid,
                           gridSpacingMeters: 150, seed: 42)
        )
        XCTAssertGreaterThan(plots.count, 10)
        XCTAssertEqual(Set(plots.map(\.plotNumber)).count, plots.count)    // unique
        XCTAssertTrue(plots.allSatisfy { $0.projectId == projectId })
        XCTAssertTrue(plots.allSatisfy { $0.stratumId == stratum.stratumId })
        XCTAssertTrue(plots.allSatisfy { !$0.visited })
    }

    func testSystematicGridIsReproducibleForSameSeed() throws {
        let stratum = SamplingGenerator.StratumInput(stratumId: UUID(), rings: [ringSeattle])
        let a = try SamplingGenerator.generate(
            strata: [stratum],
            options: .init(projectId: UUID(), scheme: .systematicGrid,
                           gridSpacingMeters: 150, seed: 123)
        )
        let b = try SamplingGenerator.generate(
            strata: [stratum],
            options: .init(projectId: UUID(), scheme: .systematicGrid,
                           gridSpacingMeters: 150, seed: 123)
        )
        XCTAssertEqual(a.map { [$0.plannedLat, $0.plannedLon] },
                       b.map { [$0.plannedLat, $0.plannedLon] })
    }

    func testSystematicGridMissingSpacingThrows() {
        let stratum = SamplingGenerator.StratumInput(stratumId: UUID(), rings: [ringSeattle])
        XCTAssertThrowsError(try SamplingGenerator.generate(
            strata: [stratum],
            options: .init(projectId: UUID(), scheme: .systematicGrid, seed: 1)
        ))
    }

    // MARK: - Stratified random

    func testStratifiedRandomYieldsRequestedCountPerStratum() throws {
        let s1 = SamplingGenerator.StratumInput(stratumId: UUID(), rings: [ringSeattle])
        let s2 = SamplingGenerator.StratumInput(stratumId: UUID(), rings: [ringSeattle])
        let plots = try SamplingGenerator.generate(
            strata: [s1, s2],
            options: .init(projectId: UUID(), scheme: .stratifiedRandom,
                           nPerStratum: 5, seed: 7)
        )
        XCTAssertEqual(plots.filter { $0.stratumId == s1.stratumId }.count, 5)
        XCTAssertEqual(plots.filter { $0.stratumId == s2.stratumId }.count, 5)
        XCTAssertEqual(plots.count, 10)
    }

    // MARK: - Manual

    func testManualSchemeReturnsEmpty() throws {
        let stratum = SamplingGenerator.StratumInput(stratumId: UUID(), rings: [ringSeattle])
        let plots = try SamplingGenerator.generate(
            strata: [stratum],
            options: .init(projectId: UUID(), scheme: .manual, seed: 1)
        )
        XCTAssertTrue(plots.isEmpty)
    }

    // MARK: - Input validation

    func testEmptyStrataThrows() {
        XCTAssertThrowsError(try SamplingGenerator.generate(
            strata: [],
            options: .init(projectId: UUID(), scheme: .systematicGrid,
                           gridSpacingMeters: 100, seed: 1)
        ))
    }
}
