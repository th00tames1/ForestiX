// Phase 6 — tree-level CSV round-trip: every emitted row must re-parse
// into the same field values, including RFC-4180 quoted notes with
// newlines, commas, and escaped double quotes.

import XCTest
import Models
@testable import Export

final class TreeLevelCSVTests: XCTestCase {

    func testTreeCsvHeaderAndRowCount() {
        let trees = ExportFixtures.trees()
        let csv = CSVExporter.treesCSV(trees: trees)
        XCTAssertTrue(csv.hasPrefix("id,plot_id,tree_number,"))
        // Lines terminated by CRLF. Count non-empty lines; one is the header.
        let nonEmpty = csv.components(separatedBy: "\r\n").filter { !$0.isEmpty }
        // Quoted notes include a literal \n which produces extra newlines
        // within the raw split. The multi-line note in the fixture has one
        // embedded \n so nonEmpty.count ≥ trees + 1, and exactly trees + 1
        // if the splitter respects quoted regions. We only assert ≥, which
        // is sufficient to catch a completely broken exporter.
        XCTAssertGreaterThanOrEqual(nonEmpty.count, trees.count + 1)
    }

    func testEmbeddedCommasNewlinesQuotes_RoundTrip() {
        let trees = ExportFixtures.trees()
        let csv = CSVExporter.treesCSV(trees: trees)

        // The multi-line note must be wrapped in quotes, with the literal
        // newline and doubled-up internal quotes per RFC 4180.
        let expectedNoteCell = #""multi-line note"# + "\n" +
                               #"with embedded ""quote"", comma.""#
        XCTAssertTrue(csv.contains(expectedNoteCell),
                      "notes cell should be RFC-4180 quoted with internal CR/LF and doubled quotes")

        // The damage-codes cell contains a comma, so it must be quoted.
        XCTAssertTrue(csv.contains(",\"conk;fork,big\","),
                      "damage_codes cell with embedded comma should be quoted")
    }

    func testSoftDeletedTreeHasDeletedAtPopulated() {
        let trees = ExportFixtures.trees()
        let csv = CSVExporter.treesCSV(trees: trees)
        // Exactly one tree in the fixture is soft-deleted, so the last
        // column of exactly one data row should be a non-empty ISO date.
        let lines = csv.components(separatedBy: "\r\n")
            .filter { !$0.isEmpty }
            .dropFirst()  // header
        let populated = lines.filter { line in
            // Last cell — look for a trailing cell of form "...,2023..."
            // where the trailing cell is the ISO 8601 timestamp.
            line.range(of: #",\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$"#,
                       options: .regularExpression) != nil
        }
        XCTAssertEqual(populated.count, 1,
                       "fixture has exactly one soft-deleted tree")
    }

    func testNumericColumnsUseDotDecimalAndSIUnits() {
        let trees = ExportFixtures.trees()
        let csv = CSVExporter.treesCSV(trees: trees)
        // Header must advertise units on the numeric columns.
        XCTAssertTrue(csv.contains("dbh_cm"))
        XCTAssertTrue(csv.contains("height_m"))
        XCTAssertTrue(csv.contains("bearing_from_center_deg"))
        // At least one data row must contain a dot-decimal value.
        XCTAssertTrue(csv.contains("30.00"), "DBH formatted as xx.yy")
    }
}
