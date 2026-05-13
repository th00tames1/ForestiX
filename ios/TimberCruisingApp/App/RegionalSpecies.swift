// Regional species presets — adopted from SilvaCruise's "pick your
// region, get the right species pre-selected" onboarding pattern.
// Cuts first-run friction: the cruiser picks once and the rest of
// the app filters its species pickers to the relevant 8–15 species.
//
// Codes follow USDA FIA standard 4-letter species codes so downstream
// CSV / handoff into Forest-Management software lines up. The
// regional groupings cover the major US timber regions; non-US users
// pick "All species" or extend via Settings later.
//
// Storage: `AppSettings.region` is a UserDefaults string keyed off
// `Region.rawValue`. nil = first-run, the picker sheet has been
// neither shown nor dismissed; "all" = explicitly all-species mode.

import Foundation

public enum Region: String, CaseIterable, Identifiable, Sendable {
    case pnwWest    = "pnw_west"
    case pnwEast    = "pnw_east"
    case nRockies   = "n_rockies"
    case nSierra    = "n_sierra"
    case sSierra    = "s_sierra"
    case caCoast    = "ca_coast"
    case southwest  = "southwest"
    case coastalPlain = "coastal_plain"
    case piedmont   = "piedmont"
    case appalachian = "appalachian"
    case bottomland = "bottomland"
    case all        = "all"

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .pnwWest:       return "PNW — West Side"
        case .pnwEast:       return "PNW — East Side"
        case .nRockies:      return "Northern Rockies"
        case .nSierra:       return "Northern Sierra"
        case .sSierra:       return "Southern Sierra"
        case .caCoast:       return "California Coast"
        case .southwest:     return "Southwest"
        case .coastalPlain:  return "Coastal Plain"
        case .piedmont:      return "Piedmont"
        case .appalachian:   return "Appalachian"
        case .bottomland:    return "Bottomland / Delta"
        case .all:           return "All species"
        }
    }

    public var subtitle: String {
        switch self {
        case .pnwWest:       return "Coastal OR/WA — DF, WH, RC, RA"
        case .pnwEast:       return "Cascades east — PP, LP, GF, ES"
        case .nRockies:      return "ID, MT — DF, LP, ES, GF, AS"
        case .nSierra:       return "Northern CA Sierra — PP, RF, IC, DF"
        case .sSierra:       return "Southern Sierra — JP, RF, IC, GS"
        case .caCoast:       return "Coast Ranges — CR, DF, TO, MA"
        case .southwest:     return "AZ/NM/UT — PP, JU, PI, AS, ES"
        case .coastalPlain:  return "Southern coastal — LL, SP, LP, SG"
        case .piedmont:      return "Mid-South Atlantic — LP, SP, RO, WO"
        case .appalachian:   return "Appalachian — RO, WO, YP, RM, BL"
        case .bottomland:    return "Mississippi delta — BG, RG, GA, SY"
        case .all:           return "Show every species in the database"
        }
    }
}

/// Pre-loaded species list for a region. Tuple = (FIA code, common
/// name). Codes are USDA / FVS standard.
public enum RegionalSpecies {

    public static func defaultSpecies(for region: Region) -> [(String, String)] {
        switch region {
        case .pnwWest:
            return [
                ("DF", "Douglas-fir"),
                ("WH", "Western hemlock"),
                ("RC", "Western redcedar"),
                ("SS", "Sitka spruce"),
                ("RA", "Red alder"),
                ("BM", "Bigleaf maple"),
                ("WW", "Western white pine"),
                ("PSF", "Pacific silver fir"),
            ]
        case .pnwEast:
            return [
                ("PP", "Ponderosa pine"),
                ("LP", "Lodgepole pine"),
                ("GF", "Grand fir"),
                ("ES", "Engelmann spruce"),
                ("DF", "Douglas-fir"),
                ("WL", "Western larch"),
                ("SAF", "Subalpine fir"),
            ]
        case .nRockies:
            return [
                ("DF", "Douglas-fir"),
                ("LP", "Lodgepole pine"),
                ("PP", "Ponderosa pine"),
                ("ES", "Engelmann spruce"),
                ("SAF", "Subalpine fir"),
                ("GF", "Grand fir"),
                ("WL", "Western larch"),
                ("AS", "Aspen"),
            ]
        case .nSierra:
            return [
                ("PP", "Ponderosa pine"),
                ("RF", "Red fir"),
                ("IC", "Incense cedar"),
                ("WF", "White fir"),
                ("DF", "Douglas-fir"),
                ("SP", "Sugar pine"),
                ("BO", "California black oak"),
            ]
        case .sSierra:
            return [
                ("JP", "Jeffrey pine"),
                ("RF", "Red fir"),
                ("IC", "Incense cedar"),
                ("WF", "White fir"),
                ("GS", "Giant sequoia"),
                ("PP", "Ponderosa pine"),
                ("SP", "Sugar pine"),
            ]
        case .caCoast:
            return [
                ("CR", "Coast redwood"),
                ("DF", "Douglas-fir"),
                ("TO", "Tanoak"),
                ("MA", "Pacific madrone"),
                ("BO", "California black oak"),
                ("LO", "Live oak"),
            ]
        case .southwest:
            return [
                ("PP", "Ponderosa pine"),
                ("JU", "Juniper"),
                ("PI", "Piñon pine"),
                ("AS", "Aspen"),
                ("ES", "Engelmann spruce"),
                ("SAF", "Subalpine fir"),
                ("DF", "Douglas-fir"),
            ]
        case .coastalPlain:
            return [
                ("LL", "Longleaf pine"),
                ("SP", "Slash pine"),
                ("LP", "Loblolly pine"),
                ("SG", "Sweetgum"),
                ("BG", "Blackgum"),
                ("RB", "River birch"),
                ("WO", "Water oak"),
            ]
        case .piedmont:
            return [
                ("LP", "Loblolly pine"),
                ("SP", "Shortleaf pine"),
                ("VP", "Virginia pine"),
                ("RO", "Red oak"),
                ("WO", "White oak"),
                ("YP", "Yellow-poplar"),
                ("HI", "Hickory"),
                ("SG", "Sweetgum"),
            ]
        case .appalachian:
            return [
                ("RO", "Red oak"),
                ("WO", "White oak"),
                ("YP", "Yellow-poplar"),
                ("RM", "Red maple"),
                ("SM", "Sugar maple"),
                ("BL", "Black locust"),
                ("BC", "Black cherry"),
                ("HI", "Hickory"),
                ("WP", "Eastern white pine"),
            ]
        case .bottomland:
            return [
                ("BG", "Baldcypress"),
                ("RG", "Red gum"),
                ("GA", "Green ash"),
                ("SY", "Sycamore"),
                ("OK", "Overcup oak"),
                ("BO", "Bur oak"),
                ("CO", "Cherrybark oak"),
            ]
        case .all:
            return []   // empty = no filter applied
        }
    }
}
