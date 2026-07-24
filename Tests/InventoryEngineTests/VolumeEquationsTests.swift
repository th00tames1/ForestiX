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

    // MARK: - Laasasenaho (Finland, verified coefficients)

    // Verified Scots-pine coefficient set (docs/volume-standards-research.md §B
    // / VolumeEquationsMetric.json "laasasenaho-pine-fi").
    private static let pineCoeffs: [String: Float] = [
        "c1": 0.036089, "c2": 2.01395, "c3": 0.99676, "c4": 2.07025, "c5": -1.07209,
        "a1": -5.39417, "a2": 3.48060, "a3": 0.039884
    ]

    func testLaasasenahoPineWorkedValue() {
        // Verified check from §B.1: pine d=20 cm, h=18 m ⇒ ≈ 0.274 m³.
        let eq = Laasasenaho(coefficients: Self.pineCoeffs)
        XCTAssertEqual(eq.totalVolumeM3(dbhCm: 20, heightM: 18),
                       0.274, accuracy: 0.01)
    }

    func testLaasasenahoDOnlyFallback() {
        // With height unusable (h ≤ 1.3 m) the d-only model engages when a1..a3
        // are present, yielding a positive volume.
        let eq = Laasasenaho(coefficients: Self.pineCoeffs)
        XCTAssertGreaterThan(eq.totalVolumeM3(dbhCm: 25, heightM: 0), 0)
        // Without the a-coefficients, no fallback ⇒ 0.
        var noFallback = Self.pineCoeffs
        noFallback["a1"] = nil; noFallback["a2"] = nil; noFallback["a3"] = nil
        XCTAssertEqual(Laasasenaho(coefficients: noFallback)
                        .totalVolumeM3(dbhCm: 25, heightM: 0), 0)
    }

    // MARK: - Formfactor (Germany, generic approximation)

    func testFormfactorMatchesGHF() {
        // V = g·h·f, g = π·(d/100)²/4. d=100 cm ⇒ g = π/4 ≈ 0.785398 m².
        // h=10, f=0.5 ⇒ V ≈ 3.92699 m³.
        let eq = Formfactor(formFactor: 0.5)
        XCTAssertEqual(eq.totalVolumeM3(dbhCm: 100, heightM: 10),
                       3.92699, accuracy: 1e-3)
    }

    // MARK: - Korea (scaffold — pending coefficients)

    func testKoreaNIFoSIsPendingAndYieldsNoVolume() {
        XCTAssertTrue(KoreaNIFoS.coefficientsPending)
        let eq = KoreaNIFoS()
        XCTAssertEqual(eq.totalVolumeM3(dbhCm: 30, heightM: 20), 0)
    }

    // MARK: - Factory recognises the metric forms

    func testFactoryRecognizesMetricForms() {
        let laasasenaho = Models.VolumeEquation(
            id: "laasasenaho-pine-fi", form: "laasasenaho",
            coefficients: Self.pineCoeffs,
            unitsIn: "cm,m", unitsOut: "m3", sourceCitation: "test")
        XCTAssertNotNil(VolumeEquationFactory.make(from: laasasenaho))

        let formfactor = Models.VolumeEquation(
            id: "formfactor-generic-de", form: "formfactor",
            coefficients: ["f": 0.5],
            unitsIn: "cm,m", unitsOut: "m3", sourceCitation: "test")
        XCTAssertNotNil(VolumeEquationFactory.make(from: formfactor))
    }

    // MARK: - Metric species → equation binding (integration)

    /// The check the unit tests above could not make: the metric equations are
    /// only useful if a seeded SpeciesConfig actually binds a scan-time species
    /// code to them. Before this seed wiring the equations existed but no
    /// species referenced them, so every metric tree resolved to *no* equation
    /// and stand volume came out 0. This walks the real bundled JSON exactly as
    /// StandSummaryViewModel does: species code → volumeEquationId → equation →
    /// positive m³.
    func testMetricSpeciesAreBoundToComputableEquations() throws {
        let species = try SeedData.bundledMetricSpecies()
        let equations = try SeedData.bundledMetricVolumeEquations()
        let eqById = Dictionary(uniqueKeysWithValues: equations.map { ($0.id, $0) })

        XCTAssertFalse(species.isEmpty, "metric species seed must not be empty")

        for sp in species {
            let record = try XCTUnwrap(
                eqById[sp.volumeEquationId],
                "\(sp.code) points at unseeded equation id \(sp.volumeEquationId)")
            let eq = try XCTUnwrap(
                VolumeEquationFactory.make(from: record),
                "factory could not build \(record.form) for \(sp.code)")
            // A realistic 25 cm / 20 m stem must produce positive volume — the
            // 0 the disconnected path used to return would fail here.
            XCTAssertGreaterThan(
                eq.totalVolumeM3(dbhCm: 25, heightM: 20), 0,
                "\(sp.code) resolved but computed 0 m³")
        }

        // Scots pine round-trips to the verified worked value through the seed.
        let pine = try XCTUnwrap(species.first { $0.code == "FI-PISY" })
        let pineEq = try XCTUnwrap(VolumeEquationFactory.make(from: eqById[pine.volumeEquationId]!))
        XCTAssertEqual(pineEq.totalVolumeM3(dbhCm: 20, heightM: 18), 0.274, accuracy: 0.01)

        // Finland + Germany are seeded; Korea is intentionally absent (pending).
        XCTAssertEqual(
            Set(species.map { $0.code }),
            ["FI-PISY", "FI-PIAB", "FI-BEPE", "FI-BEPU",
             "DE-PIAB", "DE-PISY", "DE-FASY", "DE-QURO"])
        XCTAssertFalse(species.contains { $0.code.hasPrefix("KR-") },
                       "Korea must stay pending — no fabricated volume binding")
    }
}
