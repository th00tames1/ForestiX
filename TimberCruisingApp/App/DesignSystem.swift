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

    /// Direction B — "Field High-Contrast". Dark-only outdoor instrument:
    /// deep slate canvas, big mono numerals, high-chroma signal colours.
    /// Chosen over the previous adaptive light/dark forest palette because
    /// the app lives outdoors — sunlight glare + gloves. The app forces
    /// dark appearance at the root (ForestixApp), so these are fixed values.

    /// Signal green — primary accents, readouts, focus. High chroma is
    /// deliberate on the dark canvas; buttons pair it with `primaryInk`.
    public static let primary        = Color(red: 0.333, green: 0.816, blue: 0.478) // #55D07A
    /// Near-black ink used ON primary surfaces (buttons) — white on the
    /// bright signal green fails 4.5:1, dark ink passes at ~9:1.
    public static let primaryInk     = Color(red: 0.024, green: 0.075, blue: 0.039) // #06130A
    public static let primaryMuted   = Color(red: 0.333, green: 0.816, blue: 0.478).opacity(0.16)

    /// Amber signal — secondary accent + warnings.
    public static let accent         = Color(red: 1.000, green: 0.706, blue: 0.329) // #FFB454

    /// Confidence tiers — same hues as the signal set so the instrument
    /// reads as one system (always paired with a text label).
    public static let confidenceOk   = Color(red: 0.333, green: 0.816, blue: 0.478) // #55D07A
    public static let confidenceWarn = Color(red: 1.000, green: 0.706, blue: 0.329) // #FFB454
    public static let confidenceBad  = Color(red: 1.000, green: 0.478, blue: 0.420) // #FF7A6B

    /// Fixed dark surfaces (slate, slightly green-cast).
    public static let canvas         = Color(red: 0.047, green: 0.059, blue: 0.063) // #0C0F10
    public static let surface        = Color(red: 0.090, green: 0.106, blue: 0.114) // #171B1D
    public static let surfaceRaised  = Color(red: 0.129, green: 0.153, blue: 0.165) // #21272A
    public static let divider        = Color(red: 0.200, green: 0.231, blue: 0.247) // #333B3F

    /// Text ramp on the dark canvas (≥4.5:1 down to textSecondary).
    public static let textPrimary    = Color(red: 0.949, green: 0.961, blue: 0.953) // #F2F5F3
    public static let textSecondary  = Color(red: 0.718, green: 0.753, blue: 0.729) // #B7C0BA
    public static let textTertiary   = Color(red: 0.475, green: 0.514, blue: 0.490) // #79837D
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
    public static let dataLarge   = Font.system(size: 26, weight: .bold, design: .monospaced)
    public static let data        = Font.system(size: 17, weight: .medium, design: .monospaced)
    public static let dataSmall   = Font.system(size: 13, weight: .medium, design: .monospaced)
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
    public static let chip: CGFloat    = 5
    public static let control: CGFloat = 8
    public static let card: CGFloat    = 10
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

// MARK: - Primary button style (Direction B)

/// Replaces `.borderedProminent` app-wide: the bright signal green needs
/// DARK ink for contrast (white fails 4.5:1), which the built-in style
/// can't do globally. Full-width label so rows of buttons share width
/// exactly like the old prominent buttons did.
public struct ForestixProminentButtonStyle: ButtonStyle {
    public init() {}
    public func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(ForestixType.bodyBold)
            .foregroundStyle(ForestixPalette.primaryInk)
            .frame(maxWidth: .infinity, minHeight: 30)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(
                RoundedRectangle(cornerRadius: ForestixRadius.control, style: .continuous)
                    .fill(ForestixPalette.primary.opacity(configuration.isPressed ? 0.78 : 1))
            )
            .animation(.easeOut(duration: 0.15), value: configuration.isPressed)
    }
}

public extension ButtonStyle where Self == ForestixProminentButtonStyle {
    static var forestixProminent: ForestixProminentButtonStyle { ForestixProminentButtonStyle() }
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
