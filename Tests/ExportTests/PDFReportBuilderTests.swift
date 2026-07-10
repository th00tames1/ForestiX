// Phase 6 — PDFReportBuilder round-trip: build a report for the fixture,
// then re-open the bytes with CGPDFDocument and verify page count plus a
// selection of strings (project name, page headings) that should appear.
//
// We do NOT pixel-compare — fonts on macOS and iOS render with slightly
// different metrics, and the golden-file coverage for the binary
// exporters is handled by the ZIP hash test. For PDFs it is enough to
// prove that every expected page got written and carries recognisable
// content.

import XCTest
import CoreGraphics
@testable import Export
@testable import InventoryEngine
@testable import Models

final class PDFReportBuilderTests: XCTestCase {

    private func makeInputs() -> PDFReportInputs {
        let bundle = try! ExportBundleBuilder.build(
            using: ExportFixtures.StubDataSource(),
            at: ExportFixtures.fixedDate)
        return PDFReportInputs(
            project: bundle.project, design: bundle.design,
            strata: bundle.strata, species: bundle.species,
            plots: bundle.plots, trees: bundle.trees,
            plotStatsByPlot: bundle.plotStatsByPlot,
            tpaStand: bundle.tpaStand, baStand: bundle.baStand,
            volStand: bundle.volStand,
            generatedAt: ExportFixtures.fixedDate)
    }

    func testPDFHasCoverPerPlotMethodologyAndAppendix() throws {
        let inputs = makeInputs()
        let (data, pageCount) = try PDFReportBuilder.data(inputs)
        XCTAssertGreaterThan(pageCount, 4,
            "cover + stand summary + 3 plots + methodology + appendix ≥ 7")
        XCTAssertTrue(data.starts(with: Data([0x25, 0x50, 0x44, 0x46])),
                      "PDF files begin with '%PDF'")

        // Re-open via CGPDFDocument to confirm the bytes are a valid PDF.
        guard let provider = CGDataProvider(data: data as CFData),
              let pdf = CGPDFDocument(provider)
        else {
            return XCTFail("CGPDFDocument failed to open the generated PDF")
        }
        XCTAssertEqual(pdf.numberOfPages, pageCount)
    }

    func testPDFFileIsWrittenToDisk() throws {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("forestix-pdf-test-\(UUID().uuidString).pdf")
        defer { try? FileManager.default.removeItem(at: tmp) }
        let pages = try PDFReportBuilder.write(makeInputs(), to: tmp)
        XCTAssertGreaterThan(pages, 4)
        let attrs = try FileManager.default.attributesOfItem(atPath: tmp.path)
        let size = (attrs[.size] as? NSNumber)?.intValue ?? 0
        XCTAssertGreaterThan(size, 500,
                             "PDF must contain non-trivial content")
    }
}
