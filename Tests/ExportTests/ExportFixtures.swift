// Shared fixtures for Phase 6 export round-trip and golden-file tests.
//
// Each test can build either:
//   • a `Bundle.minimal()` — three plots, five trees each, two strata — for
//     end-to-end round-trip checks, or
//   • individual model instances via the helpers (makePlot, makeTree) for
//     targeted unit tests.
//
// Dates are pinned to a fixed Unix epoch so golden files and RFC 4180
// quoting tests produce stable output.

import Foundation
import Models
import InventoryEngine
import Export

enum ExportFixtures {

    static let fixedDate = Date(timeIntervalSince1970: 1_700_000_000)

    static let projectId = UUID(uuidString: "11111111-1111-1111-1111-111111111111")!
    static let designId  = UUID(uuidString: "22222222-2222-2222-2222-222222222222")!
    static let stratumAId = UUID(uuidString: "AAAAAAAA-0000-0000-0000-000000000001")!
    static let stratumBId = UUID(uuidString: "BBBBBBBB-0000-0000-0000-000000000002")!

    static func project() -> Project {
        Project(
            id: projectId,
            name: "Cascade Demo",
            description: "Phase 6 fixture project",
            owner: "demo-cruiser",
            createdAt: fixedDate, updatedAt: fixedDate,
            units: .metric,
            breastHeightConvention: .uphill,
            slopeCorrection: false,
            lidarBiasMm: 0.5, depthNoiseMm: 1.0,
            dbhCorrectionAlpha: 0, dbhCorrectionBeta: 1,
            vioDriftFraction: 0.02)
    }

    static func design() -> CruiseDesign {
        CruiseDesign(
            id: designId, projectId: projectId,
            plotType: .fixedArea, plotAreaAcres: 0.1,
            baf: nil, samplingScheme: .systematicGrid,
            gridSpacingMeters: 50,
            heightSubsampleRule: .everyKth(k: 3))
    }

