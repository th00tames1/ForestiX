// Field log — dedicated screen that owns the full measurement history.
//
// Moved out of the Quick Measure hub as part of the hub-and-spoke
// redesign: the home used to stack masthead + capacity warning +
// instrument panel + log table all on one screen ("때려박은 느낌"),
// which made the first impression feel like a dashboard rather than
// a tool. The hub now only routes; each spoke owns its own screen.
//
// This screen:
//   • Summary header — total count + readings-today + "last" timestamp
//   • Capacity banner — only when the log is near its cap
//   • Native iOS List — swipe-to-delete works, Dynamic Type respected,
//     VoiceOver row traversal is standard. (The old VStack-in-panel
//     version couldn't host `.swipeActions`.)
//   • Export CSV in the toolbar
//   • Empty state sized for the whole screen, not a slim card row
//
// The same `QuickMeasureEntry` / `QuickMeasureHistory` backing store
// powers the screen — no changes to the durability / schema layer.

import SwiftUI
import Models
import Sensors

public struct FieldLogScreen: View {

    @EnvironmentObject private var history: QuickMeasureHistory
    @EnvironmentObject private var settings: AppSettings
    @State private var shareURL: URL?

    public init() {}

    public var body: some View {
        Group {
            if history.entries.isEmpty {
                emptyState
            } else {
                populatedList
            }
        }
        .background(ForestixPalette.canvas.ignoresSafeArea())
        .navigationTitle("Field log")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            if !history.entries.isEmpty {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button {
                            shareURL = history.exportCSV()
                        } label: {
                            Label("CSV (single file)", systemImage: "doc.text")
                        }
                        Button {
                            shareURL = history.exportBundle(
                                logRule: settings.logRule)
                        } label: {
                            Label("Bundle (5-file zip)", systemImage: "doc.zipper")
                        }
                    } label: {
                        Label("Export", systemImage: "square.and.arrow.up")
                            .foregroundStyle(ForestixPalette.primary)
                    }
                    .accessibilityIdentifier("fieldLog.exportMenu")
                }
            }
        }
        #if os(iOS)
        .sheet(item: Binding(
            get: { shareURL.map(ShareWrapper.init) },
            set: { shareURL = $0?.url })
        ) { wrapper in
            FieldLogShareSheet(url: wrapper.url)
        }
        #endif
    }

    // MARK: - Populated list

    private var populatedList: some View {
        List {
            // Plot summary card — shows BA / TPA / QMD + stocking
            // gauge + species mix for the active plot. Hidden on
            // hosts with fewer than two readings (gauge needs data).
            if let plotID = history.activePlotID,
               let plot = history.plot(id: plotID) {
                let plotEntries = history.entries(forPlot: plotID)
                if plotEntries.count >= 1 {
                    Section {
                        PlotSummaryCard(
                            plot: plot,
                            entries: plotEntries,
                            unitSystem: settings.unitSystem,
                            logRule: settings.logRule,
                            areaUnit: settings.unitSystem.areaUnit)
                            .listRowInsets(EdgeInsets(
                                top: ForestixSpace.sm,
                                leading: ForestixSpace.md,
                                bottom: ForestixSpace.sm,
                                trailing: ForestixSpace.md))
                            .listRowBackground(Color.clear)
                    }
                }
            }
            Section {
                summaryHeader
                    .listRowInsets(EdgeInsets(top: ForestixSpace.sm,
                                              leading: ForestixSpace.md,
                                              bottom: ForestixSpace.sm,
                                              trailing: ForestixSpace.md))
                    .listRowBackground(ForestixPalette.surface)
                if history.isNearCapacity {
                    capacityBanner
                        .listRowInsets(EdgeInsets(top: ForestixSpace.xs,
                                                  leading: ForestixSpace.md,
                                                  bottom: ForestixSpace.xs,
                                                  trailing: ForestixSpace.md))
                        .listRowBackground(ForestixPalette.surface)
                }
            }

            Section {
                ForEach(history.entries) { entry in
                    FieldLogRow(entry: entry,
                                unitSystem: settings.unitSystem)
                        .listRowBackground(ForestixPalette.surface)
                        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                            Button(role: .destructive) {
                                history.delete(id: entry.id)
                            } label: {
                                Label("Delete", systemImage: "trash")
                            }
                        }
                }
            } header: {
                FieldLogColumnHeader()
                    .textCase(nil)
            }
        }
        #if os(iOS)
        .listStyle(.insetGrouped)
        #endif
        .scrollContentBackground(.hidden)
    }

    // MARK: - Summary header

    private var summaryHeader: some View {
        let now = Date()
        let cal = Calendar.current
        let todayCount = history.entries.filter {
            cal.isDate($0.createdAt, inSameDayAs: now)
        }.count
        let lastAgo = history.entries.first.map {
            compactRelativeAgo($0.createdAt, now: now)
        } ?? "—"

        return HStack(alignment: .firstTextBaseline, spacing: ForestixSpace.lg) {
            summaryCell(value: "\(history.entries.count)", label: "TOTAL")
            summaryCell(value: "\(todayCount)",            label: "TODAY")
            summaryCell(value: lastAgo,                     label: "LAST")
            Spacer(minLength: 0)
        }
    }

    private func summaryCell(value: String, label: String) -> some View {
        // Single-line throughout: "LAST" can carry a date ("Mar 14") at
        // 26 pt, which is what would push this row into wrapping first.
        VStack(alignment: .leading, spacing: 2) {
            Text(value)
                .font(ForestixType.dataLarge)
                .foregroundStyle(ForestixPalette.textPrimary)
                .lineLimit(1)
                .allowsTightening(true)
                .minimumScaleFactor(FieldLogTable.valueScaleFloor)
            Text(label)
                .font(ForestixType.sectionHead)
                .tracking(FieldLogTable.headerTracking)
                .foregroundStyle(ForestixPalette.textTertiary)
                .lineLimit(1)
                .allowsTightening(true)
                .minimumScaleFactor(FieldLogTable.labelScaleFloor)
        }
    }

    // MARK: - Capacity banner

    private var capacityBanner: some View {
        HStack(spacing: ForestixSpace.xs) {
            Image(systemName: "exclamationmark.triangle")
                .foregroundStyle(ForestixPalette.confidenceWarn)
            Text("Log nearly full. Export soon to free space.")
                .font(ForestixType.caption)
                .foregroundStyle(ForestixPalette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(ForestixSpace.sm)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: ForestixRadius.control,
                             style: .continuous)
                .fill(ForestixPalette.confidenceWarn.opacity(0.12))
        )
    }

    // MARK: - Empty state

    private var emptyState: some View {
        VStack(spacing: ForestixSpace.md) {
            Spacer()
            Image(systemName: "tray")
                .font(.system(size: 34, weight: .light))
                .foregroundStyle(ForestixPalette.textTertiary)
            Text("No readings yet")
                .font(ForestixType.bodyBold)
                .foregroundStyle(ForestixPalette.textPrimary)
            Text("Accept a scan in a measurement tool and it'll land here.")
                .font(ForestixType.caption)
                .foregroundStyle(ForestixPalette.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, ForestixSpace.xl)
            Spacer()
            Spacer()
        }
        .frame(maxWidth: .infinity)
    }
}

