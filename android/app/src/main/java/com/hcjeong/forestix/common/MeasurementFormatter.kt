// Unit-aware display formatter — central source of truth for how every
// measurement renders given the cruiser's chosen UnitSystem. Direct port
// of iOS App/MeasurementFormatter.swift. Storage stays metric; the
// display layer converts once on the way to the screen / CSV / share.

package com.hcjeong.forestix.common

import java.util.Locale

object MeasurementFormatter {

    // Diameter — stored cm.
    fun diameter(cm: Double, system: UnitSystem): String = when (system) {
        UnitSystem.METRIC -> String.format(Locale.US, "%.1f cm", cm)
        UnitSystem.IMPERIAL -> String.format(Locale.US, "%.1f in", cm / 2.54)
    }

    fun diameterSigma(mm: Double, system: UnitSystem): String = when (system) {
        UnitSystem.METRIC -> String.format(Locale.US, "\u00B1%.1f mm", mm)
        UnitSystem.IMPERIAL -> String.format(Locale.US, "\u00B1%.2f in", mm / 25.4)
    }

    // Height — stored m.
    fun height(m: Double, system: UnitSystem): String = when (system) {
        UnitSystem.METRIC -> String.format(Locale.US, "%.1f m", m)
        UnitSystem.IMPERIAL -> String.format(Locale.US, "%.1f ft", m * 3.28084)
    }

    fun heightSigma(m: Double, system: UnitSystem): String = when (system) {
        UnitSystem.METRIC -> String.format(Locale.US, "\u00B1%.1f m", m)
        UnitSystem.IMPERIAL -> String.format(Locale.US, "\u00B1%.1f ft", m * 3.28084)
    }

    // Distance / generic length — stored m.
    fun distance(m: Double, system: UnitSystem): String = when (system) {
        UnitSystem.METRIC -> String.format(Locale.US, "%.2f m", m)
        UnitSystem.IMPERIAL -> String.format(Locale.US, "%.1f ft", m * 3.28084)
    }

    fun diameterUnit(system: UnitSystem) = if (system == UnitSystem.METRIC) "cm" else "in"
    fun heightUnit(system: UnitSystem) = if (system == UnitSystem.METRIC) "m" else "ft"
}
