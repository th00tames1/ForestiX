// Phase 7.2 hardening — first-launch seeding of the species + volume
// equation tables.
//
// The bundled JSONs ship with the canonical PNW starter set
// (Douglas-fir, western hemlock, western redcedar, red alder). Without
// this loader, a real cruiser opening the app for the first time would
// face an empty species picker with no way forward — which the audit
// found to be the single biggest field-pilot blocker.
//
// Strategy: idempotent. On every launch we ask the species repository
// for a count; if zero, we insert every entry from the bundled JSONs.
// Once the cruiser has added or edited their own species we never
// overwrite — production cruisers calibrate their own coefficient set.

import Foundation
import Models

public enum SeedDataLoader {

    /// Seed the bundled starter sets, additively and idempotently — safe to
    /// call on every launch. Never overwrites a record the cruiser already has
    /// (production cruisers calibrate their own coefficients); only inserts
    /// bundled defaults whose id / code is still missing. This is what lets a
    /// NEW bundled set — e.g. the internationalisation framework's metric
    /// species + m³ equations — land on an already-seeded install instead of
    /// being gated out by a non-empty table (the old fast-path early-returned
    /// the moment any species existed, which silently stranded the metric
    /// equations no species was bound to).
    /// Returns the newly-inserted (species, equations) counts.
    @discardableResult
    public static func bootstrapIfNeeded(
        speciesRepo: any SpeciesConfigRepository,
        volRepo: any VolumeEquationRepository
    ) throws -> (speciesInserted: Int, equationsInserted: Int) {
        let existingSpecies = try speciesRepo.list()

        // Volume equations first so the species inserts find their FK. The US
        // PNW starter set plus the internationalisation framework's metric (m³)
        // equations (Laasasenaho + form factor). A missing metric asset must
        // not block the US set, so it is loaded defensively.
        let metricEqs = (try? SeedData.bundledMetricVolumeEquations()) ?? []
        let bundledEqs = try SeedData.bundledVolumeEquations() + metricEqs
        let existingEqIds = Set(try volRepo.list().map { $0.id })
        var insertedEqs = 0
        for eq in bundledEqs where !existingEqIds.contains(eq.id) {
            _ = try volRepo.create(eq)
            insertedEqs += 1
        }

        // Species — the US set plus the metric-country species, each already
        // bound to a seeded equation id so metric stem volume resolves. Metric
        // asset loaded defensively.
        let metricSpecies = (try? SeedData.bundledMetricSpecies()) ?? []
        let bundledSpecies = try SeedData.bundledSpecies() + metricSpecies
        let existingCodes = Set(existingSpecies.map { $0.code })
        var insertedSpecies = 0
        for sp in bundledSpecies where !existingCodes.contains(sp.code) {
            _ = try speciesRepo.create(sp)
            insertedSpecies += 1
        }

        return (insertedSpecies, insertedEqs)
    }
}
