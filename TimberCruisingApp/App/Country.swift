// Internationalisation framework — Country → Region → volume/log standard.
//
// A country sits ABOVE the existing US `Region` map. It decides:
//   • whether board-foot log rules apply at all (US only — Scribner / Doyle /
//     International ¼″ are a North-American concept; metric countries never
//     show a log-rule picker),
//   • which cubic volume unit stand summaries + exports speak (US = cubic feet
//     with a board-foot overlay; everyone else = m³),
//   • whether the first-run cascade shows the 11-region US picker or skips
//     straight past it (metric countries use a single implicit region),
//   • the species preset set the scan-time pickers pre-load.
//
// LOCKED cross-platform design, shared with the Android sibling: the `raw`
// ids, the species codes, the volume-standard decisions and the `tc.country`
// storage key are identical byte-for-byte so backup/restore and CSV handoff
// line up across platforms. Storage defaults to `.unitedStates` so every
// pre-existing install keeps behaving exactly as it did before this landed.

import Foundation
import Sensors   // LogRule (for Region.defaultLogRule)

public enum Country: String, CaseIterable, Identifiable, Sendable {
    case unitedStates = "us"
    case finland      = "fi"
    case germany      = "de"
    case southKorea   = "kr"

    public var id: String { rawValue }

    public static let `default`: Country = .unitedStates

    /// Tolerant decode used by AppSettings (nil / unknown → default).
    public static func fromRaw(_ raw: String?) -> Country {
        raw.flatMap(Country.init(rawValue:)) ?? .default
    }

    public var displayName: String {
        switch self {
        case .unitedStates: return "United States"
        case .finland:      return "Finland"
        case .germany:      return "Germany"
        case .southKorea:   return "South Korea"
        }
    }

    /// Flag glyph for the cascade rows (iOS presentation only).
    public var flag: String {
        switch self {
        case .unitedStates: return "🇺🇸"
        case .finland:      return "🇫🇮"
        case .germany:      return "🇩🇪"
        case .southKorea:   return "🇰🇷"
        }
    }

    public var subtitle: String {
        switch self {
        case .unitedStates: return "11 timber regions · board-foot log rules"
        case .finland:      return "Nordic · Laasasenaho stem volume (m³)"
        case .germany:      return "Central Europe · form-factor volume (m³)"
        case .southKorea:   return "NIFoS metric (m³) · coefficients pending"
        }
    }

    // MARK: - Volume / log-rule policy

    /// Board-foot log rules are North-America-only. TRUE for the US alone; the
    /// Settings log-rule picker is hidden for every metric country.
    public var usesLogRule: Bool { self == .unitedStates }

    /// Cubic unit the country's volume is expressed in. The US keeps cubic feet
    /// (with the board-foot log-rule overlay on top); metric countries use m³.
    /// The engine always computes m³ internally — this governs display/export.
    public var volumeUnit: VolumeUnit {
        self == .unitedStates ? .cubicFeet : .cubicMeters
    }

    /// The stem-volume standard backing this country (drives the read-only
    /// Settings "Volume standard" row + the pending state for Korea).
    public var volumeStandard: VolumeStandard {
        switch self {
        case .unitedStates: return .usLogRule
        case .finland:      return .laasasenaho1982
        case .germany:      return .germanFormFactor
        case .southKorea:   return .koreaNIFoSPending
        }
    }

    // MARK: - Regions

    /// The US owns the 11-region timber map; metric countries use a single
    /// implicit region, so the cascade skips the region step for them.
    public var regions: [Region] {
        self == .unitedStates ? Region.allCases : []
    }

    public var hasRegions: Bool { !regions.isEmpty }

    // MARK: - Species presets

    /// Species pre-loaded into scan-time pickers. The US delegates to the
    /// existing per-region FIA presets; metric countries carry a flat national
    /// list. `region` is only consulted for the US.
    public func speciesPresets(region: Region?) -> [(String, String)] {
        switch self {
        case .unitedStates: return RegionalSpecies.defaultSpecies(for: region ?? .all)
        case .finland:      return Country.finlandSpecies
        case .germany:      return Country.germanySpecies
        case .southKorea:   return Country.koreaSpecies
        }
    }

