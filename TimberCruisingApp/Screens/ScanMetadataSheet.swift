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

/// The label on the scan screens' details chip — what has been attached so
/// far, or the invitation when nothing has.
///
/// FIELD REPORT 7 — this used to be a private computed property on the
/// height screen, which is part of why only the height screen offered the
/// chip at all. Both scans call this now, so the two cannot drift.
public enum ScanMetadataChip {

    public static func label(speciesCode: String?,
                             position: QuickMeasureEntry.StemPosition? = nil,
                             damageCodes: [String] = [],
                             note: String = "") -> String {
        var bits: [String] = []
        if let s = speciesCode, !s.isEmpty {
            bits.append(RegionalSpecies.name(forCode: s))
        }
        // `.dbh` is the default the diameter scan starts on, so it is not
        // something the cruiser attached — only a deliberate move off it
        // (butt, upper stem, stump) is worth a word here.
        if let p = position, p != .dbh {
            bits.append(p.displayName)
        }
        if !damageCodes.isEmpty {
            bits.append(damageCodes.count == 1
                        ? "1 tag" : "\(damageCodes.count) tags")
        }
        // A note used to leave the chip reading "Add details", which told
        // the cruiser their typing had gone nowhere.
        if !note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            bits.append("note")
        }
        return bits.isEmpty ? "Add details" : bits.joined(separator: " · ")
    }
}

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
            SpeciesPickerField(speciesCode: $speciesCode)
        }
    }

    // MARK: - Position

    /// Breast height in the cruiser's own unit.
    ///
    /// The two are the SAME HEIGHT, not a conversion of one another: 4.5 ft is
    /// the US definition and 1.37 m is that height written in metres. Both are
    /// spelled out rather than computed, because a formatter would render the
    /// imperial side as "4.49 ft" and invite the reader to think the app had
    /// rounded a metric standard into an imperial one.
    private var breastHeightWord: String {
        settings.unitSystem == .metric ? "1.37 m" : "4.5 ft"
    }

    private var positionSection: some View {
        Section(
            header: Text("POSITION").font(ForestixType.sectionHead),
            footer: Text("Default DBH = \(breastHeightWord). Mark butt / upper / stump if you measured elsewhere.")
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

// MARK: - Species picker

/// THE species control. Both places a cruiser picks a species go through this
/// one view — the reading-details sheet and the measure chooser — so the list,
/// the ordering and the typed-code escape cannot drift apart.
///
/// The regional presets are a convenience, not a boundary: a cruiser standing
/// in front of a tree the preset does not carry can type its code rather than
/// filing it under "Other" and losing which species it actually was.
///
/// `compact` is the pill the measure chooser wants, where the control sits
/// beside a text field with no section header to name it; the sheet's row
/// spans its section and says "— Unspecified —" when empty.
///
/// `provisional` says the code showing was filled in by the app, not chosen by
/// the cruiser: it is drawn in the same dim tertiary the empty control uses, so
/// a species the app guessed never looks like one somebody confirmed. `onPick`
/// fires on EVERY selection — including re-picking the code already showing,
/// which is exactly how a cruiser confirms a guess — so the host cannot use a
/// value change to decide the control was touched.
public struct SpeciesPickerField: View {

    @EnvironmentObject private var settings: AppSettings
    @Binding public var speciesCode: String?
    public var unspecifiedLabel: String = "— Unspecified —"
    public var compact: Bool = false
    public var provisional: Bool = false
    public var onPick: (() -> Void)?

    @State private var promptingForCode = false
    @State private var typedCode = ""

    public init(speciesCode: Binding<String?>,
                unspecifiedLabel: String = "— Unspecified —",
                compact: Bool = false,
                provisional: Bool = false,
                onPick: (() -> Void)? = nil) {
        self._speciesCode = speciesCode
        self.unspecifiedLabel = unspecifiedLabel
        self.compact = compact
        self.provisional = provisional
        self.onPick = onPick
    }

    public var body: some View {
        Menu {
            Button("— Unspecified —") {
                speciesCode = nil
                onPick?()
            }
            ForEach(speciesOptions, id: \.0) { code, name in
                Button {
                    speciesCode = code
                    onPick?()
                } label: {
                    Text(Self.pickerLabel(name: name, code: code))
                }
            }
            Button("Type a code…") {
                typedCode = speciesCode ?? ""
                promptingForCode = true
            }
        } label: {
            HStack(spacing: 4) {
                Text(selectedLabel)
                    .foregroundStyle(speciesCode == nil || provisional
                                     ? ForestixPalette.textTertiary
                                     : ForestixPalette.textPrimary)
                    .lineLimit(1)
                if !compact { Spacer(minLength: 0) }
                Image(systemName: "chevron.up.chevron.down")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(ForestixPalette.textSecondary)
            }
            .padding(.horizontal, compact ? ForestixSpace.sm : 0)
            .padding(.vertical, compact ? 10 : 0)
            .background {
                if compact {
                    RoundedRectangle(cornerRadius: ForestixRadius.control,
                                     style: .continuous)
                        .stroke(ForestixPalette.divider, lineWidth: 1)
                }
            }
        }
        .accessibilityIdentifier("speciesPicker")
        .alert("Species code", isPresented: $promptingForCode) {
            TextField("Code not in the list", text: $typedCode)
            Button("Cancel", role: .cancel) {}
            Button("Use") {
                // An empty box means "no species", not a species whose code
                // is the empty string.
                let trimmed = typedCode
                    .trimmingCharacters(in: .whitespaces).uppercased()
                speciesCode = trimmed.isEmpty ? nil : trimmed
                onPick?()
            }
        }
    }

    private var selectedLabel: AttributedString {
        guard let code = speciesCode else {
            return AttributedString(unspecifiedLabel)
        }
        guard let match = speciesOptions.first(where: { $0.0 == code }) else {
            // A code the list does not carry is shown as typed, not silently
            // relabelled to something the cruiser did not choose.
            return AttributedString(code)
        }
        return Self.pickerLabel(name: match.1, code: match.0)
    }

    /// The active region's curated list plus the permanent "OT · Other"
    /// escape — cruisers occasionally measure non-regional trees. The US
    /// resolves its per-region FIA presets; metric countries carry a flat
    /// national list.
    private var speciesOptions: [(String, String)] {
        settings.country.speciesPresets(region: settings.region) + [("OT", "Other")]
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