    static func strata() -> [Stratum] {
        [
            Stratum(id: stratumAId, projectId: projectId,
                    name: "Unit A", areaAcres: 20,
                    polygonGeoJSON: #"{"type":"Polygon","coordinates":[[[-122.31,47.60],[-122.29,47.60],[-122.29,47.62],[-122.31,47.62],[-122.31,47.60]]]}"#),
            Stratum(id: stratumBId, projectId: projectId,
                    name: "Unit B", areaAcres: 30,
                    polygonGeoJSON: #"{"type":"Polygon","coordinates":[[[-122.29,47.60],[-122.27,47.60],[-122.27,47.62],[-122.29,47.62],[-122.29,47.60]]]}"#)
        ]
    }

    static func plannedPlots() -> [PlannedPlot] {
        let base = UUID(uuidString: "CCCCCCCC-0000-0000-0000-000000000000")!
        return (1...4).map { n in
            PlannedPlot(
                id: UUID(uuidString: "CCCCCCCC-0000-0000-0000-00000000000\(n)") ?? base,
                projectId: projectId,
                stratumId: n <= 2 ? stratumAId : stratumBId,
                plotNumber: n,
                plannedLat: 47.6 + Double(n) * 0.001,
                plannedLon: -122.3 + Double(n) * 0.001,
                visited: n <= 3)
        }
    }

    static func plots() -> [Plot] {
        // Three closed plots + one open (should not contribute to stand stats).
        let ids = [
            UUID(uuidString: "DDDDDDDD-0000-0000-0000-000000000001")!,
            UUID(uuidString: "DDDDDDDD-0000-0000-0000-000000000002")!,
            UUID(uuidString: "DDDDDDDD-0000-0000-0000-000000000003")!
        ]
        return ids.enumerated().map { idx, pid in
            Plot(
                id: pid,
                projectId: projectId,
                plannedPlotId: nil,
                plotNumber: idx + 1,
                centerLat: 47.6 + Double(idx) * 0.001,
                centerLon: -122.3 + Double(idx) * 0.001,
                positionSource: .gpsAveraged,
                positionTier: .B,
                gpsNSamples: 40, gpsMedianHAccuracyM: 3.5, gpsSampleStdXyM: 1.2,
                offsetWalkM: nil, slopeDeg: 5, aspectDeg: 180,
                plotAreaAcres: 0.1,
                startedAt: fixedDate,
                closedAt: fixedDate.addingTimeInterval(3600 * Double(idx + 1)),
                closedBy: "demo-cruiser",
                notes: idx == 0 ? "first plot" : "",
                coverPhotoPath: nil,
                panoramaPath: nil)
        }
    }

    static func trees() -> [Tree] {
        let plots = Self.plots()
        var all: [Tree] = []
        for (i, plot) in plots.enumerated() {
            let dbhs: [Float] = [30, 32, 28, 36, 22]
            let heights: [Float?] = [25, 26, nil, 28, nil]
            for (j, dbh) in dbhs.enumerated() {
                let treeId = UUID(uuidString: "EEEEEEEE-00\(i)0-0000-0000-00000000000\(j+1)") ?? UUID()
                all.append(Tree(
                    id: treeId,
                    plotId: plot.id,
                    treeNumber: j + 1,
                    speciesCode: j % 2 == 0 ? "DF" : "WH",
                    status: .live,
                    dbhCm: dbh,
                    dbhMethod: .manualCaliper,
                    dbhSigmaMm: 3.0, dbhRmseMm: 2.5,
                    dbhCoverageDeg: 220, dbhNInliers: 14,
                    dbhConfidence: .green,
                    dbhIsIrregular: false,
                    heightM: heights[j],
                    heightMethod: heights[j] != nil ? .tapeTangent : nil,
                    heightSource: heights[j] != nil ? "measured" : nil,
                    heightSigmaM: heights[j] != nil ? 0.5 : nil,
                    heightDHM: heights[j] != nil ? 15 : nil,
                    heightAlphaTopDeg: heights[j] != nil ? 22 : nil,
                    heightAlphaBaseDeg: heights[j] != nil ? -8 : nil,
                    heightConfidence: heights[j] != nil ? .green : nil,
                    bearingFromCenterDeg: Float(45 * j),
                    distanceFromCenterM: Float(3 + j),
                    boundaryCall: "in",
                    crownClass: "dominant",
                    damageCodes: j == 1 ? ["conk", "fork,big"] : [],
                    isMultistem: false,
                    parentTreeId: nil,
                    notes: j == 0 && i == 0
                        ? "multi-line note\nwith embedded \"quote\", comma."
                        : "",
                    photoPath: nil,
                    rawScanPath: nil,
                    createdAt: fixedDate,
                    updatedAt: fixedDate,
                    deletedAt: (i == 0 && j == 4) ? fixedDate : nil))
            }
        }
        return all
    }

    static func species() -> [SpeciesConfig] {
        [
            SpeciesConfig(code: "DF",
                          commonName: "Douglas-fir",
                          scientificName: "Pseudotsuga menziesii",
                          volumeEquationId: "bruce-df",
                          merchTopDibCm: 12, stumpHeightCm: 30,
                          expectedDbhMinCm: 5, expectedDbhMaxCm: 150,
                          expectedHeightMinM: 3, expectedHeightMaxM: 70),
            SpeciesConfig(code: "WH",
                          commonName: "western hemlock",
                          scientificName: "Tsuga heterophylla",
                          volumeEquationId: "chambers-foltz",
                          merchTopDibCm: 10, stumpHeightCm: 30,
                          expectedDbhMinCm: 5, expectedDbhMaxCm: 120,
                          expectedHeightMinM: 3, expectedHeightMaxM: 60)
        ]
    }

    static func volumeEquations() -> [Models.VolumeEquation] {
        [
            Models.VolumeEquation(
                id: "bruce-df", form: "bruce",
                coefficients: ["b0": -2.725, "b1": 1.8219, "b2": 1.0757],
                unitsIn: "cm, m", unitsOut: "m3",
                sourceCitation: "Bruce (1950)"),
            Models.VolumeEquation(
                id: "chambers-foltz", form: "chambers_foltz",
                coefficients: ["b0": -2.9, "b1": 1.85, "b2": 1.1],
                unitsIn: "cm, m", unitsOut: "m3",
                sourceCitation: "Chambers & Foltz (1980)")
        ]
    }

    static func hdFits() -> [HeightDiameterFit] {
        [
            HeightDiameterFit(
                id: UUID(),
                projectId: projectId,
                speciesCode: "DF",
                modelForm: "naslund",
                coefficients: ["a": 1.2, "b": 0.25],
                nObs: 10, rmse: 1.3,
                updatedAt: fixedDate)
        ]
    }

    /// Stub implementation of ExportDataSource backed by the fixtures.
    final class StubDataSource: ExportDataSource {
        let cachedProject = ExportFixtures.project()
        let cachedDesign  = ExportFixtures.design()
        let cachedStrata  = ExportFixtures.strata()
        let cachedPlanned = ExportFixtures.plannedPlots()
        let cachedPlots   = ExportFixtures.plots()
        let cachedTrees   = ExportFixtures.trees()
        let cachedSpecies = ExportFixtures.species()
        let cachedVolume  = ExportFixtures.volumeEquations()
        let cachedHD      = ExportFixtures.hdFits()

        func project() throws -> Project { cachedProject }
        func cruiseDesign(forProjectId: UUID) throws -> CruiseDesign { cachedDesign }
        func strata(forProjectId: UUID) throws -> [Stratum] { cachedStrata }
        func plannedPlots(forProjectId: UUID) throws -> [PlannedPlot] { cachedPlanned }
        func plots(forProjectId: UUID) throws -> [Plot] { cachedPlots }
        func trees(forPlotId: UUID) throws -> [Tree] {
            cachedTrees.filter { $0.plotId == forPlotId }
        }
        func species() throws -> [SpeciesConfig] { cachedSpecies }
        func volumeEquations() throws -> [Models.VolumeEquation] { cachedVolume }
        func hdFits(forProjectId: UUID) throws -> [HeightDiameterFit] { cachedHD }
    }
}