// MARK: - Table geometry

/// Splits the proposed width across its subviews on fixed WEIGHTS. Both the
/// column header and every row lay out through this, so the columns line up
/// without either side hard-coding a point width — and on a narrow phone the
/// columns give ground proportionally instead of the last one falling off the
/// end.
private struct WeightedColumns: Layout {

    let weights: [CGFloat]
    let spacing: CGFloat

    private func widths(for total: CGFloat) -> [CGFloat] {
        let usable = max(0, total - spacing * CGFloat(max(0, weights.count - 1)))
        let sum = weights.reduce(0, +)
        guard sum > 0 else { return weights.map { _ in 0 } }
        return weights.map { usable * $0 / sum }
    }

    func sizeThatFits(proposal: ProposedViewSize,
                      subviews: Subviews,
                      cache: inout ()) -> CGSize {
        // No width proposed (an "ideal size" query, which List does make while
        // estimating row heights): answer with the content's own width rather
        // than the 10 pt placeholder `replacingUnspecifiedDimensions` returns,
        // which would report a row one line tall of squeezed columns.
        guard let total = proposal.width, total > 0 else {
            let ideal = subviews.map { $0.sizeThatFits(.unspecified) }
            let width = ideal.reduce(0) { $0 + $1.width }
                + spacing * CGFloat(max(0, subviews.count - 1))
            return CGSize(width: width, height: ideal.map(\.height).max() ?? 0)
        }
        let w = widths(for: total)
        let height = zip(subviews, w).map { sub, width in
            sub.sizeThatFits(ProposedViewSize(width: width,
                                              height: proposal.height)).height
        }.max() ?? 0
        return CGSize(width: total, height: height)
    }

