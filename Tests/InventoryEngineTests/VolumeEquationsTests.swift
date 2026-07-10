// Spec §7.7 Volume Engine — form tests.
//
// NOTE ON COEFFICIENT PROVENANCE:
//   The Bruce (Douglas-fir) and Chambers-Foltz (Western Hemlock) coefficients
//   shipped in `Resources/VolumeEquationsPNW.json` are placeholders pending
//   verification against primary sources. These tests therefore verify the
//   *form* (log-linear behavior, units, monotonicity, etc.) rather than
//   specific published numerical outputs. The Done-criteria check against
//   published tables is flagged as an unresolved open question for the
//   Phase 0 report.

import XCTest
import Models
@testable import InventoryEngine

final class VolumeEquationsTests: XCTestCase {

    // MARK: - Schumacher-Hall (generic SI form)

    func testSchumacherHallKnownForm() {
        // V = 1e-4 · D^2 · H = 1e-4 · 1000 · 30 = 3.0 m³ at D=100cm→ but we use D²·H
        // Use a = 1e-4, b = 2, c = 1 ⇒ V(30 cm, 20 m) = 1e-4 · 900 · 20 = 1.8 m³.
        let eq = SchumacherHall(coefficients: ["a": 1e-4, "b": 2, "c": 1])
        XCTAssertEqual(eq.totalVolumeM3(dbhCm: 30, heightM: 20), 1.8, accuracy: 1e-4)
    }

    func testSchumacherHallZeroGuard() {
        let eq = SchumacherHall(coefficients: ["a": 1, "b": 1, "c": 1])
        XCTAssertEqual(eq.totalVolumeM3(dbhCm: 0, heightM: 10), 0)
        XCTAssertEqual(eq.totalVolumeM3(dbhCm: 10, heightM: 0), 0)
    }

    func testMerchantableUsesFraction() {
        let eq = SchumacherHall(coefficients: ["a": 1e-4, "b": 2, "c": 1,
                                               "merchFraction": 0.8])
        let total = eq.totalVolumeM3(dbhCm: 30, heightM: 20)
        let merch = eq.merchantableVolumeM3(dbhCm: 30, heightM: 20,
                                            topDibCm: 10, stumpHeightCm: 30)
        XCTAssertEqual(merch, total * 0.8, accuracy: 1e-5)
    }

    // MARK: - Bruce Douglas-Fir (log-linear imperial)

    func testBruceDFReturnsPositiveMonotonic() {
        // Use placeholder-but-plausible coefficients; verify monotonicity
        // (form check only, not absolute values).
        let eq = BruceDouglasFir(coefficients: ["b0": -2.6, "b1": 1.8, "b2": 1.1])
        let v1 = eq.totalVolumeM3(dbhCm: 30, heightM: 20)
        let v2 = eq.totalVolumeM3(dbhCm: 40, heightM: 25)
        XCTAssertGreaterThan(v1, 0)
        XCTAssertGreaterThan(v2, v1, "larger tree must produce greater volume")
    }

    func testBruceDFLogLinearIdentity() {
        // For b0=0, b1=1, b2=1, log10(V_cf) = log10(D_in) + log10(H_ft),
        // so V_cf = D_in · H_ft. Verify unit conversion out to m³.
        let eq = BruceDouglasFir(coefficients: ["b0": 0, "b1": 1, "b2": 1])
        let dCm: Float = 30
        let hM: Float = 20
        let dIn = dCm / 2.54
        let hFt = hM / 0.3048
        let expectedFt3 = dIn * hFt
        let expectedM3 = expectedFt3 * 0.0283168466
        XCTAssertEqual(eq.totalVolumeM3(dbhCm: dCm, heightM: hM),
                       expectedM3, accuracy: 1e-3)
    }

    // MARK: - Chambers-Foltz Hemlock (same form; just verify wiring)

    func testChambersFoltzWHLogLinearIdentity() {
        let eq = ChambersFoltzHemlock(coefficients: ["b0": 0, "b1": 1, "b2": 1])
        let dCm: Float = 30
        let hM: Float = 20
        let dIn = dCm / 2.54
        let hFt = hM / 0.3048
        let expectedM3 = (dIn * hFt) * 0.0283168466
        XCTAssertEqual(eq.totalVolumeM3(dbhCm: dCm, heightM: hM),
                       expectedM3, accuracy: 1e-3)
    }

    // MARK: - TableLookup (bilinear interpolation)

    func testTableLookupExactGridPoint() {
        // 2x2 grid, (20, 30 cm) × (15, 25 m). Interior values picked freely.
        let coeffs: [String: Float] = [
            "dbh_0": 20, "dbh_1": 30,
            "h_0": 15,   "h_1": 25,
            "v_0_0": 0.5, "v_0_1": 1.0,
            "v_1_0": 1.0, "v_1_1": 2.0
        ]
        let eq = TableLookup(coefficients: coeffs)
        XCTAssertEqual(eq.totalVolumeM3(dbhCm: 20, heightM: 15), 0.5, accuracy: 1e-5)
        XCTAssertEqual(eq.totalVolumeM3(dbhCm: 30, heightM: 25), 2.0, accuracy: 1e-5)
    }

    func testTableLookupBilinearInterior() {
        let coeffs: [String: Float] = [
            "dbh_0": 20, "dbh_1": 30,
            "h_0": 15,   "h_1": 25,
            "v_0_0": 0.5, "v_0_1": 1.0,
            "v_1_0": 1.0, "v_1_1": 2.0
        ]
        let eq = TableLookup(coefficients: coeffs)
        // midpoint (25 cm, 20 m): bilinear of corners = (0.5+1.0+1.0+2.0)/4 = 1.125.
        XCTAssertEqual(eq.totalVolumeM3(dbhCm: 25, heightM: 20), 1.125, accuracy: 1e-5)
    }

    func testTableLookupClampsOutOfRange() {
        let coeffs: [String: Float] = [
            "dbh_0": 20, "dbh_1": 30,
            "h_0": 15,   "h_1": 25,
            "v_0_0": 0.5, "v_0_1": 1.0,
            "v_1_0": 1.0, "v_1_1": 2.0
        ]
        let eq = TableLookup(coefficients: coeffs)
        XCTAssertEqual(eq.totalVolumeM3(dbhCm: 10, heightM: 10), 0.5, accuracy: 1e-5)
        XCTAssertEqual(eq.totalVolumeM3(dbhCm: 99, heightM: 99), 2.0, accuracy: 1e-5)
    }

    // MARK: - Factory

    func testFactoryRecognizesKnownForms() {
        let bruce = Models.VolumeEquation(
            id: "bruce-df",
            form: "bruce",
            coefficients: ["b0": -2.6, "b1": 1.8, "b2": 1.1],
            unitsIn: "cm,m",
            unitsOut: "m3",
            sourceCitation: "test"
        )
        XCTAssertNotNil(VolumeEquationFactory.make(from: bruce))
    }

    func testFactoryReturnsNilForUnknownForm() {
        let junk = Models.VolumeEquation(
            id: "x",
            form: "definitely-not-a-form",
            coefficients: [:],
            unitsIn: "cm,m",
            unitsOut: "m3",
            sourceCitation: "test"
        )
        XCTAssertNil(VolumeEquationFactory.make(from: junk))
    }
}
