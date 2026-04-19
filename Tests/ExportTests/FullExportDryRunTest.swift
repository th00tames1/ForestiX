// Phase 6 acceptance: produce every artefact for the Cascade Demo
// fixture in a scratch directory and report the file list + sizes.
// Runs as part of the regular test suite so CI catches drift, and
// leaves its output behind so a human can inspect sample artefacts
// after a `swift test --filter FullExportDryRunTest` on their dev box.

import XCTest
@testable import Export

final class FullExportDryRunTest: XCTestCase {

    func testProducesAllExpectedArtefacts() throws {
        let bundle = try ExportBundleBuilder.build(
            using: ExportFixtures.StubDataSource(),
            at: ExportFixtures.fixedDate)
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("forestix-phase6-dryrun-\(UUID().uuidString)")
        try FileManager.default.createDirectory(
            at: tmp, withIntermediateDirectories: true)
        let result = try FullCruiseExporter.write(bundle: bundle, into: tmp)
        XCTAssertEqual(result.folder.deletingLastPathComponent().lastPathComponent,
                       "Exports")

        // Every artefact kind should be present.
        let kinds = Set(result.artefacts.map { $0.kind.rawValue })
        for required: ExportArtefact.Kind in [
            .csvTrees, .csvPlots, .csvStandSummary,
            .csvStrata, .csvPlanned,
            .geojsonCruise, .geojsonPlan,
            .shapefilePlots, .shapefilePlanned, .shapefileStrata,
            .pdfReport
        ] {
            XCTAssertTrue(kinds.contains(required.rawValue),
                          "missing artefact: \(required.rawValue)")
        }

        // Every artefact must exist on disk and be non-empty.
        let fm = FileManager.default
        for art in result.artefacts {
            XCTAssertTrue(fm.fileExists(atPath: art.url.path),
                          "\(art.url.lastPathComponent) missing")
            let size = (try fm.attributesOfItem(atPath: art.url.path)[.size]
                        as? NSNumber)?.intValue ?? 0
            XCTAssertGreaterThan(size, 0, "\(art.url.lastPathComponent) empty")
        }

        print("Phase 6 dry-run folder:", result.folder.path)
        for art in result.artefacts {
            let size = (try fm.attributesOfItem(atPath: art.url.path)[.size]
                        as? NSNumber)?.intValue ?? 0
            print("  \(art.url.lastPathComponent) — \(size) bytes")
        }
    }
}