    func placeSubviews(in bounds: CGRect,
                       proposal: ProposedViewSize,
                       subviews: Subviews,
                       cache: inout ()) {
        let w = widths(for: bounds.width)
        var x = bounds.minX
        for (index, sub) in subviews.enumerated() {
            let width = index < w.count ? w[index] : 0
            sub.place(at: CGPoint(x: x, y: bounds.midY),
                      anchor: .leading,
                      proposal: ProposedViewSize(width: width,
                                                 height: bounds.height))
            x += width + spacing
        }
    }
}

/// Shared geometry for the field-log table.
///
/// FIELD REPORT — the columns used to be pinned to 52 / 96 / 64 pt with QUALITY
/// unsized. On a 360 pt phone a list row has ~288 pt of content, and those four
/// columns plus their 12 pt gaps demanded 314 (measured): "PRECISION" wrapped to
/// "PRECISI/ON", "QUALITY" to "QUALI/TY", and the GOOD chip broke to "GOO/D".
/// A sampling-plot VALUE ("r 5.6 m · 98.5 m²") never fitted 96 pt either and
/// truncated mid-number.
///
/// Now the four columns share whatever width the row has, on these weights.
/// At 288 pt of content that resolves to TYPE 51.7 / VALUE 91.8 / PREC 57.4 /
/// QUALITY 63.1, against measured demands of "Height" 48.2, "31.4 cm" 73.6,
/// "±1.1 mm" 56.3 and a CHECK chip 65.6 — every header and every ordinary cell
/// fits outright, and the widest chip label sits at 0.96. Every cell is
/// single-line, so nothing can break mid-word; the scale floors below are a
/// backstop for the rare long value, not the layout.
private enum FieldLogTable {
    /// TYPE · VALUE · PRECISION · QUALITY. VALUE carries the longest strings,
    /// so it gets the biggest share.
    static let weights: [CGFloat] = [4.5, 8, 5, 5.5]
    /// 8 pt, not the 12 pt row default: three 12 pt gaps cost 36 pt of column
    /// width on the phone that could least afford it.
    static let gap: CGFloat = ForestixSpace.xs
    /// ALL-CAPS header tracking. The house `sectionHead` value is 1.2, which
    /// adds ~8 pt to a seven-letter header — on its own enough to push QUALITY
    /// out of its column at 360 pt. 0.8 keeps the spaced-caps look and fits.
    static let headerTracking: CGFloat = 0.8
    /// `ForestixType` is a FIXED-size ramp (`Font.system(size:)`), so the
    /// system text-size setting does not stretch these columns on iOS the way
    /// it does on Android. What bounds the remaining variation is this floor:
    /// a cell shrinks a little rather than truncating, and never below it.
    static let labelScaleFloor: CGFloat = 0.8
    /// Values may be much longer than their column: measured at 13/17 pt, a
    /// sampling-plot row ("r 5.6 m · 98.5 m²") wants 179 pt against the 92 pt
    /// VALUE gets on a 360 pt phone, i.e. 0.51 scale. The floor sits well
    /// under that so the string always lands whole — a truncated measurement
    /// is a wrong measurement, and it is only ever the plot/crown rows that
    /// scale at all.
    static let valueScaleFloor: CGFloat = 0.4
}

