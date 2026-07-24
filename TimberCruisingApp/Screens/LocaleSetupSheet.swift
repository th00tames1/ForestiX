// First-run locale cascade — replaces the US-only RegionPickerSheet.
//
//   Step 1  "Where are you cruising?"  → pick a Country.
//   Step 2  "Pick your region"         → US only; metric countries skip it.
//
// The volume standard and (US) default log rule are derived automatically:
//   • US West regions → Scribner, US East regions → Doyle (existing convention)
//   • metric countries → their m³ standard, no log rule.
//
// Dismissing at any point (Skip, a row pick, or a swipe-down) stamps
// `regionPickerSeen` — the presenting screen does this in its onDismiss — so
// the cascade never auto-nags again. Re-selectable any time from
// Settings → Country & region.

import SwiftUI

public struct LocaleSetupSheet: View {

    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss
    @State private var path = NavigationPath()

    public init() {}

    public var body: some View {
        NavigationStack(path: $path) {
            countryStep
                .navigationDestination(for: Country.self) { country in
                    regionStep(for: country)
                }
        }
    }

    // MARK: - Step 1 · Country

    private var countryStep: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: ForestixSpace.lg) {
                intro(
                    title: "Where are you cruising?",
                    detail: "Sets your units and volume standard. Change it any time in Settings.")
                VStack(spacing: ForestixSpace.sm) {
                    ForEach(Country.allCases) { country in
                        LocaleRow(
                            glyph: country.flag,
                            title: country.displayName,
                            subtitle: country.subtitle,
                            isSelected: settings.country == country,
                            showsChevron: country.hasRegions
                        ) {
                            choose(country)
                        }
                    }
                }
            }
            .padding(.horizontal, ForestixSpace.md)
            .padding(.top, ForestixSpace.sm)
            .padding(.bottom, ForestixSpace.xl)
        }
        .background(ForestixPalette.canvas.ignoresSafeArea())
        .navigationTitle("Country")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Skip") {
                    settings.regionPickerSeen = true
                    dismiss()
                }
            }
        }
    }

    // MARK: - Step 2 · Region (US only)

    private func regionStep(for country: Country) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: ForestixSpace.lg) {
                intro(
                    title: "Pick your region",
                    detail: "Loads the right species list and log rule for your region. Change it any time in Settings.")
                VStack(spacing: ForestixSpace.sm) {
                    ForEach(country.regions) { region in
                        LocaleRow(
                            glyph: nil,
                            systemGlyph: regionGlyph(region),
                            title: region.displayName,
                            subtitle: region.subtitle,
                            isSelected: settings.region == region,
                            showsChevron: false
                        ) {
                            choose(region)
                        }
                    }
                }
            }
            .padding(.horizontal, ForestixSpace.md)
            .padding(.top, ForestixSpace.sm)
            .padding(.bottom, ForestixSpace.xl)
        }
        .background(ForestixPalette.canvas.ignoresSafeArea())
        .navigationTitle("Pick your region")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
    }

    // MARK: - Selection

    private func choose(_ country: Country) {
        settings.country = country
        // Selecting a country drives the unit system (US → imperial, metric
        // countries → metric). The manual Units toggle in Settings can still
        // override this afterwards.
        settings.unitSystem = country.defaultUnitSystem
        if country.hasRegions {
            path.append(country)          // → region step
        } else {
            settings.region = nil          // metric: single implicit region
            settings.regionPickerSeen = true
            dismiss()
        }
    }

    private func choose(_ region: Region) {
        settings.region = region
        settings.logRule = region.defaultLogRule   // West→Scribner, East→Doyle
        settings.regionPickerSeen = true
        dismiss()
    }

    // MARK: - Bits

    private func intro(title: String, detail: String) -> some View {
        VStack(alignment: .leading, spacing: ForestixSpace.xs) {
            Text(title)
                .font(ForestixType.bodyBold)
                .foregroundStyle(ForestixPalette.textPrimary)
            Text(detail)
                .font(ForestixType.caption)
                .foregroundStyle(ForestixPalette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func regionGlyph(_ region: Region) -> String {
        switch region {
        case .pnwWest, .pnwEast, .nRockies: return "tree"
        case .nSierra, .sSierra:            return "mountain.2"
        case .caCoast:                      return "tree"
        case .southwest:                    return "sun.max"
        case .coastalPlain, .bottomland:    return "drop"
        case .piedmont, .appalachian:       return "leaf"
        case .all:                          return "list.bullet"
        }
    }
}

// MARK: - Row

private struct LocaleRow: View {
    var glyph: String? = nil          // emoji flag (country rows)
    var systemGlyph: String? = nil    // SF Symbol (region rows)
    let title: String
    let subtitle: String
    let isSelected: Bool
    let showsChevron: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: ForestixSpace.md) {
                ZStack {
                    Circle()
                        .fill(ForestixPalette.primaryMuted)
                        .frame(width: 36, height: 36)
                    if let glyph {
                        Text(glyph).font(.system(size: 18))
                    } else if let systemGlyph {
                        Image(systemName: systemGlyph)
                            .font(.system(size: 15, weight: .medium))
                            .foregroundStyle(ForestixPalette.primary)
                    }
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(ForestixType.bodyBold)
                        .foregroundStyle(ForestixPalette.textPrimary)
                    Text(subtitle)
                        .font(ForestixType.caption)
                        .foregroundStyle(ForestixPalette.textSecondary)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
                if isSelected {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(ForestixPalette.primary)
                } else if showsChevron {
                    Image(systemName: "chevron.forward")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(ForestixPalette.textTertiary)
                }
            }
            .padding(ForestixSpace.md)
            .frame(maxWidth: .infinity)
            .background(
                RoundedRectangle(cornerRadius: ForestixRadius.card, style: .continuous)
                    .fill(ForestixPalette.surface))
            .overlay(
                RoundedRectangle(cornerRadius: ForestixRadius.card, style: .continuous)
                    .stroke(isSelected ? ForestixPalette.primary : ForestixPalette.divider,
                            lineWidth: isSelected ? 1.5 : 0.5))
        }
        .buttonStyle(.plain)
    }
}
