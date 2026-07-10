// Port of iOS Persistence/SeedDataLoader.swift (Phase 7.2 hardening) —
// first-launch seeding of the species + volume equation tables.
//
// The bundled JSONs ship with the canonical PNW starter set (Douglas-fir,
// western hemlock, western redcedar, red alder). Without this loader, a
// cruiser opening the app for the first time would land on
// CruiseDesignScreen with an empty species picker and have no way forward.
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

    /// Load the PNW starter set if (and only if) the species table is
    /// currently empty. Idempotent — safe to call on every launch.
    /// Returns the inserted counts so the caller can log / surface the
    /// result. Throws SeedData.SeedDataError when the bundled JSON is
    /// missing or undecodable.
    suspend fun bootstrapIfNeeded(
        context: Context,
        speciesRepo: SpeciesConfigRepository,
        volRepo: VolumeEquationRepository,
    ): BootstrapResult {
        // Fast-path: skip everything if the cruiser already has species.
        val existing = speciesRepo.list()
        if (existing.isNotEmpty()) return BootstrapResult(0, 0)

        // Volume equations first so the species inserts find their FK.
        val bundledEqs = SeedData.bundledVolumeEquations(context)
        val existingEqIds = volRepo.list().map { it.id }.toSet()
        var insertedEqs = 0
        for (eq in bundledEqs) {
            if (eq.id in existingEqIds) continue
            volRepo.create(eq)
            insertedEqs += 1
        }

        // Species.
        val bundledSpecies = SeedData.bundledSpecies(context)
        var insertedSpecies = 0
        for (sp in bundledSpecies) {
            speciesRepo.create(sp)
            insertedSpecies += 1
        }

        return BootstrapResult(
            speciesInserted = insertedSpecies,
            equationsInserted = insertedEqs)
    }
}
