// Phase 5 §9.2 integration test: program-build a project, add trees through
// the repo layer the same way the UI does, close every plot, trigger the H–D
// rolling update, and then verify stand-level statistics.
//
// The scenario is deterministic — fixed DBH/height distributions across three
// plots so the expected TPA/BA/gross V can be computed by hand (see inline
// comments on each assertion).

import XCTest
@testable import Persistence
@testable import Models
@testable import Common
@testable import InventoryEngine

final class FullTallyLoopIntegrationTests: XCTestCase {

    // MARK: - Fixture

    private struct World {
        let stack: CoreDataStack
        let projectRepo: any ProjectRepository
        let cruiseDesignRepo: any CruiseDesignRepository
        let speciesRepo: any SpeciesConfigRepository
        let volRepo: any VolumeEquationRepository
        let plotRepo: any PlotRepository
        let treeRepo: any TreeRepository
        let hdFitRepo: any HeightDiameterFitRepository
    }

    private func makeWorld() throws -> World {
        let model = try TestModelLoader.loadTimberCruisingModel()
        let stack = try CoreDataStack(configuration: .inMemory, model: model)
        return World(
            stack: stack,
            projectRepo: CoreDataProjectRepository(stack: stack),
            cruiseDesignRepo: CoreDataCruiseDesignRepository(stack: stack),
            speciesRepo: CoreDataSpeciesConfigRepository(stack: stack),
            volRepo: CoreDataVolumeEquationRepository(stack: stack),
            plotRepo: CoreDataPlotRepository(stack: stack),
            treeRepo: CoreDataTreeRepository(stack: stack),
            hdFitRepo: CoreDataHeightDiameterFitRepository(stack: stack))
    }

    // MARK: - Test

