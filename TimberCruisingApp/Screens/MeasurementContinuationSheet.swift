// Post-measurement continuation prompt — what the cruiser sees after
// they hit Accept on a Quick Measure scan, before the cover dismisses
// them back to the hub.
//
// Cruiser feedback: "측정 이후에 워크플로우로 자연스럽게 넘어가게
// 해야지. dbh 재고 결과 나오고 땡이 아니라". Right — the natural
// continuation after a DBH on tree #7 is height on tree #7, then
// tree #8's DBH, etc. Dumping back to home meant 2 extra taps for
// each pivot. This sheet keeps the loop tight.
//
// Two presentation contexts:
//
//   • From the DBH cover's onAccept:
//       — "Measure height (tree #N)"  → Height scan, same tree
//       — "Next tree"                 → DBH scan, auto-incremented
//       — "Done"                      → back to hub
//
//   • From the Height cover's onAccept:
//       — "Next tree"                 → DBH scan, auto-incremented
//       — "Done"                      → back to hub
//
// Hidden when the cruiser already has BOTH dbh + height on that
// tree — at that point only "Next tree" / "Done" make sense.

import SwiftUI

public struct MeasurementContinuationSheet: View {

    public enum NextAction {
        case measureHeightSameTree
        case startNewTreeDiameter
        case done
    }

    public enum Origin {
        case afterDiameter
        case afterHeight

        var headlineSubject: String {
            switch self {
            case .afterDiameter: return "Diameter saved"
            case .afterHeight:   return "Height saved"
            }
        }
    }

    let origin: Origin
    let treeNumber: Int
    /// True when this tree already has a height reading (so we don't
    /// offer "Measure height" again for the same stem).
    let treeAlreadyHasHeight: Bool
    let onPick: (NextAction) -> Void

    @Environment(\.dismiss) private var dismiss

    public init(origin: Origin,
                treeNumber: Int,
                treeAlreadyHasHeight: Bool,
                onPick: @escaping (NextAction) -> Void) {
        self.origin = origin
        self.treeNumber = treeNumber
        self.treeAlreadyHasHeight = treeAlreadyHasHeight
        self.onPick = onPick
    }

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: ForestixSpace.md) {
                    masthead
                    actions
                }
                .padding(.horizontal, ForestixSpace.md)
                .padding(.top, ForestixSpace.sm)
                .padding(.bottom, ForestixSpace.xl)
            }
            .background(ForestixPalette.canvas.ignoresSafeArea())
            .navigationTitle("Tree #\(treeNumber)")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") {
                        onPick(.done)
                        dismiss()
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    // MARK: - Masthead

    private var masthead: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(origin.headlineSubject)
                .font(ForestixType.title)
                .foregroundStyle(ForestixPalette.textPrimary)
            Text("What's next on tree #\(treeNumber)?")
                .font(ForestixType.body)
                .foregroundStyle(ForestixPalette.textSecondary)
        }
        .padding(.top, ForestixSpace.sm)
    }

    // MARK: - Actions

    private var actions: some View {
        VStack(spacing: ForestixSpace.sm) {
            // Same-tree height capture is the primary recommendation
            // after a DBH. Hidden when the tree already has height,
            // and not shown after a height scan.
            if origin == .afterDiameter, !treeAlreadyHasHeight {
                ActionCard(
                    title: "Measure height",
                    subtitle: "Stay on tree #\(treeNumber)",
                    systemImage: "arrow.up.and.down",
                    style: .primary
                ) {
                    onPick(.measureHeightSameTree)
                    dismiss()
                }
            }

            ActionCard(
                title: "Next tree",
                subtitle: "Start tree #\(treeNumber + 1) with a fresh diameter scan",
                systemImage: "plus.circle",
                style: origin == .afterHeight || treeAlreadyHasHeight
                    ? .primary : .secondary
            ) {
                onPick(.startNewTreeDiameter)
                dismiss()
            }

            ActionCard(
                title: "Back to hub",
                subtitle: "Stop measuring for now",
                systemImage: "house",
                style: .tertiary
            ) {
                onPick(.done)
                dismiss()
            }
        }
    }
}

// MARK: - Action card

private struct ActionCard: View {

    enum Style { case primary, secondary, tertiary }

    let title: String
    let subtitle: String
    let systemImage: String
    let style: Style
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: ForestixSpace.md) {
                ZStack {
                    RoundedRectangle(cornerRadius: ForestixRadius.control,
                                     style: .continuous)
                        .fill(glyphBackground)
                        .frame(width: 44, height: 44)
                    Image(systemName: systemImage)
                        .font(.system(size: 18, weight: .medium))
                        .foregroundStyle(glyphForeground)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(ForestixType.bodyBold)
                        .foregroundStyle(titleColor)
                    Text(subtitle)
                        .font(ForestixType.caption)
                        .foregroundStyle(subtitleColor)
                        .lineLimit(2)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.forward")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(chevronColor)
            }
            .padding(ForestixSpace.md)
            .frame(maxWidth: .infinity)
            .background(cardBackground)
            .overlay(cardBorder)
        }
        .buttonStyle(.plain)
    }

    private var cardBackground: some View {
        RoundedRectangle(cornerRadius: ForestixRadius.card, style: .continuous)
            .fill(style == .primary
                  ? ForestixPalette.primary.opacity(0.10)
                  : ForestixPalette.surface)
    }

    private var cardBorder: some View {
        RoundedRectangle(cornerRadius: ForestixRadius.card, style: .continuous)
            .stroke(style == .primary
                    ? ForestixPalette.primary.opacity(0.35)
                    : ForestixPalette.divider,
                    lineWidth: style == .primary ? 1.0 : 0.5)
    }

    private var glyphBackground: Color {
        style == .primary
            ? ForestixPalette.primary
            : ForestixPalette.primaryMuted
    }

    private var glyphForeground: Color {
        style == .primary ? Color.white : ForestixPalette.primary
    }

    private var titleColor: Color {
        ForestixPalette.textPrimary
    }

    private var subtitleColor: Color {
        ForestixPalette.textSecondary
    }

    private var chevronColor: Color {
        style == .tertiary
            ? ForestixPalette.textTertiary
            : ForestixPalette.textSecondary
    }
}
