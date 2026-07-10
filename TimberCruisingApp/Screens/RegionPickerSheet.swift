// First-run region picker. Shown once after install (and re-openable
// from Settings) — picks one of 11 US timber regions and pre-loads
// the matching 7-9 FIA species codes into the species selection used
// across the app. Adopted from SilvaCruise's onboarding pattern.
//
// Dismissing without picking is allowed — the cruiser can pick later
// in Settings → Region. We mark `regionPickerSeen = true` either way
// so the sheet doesn't auto-present every launch.

import SwiftUI

public struct RegionPickerSheet: View {

    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss

    public init() {}

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: ForestixSpace.lg) {
                    intro
                    regionList
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
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Skip") {
                        settings.regionPickerSeen = true
                        dismiss()
                    }
                }
            }
        }
    }

    // MARK: - Intro

    private var intro: some View {
        VStack(alignment: .leading, spacing: ForestixSpace.xs) {
            Text("Which forest do you cruise?")
                .font(ForestixType.bodyBold)
                .foregroundStyle(ForestixPalette.textPrimary)
            Text("Pre-loads the right species for your region. You can change this any time in Settings.")
                .font(ForestixType.caption)
                .foregroundStyle(ForestixPalette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    // MARK: - Region list

    private var regionList: some View {
        VStack(spacing: ForestixSpace.sm) {
            ForEach(Region.allCases) { r in
                RegionRow(region: r,
                          isSelected: settings.region == r) {
                    settings.region = r
                    settings.regionPickerSeen = true
                    dismiss()
                }
            }
        }
    }
}

// MARK: - Row

private struct RegionRow: View {
    let region: Region
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: ForestixSpace.md) {
                ZStack {
                    Circle()
                        .fill(ForestixPalette.primaryMuted)
                        .frame(width: 36, height: 36)
                    Image(systemName: glyph)
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(ForestixPalette.primary)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(region.displayName)
                        .font(ForestixType.bodyBold)
                        .foregroundStyle(ForestixPalette.textPrimary)
                    Text(region.subtitle)
                        .font(ForestixType.caption)
                        .foregroundStyle(ForestixPalette.textSecondary)
                        .lineLimit(2)
                }
                Spacer(minLength: 0)
                if isSelected {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(ForestixPalette.primary)
                } else {
                    Image(systemName: "chevron.forward")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(ForestixPalette.textTertiary)
                }
            }
            .padding(ForestixSpace.md)
            .frame(maxWidth: .infinity)
            .background(
                RoundedRectangle(cornerRadius: ForestixRadius.card,
                                 style: .continuous)
                    .fill(ForestixPalette.surface))
            .overlay(
                RoundedRectangle(cornerRadius: ForestixRadius.card,
                                 style: .continuous)
                    .stroke(isSelected
                            ? ForestixPalette.primary
                            : ForestixPalette.divider,
                            lineWidth: isSelected ? 1.5 : 0.5))
        }
        .buttonStyle(.plain)
    }

    private var glyph: String {
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