    func testProgramBuild_ThreePlots_CloseAndStandStats() throws {
        let w = try makeWorld()
        let now = Date(timeIntervalSince1970: 1_700_000_000)

        // ---------- Project + design ----------
        let project = try w.projectRepo.create(
            Project(
                id: UUID(), name: "IntTest", description: "",
                owner: "test",
                createdAt: now, updatedAt: now,
                units: .metric, breastHeightConvention: .uphill,
                slopeCorrection: false,
                lidarBiasMm: 0, depthNoiseMm: 0,
                dbhCorrectionAlpha: 0, dbhCorrectionBeta: 1,
                vioDriftFraction: 0.02))
        let design = try w.cruiseDesignRepo.create(
            CruiseDesign(
                id: UUID(), projectId: project.id,
                plotType: .fixedArea,
                plotAreaAcres: 0.1,
                baf: nil,
                samplingScheme: .systematicGrid,
                gridSpacingMeters: 50,
                heightSubsampleRule: .everyKth(k: 3)))

        // ---------- Species + volume equation ----------
        // Minimal linear volume for determinism: V = 1e-4 * D² * H (m³).
        let volId = "lin-unit"
        _ = try w.volRepo.create(
            Models.VolumeEquation(
                id: volId,
                form: "test_linear",
                coefficients: [:],
                unitsIn: "cm, m",
                unitsOut: "m3",
                sourceCitation: "test"))
        _ = try w.speciesRepo.create(
            SpeciesConfig(
                code: "DF",
                commonName: "Douglas-fir",
                scientificName: "Pseudotsuga menziesii",
                volumeEquationId: volId,
                merchTopDibCm: 12,
                stumpHeightCm: 30,
                expectedDbhMinCm: 5,
                expectedDbhMaxCm: 150,
                expectedHeightMinM: 3,
                expectedHeightMaxM: 70))

        // ---------- 3 plots, 10 trees each at fixed DBH/H ----------
        // Plot 1: 10 × D=30cm, H=25m
        // Plot 2: 10 × D=40cm, H=30m
        // Plot 3: 10 × D=20cm, H=18m
        // Fixed area 0.1 ac ⇒ EF = 10 (constant for all plots).
        let scenarios: [(num: Int, dbh: Float, height: Float)] = [
            (1, 30, 25),
            (2, 40, 30),
            (3, 20, 18)
        ]
        var plotIds: [UUID] = []
        for s in scenarios {
            let p = try w.plotRepo.create(
                Plot(
                    id: UUID(), projectId: project.id,
                    plannedPlotId: nil, plotNumber: s.num,
                    centerLat: 0, centerLon: 0,
                    positionSource: .manual, positionTier: .D,
                    gpsNSamples: 0, gpsMedianHAccuracyM: 0,
                    gpsSampleStdXyM: 0, offsetWalkM: nil,
                    slopeDeg: 0, aspectDeg: 0,
                    plotAreaAcres: 0.1,
                    startedAt: now, closedAt: nil, closedBy: nil,
                    notes: "", coverPhotoPath: nil, panoramaPath: nil))
            plotIds.append(p.id)
            for i in 1...10 {
                _ = try w.treeRepo.create(
                    Tree(
                        id: UUID(), plotId: p.id,
                        treeNumber: i, speciesCode: "DF", status: .live,
                        dbhCm: s.dbh,
                        dbhMethod: .manualCaliper, dbhSigmaMm: nil,
                        dbhRmseMm: nil, dbhCoverageDeg: nil,
                        dbhNInliers: nil, dbhConfidence: .green,
                        dbhIsIrregular: false,
                        heightM: s.height, heightMethod: .manualEntry,
                        heightSource: "measured",
                        heightSigmaM: nil, heightDHM: nil,
                        heightAlphaTopDeg: nil, heightAlphaBaseDeg: nil,
                        heightConfidence: .green,
                        bearingFromCenterDeg: nil, distanceFromCenterM: nil,
                        boundaryCall: nil, crownClass: nil,
                        damageCodes: [], isMultistem: false, parentTreeId: nil,
                        notes: "", photoPath: nil, rawScanPath: nil,
                        createdAt: now, updatedAt: now, deletedAt: nil))
            }
        }

        // Add a soft-deleted tree to plot 1 to confirm it's excluded everywhere.
        let deleted = try w.treeRepo.create(
            Tree(
                id: UUID(), plotId: plotIds[0],
                treeNumber: 99, speciesCode: "DF", status: .live,
                dbhCm: 500,                // wildly out of range
                dbhMethod: .manualCaliper, dbhSigmaMm: nil,
                dbhRmseMm: nil, dbhCoverageDeg: nil, dbhNInliers: nil,
                dbhConfidence: .red, dbhIsIrregular: false,
                heightM: 80, heightMethod: .manualEntry,
                heightSource: "measured",
                heightSigmaM: nil, heightDHM: nil,
                heightAlphaTopDeg: nil, heightAlphaBaseDeg: nil,
                heightConfidence: .red,
                bearingFromCenterDeg: nil, distanceFromCenterM: nil,
                boundaryCall: nil, crownClass: nil,
                damageCodes: [], isMultistem: false, parentTreeId: nil,
                notes: "", photoPath: nil, rawScanPath: nil,
                createdAt: now, updatedAt: now, deletedAt: nil))
        try w.treeRepo.delete(id: deleted.id)

        // ---------- Close every plot ----------
        for pid in plotIds {
            guard var p = try w.plotRepo.read(id: pid) else {
                XCTFail("Plot missing"); return
            }
            p.closedAt = now
            p.closedBy = "test"
            _ = try w.plotRepo.update(p)
        }

        // ---------- Trigger H-D rolling update ----------
        let species = try w.speciesRepo.list()
        let speciesByCode = Dictionary(uniqueKeysWithValues: species.map { ($0.code, $0) })
        var allObs: [(Float, Float)] = []
        for pid in plotIds {
            for t in try w.treeRepo.listByPlot(pid, includeDeleted: false)
                where t.heightSource == "measured" {
                if let h = t.heightM { allObs.append((t.dbhCm, h)) }
            }
        }
        XCTAssertEqual(allObs.count, 30)
        _ = try w.hdFitRepo.recomputeForSpecies(
            projectId: project.id, speciesCode: "DF",
            observations: allObs.map { (dbhCm: $0.0, heightM: $0.1) },
            minN: 8, now: now)
        let stored = try w.hdFitRepo.forProjectAndSpecies(
            projectId: project.id, speciesCode: "DF")
        XCTAssertNotNil(stored)
        XCTAssertEqual(stored?.nObs, 30)

        // ---------- Compute per-plot stats ----------
        var volEqs: [String: any InventoryEngine.VolumeEquation] = [:]
        volEqs["DF"] = LinearVolume()  // injected below

        var perPlotStats: [PlotStats] = []
        for pid in plotIds {
            guard let p = try w.plotRepo.read(id: pid) else { continue }
            let trees = try w.treeRepo.listByPlot(pid, includeDeleted: false)
            let stats = PlotStatsCalculator.compute(
                plot: p, cruiseDesign: design, trees: trees,
                species: speciesByCode,
                volumeEquations: volEqs,
                hdFits: [:])
            perPlotStats.append(stats)
        }

        // Each plot: 10 live trees, fixed EF=10 ⇒ TPA = 100/ac across all plots.
        XCTAssertEqual(perPlotStats[0].liveTreeCount, 10)
        XCTAssertEqual(perPlotStats[0].tpa, 100, accuracy: 1e-3)
        XCTAssertEqual(perPlotStats[1].tpa, 100, accuracy: 1e-3)
        XCTAssertEqual(perPlotStats[2].tpa, 100, accuracy: 1e-3)

        // Soft-deleted tree must be excluded on plot 1.
        XCTAssertEqual(perPlotStats[0].liveTreeCount, 10,
                       "Soft-deleted tree leaked into stats")

        // Plot 1 BA/ac: BA per tree = π·(0.30)²/4 = 0.07069 m², × EF=10 × 10 trees
        // ⇒ 7.069 m²/ac.
        XCTAssertEqual(perPlotStats[0].baPerAcreM2, 7.069, accuracy: 0.005)
        // Plot 1 V/ac: V per tree = 1e-4 · 30² · 25 = 2.25 m³, × EF=10 × 10
        // ⇒ 225 m³/ac.
        XCTAssertEqual(perPlotStats[0].grossVolumePerAcreM3, 225, accuracy: 0.1)

        // ---------- Stratified (unstratified) stand stats ----------
        let tpaRows = perPlotStats.map {
            ("__unstratified__", Double($0.tpa))
        }
        let baRows = perPlotStats.map {
            ("__unstratified__", Double($0.baPerAcreM2))
        }
        let volRows = perPlotStats.map {
            ("__unstratified__", Double($0.grossVolumePerAcreM3))
        }
        let tpaStand = StandStatsCalculator.compute(
            plotValues: tpaRows, stratumAreasAcres: [:])
        let baStand = StandStatsCalculator.compute(
            plotValues: baRows, stratumAreasAcres: [:])
        let volStand = StandStatsCalculator.compute(
            plotValues: volRows, stratumAreasAcres: [:])

        // TPA is identical across plots ⇒ mean = 100, SE = 0.
        XCTAssertEqual(tpaStand.mean, 100, accuracy: 1e-6)
        XCTAssertEqual(tpaStand.seMean, 0, accuracy: 1e-6)

        // BA per-plot: 7.069, 12.566, 3.142 (m²/ac). Mean = 7.592.
        XCTAssertEqual(baStand.mean, 7.592, accuracy: 0.01)
        XCTAssertGreaterThan(baStand.seMean, 0)
        XCTAssertGreaterThan(baStand.ci95HalfWidth, 0)

        // Gross V per-plot: 225, 480, 72 (m³/ac). Mean = 259.
        XCTAssertEqual(volStand.mean, 259, accuracy: 0.5)
        XCTAssertEqual(volStand.nPlots, 3)
    }

