// Phase 7.2 hardening — first-launch seeding of the species + volume
// equation tables.
//
// The bundled JSONs ship with the canonical PNW starter set
// (Douglas-fir, western hemlock, western redcedar, red alder). Without
// this loader, a real cruiser opening the app for the first time would
// land on CruiseDesignScreen with an empty species picker and have no
// way forward — which the audit found to be the single biggest field-
// pilot blocker.
//
// Strategy: idempotent. On every launch we ask the species repository
// for a count; if zero, we insert every entry from the bundled JSONs.
// Once the cruiser has added or edited their own species we never
// overwrite — production cruisers calibrate their own coefficient set.

import Foundation
import Models

public enum SeedDataLoader {

    /// Load the PNW starter set if (and only if) the species table is
    /// currently empty. Idempotent — safe to call on every launch.
    /// Returns the (newly inserted, skipped) counts so the caller can
    /// log / surface the result.
    @discardableResult
    public static func bootstrapIfNeeded(
        speciesRepo: any SpeciesConfigRepository,
        volRepo: any VolumeEquationRepository
    ) throws -> (speciesInserted: Int, equationsInserted: Int) {
        // Fast-path: skip everything if the cruiser already has species.
        let existing = try speciesRepo.list()
        guard existing.isEmpty else { return (0, 0) }

        // Volume equations first so the species inserts find their FK.
        let bundledEqs = try SeedData.bundledVolumeEquations()
        let existingEqIds = Set(try volRepo.list().map { $0.id })
        var insertedEqs = 0
        for eq in bundledEqs where !existingEqIds.contains(eq.id) {
            _ = try volRepo.create(eq)
            insertedEqs += 1
        }

        // Species.
        let bundledSpecies = try SeedData.bundledSpecies()
        var insertedSpecies = 0
        for sp in bundledSpecies {
            _ = try speciesRepo.create(sp)
            insertedSpecies += 1
        }

        return (insertedSpecies, insertedEqs)
    }
}
