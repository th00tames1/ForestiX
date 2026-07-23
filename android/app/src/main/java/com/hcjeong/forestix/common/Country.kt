// Internationalization framework — Country sits ABOVE Region and decides the
// forestry paradigm: volume standard, unit (board-foot vs m³) and whether the
// board-foot Log rule concept even applies. A locked cross-platform design
// shared with the iOS sibling — Country ids, standard ids and unit-branch
// decisions are identical so exports and handoff line up across platforms.
//
// Only the United States uses the North-American board-foot log-rule system
// (Scribner / Doyle / International ¼″). Every other country expresses stem
// volume in cubic metres via a volume/taper function or national table
// (see docs/volume-standards-research.md §D). Korea is scaffolded but its
// official NIFoS coefficients are PENDING, so its volume standard returns
// nothing rather than a fabricated number.
//
// Storage: `AppSettings.country` is a preferences string keyed off
// `Country.raw`, defaulting to UNITED_STATES so existing US installs are
// unchanged. Region continues to work, but only for countries that have
// regions (the US).

package com.hcjeong.forestix.common

import com.hcjeong.forestix.sensors.LogRule

/// Which unit standing/stem volume is expressed in. Governs display + export
/// rendering only — the engine always computes and stores m³ internally.
enum class VolumeUnit {
    /// North-America: cubic feet + board feet (via the selected log rule).
    CUBIC_FEET,
    /// Rest of world: cubic metres.
    CUBIC_METERS,
}

/// The areal basis a country expresses per-area densities in (trees, basal
/// area, volume per unit land area). The US cruises per acre; metric countries
/// per hectare. The inventory engine computes everything per ACRE internally
/// (plot areas persist as acres), so this governs display/export conversion and
/// labels only.
enum class AreaUnit {
    ACRE,
    HECTARE;

    /// Multiply a canonical per-ACRE density by this to express it per this
    /// unit. 1 hectare = 2.4710538147 acres, so per-hectare = per-acre × that.
    val perAcreDensityFactor: Double
        get() = if (this == HECTARE) 2.4710538147 else 1.0

    /// Convert a stored area in acres to this unit (acres → hectares).
    fun fromAcres(acres: Double): Double =
        if (this == HECTARE) acres / 2.4710538147 else acres

    /// Density-label suffix: "/ha" or "/ac".
    val densitySuffix: String get() = if (this == HECTARE) "/ha" else "/ac"

    /// Bare area-unit abbreviation: "ha" or "ac".
    val abbreviation: String get() = if (this == HECTARE) "ha" else "ac"

    /// A density label built from a base quantity, e.g. densityLabel("m³") →
    /// "m³/ha" (metric) or "m³/ac" (US).
    fun densityLabel(base: String): String = base + densitySuffix
}

enum class Country(val raw: String) {
    UNITED_STATES("us"),
    FINLAND("fi"),
    GERMANY("de"),
    SOUTH_KOREA("kr");

    val id: String get() = raw

    val displayName: String
        get() = when (this) {
            UNITED_STATES -> "United States"
            FINLAND -> "Finland"
            GERMANY -> "Germany"
            SOUTH_KOREA -> "South Korea"
        }

    val subtitle: String
        get() = when (this) {
            UNITED_STATES -> "11 timber regions · board-foot log rules"
            FINLAND -> "Nordic · Laasasenaho stem volume (m³)"
            GERMANY -> "Central Europe · form-factor volume (m³)"
            SOUTH_KOREA -> "NIFoS metric (m³) · coefficients pending"
        }

    /// Board-foot log rules are a North-America-only concept. Only the US
    /// exposes the Log-rule setting; metric countries hide it entirely.
    val usesLogRule: Boolean get() = this == UNITED_STATES

    /// US renders cubic/board feet; everyone else renders cubic metres.
    val volumeUnit: VolumeUnit
        get() = if (this == UNITED_STATES) VolumeUnit.CUBIC_FEET else VolumeUnit.CUBIC_METERS

