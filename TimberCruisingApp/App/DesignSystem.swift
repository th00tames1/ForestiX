// Forestix design system — single source of truth for palette,
// typography, spacing, and shape tokens.
//
// Design philosophy: this is a professional instrument, not a
// consumer app. Restrained palette (one primary + neutrals + muted
// tier colours), typographic hierarchy, and data-forward layouts.
// No saturated gradients, no decorative flourishes, no traffic-light
// red/yellow/green. Cruisers are looking at measurements, not at the
// app itself.
//
// Usage: every screen pulls colours and type via `ForestixTheme` so
// tweaking a token updates the whole app. Screens should not hard-
// code `Color.green` / `.blue` / `.orange` — they should ask for a
// semantic token like `.confidenceOk` or `.surfaceRaised`.

import SwiftUI

// MARK: - Palette

public enum ForestixPalette {

    /// Primary brand — deep forest green. Used sparingly: app wordmark,
    /// key accents, focused action states. Desaturated on purpose so
    /// the UI doesn't read as "Saint Patrick's Day".
    public static let primary        = Color(red: 0.176, green: 0.373, blue: 0.290)
    public static let primaryMuted   = Color(red: 0.176, green: 0.373, blue: 0.290).opacity(0.15)

    /// Neutral earth accent — birch-bark beige. Used for secondary
    /// highlights and tier-neutral elements.
    public static let accent         = Color(red: 0.788, green: 0.655, blue: 0.420)

    /// Confidence tier colours (spec §7.9 green/yellow/red). Muted so
    /// they read as status indicators, not urgency alarms.
    public static let confidenceOk   = Color(red: 0.290, green: 0.541, blue: 0.361)
    public static let confidenceWarn = Color(red: 0.722, green: 0.537, blue: 0.290)
    public static let confidenceBad  = Color(red: 0.690, green: 0.337, blue: 0.337)

    /// Background / surface layers. Tied to system semantic colours
    /// so dark mode just works.
    #if os(iOS)
    public static let canvas         = Color(uiColor: .systemBackground)
    public static let surface        = Color(uiColor: .secondarySystemBackground)
    public static let surfaceRaised  = Color(uiColor: .tertiarySystemBackground)
    public static let divider        = Color(uiColor: .separator)
    #else
    public static let canvas         = Color(nsColor: .windowBackgroundColor)
    public static let surface        = Color.gray.opacity(0.10)
    public static let surfaceRaised  = Color.gray.opacity(0.15)
    public static let divider        = Color.gray.opacity(0.25)
    #endif

    /// Text hierarchy — primary / secondary / tertiary labels.
    #if os(iOS)
    public static let textPrimary    = Color(uiColor: .label)
    public static let textSecondary  = Color(uiColor: .secondaryLabel)
    public static let textTertiary   = Color(uiColor: .tertiaryLabel)
    #else
    public static let textPrimary    = Color(nsColor: .labelColor)
    public static let textSecondary  = Color(nsColor: .secondaryLabelColor)
    public static let textTertiary   = Color(nsColor: .tertiaryLabelColor)
    #endif
}

// MARK: - Typography

public enum ForestixType {

    /// Large section or screen title. Use sparingly (once per screen).
    public static let title       = Font.system(size: 28, weight: .semibold, design: .default)
    /// Section heading inside a screen.
    public static let sectionHead = Font.system(size: 13, weight: .semibold, design: .default)
        .lowercaseSmallCaps()
    /// Default body copy.
    public static let body        = Font.system(size: 15, weight: .regular, design: .default)
    /// Body emphasis — short inline highlights.
    public static let bodyBold    = Font.system(size: 15, weight: .semibold, design: .default)
    /// Secondary body (captions, helper text).
    public static let caption     = Font.system(size: 12, weight: .regular, design: .default)
    /// Tabular numeric readouts (DBH, Height, dates). Monospaced so
    /// columns line up like a measurement log.
    public static let dataLarge   = Font.system(size: 22, weight: .semibold, design: .monospaced)
    public static let data        = Font.system(size: 15, weight: .medium, design: .monospaced)
    public static let dataSmall   = Font.system(size: 12, weight: .medium, design: .monospaced)
}

// MARK: - Spacing

public enum ForestixSpace {
    /// 4 pt — hairline gaps between lines of a single block.
    public static let xxs: CGFloat = 4
    /// 8 pt — spacing inside compact controls (chip padding).
    public static let xs:  CGFloat = 8
    /// 12 pt — default internal padding for rows.
    public static let sm:  CGFloat = 12
    /// 16 pt — default horizontal inset and section internal gap.
    public static let md:  CGFloat = 16
    /// 24 pt — gap between sections on a screen.
    public static let lg:  CGFloat = 24
    /// 32 pt — top padding under a large screen title.
    public static let xl:  CGFloat = 32
}

// MARK: - Shape

public enum ForestixRadius {
    public static let chip: CGFloat    = 6
    public static let control: CGFloat = 10
    public static let card: CGFloat    = 12
}

// MARK: - View helpers

public extension View {

    /// Standard panel — surface layer with the canonical card radius.
    func forestixPanel(raised: Bool = false) -> some View {
        let fill = raised ? ForestixPalette.surfaceRaised : ForestixPalette.surface
        return self.background(
            RoundedRectangle(cornerRadius: ForestixRadius.card,
                             style: .continuous)
                .fill(fill)
        )
    }

    /// Hairline divider below the view (respects system separator).
    func forestixBottomDivider() -> some View {
        self.overlay(alignment: .bottom) {
            Rectangle()
                .fill(ForestixPalette.divider)
                .frame(height: 0.5)
        }
    }
}

// MARK: - Confidence tier helpers

public enum ConfidenceStyle {

    public struct Descriptor {
        public let label: String
        public let color: Color
    }

    /// Translates spec §7.9 raw tier strings into a cruiser-friendly
    /// descriptor. The returned colour is from the design palette, so
    /// every confidence-adjacent UI surface renders the same hue.
    public static func descriptor(for rawTier: String) -> Descriptor {
        // Parallel construction — all adjectives, all short, all fit
        // the same chip width. "Usable" vs "Low quality" mixed an
        // adjective with a noun phrase and wrapped awkwardly in the
        // FIELD LOG's narrow QUALITY column.
        switch rawTier {
        case "green":  return Descriptor(label: "Good",  color: ForestixPalette.confidenceOk)
        case "yellow": return Descriptor(label: "Fair",  color: ForestixPalette.confidenceWarn)
        case "red":    return Descriptor(label: "Check", color: ForestixPalette.confidenceBad)
        default:       return Descriptor(label: rawTier.capitalized,
                                          color: ForestixPalette.textSecondary)
        }
    }
}
