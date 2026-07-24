// Regional species presets — direct port of iOS App/RegionalSpecies.swift.
// A "pick your region, get the right species pre-selected" onboarding
// pattern. Cuts first-run friction: the cruiser
// picks once and the rest of the app filters its species pickers to the
// relevant 8–15 species.
//
// Codes follow USDA FIA standard 4-letter species codes so downstream
// CSV / handoff into Forest-Management software lines up. The regional
// groupings cover the major US timber regions; non-US users pick
// "All species" or extend via Settings later.
//
// Storage: `AppSettings.region` is a preferences string keyed off
// `Region.raw`. null = first-run, the picker sheet has been neither shown
// nor dismissed; "all" = explicitly all-species mode.
//
// Raw values match the Swift rawValue strings byte-for-byte.

package com.hcjeong.forestix.common

import java.util.Locale

enum class Region(val raw: String) {
    PNW_WEST("pnw_west"),
    PNW_EAST("pnw_east"),
    N_ROCKIES("n_rockies"),
    N_SIERRA("n_sierra"),
    S_SIERRA("s_sierra"),
    CA_COAST("ca_coast"),
    SOUTHWEST("southwest"),
    COASTAL_PLAIN("coastal_plain"),
    PIEDMONT("piedmont"),
    APPALACHIAN("appalachian"),
    BOTTOMLAND("bottomland"),
    ALL("all");

    val id: String get() = raw

    val displayName: String
        get() = when (this) {
            PNW_WEST -> "PNW — West Side"
            PNW_EAST -> "PNW — East Side"
            N_ROCKIES -> "Northern Rockies"
            N_SIERRA -> "Northern Sierra"
            S_SIERRA -> "Southern Sierra"
            CA_COAST -> "California Coast"
            SOUTHWEST -> "Southwest"
            COASTAL_PLAIN -> "Coastal Plain"
            PIEDMONT -> "Piedmont"
            APPALACHIAN -> "Appalachian"
            BOTTOMLAND -> "Bottomland / Delta"
            ALL -> "All species"
        }

    val subtitle: String
        get() = when (this) {
            PNW_WEST -> "Coastal OR/WA — DF, WH, RC, RA"
            PNW_EAST -> "Cascades east — PP, LP, GF, ES"
            N_ROCKIES -> "ID, MT — DF, LP, ES, GF, AS"
            N_SIERRA -> "Northern CA Sierra — PP, RF, IC, DF"
            S_SIERRA -> "Southern Sierra — JP, RF, IC, GS"
            CA_COAST -> "Coast Ranges — CR, DF, TO, MA"
            SOUTHWEST -> "AZ/NM/UT — PP, JU, PI, AS, ES"
            COASTAL_PLAIN -> "Southern coastal — LL, SP, LP, SG"
            PIEDMONT -> "Mid-South Atlantic — LP, SP, RO, WO"
            APPALACHIAN -> "Appalachian — RO, WO, YP, RM, BL"
            BOTTOMLAND -> "Mississippi delta — BG, RG, GA, SY"
            ALL -> "Show every species in the database"
        }

    companion object {
        fun fromRaw(s: String?) = entries.firstOrNull { it.raw == s }
    }
}

/// Pre-loaded species list for a region. Pair = (FIA code, common
/// name). Codes are USDA / FVS standard.
object RegionalSpecies {