    func testMultistemChildrenContributeToBasalArea() throws {
        let w = try makeWorld()
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        let project = try w.projectRepo.create(
            Project(
                id: UUID(), name: "MultiStem", description: "",
                owner: "test",
                createdAt: now, updatedAt: now,
                units: .metric, breastHeightConvention: .uphill,
                slopeCorrection: false,
                lidarBiasMm: 0, depthNoiseMm: 0,
                dbhCorrectionAlpha: 0, dbhCorrectionBeta: 1,
                vioDriftFraction: 0.02))
        let design = try w.cruiseDesignRepo.create(
            CruiseDesign(
                id: UUID(), projectId: project.id,
                plotType: .fixedArea, plotAreaAcres: 0.1,
                baf: nil, samplingScheme: .systematicGrid,
                gridSpacingMeters: 50))
        _ = try w.volRepo.create(
            Models.VolumeEquation(
                id: "lin", form: "test_linear", coefficients: [:],
                unitsIn: "cm, m", unitsOut: "m3", sourceCitation: "test"))
        _ = try w.speciesRepo.create(
            SpeciesConfig(
                code: "DF", commonName: "DF", scientificName: "",
                volumeEquationId: "lin",
                merchTopDibCm: 12, stumpHeightCm: 30,
                expectedDbhMinCm: 5, expectedDbhMaxCm: 150,
                expectedHeightMinM: 3, expectedHeightMaxM: 70))

        let p = try w.plotRepo.create(
            Plot(
                id: UUID(), projectId: project.id,
                plannedPlotId: nil, plotNumber: 1,
                centerLat: 0, centerLon: 0,
                positionSource: .manual, positionTier: .D,
                gpsNSamples: 0, gpsMedianHAccuracyM: 0, gpsSampleStdXyM: 0,
                offsetWalkM: nil, slopeDeg: 0, aspectDeg: 0,
                plotAreaAcres: 0.1,
                startedAt: now, closedAt: nil, closedBy: nil,
                notes: "", coverPhotoPath: nil, panoramaPath: nil))
        // Main stem + 2 multistem children: DBHs 30, 20, 15 cm.
        let mainId = UUID()
        _ = try w.treeRepo.create(
            Tree(
                id: mainId, plotId: p.id, treeNumber: 1,
                speciesCode: "DF", status: .live,
                dbhCm: 30, dbhMethod: .manualCaliper,
                dbhSigmaMm: nil, dbhRmseMm: nil, dbhCoverageDeg: nil,
                dbhNInliers: nil, dbhConfidence: .green,
                dbhIsIrregular: false,
                heightM: 25, heightMethod: .manualEntry,
                heightSource: "measured",
                heightSigmaM: nil, heightDHM: nil,
                heightAlphaTopDeg: nil, heightAlphaBaseDeg: nil,
                heightConfidence: .green,
                bearingFromCenterDeg: nil, distanceFromCenterM: nil,
                boundaryCall: nil, crownClass: nil,
                damageCodes: [], isMultistem: false, parentTreeId: nil,
                notes: "", photoPath: nil, rawScanPath: nil,
                createdAt: now, updatedAt: now, deletedAt: nil))
        for (i, dbh) in [(2, Float(20)), (3, Float(15))] {
            _ = try w.treeRepo.create(
                Tree(
                    id: UUID(), plotId: p.id, treeNumber: i,
                    speciesCode: "DF", status: .live,
                    dbhCm: dbh, dbhMethod: .manualCaliper,
                    dbhSigmaMm: nil, dbhRmseMm: nil, dbhCoverageDeg: nil,
                    dbhNInliers: nil, dbhConfidence: .green,
                    dbhIsIrregular: false,
                    heightM: 22, heightMethod: .manualEntry,
                    heightSource: "measured",
                    heightSigmaM: nil, heightDHM: nil,
                    heightAlphaTopDeg: nil, heightAlphaBaseDeg: nil,
                    heightConfidence: .green,
                    bearingFromCenterDeg: nil, distanceFromCenterM: nil,
                    boundaryCall: nil, crownClass: nil,
                    damageCodes: [], isMultistem: true, parentTreeId: mainId,
                    notes: "", photoPath: nil, rawScanPath: nil,
                    createdAt: now, updatedAt: now, deletedAt: nil))
        }

        let trees = try w.treeRepo.listByPlot(p.id, includeDeleted: false)
        let speciesByCode = Dictionary(
            uniqueKeysWithValues: try w.speciesRepo.list().map { ($0.code, $0) })
        var eqs: [String: any InventoryEngine.VolumeEquation] = [:]
        eqs["DF"] = LinearVolume()

        let stats = PlotStatsCalculator.compute(
            plot: p, cruiseDesign: design,
            trees: trees, species: speciesByCode,
            volumeEquations: eqs, hdFits: [:])

        // Sum of basal areas:
        //   π/4 · [(0.30)² + (0.20)² + (0.15)²]
        //   = π/4 · (0.09 + 0.04 + 0.0225)
        //   = π/4 · 0.1525 = 0.11977 m²
        // × EF=10 ⇒ 1.1977 m²/ac.
        XCTAssertEqual(stats.liveTreeCount, 3)
        XCTAssertEqual(stats.baPerAcreM2, 1.1977, accuracy: 0.005,
                       "Multistem children must contribute to BA")
    }
}

// Injected for deterministic volume math; mirrors the test species' equation.
private struct LinearVolume: InventoryEngine.VolumeEquation {
    func totalVolumeM3(dbhCm: Float, heightM: Float) -> Float {
        1e-4 * dbhCm * dbhCm * heightM
    }
    func merchantableVolumeM3(
        dbhCm: Float, heightM: Float,
        topDibCm: Float, stumpHeightCm: Float
    ) -> Float {
        // 80% of gross for simplicity.
        0.8 * totalVolumeM3(dbhCm: dbhCm, heightM: heightM)
    }
}
