// Post-scan metadata sheet — attaches species + position + damage
// + note to a freshly-fitted scan before the cruiser hits Accept.
//
// Pragmatic compromise: the ideal in-scene UX is to long-press
// the world-anchored AR cylinder/sphere to edit, keeping the cruiser
// in the AR scene. That's a significant refactor of ARSceneMarker
// gesture handling. This sheet is the 70 % solution — reachable from
// a single "Edit details" pill on the result panel, doesn't leave
// the scan cover, and the bound values flow into the
// QuickMeasureEntry written on Accept.
//
// Long-press AR editing remains on the roadmap; this sheet keeps
// the data model and CSV export honest in the meantime.

import SwiftUI
import Models

public struct ScanMetadataSheet: View {

    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss

    /// What scan kind we're attaching metadata to.
    public enum Kind { case diameter, height }
    public let kind: Kind

    @Binding public var speciesCode: String?
    @Binding public var position: QuickMeasureEntry.StemPosition?
    @Binding public var damageCodes: [String]
    @Binding public var note: String

    public init(kind: Kind,
                speciesCode: Binding<String?>,
                position: Binding<QuickMeasureEntry.StemPosition?>,
                damageCodes: Binding<[String]>,
                note: Binding<String>) {
        self.kind = kind
        self._speciesCode = speciesCode
        self._position = position
        self._damageCodes = damageCodes
        self._note = note
    }

    public var body: some View {
        NavigationStack {
            Form {
                speciesSection
                if kind == .diameter {
                    positionSection
                }
                damageSection
                noteSection
            }
            .navigationTitle("Reading details")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    // MARK: - Species

    private var speciesSection: some View {
        Section(header: Text("SPECIES").font(ForestixType.sectionHead)) {
            Picker("Species",
                   selection: Binding(
                    get: { speciesCode ?? "" },
                    set: { speciesCode = $0.isEmpty ? nil : $0 })
            ) {
                Text("— Unspecified —").tag("")
                ForEach(speciesOptions, id: \.0) { code, name in
                    Text(Self.pickerLabel(name: name, code: code)).tag(code)
                }
            }
        }
    }

    private var speciesOptions: [(String, String)] {
        // Country-scoped: the US resolves its per-region FIA presets; metric
        // countries carry a flat national list.
        let preset = settings.country.speciesPresets(region: settings.region)
        // Always allow "Other" rather than locking to the preset
        // list — cruisers occasionally measure non-preset trees.
        return preset + [("OT", "Other")]
    }

    /// Name-first option label: the common name reads prominently, the
    /// FIA code trails as a dim secondary suffix (" · DF"). Selecting the
    /// row still stores the code — this only reshapes what's shown.
    private static func pickerLabel(name: String, code: String) -> AttributedString {
        var label = AttributedString(name)
        var suffix = AttributedString(" · \(code)")
        suffix.foregroundColor = ForestixPalette.textSecondary
        suffix.font = .caption
        label.append(suffix)
        return label
    }

    // MARK: - Position

    private var positionSection: some View {
        Section(
            header: Text("POSITION").font(ForestixType.sectionHead),
            footer: Text("Default DBH = 1.3 m. Mark butt / upper / stump if you measured elsewhere.")
        ) {
            Picker("Where on the stem",
                   selection: Binding(
                    get: { position ?? .dbh },
                    set: { position = $0 })
            ) {
                ForEach(QuickMeasureEntry.StemPosition.allCases, id: \.self) { p in
                    Text(p.displayName).tag(p)
                }
            }
            .pickerStyle(.segmented)
        }
    }

    // MARK: - Damage

    /// Multi-select damage tags as a wrapping chip row — the one place
    /// chips are the control (single-choice pickers are segmented).
    /// Mirrors Android's FilterChip row: selected = primary-muted fill +
    /// primary border; unselected = surface + divider border.
    private var damageSection: some View {
        Section(
            header: Text("DAMAGE").font(ForestixType.sectionHead),
            footer: Text("Drives cull deductions in stand-and-stock reports.")
        ) {
            ChipWrapLayout(hSpacing: ForestixSpace.xs,
                           vSpacing: ForestixSpace.xs) {
                ForEach(damageOptions, id: \.self) { tag in
                    damageChip(tag)
                }
            }
            .padding(.vertical, ForestixSpace.xxs)
        }
    }

    private func damageChip(_ tag: String) -> some View {
        let selected = damageCodes.contains(tag)
        return Button {
            if selected {
                damageCodes.removeAll { $0 == tag }
            } else {
                damageCodes.append(tag)
            }
        } label: {
            Text(tag.capitalized)
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(ForestixPalette.textPrimary)
                .padding(.horizontal, ForestixSpace.sm)
                .frame(height: 32)
                .background(
                    RoundedRectangle(cornerRadius: ForestixRadius.control,
                                     style: .continuous)
                        .fill(selected
                              ? ForestixPalette.primaryMuted
                              : ForestixPalette.surface))
                .overlay(
                    RoundedRectangle(cornerRadius: ForestixRadius.control,
                                     style: .continuous)
                        .stroke(selected
                                ? ForestixPalette.primary
                                : ForestixPalette.divider,
                                lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private var damageOptions: [String] {
        ["sweep", "fork", "broken-top", "rot", "scar", "lean"]
    }

    // MARK: - Note

    private var noteSection: some View {
        Section(header: Text("NOTE").font(ForestixType.sectionHead)) {
            TextField("Free-text note (optional)",
                      text: $note, axis: .vertical)
                .lineLimit(2...4)
        }
    }
}

// MARK: - Wrapping chip layout

/// Minimal flow layout for the damage chips: children lay out
/// left-to-right at their ideal size and wrap to a new line when the
/// row is full — the same geometry as the Compose `FlowRow` hosting
/// Android's FilterChips.
private struct ChipWrapLayout: Layout {

    var hSpacing: CGFloat = 8
    var vSpacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize,
                      subviews: Subviews,
                      cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        let rows = computeRows(maxWidth: maxWidth, subviews: subviews)
        let height = rows.reduce(0) { $0 + $1.height }
            + vSpacing * CGFloat(max(0, rows.count - 1))
        let width = proposal.width ?? rows.map(\.width).max() ?? 0
        return CGSize(width: width, height: height)
    }

    func placeSubviews(in bounds: CGRect,
                       proposal: ProposedViewSize,
                       subviews: Subviews,
                       cache: inout ()) {
        var index = 0
        var y = bounds.minY
        for row in computeRows(maxWidth: bounds.width, subviews: subviews) {
            var x = bounds.minX
            for size in row.sizes {
                subviews[index].place(
                    at: CGPoint(x: x, y: y),
                    proposal: ProposedViewSize(size))
                x += size.width + hSpacing
                index += 1
            }
            y += row.height + vSpacing
        }
    }

    private struct Row {
        var sizes: [CGSize] = []
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    private func computeRows(maxWidth: CGFloat,
                             subviews: Subviews) -> [Row] {
        var rows: [Row] = []
        var current = Row()
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            let widthIfAppended = current.sizes.isEmpty
                ? size.width
                : current.width + hSpacing + size.width
            if widthIfAppended > maxWidth, !current.sizes.isEmpty {
                rows.append(current)
                current = Row()
                current.width = size.width
            } else {
                current.width = widthIfAppended
            }
            current.sizes.append(size)
            current.height = max(current.height, size.height)
        }
        if !current.sizes.isEmpty { rows.append(current) }
        return rows
    }
}
