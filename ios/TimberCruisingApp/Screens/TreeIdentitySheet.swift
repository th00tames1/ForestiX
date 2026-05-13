// Pre-scan tree identity picker.
//
// Cruiser feedback: "DBH랑 Height를 따로 재면 같은 나무인지 다른
// 나무인지 모르잖아? 재기 전에 미리 tree를 추가하는지, 기존에 있는
// tree에서 정보를 업데이트 하는지 선택하게 해야 할 듯".
//
// This sheet appears between the cruiser tapping a measurement card
// (Diameter / Height) on the Quick Measure home and the actual scan
// cover opening. It offers three choices:
//
//   1. Continue last tree (#N) — one-tap quick path, the most common
//      case when the cruiser is doing DBH then Height back-to-back
//      on the same stem.
//   2. New tree (#N+1) — auto-incremented, also one-tap.
//   3. Pick from existing — opens an inline picker listing every
//      distinct tree number with the readings already attached.
//
// The chosen tree number is passed back via `onPick` and ends up on
// the QuickMeasureEntry that the scan cover writes into history.

import SwiftUI

public struct TreeIdentitySheet: View {

    /// What the sheet is gating: a Diameter scan, a Height scan, or
    /// (later) something else. Shown in the header copy so the
    /// cruiser knows what they're about to start.
    public enum ScanKind {
        case diameter
        case height

        var title: String {
            switch self {
            case .diameter: return "Diameter scan"
            case .height:   return "Height scan"
            }
        }

        var subtitle: String {
            switch self {
            case .diameter: return "Pick which tree this reading is for."
            case .height:   return "Pick which tree this reading is for."
            }
        }
    }

    let scanKind: ScanKind
    let history: QuickMeasureHistory
    let onPick: (Int) -> Void
    let onCancel: () -> Void

    @State private var showAllTrees = false
    @Environment(\.dismiss) private var dismiss

    public init(scanKind: ScanKind,
                history: QuickMeasureHistory,
                onPick: @escaping (Int) -> Void,
                onCancel: @escaping () -> Void) {
        self.scanKind = scanKind
        self.history = history
        self.onPick = onPick
        self.onCancel = onCancel
    }

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: ForestixSpace.md) {
                    header
                    primaryChoices
                    if showAllTrees {
                        allTreesSection
                    }
                }
                .padding(.horizontal, ForestixSpace.md)
                .padding(.top, ForestixSpace.sm)
                .padding(.bottom, ForestixSpace.xl)
            }
            .background(ForestixPalette.canvas.ignoresSafeArea())
            .navigationTitle(scanKind.title)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        onCancel()
                        dismiss()
                    }
                }
            }
        }
    }

    // MARK: - Header

    private var header: some View {
        Text(scanKind.subtitle)
            .font(ForestixType.body)
            .foregroundStyle(ForestixPalette.textSecondary)
            .padding(.top, ForestixSpace.xs)
    }

    // MARK: - Primary choices

    /// Two-row stack: continue last tree, or start a new one. These
    /// are the two paths a cruiser hits 95 % of the time. A third
    /// "Pick from existing" row expands a list when tapped.
    private var primaryChoices: some View {
        VStack(spacing: ForestixSpace.sm) {
            if let last = history.lastTreeNumber {
                ChoiceRow(
                    title: "Continue tree #\(last)",
                    subtitle: history.summary(forTreeNumber: last)
                        ?? "Add another reading to this tree",
                    systemImage: "arrow.triangle.2.circlepath",
                    accent: ForestixPalette.primary,
                    accessibilityId: "treeIdentity.continueLast"
                ) {
                    onPick(last)
                    dismiss()
                }
            }
            ChoiceRow(
                title: "New tree #\(history.suggestedNextTreeNumber)",
                subtitle: "Start a fresh tree on this reading",
                systemImage: "plus.circle",
                accent: ForestixPalette.primary,
                accessibilityId: "treeIdentity.newTree"
            ) {
                onPick(history.suggestedNextTreeNumber)
                dismiss()
            }
            if !history.distinctTreeNumbers.isEmpty {
                Button {
                    showAllTrees.toggle()
                } label: {
                    HStack {
                        Text(showAllTrees
                             ? "Hide all trees"
                             : "Pick from existing trees")
                            .font(ForestixType.bodyBold)
                            .foregroundStyle(ForestixPalette.primary)
                        Spacer()
                        Image(systemName: showAllTrees
                              ? "chevron.up"
                              : "chevron.down")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(ForestixPalette.primary)
                    }
                    .padding(.horizontal, ForestixSpace.md)
                    .padding(.vertical, ForestixSpace.sm)
                    .frame(maxWidth: .infinity)
                    .background(
                        RoundedRectangle(cornerRadius: ForestixRadius.card,
                                         style: .continuous)
                            .stroke(ForestixPalette.primary.opacity(0.4),
                                    lineWidth: 0.75))
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("treeIdentity.pickExisting")
            }
        }
    }

    // MARK: - All trees expanded list

    private var allTreesSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            sectionHeader("ALL TREES")
            VStack(spacing: 0) {
                ForEach(history.distinctTreeNumbers, id: \.self) { n in
                    Button {
                        onPick(n)
                        dismiss()
                    } label: {
                        HStack(spacing: ForestixSpace.sm) {
                            Text("#\(n)")
                                .font(ForestixType.dataSmall)
                                .foregroundStyle(ForestixPalette.textPrimary)
                                .frame(width: 44, alignment: .leading)
                            Text(history.summary(forTreeNumber: n) ?? "—")
                                .font(ForestixType.caption)
                                .foregroundStyle(ForestixPalette.textSecondary)
                            Spacer()
                            Image(systemName: "chevron.forward")
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundStyle(ForestixPalette.textTertiary)
                        }
                        .padding(.horizontal, ForestixSpace.md)
                        .padding(.vertical, ForestixSpace.sm)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    if n != history.distinctTreeNumbers.last {
                        Rectangle()
                            .fill(ForestixPalette.divider)
                            .frame(height: 0.5)
                            .padding(.leading, ForestixSpace.md + 44 + ForestixSpace.sm)
                    }
                }
            }
            .background(
                RoundedRectangle(cornerRadius: ForestixRadius.card,
                                 style: .continuous)
                    .fill(ForestixPalette.surface))
        }
    }

    private func sectionHeader(_ text: String) -> some View {
        Text(text)
            .font(ForestixType.sectionHead)
            .tracking(1.2)
            .foregroundStyle(ForestixPalette.textTertiary)
            .padding(.bottom, ForestixSpace.xs)
            .padding(.leading, ForestixSpace.xs)
    }
}

// MARK: - Choice row component

private struct ChoiceRow: View {
    let title: String
    let subtitle: String
    let systemImage: String
    let accent: Color
    let accessibilityId: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: ForestixSpace.md) {
                ZStack {
                    RoundedRectangle(cornerRadius: ForestixRadius.control,
                                     style: .continuous)
                        .fill(accent.opacity(0.15))
                        .frame(width: 40, height: 40)
                    Image(systemName: systemImage)
                        .font(.system(size: 17, weight: .medium))
                        .foregroundStyle(accent)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(ForestixType.bodyBold)
                        .foregroundStyle(ForestixPalette.textPrimary)
                    Text(subtitle)
                        .font(ForestixType.caption)
                        .foregroundStyle(ForestixPalette.textSecondary)
                        .lineLimit(2)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.forward")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(ForestixPalette.textTertiary)
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
                    .stroke(ForestixPalette.divider, lineWidth: 0.5))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(accessibilityId)
    }
}
