// Spec §6.2 (SpeciesConfig, VolumeEquation, HeightDiameterFit).
// REQ-PRJ-005, §7.4, §7.7.
//
// Note: the struct `VolumeEquation` defined here is the persisted *record* of
// equation coefficients. The protocol named `VolumeEquation` in
// InventoryEngine/VolumeEquations/ is a different type in a different module
// (see user-approved decision on name collision: split modules, disambiguate
// by module prefix if needed).

import Foundation

// MARK: - §6.2 SpeciesConfig

public struct SpeciesConfig: Identifiable, Codable, Sendable {
    public let code: String                 // e.g., "DF"
    public var id: String { code }
    public var commonName: String
    public var scientificName: String
    public var volumeEquationId: String
    public var merchTopDibCm: Float
    public var stumpHeightCm: Float
    public var expectedDbhMinCm: Float
    public var expectedDbhMaxCm: Float
    public var expectedHeightMinM: Float
    public var expectedHeightMaxM: Float

    public init(
        code: String,
        commonName: String,
        scientificName: String,
        volumeEquationId: String,
        merchTopDibCm: Float,
        stumpHeightCm: Float,
        expectedDbhMinCm: Float,
        expectedDbhMaxCm: Float,
        expectedHeightMinM: Float,
        expectedHeightMaxM: Float
    ) {
        self.code = code
        self.commonName = commonName
        self.scientificName = scientificName
        self.volumeEquationId = volumeEquationId
        self.merchTopDibCm = merchTopDibCm
        self.stumpHeightCm = stumpHeightCm
        self.expectedDbhMinCm = expectedDbhMinCm
        self.expectedDbhMaxCm = expectedDbhMaxCm
        self.expectedHeightMinM = expectedHeightMinM
        self.expectedHeightMaxM = expectedHeightMaxM
    }
}

// MARK: - §6.2 VolumeEquation (record)

public struct VolumeEquation: Identifiable, Codable, Sendable {
    public let id: String
    public var form: String                 // e.g., "bruce", "schumacher_hall"
    public var coefficients: [String: Float]
    public var unitsIn: String              // e.g., "cm, m"
    public var unitsOut: String             // e.g., "m3"
    public var sourceCitation: String

    public init(
        id: String,
        form: String,
        coefficients: [String: Float],
        unitsIn: String,
        unitsOut: String,
        sourceCitation: String
    ) {
        self.id = id
        self.form = form
        self.coefficients = coefficients
        self.unitsIn = unitsIn
        self.unitsOut = unitsOut
        self.sourceCitation = sourceCitation
    }
}

// MARK: - §6.2 HeightDiameterFit

public struct HeightDiameterFit: Identifiable, Codable, Sendable {
    public let id: UUID
    public let projectId: UUID
    public let speciesCode: String
    public var modelForm: String            // "naslund"
    public var coefficients: [String: Float]
    public var nObs: Int
    public var rmse: Float
    public var updatedAt: Date

    public init(
        id: UUID,
        projectId: UUID,
        speciesCode: String,
        modelForm: String,
        coefficients: [String: Float],
        nObs: Int,
        rmse: Float,
        updatedAt: Date
    ) {
        self.id = id
        self.projectId = projectId
        self.speciesCode = speciesCode
        self.modelForm = modelForm
        self.coefficients = coefficients
        self.nObs = nObs
        self.rmse = rmse
        self.updatedAt = updatedAt
    }
}
