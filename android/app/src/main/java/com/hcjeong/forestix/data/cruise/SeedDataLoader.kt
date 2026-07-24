// Port of iOS Persistence/SeedDataLoader.swift (Phase 7.2 hardening) —
// first-launch seeding of the species + volume equation tables.
//
// The bundled JSONs ship with the canonical PNW starter set (Douglas-fir,
// western hemlock, western redcedar, red alder). Without this loader, a
// cruiser opening the app for the first time would face an empty species
// picker with no way forward.
//
// Strategy: idempotent. On every launch we ask the species repository for
// its contents; if empty, we insert every entry from the bundled JSONs
// (app/src/main/assets/). Once the cruiser has added or edited their own
// species we never overwrite — production cruisers calibrate their own
// coefficient set.

package com.hcjeong.forestix.data.cruise

import android.content.Context

object SeedDataLoader {

    /// Return counts from `bootstrapIfNeeded` — the Kotlin spelling of the
    /// iOS labelled tuple `(speciesInserted: Int, equationsInserted: Int)`.
    data class BootstrapResult(
        val speciesInserted: Int,
        val equationsInserted: Int,
    )

    /// Seed the bundled starter sets, additively and idempotently — safe to
    /// call on every launch. Never overwrites a record the cruiser already has
    /// (production cruisers calibrate their own coefficients); only inserts
    /// bundled defaults whose id / code is still missing. This is what lets a
    /// NEW bundled set — e.g. the internationalization framework's metric
    /// species + m³ equations — land on an already-seeded install instead of
    /// being gated out by a non-empty table (the old fast-path early-returned
    /// the moment any species existed, which silently stranded the metric
    /// equations no species was bound to).
    /// Throws SeedData.SeedDataError when a bundled JSON is missing/undecodable.
    suspend fun bootstrapIfNeeded(
        context: Context,
        speciesRepo: SpeciesConfigRepository,
        volRepo: VolumeEquationRepository,
    ): BootstrapResult {
        val existingSpecies = speciesRepo.list()

        // Volume equations first so the species inserts find their FK.
        // The US PNW starter set plus the internationalization framework's
        // metric (m³) equations (Laasasenaho + form factor). A missing metric
        // asset must not block the US set, so it is loaded defensively.
        val bundledEqs = SeedData.bundledVolumeEquations(context) +
            runCatching { SeedData.bundledMetricVolumeEquations(context) }.getOrDefault(emptyList())
        val existingEqIds = volRepo.list().map { it.id }.toSet()
        var insertedEqs = 0
        for (eq in bundledEqs) {
            if (eq.id in existingEqIds) continue
            volRepo.create(eq)
            insertedEqs += 1
        }

        // Species — the US set plus the metric-country species, each already
        // bound to a seeded equation id so metric stem volume resolves. Metric
        // asset loaded defensively.
        val bundledSpecies = SeedData.bundledSpecies(context) +
            runCatching { SeedData.bundledMetricSpecies(context) }.getOrDefault(emptyList())
        val existingCodes = existingSpecies.map { it.code }.toSet()
        var insertedSpecies = 0
        for (sp in bundledSpecies) {
            if (sp.code in existingCodes) continue
            speciesRepo.create(sp)
            insertedSpecies += 1
        }

        return BootstrapResult(
            speciesInserted = insertedSpecies,
            equationsInserted = insertedEqs)
    }
}