    // Metric species codes are COUNTRY-PREFIXED so they never collide with the
    // 2-letter US FIA codes and read back unambiguously across platforms. These
    // lists match the Android sibling's CountrySpecies byte-for-byte.

    // Finnish set — bound (via the seeded VolumeEquation records) onto the
    // Laasasenaho pine / spruce / birch volume functions.
    static let finlandSpecies: [(String, String)] = [
        ("FI-PISY", "Scots pine"),
        ("FI-PIAB", "Norway spruce"),
        ("FI-BEPE", "Silver birch"),
        ("FI-BEPU", "Downy birch"),
    ]

    // German set — backed by the generic form-factor volume.
    static let germanySpecies: [(String, String)] = [
        ("DE-PIAB", "Norway spruce (Fichte)"),
        ("DE-PISY", "Scots pine (Kiefer)"),
        ("DE-FASY", "European beech (Buche)"),
        ("DE-QURO", "Pedunculate oak (Eiche)"),
    ]

    // Korean set — SCAFFOLD ONLY. Volume is pending official NIFoS
    // coefficients; the species are real so tally / DBH / height / BA / TPA all
    // work today.
    static let koreaSpecies: [(String, String)] = [
        ("KR-PIDE", "소나무 (Pinus densiflora)"),
        ("KR-PIKO", "잣나무 (Pinus koraiensis)"),
        ("KR-LAKA", "낙엽송 (Larix kaempferi)"),
        ("KR-PIRI", "리기다소나무 (Pinus rigida)"),
        ("KR-CRJA", "삼나무 (Cryptomeria japonica)"),
        ("KR-CHOB", "편백 (Chamaecyparis obtusa)"),
        ("KR-QUSP", "참나무류 (Quercus spp.)"),
        ("KR-ROPS", "아까시 (Robinia pseudoacacia)"),
    ]

    /// Every non-US preset, folded into the display-name lookup so a code
    /// tallied under a metric country still resolves to its name anywhere.
    static var allNonUSSpecies: [(String, String)] {
        finlandSpecies + germanySpecies + koreaSpecies
    }
}

// MARK: - US default log rule (derived from the picked region)

public extension Region {
    /// US convention: the Eastern US is Doyle country; the West defaults to
    /// Scribner (the USFS Western standard). The first-run cascade and Settings
    /// both derive the default log rule from the region through this.
    var defaultLogRule: LogRule {
        switch self {
        case .coastalPlain, .piedmont, .appalachian, .bottomland:
            return .doyle
        default:
            return .scribner
        }
    }
}

// MARK: - Volume unit

/// The cubic unit a country expresses stem volume in. Display/export only —
/// the engine always computes and stores m³ internally.
public enum VolumeUnit: String, Sendable {
    case cubicFeet     // US (board-foot log scaling rides on top of this)
    case cubicMeters   // metric countries

    public var abbreviation: String {
        self == .cubicMeters ? "m³" : "ft³"
    }

    public var perAcreLabel: String {
        self == .cubicMeters ? "m³/ac" : "ft³/ac"
    }
}

// MARK: - Volume standard

/// The stem-volume standard behind a country. Descriptive — the numbers live
/// in the InventoryEngine equations; this drives the Settings read-only row and
/// the "pending" state for Korea.
public enum VolumeStandard: String, Sendable {
    case usLogRule          // US: cubic + board-foot log rules
    case laasasenaho1982    // Finland: Laasasenaho (1982) total stem volume
    case germanFormFactor   // Germany: V = g·h·f form-factor approximation
    case koreaNIFoSPending  // Korea: NIFoS table, coefficients pending

    /// Read-only label shown in Settings → Country & region. Matches Android.
    public var displayName: String {
        switch self {
        case .usLogRule:         return "Board-foot log rule (Scribner / Doyle / Int'l ¼″)"
        case .laasasenaho1982:   return "Laasasenaho (1982) · m³"
        case .germanFormFactor:  return "Form factor  V = g·h·f · m³ (approx.)"
        case .koreaNIFoSPending: return "NIFoS national table — coefficients pending"
        }
    }

    /// TRUE while a country is selectable but cannot yet compute volume (Korea).
    /// Stand-level volume renders "—" rather than a fabricated number.
    public var isPending: Bool { self == .koreaNIFoSPending }
}
