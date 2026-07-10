// Phase 7 — matcher that maps a spoken phrase to a species code.
// Unit tests only — no Speech framework dependency.

import XCTest
@testable import Common

final class SpeciesVoiceMatcherTests: XCTestCase {

    private let candidates: [(code: String,
                              commonName: String,
                              scientificName: String)] = [
        ("DF", "Douglas-fir",     "Pseudotsuga menziesii"),
        ("WH", "western hemlock", "Tsuga heterophylla"),
        ("WRC", "western redcedar","Thuja plicata"),
        ("RA", "red alder",       "Alnus rubra")
    ]

    func testExactCodeWins() {
        XCTAssertEqual(SpeciesVoiceMatcher.bestMatch(
            for: "DF", candidates: candidates), "DF")
    }

    func testCommonNameSubstring() {
        XCTAssertEqual(SpeciesVoiceMatcher.bestMatch(
            for: "red alder", candidates: candidates), "RA")
    }

    func testScientificNameSubstring() {
        XCTAssertEqual(SpeciesVoiceMatcher.bestMatch(
            for: "pseudotsuga", candidates: candidates), "DF")
    }

    func testPartialMatchOnMultiwordName() {
        XCTAssertEqual(SpeciesVoiceMatcher.bestMatch(
            for: "western hemlock tree",
            candidates: candidates), "WH")
    }

    func testNoMatchReturnsNil() {
        XCTAssertNil(SpeciesVoiceMatcher.bestMatch(
            for: "mahogany", candidates: candidates))
    }

    func testEmptyInputReturnsNil() {
        XCTAssertNil(SpeciesVoiceMatcher.bestMatch(
            for: "   ", candidates: candidates))
    }

    func testShortTokensIgnored() {
        // "fir" — only 3 chars, still counts; "of" — 2 chars, ignored.
        // But we avoid matching a random two-letter prefix against
        // "red alder" via the substring check.
        let code = SpeciesVoiceMatcher.bestMatch(
            for: "fir of", candidates: candidates)
        XCTAssertEqual(code, "DF") // matches "fir" in Douglas-fir
    }
}