/// One cell of the table: always a single line, tightened and then scaled
/// down to fit rather than wrapped or truncated.
private struct FieldLogCell: View {
    let text: String
    let font: Font
    let color: Color
    var alignment: Alignment = .trailing
    var tracking: CGFloat = 0
    var scaleFloor: CGFloat = FieldLogTable.labelScaleFloor

    var body: some View {
        Text(text)
            .font(font)
            .tracking(tracking)
            .foregroundStyle(color)
            .lineLimit(1)
            .allowsTightening(true)
            .minimumScaleFactor(scaleFloor)
            .frame(maxWidth: .infinity, alignment: alignment)
    }
}

// MARK: - Column header

/// The column header lives as the List `Section` header, which gets
/// inset-grouped styling for free. It's a separate view, so it shares the
/// column weights with the row below — update `FieldLogTable` or neither.
///
/// "PREC" rather than "PRECISION": at 13 pt spaced caps the full word measures
/// 81 pt, which no sane share of a 360 pt row gives it. The header is shortened
/// rather than allowed to wrap, and the same word is used on Android.
private struct FieldLogColumnHeader: View {
    var body: some View {
        WeightedColumns(weights: FieldLogTable.weights,
                        spacing: FieldLogTable.gap) {
            cell("TYPE", alignment: .leading)
            cell("VALUE")
            cell("PREC")
            cell("QUALITY")
        }
    }

    private func cell(_ text: String,
                      alignment: Alignment = .trailing) -> some View {
        FieldLogCell(text: text,
                     font: ForestixType.sectionHead,
                     color: ForestixPalette.textTertiary,
                     alignment: alignment,
                     tracking: FieldLogTable.headerTracking)
    }
}

// MARK: - Row

private struct FieldLogRow: View {
    let entry: QuickMeasureEntry
    let unitSystem: UnitSystem

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            // Same weights as the column header — the two are one table.
            WeightedColumns(weights: FieldLogTable.weights,
                            spacing: FieldLogTable.gap) {
                FieldLogCell(text: typeLabel,
                             font: ForestixType.dataSmall,
                             color: ForestixPalette.textSecondary,
                             alignment: .leading)

                FieldLogCell(text: valueText,
                             font: ForestixType.data,
                             color: ForestixPalette.textPrimary,
                             scaleFloor: FieldLogTable.valueScaleFloor)

                FieldLogCell(text: sigmaText,
                             font: ForestixType.dataSmall,
                             color: ForestixPalette.textTertiary)

                TierChip(rawTier: entry.confidenceRaw)
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
            HStack(spacing: 6) {
                if let n = entry.treeNumber {
                    Text("#\(n)")
                        .font(ForestixType.dataSmall)
                        .foregroundStyle(ForestixPalette.primary)
                        .padding(.horizontal, 5)
                        .padding(.vertical, 1)
                        .overlay(
                            Capsule()
                                .stroke(ForestixPalette.primary.opacity(0.4),
                                        lineWidth: 0.5))
                }
                Text(timestampText)
                    .font(ForestixType.dataSmall)
                    .foregroundStyle(ForestixPalette.textTertiary)
                    .lineLimit(1)
            }
            // No indent: the old one was "52 + gap", i.e. hard-coded to the
            // width the TYPE column no longer has. The meta line is a
            // sub-label of the row, so it reads from the row's own edge.
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(
            (entry.treeNumber.map { "Tree \($0). " } ?? "") +
            "\(typeLabel) \(valueText), precision \(sigmaText), \(entry.confidenceRaw)")
    }

