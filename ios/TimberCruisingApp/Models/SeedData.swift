// Phase 7.2 hardening — bundled species / volume-equation seed data.
//
// Spec §6.2 says the PNW starter set ships in
// `Resources/SpeciesDefaults.json` and `Resources/VolumeEquationsPNW.json`,
// but the original Phase 0 build never wired them into Core Data, so a
// real cruiser opening the app for the first time would land on
// CruiseDesignScreen with an empty species picker.
//
// This file owns the JSON → typed-model decode. The actual Core Data
// insertion lives in `Persistence/SeedDataLoader.swift` so this file
// stays free of any Persistence dependency and can be exercised from
// any test that needs the canonical PNW set.

import Foundation

// MARK: - Wire types matching the bundled JSON

/// On-disk shape of `SpeciesDefaults.json`.
public struct SpeciesSeedFile: Decodable, Sendable {
    public let species: [SpeciesConfigSeed]
}

public struct SpeciesConfigSeed: Decodable, Sendable {
    public let code: String
    public let commonName: String
    public let scientificName: String
    public let volumeEquationId: String
    public let merchTopDibCm: Float
    public let stumpHeightCm: Float
    public let expectedDbhMinCm: Float
    public let expectedDbhMaxCm: Float
    public let expectedHeightMinM: Float
    public let expectedHeightMaxM: Float

    public func toModel() -> SpeciesConfig {
        SpeciesConfig(
            code: code, commonName: commonName,
            scientificName: scientificName,
            volumeEquationId: volumeEquationId,
            merchTopDibCm: merchTopDibCm, stumpHeightCm: stumpHeightCm,
            expectedDbhMinCm: expectedDbhMinCm,
            expectedDbhMaxCm: expectedDbhMaxCm,
            expectedHeightMinM: expectedHeightMinM,
            expectedHeightMaxM: expectedHeightMaxM)
    }
}

/// On-disk shape of `VolumeEquationsPNW.json`.
public struct VolumeEquationSeedFile: Decodable, Sendable {
    public let equations: [VolumeEquationSeed]
}

public struct VolumeEquationSeed: Decodable, Sendable {
    public let id: String
    public let form: String
    public let coefficients: [String: Float]
    public let unitsIn: String
    public let unitsOut: String
    public let sourceCitation: String

    public func toModel() -> VolumeEquation {
        VolumeEquation(
            id: id, form: form, coefficients: coefficients,
            unitsIn: unitsIn, unitsOut: unitsOut,
            sourceCitation: sourceCitation)
    }
}

// MARK: - Loader

public enum SeedData {

    public enum SeedDataError: Error, CustomStringConvertible {
        case resourceMissing(String)
        case decodeFailed(String, underlying: Error)

        public var description: String {
            switch self {
            case .resourceMissing(let name):
                return "Bundled seed resource \"\(name)\" was not found in Models.bundle. Check that Package.swift still ships Resources/ via .process()."
            case .decodeFailed(let name, let err):
                return "Failed to decode \(name): \(err.localizedDescription)"
            }
        }
    }

    /// Read + decode the bundled `SpeciesDefaults.json`.
    public static func bundledSpecies() throws -> [SpeciesConfig] {
        let file = try decode(SpeciesSeedFile.self, named: "SpeciesDefaults")
        return file.species.map { $0.toModel() }
    }

    /// Read + decode the bundled `VolumeEquationsPNW.json`.
    public static func bundledVolumeEquations() throws -> [VolumeEquation] {
        let file = try decode(VolumeEquationSeedFile.self,
                              named: "VolumeEquationsPNW")
        return file.equations.map { $0.toModel() }
    }

    private static func decode<T: Decodable>(
        _ type: T.Type, named name: String
    ) throws -> T {
        guard let url = Bundle.module.url(forResource: name,
                                          withExtension: "json") else {
            throw SeedDataError.resourceMissing("\(name).json")
        }
        do {
            let data = try Data(contentsOf: url)
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            throw SeedDataError.decodeFailed(name, underlying: error)
        }
    }
}
