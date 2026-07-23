// Port of iOS InventoryEngine/VolumeEquations/VolumeEquationFactory.swift.
// Spec §7.7. Constructs a concrete inventory.VolumeEquation from a
// data.cruise.VolumeEquation *record* (which stores the equation form name
// and a coefficient dictionary per §6.2).
//
// This is the one place where both the cruise record and the engine
// interface are in scope, hence the explicit package-qualified type name.

package com.hcjeong.forestix.inventory

object VolumeEquationFactory {

    /// Returns null if the record's `form` is not recognized.
    fun make(record: com.hcjeong.forestix.data.cruise.VolumeEquation): VolumeEquation? =
        when (record.form) {
            "bruce" -> BruceDouglasFir(coefficients = record.coefficients)
            "chambers_foltz" -> ChambersFoltzHemlock(coefficients = record.coefficients)
            "schumacher_hall" -> SchumacherHall(coefficients = record.coefficients)
            "table_lookup" -> TableLookup(coefficients = record.coefficients)
            // Metric (m³) engine — internationalization framework.
            "laasasenaho" -> Laasasenaho(coefficients = record.coefficients)
            "formfactor" -> Formfactor(coefficients = record.coefficients)
            else -> null
        }
}
