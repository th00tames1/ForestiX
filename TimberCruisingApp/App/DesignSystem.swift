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
#if canImport(UIKit)
import UIKit
#endif

// MARK: - Palette

public enum ForestixPalette {

    /// Direction B — "Field High-Contrast", now in TWO appearances that
    /// share one identity (signal colours, big mono numerals, squarer
    /// shapes): a light "paper" variant (the default) and the dark slate
    /// variant for night / low-glare work. Tokens are trait-dynamic; the
    /// root drives the trait from AppSettings.appearance, so the whole
    /// app + system sheets flip together.
    ///
    /// Signal colours differ per appearance ON PURPOSE: the bright
    /// #55D07A/#FFB454 pair reads perfectly on dark slate but fails
    /// contrast on paper, so light mode deepens each hue to ≥4.5:1 for
    /// text-sized uses while keeping the same visual family.

    #if canImport(UIKit)
    private static func dyn(_ l: UIColor, _ d: UIColor) -> Color {
        Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? d : l })
    }
    private static func rgb(_ r: CGFloat, _ g: CGFloat, _ b: CGFloat, _ a: CGFloat = 1) -> UIColor {
        UIColor(red: r, green: g, blue: b, alpha: a)
    }

    /// Signal green — primary accents, readouts, buttons.
    public static let primary        = dyn(rgb(0.184, 0.643, 0.357),   // #2FA45B
                                           rgb(0.333, 0.816, 0.478))   // #55D07A
    /// Ink ON primary surfaces — dark in both modes (≥5.9:1 on either green).
    public static let primaryInk     = Color(red: 0.024, green: 0.075, blue: 0.039) // #06130A
    public static let primaryMuted   = dyn(rgb(0.184, 0.643, 0.357, 0.14),
                                           rgb(0.333, 0.816, 0.478, 0.16))

    public static let accent         = dyn(rgb(0.710, 0.463, 0.078),   // #B57614
                                           rgb(1.000, 0.706, 0.329))   // #FFB454

    public static let confidenceOk   = dyn(rgb(0.114, 0.478, 0.263),   // #1D7A43
                                           rgb(0.333, 0.816, 0.478))   // #55D07A
    public static let confidenceWarn = dyn(rgb(0.604, 0.392, 0.078),   // #9A6414
                                           rgb(1.000, 0.706, 0.329))   // #FFB454
    public static let confidenceBad  = dyn(rgb(0.690, 0.227, 0.180),   // #B03A2E
                                           rgb(1.000, 0.478, 0.420))   // #FF7A6B

    /// Surfaces — green-cast paper / green-cast slate.
    public static let canvas         = dyn(rgb(0.957, 0.965, 0.957),   // #F4F6F4
                                           rgb(0.047, 0.059, 0.063))   // #0C0F10
    public static let surface        = dyn(rgb(1.000, 1.000, 1.000),
                                           rgb(0.090, 0.106, 0.114))   // #171B1D
    public static let surfaceRaised  = dyn(rgb(0.914, 0.929, 0.914),   // #E9EDE9
                                           rgb(0.129, 0.153, 0.165))   // #21272A
    public static let divider        = dyn(rgb(0.827, 0.851, 0.827),   // #D3D9D3
                                           rgb(0.200, 0.231, 0.247))   // #333B3F

    public static let textPrimary    = dyn(rgb(0.090, 0.110, 0.098),   // #171C19
                                           rgb(0.949, 0.961, 0.953))   // #F2F5F3
    public static let textSecondary  = dyn(rgb(0.306, 0.345, 0.322),   // #4E5852
                                           rgb(0.718, 0.753, 0.729))   // #B7C0BA
    public static let textTertiary   = dyn(rgb(0.494, 0.529, 0.506),   // #7E8781
                                           rgb(0.475, 0.514, 0.490))   // #79837D
    #else
    // Non-UIKit test host — fixed light values.
    public static let primary        = Color(red: 0.184, green: 0.643, blue: 0.357)
    public static let primaryInk     = Color(red: 0.024, green: 0.075, blue: 0.039)
    public static let primaryMuted   = Color(red: 0.184, green: 0.643, blue: 0.357).opacity(0.14)
    public static let accent         = Color(red: 0.710, green: 0.463, blue: 0.078)
    public static let confidenceOk   = Color(red: 0.114, green: 0.478, blue: 0.263)
    public static let confidenceWarn = Color(red: 0.604, green: 0.392, blue: 0.078)
    public static let confidenceBad  = Color(red: 0.690, green: 0.227, blue: 0.180)
    public static let canvas         = Color(red: 0.957, green: 0.965, blue: 0.957)
    public static let surface        = Color.white
    public static let surfaceRaised  = Color(red: 0.914, green: 0.929, blue: 0.914)
    public static let divider        = Color(red: 0.827, green: 0.851, blue: 0.827)
    public static let textPrimary    = Color(red: 0.090, green: 0.110, blue: 0.098)
    public static let textSecondary  = Color(red: 0.306, green: 0.345, blue: 0.322)
    public static let textTertiary   = Color(red: 0.494, green: 0.529, blue: 0.506)
    #endif
}

// MARK: - Typography

public enum ForestixType {

    /// Large section or screen title. Use sparingly (once per screen).
    public static let title       = Font.system(size: 28, weight: .semibold, design: .default)
    /// Section heading inside a screen. Call sites pass ALL-CAPS strings
    /// — the documented contract on both platforms (no small caps).
    public static let sectionHead = Font.system(size: 13, weight: .semibold, design: .default)
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
