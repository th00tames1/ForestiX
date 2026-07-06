// Bundled species / volume-equation seed data — direct port of iOS
// Models/SeedData.swift (Phase 7.2 hardening).
//
// Spec §6.2 ships the PNW starter set in `SpeciesDefaults.json` and
// `VolumeEquationsPNW.json`. On iOS these live in Models.bundle; on Android
// the same files ship in `app/src/main/assets/` and are decoded here. The
// actual database insertion lives in the persistence layer so this file
// stays free of any Room dependency and can be exercised from any test
// that needs the canonical PNW set.
//
// Parsing uses org.json (android framework) rather than kotlinx-serialization
// because the serialization compiler plugin is not applied in this module.

package com.hcjeong.forestix.data.cruise

import android.content.Context
import org.json.JSONObject

// MARK: - Wire types matching the bundled JSON

/// On-disk shape of `SpeciesDefaults.json`.
data class SpeciesSeedFile(
    val species: List<SpeciesConfigSeed>,
)

data class SpeciesConfigSeed(
    val code: String,
    val commonName: String,
    val scientificName: String,
    val volumeEquationId: String,
    val merchTopDibCm: Float,
    val stumpHeightCm: Float,
    val expectedDbhMinCm: Float,
    val expectedDbhMaxCm: Float,
    val expectedHeightMinM: Float,
    val expectedHeightMaxM: Float,
) {
    fun toModel() = SpeciesConfig(
        code = code, commonName = commonName,
        scientificName = scientificName,
        volumeEquationId = volumeEquationId,
        merchTopDibCm = merchTopDibCm, stumpHeightCm = stumpHeightCm,
        expectedDbhMinCm = expectedDbhMinCm,
        expectedDbhMaxCm = expectedDbhMaxCm,
        expectedHeightMinM = expectedHeightMinM,
        expectedHeightMaxM = expectedHeightMaxM)
}

/// On-disk shape of `VolumeEquationsPNW.json`.
data class VolumeEquationSeedFile(
    val equations: List<VolumeEquationSeed>,
)

data class VolumeEquationSeed(
    val id: String,
    val form: String,
    val coefficients: Map<String, Float>,
    val unitsIn: String,
    val unitsOut: String,
    val sourceCitation: String,
) {
    fun toModel() = VolumeEquation(
        id = id, form = form, coefficients = coefficients,
        unitsIn = unitsIn, unitsOut = unitsOut,
        sourceCitation = sourceCitation)
}

// MARK: - Loader

object SeedData {

    sealed class SeedDataError(override val message: String) : Exception(message) {
        class ResourceMissing(name: String) : SeedDataError(
            "Bundled seed resource \"$name\" was not found in assets/. " +
                "Check that app/src/main/assets/ still ships the seed JSON files.")

        class DecodeFailed(name: String, underlying: Exception) : SeedDataError(
            "Failed to decode $name: ${underlying.message}")
    }

    /// Read + decode the bundled `SpeciesDefaults.json`.
    @Throws(SeedDataError::class)
    fun bundledSpecies(context: Context): List<SpeciesConfig> {
        val root = readJson(context, "SpeciesDefaults")
        return try {
            val arr = root.getJSONArray("species")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SpeciesConfigSeed(
                    code = o.getString("code"),
                    commonName = o.getString("commonName"),
                    scientificName = o.getString("scientificName"),
                    volumeEquationId = o.getString("volumeEquationId"),
                    merchTopDibCm = o.getDouble("merchTopDibCm").toFloat(),
                    stumpHeightCm = o.getDouble("stumpHeightCm").toFloat(),
                    expectedDbhMinCm = o.getDouble("expectedDbhMinCm").toFloat(),
                    expectedDbhMaxCm = o.getDouble("expectedDbhMaxCm").toFloat(),
                    expectedHeightMinM = o.getDouble("expectedHeightMinM").toFloat(),
                    expectedHeightMaxM = o.getDouble("expectedHeightMaxM").toFloat(),
                ).toModel()
            }
        } catch (e: Exception) {
            if (e is SeedDataError) throw e
            throw SeedDataError.DecodeFailed("SpeciesDefaults", e)
        }
    }

    /// Read + decode the bundled `VolumeEquationsPNW.json`.
    @Throws(SeedDataError::class)
    fun bundledVolumeEquations(context: Context): List<VolumeEquation> {
        val root = readJson(context, "VolumeEquationsPNW")
        return try {
            val arr = root.getJSONArray("equations")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val coeffObj = o.getJSONObject("coefficients")
                val coefficients = buildMap {
                    for (key in coeffObj.keys()) {
                        put(key, coeffObj.getDouble(key).toFloat())
                    }
                }
                VolumeEquationSeed(
                    id = o.getString("id"),
                    form = o.getString("form"),
                    coefficients = coefficients,
                    unitsIn = o.getString("unitsIn"),
                    unitsOut = o.getString("unitsOut"),
                    sourceCitation = o.getString("sourceCitation"),
                ).toModel()
            }
        } catch (e: Exception) {
            if (e is SeedDataError) throw e
            throw SeedDataError.DecodeFailed("VolumeEquationsPNW", e)
        }
    }

    private fun readJson(context: Context, name: String): JSONObject {
        val text = try {
            context.assets.open("$name.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            throw SeedDataError.ResourceMissing("$name.json")
        }
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            throw SeedDataError.DecodeFailed(name, e)
        }
    }
}