    fun defaultSpecies(region: Region): List<Pair<String, String>> = when (region) {
        Region.PNW_WEST -> listOf(
            "DF" to "Douglas-fir",
            "WH" to "Western hemlock",
            "RC" to "Western redcedar",
            "SS" to "Sitka spruce",
            "RA" to "Red alder",
            "BM" to "Bigleaf maple",
            "WW" to "Western white pine",
            "PSF" to "Pacific silver fir",
        )
        Region.PNW_EAST -> listOf(
            "PP" to "Ponderosa pine",
            "LP" to "Lodgepole pine",
            "GF" to "Grand fir",
            "ES" to "Engelmann spruce",
            "DF" to "Douglas-fir",
            "WL" to "Western larch",
            "SAF" to "Subalpine fir",
        )
        Region.N_ROCKIES -> listOf(
            "DF" to "Douglas-fir",
            "LP" to "Lodgepole pine",
            "PP" to "Ponderosa pine",
            "ES" to "Engelmann spruce",
            "SAF" to "Subalpine fir",
            "GF" to "Grand fir",
            "WL" to "Western larch",
            "AS" to "Aspen",
        )
        Region.N_SIERRA -> listOf(
            "PP" to "Ponderosa pine",
            "RF" to "Red fir",
            "IC" to "Incense cedar",
            "WF" to "White fir",
            "DF" to "Douglas-fir",
            "SP" to "Sugar pine",
            "BO" to "California black oak",
        )
        Region.S_SIERRA -> listOf(
            "JP" to "Jeffrey pine",
            "RF" to "Red fir",
            "IC" to "Incense cedar",
            "WF" to "White fir",
            "GS" to "Giant sequoia",
            "PP" to "Ponderosa pine",
            "SP" to "Sugar pine",
        )
        Region.CA_COAST -> listOf(
            "CR" to "Coast redwood",
            "DF" to "Douglas-fir",
            "TO" to "Tanoak",
            "MA" to "Pacific madrone",
            "BO" to "California black oak",
            "LO" to "Live oak",
        )
        Region.SOUTHWEST -> listOf(
            "PP" to "Ponderosa pine",
            "JU" to "Juniper",
            "PI" to "Piñon pine",
            "AS" to "Aspen",
            "ES" to "Engelmann spruce",
            "SAF" to "Subalpine fir",
            "DF" to "Douglas-fir",
        )
        Region.COASTAL_PLAIN -> listOf(
            "LL" to "Longleaf pine",
            "SP" to "Slash pine",
            "LP" to "Loblolly pine",
            "SG" to "Sweetgum",
            "BG" to "Blackgum",
            "RB" to "River birch",
            "WO" to "Water oak",
        )
        Region.PIEDMONT -> listOf(
            "LP" to "Loblolly pine",
            "SP" to "Shortleaf pine",
            "VP" to "Virginia pine",
            "RO" to "Red oak",
            "WO" to "White oak",
            "YP" to "Yellow-poplar",
            "HI" to "Hickory",
            "SG" to "Sweetgum",
        )
        Region.APPALACHIAN -> listOf(
            "RO" to "Red oak",
            "WO" to "White oak",
            "YP" to "Yellow-poplar",
            "RM" to "Red maple",
            "SM" to "Sugar maple",
            "BL" to "Black locust",
            "BC" to "Black cherry",
            "HI" to "Hickory",
            "WP" to "Eastern white pine",
        )
        Region.BOTTOMLAND -> listOf(
            "BG" to "Baldcypress",
            "RG" to "Red gum",
            "GA" to "Green ash",
            "SY" to "Sycamore",
            "OK" to "Overcup oak",
            "BO" to "Bur oak",
            "CO" to "Cherrybark oak",
        )
        Region.ALL -> emptyList()   // empty = no filter applied
    }

    /// Resolves a stored FIA code to its human-readable common name,
    /// searching across every region's preset list so a code tallied in
    /// one region still reads correctly anywhere it's displayed. This is
    /// display-only: stored `speciesCode` values and CSV columns keep the
    /// code. Free-typed or otherwise unknown codes (not in any region,
    /// e.g. "OT") fall back to the raw code so nothing is hidden.
    fun nameForCode(code: String): String {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return code
        return canonicalNames[trimmed.uppercase(Locale.US)] ?: code
    }

    /// Code → common name, built once from all regional presets. First
    /// occurrence wins when a code appears in several regions (a handful
    /// of FIA codes collide across regions).
    private val canonicalNames: Map<String, String> by lazy {
        val map = LinkedHashMap<String, String>()
        Region.entries.forEach { region ->
            defaultSpecies(region).forEach { (code, name) ->
                val key = code.uppercase(Locale.US)
                if (!map.containsKey(key)) map[key] = name
            }
        }
        // Also fold the metric per-country species (FI/DE/KR). Their codes are
        // country-prefixed (e.g. "FI-PISY") so they never collide with the US
        // FIA codes above; without this a metric cruiser's tallied code renders
        // as the raw prefix everywhere nameForCode is called. iOS folds
        // Country.allNonUSSpecies the same way (RegionalSpecies.swift).
        Country.entries.forEach { country ->
            CountrySpecies.defaultSpecies(country).forEach { (code, name) ->
                val key = code.uppercase(Locale.US)
                if (!map.containsKey(key)) map[key] = name
            }
        }
        map
    }
}
