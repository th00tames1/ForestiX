// Spec §7.7. Constructs a concrete InventoryEngine.VolumeEquation from a
// Models.VolumeEquation *record* (which stores the equation form name and a
// coefficient dictionary per §6.2).
//
// This is the one place where both the Models record and the engine protocol
// are in scope, hence the explicit module-prefixed type names.

import Foundation
import Models

public enum VolumeEquationFactory {

    /// Returns nil if the record's `form` is not recognized.
    public static func make(from record: Models.VolumeEquation)
    -> (any InventoryEngine.VolumeEquation)? {
        switch record.form {
        case "bruce":              return BruceDouglasFir(coefficients: record.coefficients)
        case "chambers_foltz":     return ChambersFoltzHemlock(coefficients: record.coefficients)
        case "schumacher_hall":    return SchumacherHall(coefficients: record.coefficients)
        case "table_lookup":       return TableLookup(coefficients: record.coefficients)
        default:                   return nil
        }
    }
}