    /// US cruises per acre; metric countries per hectare. Drives the
    /// display/export density conversion + labels (the engine stays per-acre).
    val areaUnit: AreaUnit
        get() = if (this == UNITED_STATES) AreaUnit.ACRE else AreaUnit.HECTARE

    /// Only the US carries the 11 sub-national timber regions. Metric
    /// countries have a single national species/volume standard, so the
    /// first-run cascade stops at the country step for them.
    val hasRegions: Boolean get() = this == UNITED_STATES

    /// Regions offered in the first-run cascade / Settings for this country.
    val regions: List<Region>
        get() = if (hasRegions) Region.entries.toList() else emptyList()

    /// True when the country is selectable but its volume coefficients are
    /// not yet bundled — stand volume must render "—", never a fabricated
    /// figure (Korea, pending official NIFoS coefficients).
    val volumeStandardPending: Boolean get() = this == SOUTH_KOREA

    /// Read-only "Volume standard" row shown in Settings, derived from the
    /// country (and, for the US, the selected region's log rule elsewhere).
    val volumeStandardLabel: String
        get() = when (this) {
            UNITED_STATES -> "Board-foot log rule (Scribner / Doyle / Int'l ¼″)"
            FINLAND -> "Laasasenaho (1982) · m³"
            GERMANY -> "Form factor  V = g·h·f · m³ (approx.)"
            SOUTH_KOREA -> "NIFoS national table — coefficients pending"
        }

    companion object {
        val default = UNITED_STATES
        fun fromRaw(s: String?) = entries.firstOrNull { it.raw == s }
    }
}

/// US default log rule derived from the picked region: the Eastern US is
/// Doyle country, the West is Scribner (USFS Western default). Used by the
/// first-run cascade + Settings so the cruiser never has to set it by hand.
val Region.defaultLogRule: LogRule
    get() = when (this) {
        Region.COASTAL_PLAIN, Region.PIEDMONT,
        Region.APPALACHIAN, Region.BOTTOMLAND -> LogRule.DOYLE
        else -> LogRule.SCRIBNER
    }

/// Per-country species presets for the scan-time species picker — the metric
/// counterpart of `RegionalSpecies.defaultSpecies(region)`. The US returns an
/// empty list because it is scoped by Region instead. Codes are country-
/// prefixed so they never collide with the US FIA codes and so a tallied
/// metric species reads back unambiguously. Pair = (code, common name).
///
/// Korea's list is a SCAFFOLD: the species are real (code + name) but their
/// volume coefficients are pending (see Country.volumeStandardPending), so no
/// volume equation is bound to them.
object CountrySpecies {

    fun defaultSpecies(country: Country): List<Pair<String, String>> = when (country) {
        Country.UNITED_STATES -> emptyList()   // scoped by Region

        Country.FINLAND -> listOf(
            "FI-PISY" to "Scots pine",
            "FI-PIAB" to "Norway spruce",
            "FI-BEPE" to "Silver birch",
            "FI-BEPU" to "Downy birch",
        )

        Country.GERMANY -> listOf(
            "DE-PIAB" to "Norway spruce (Fichte)",
            "DE-PISY" to "Scots pine (Kiefer)",
            "DE-FASY" to "European beech (Buche)",
            "DE-QURO" to "Pedunculate oak (Eiche)",
        )

        // Scaffold only — coefficients pending official NIFoS values.
        Country.SOUTH_KOREA -> listOf(
            "KR-PIDE" to "소나무 (Pinus densiflora)",
            "KR-PIKO" to "잣나무 (Pinus koraiensis)",
            "KR-LAKA" to "낙엽송 (Larix kaempferi)",
            "KR-PIRI" to "리기다소나무 (Pinus rigida)",
            "KR-CRJA" to "삼나무 (Cryptomeria japonica)",
            "KR-CHOB" to "편백 (Chamaecyparis obtusa)",
            "KR-QUSP" to "참나무류 (Quercus spp.)",
            "KR-ROPS" to "아까시 (Robinia pseudoacacia)",
        )
    }
}