    private var typeLabel: String {
        switch entry.kind {
        case .dbh:          return "DBH"
        case .height:       return "Height"
        case .crown:        return "Crown"
        case .distance:     return "Dist"
        case .samplingPlot: return "Plot"
        }
    }

    private var valueText: String {
        switch entry.kind {
        case .dbh:
            return MeasurementFormatter.diameter(cm: entry.value, in: unitSystem)
        case .height:
            return MeasurementFormatter.height(m:  entry.value, in: unitSystem)
        case .crown:
            // Show width × height in metres for compactness.
            let h = entry.secondaryValue ?? 0
            return String(format: "%.1f × %.1f m", entry.value, h)
        case .distance:
            if entry.value < 1 {
                return String(format: "%.0f cm", entry.value * 100)
            }
            return String(format: "%.2f m", entry.value)
        case .samplingPlot:
            let area = entry.secondaryValue
                ?? (.pi * entry.value * entry.value)
            return String(format: "r %.1f m · %.1f m²", entry.value, area)
        }
    }

    private var sigmaText: String {
        guard let s = entry.sigma, s > 0 else { return "—" }
        switch entry.kind {
        case .dbh:    return MeasurementFormatter.diameterSigma(mm: s, in: unitSystem)
        case .height: return MeasurementFormatter.heightSigma(m:  s, in: unitSystem)
        case .crown, .distance, .samplingPlot:
            return String(format: "±%.2f m", s)
        }
    }

    private var timestampText: String {
        compactRelativeAgo(entry.createdAt)
    }
}

// MARK: - Compact relative time

/// Compact relative timestamp — "now" (<60 s), "5m", "3h", "2d",
/// then "MMM d" from a week out. Verbatim port of the Android
/// FieldLogScreen `relativeAgo` thresholds so the LAST summary cell
/// and row timestamps render identically on both platforms.
private func compactRelativeAgo(_ date: Date, now: Date = Date()) -> String {
    let diff = max(0, now.timeIntervalSince(date))
    let mins = Int(diff) / 60
    let hrs  = mins / 60
    let days = hrs / 24
    if mins < 1  { return "now" }
    if mins < 60 { return "\(mins)m" }
    if hrs  < 24 { return "\(hrs)h" }
    if days < 7  { return "\(days)d" }
    let fmt = DateFormatter()
    fmt.locale = Locale(identifier: "en_US")
    fmt.dateFormat = "MMM d"
    return fmt.string(from: date)
}

// MARK: - Tier chip (shared pattern)

private struct TierChip: View {
    let rawTier: String
    var body: some View {
        let d = ConfidenceStyle.descriptor(for: rawTier)
        return Text(d.label.uppercased())
            .font(ForestixType.sectionHead)
            .tracking(0.8)
            // One line, always: a squeezed column used to break the chip
            // mid-word ("GOO/D") rather than let it stay a word.
            .lineLimit(1)
            .allowsTightening(true)
            .minimumScaleFactor(FieldLogTable.labelScaleFloor)
            .fixedSize(horizontal: true, vertical: false)
            .padding(.horizontal, ForestixSpace.xs)
            .padding(.vertical, 3)
            .overlay(
                RoundedRectangle(cornerRadius: ForestixRadius.chip,
                                 style: .continuous)
                    .stroke(d.color, lineWidth: 0.75)
            )
            .foregroundStyle(d.color)
    }
}

// MARK: - Share sheet plumbing

#if os(iOS)
private struct ShareWrapper: Identifiable {
    let url: URL
    var id: URL { url }
}

private struct FieldLogShareSheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }
    func updateUIViewController(_: UIActivityViewController, context: Context) {}
}
#endif
