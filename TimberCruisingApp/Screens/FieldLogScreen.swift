// Field log — dedicated screen that owns the full measurement history.
//
// FIELD REPORT 5 — the log is now ONE ROW PER TREE.
//
// It used to be one row per MEASUREMENT, so a tree the cruiser had
// diametered and then measured the height of appeared twice, in two
// places in the list, joined only by a small "#12" on each row's meta
// line. Reading back a plot meant scanning for pairs. The table now leads
// with the tree number and puts that tree's diameter and height beside it,
// which is the shape of the paper tally sheet this replaces.
//
// The ± RANGE and QUALITY columns are gone with it. Four columns on a
// phone had every cell scaling to fit, and the two that were dropped were
// the two a cruiser was not reading in the field. Neither number is lost:
// σ is still recorded on the entry, still exported, and now shown in the
// detail sheet — which is what a tap on a row opens, and which carries the
// whole record (species, stem position, damage, note, position, photo, and
// in developer mode the ground truth typed against that tree).
//
// Readings that were never attached to a tree — a sampling-plot record, a
// standalone crown or distance — still get their own row. They are real
// records; grouping by tree must not make them disappear.
//
// The screen otherwise keeps its shape:
//   • Plot summary card for the active plot
//   • Summary header — total count + readings-today + "last" timestamp
//   • Capacity banner — only when the log is near its cap
//   • Native iOS List, so swipe-to-delete and VoiceOver traversal are
//     standard
//   • Export CSV / bundle in the toolbar
//
// The same `QuickMeasureEntry` / `QuickMeasureHistory` backing store powers
// it — no changes to the durability / schema layer.

import SwiftUI
import Models
import Sensors

public struct FieldLogScreen: View {

    @EnvironmentObject private var history: QuickMeasureHistory
    @EnvironmentObject private var settings: AppSettings
    @State private var shareURL: URL?
    /// The row whose detail sheet is open. nil = closed.
    @State private var inspecting: FieldLogRowModel?
    /// The row a swipe asked to delete, held until the cruiser confirms.
    /// Only multi-reading rows go through here (see `requestDelete`).
    @State private var pendingDelete: FieldLogRowModel?

    public init() {}

    private var rows: [FieldLogRowModel] {
        FieldLogRowModel.rows(from: history.entries)
    }

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
                            Label("All tables (zip of 5 CSVs)", systemImage: "doc.zipper")
                        }
                    } label: {
                        Label("Export", systemImage: "square.and.arrow.up")
                            .foregroundStyle(ForestixPalette.primary)
                    }
                    .accessibilityIdentifier("fieldLog.exportMenu")
                }
            }
        }
        .sheet(item: $inspecting) { row in
            FieldLogDetailSheet(row: row, unitSystem: settings.unitSystem)
                .environmentObject(settings)
        }
        // A tree row can carry more than one reading, and a swipe is a
        // cheap gesture — so a swipe that would take BOTH the diameter and
        // the height says what it is about to take first. Single-reading
        // rows delete straight away, as they always have.
        .confirmationDialog(
            pendingDelete.map { "Delete \($0.title)?" } ?? "",
            isPresented: Binding(get: { pendingDelete != nil },
                                 set: { if !$0 { pendingDelete = nil } }),
            titleVisibility: .visible
        ) {
            if let row = pendingDelete {
                Button("Delete \(row.entries.count) readings", role: .destructive) {
                    for entry in row.entries { history.delete(id: entry.id) }
                    pendingDelete = nil
                }
            }
            Button("Cancel", role: .cancel) { pendingDelete = nil }
        } message: {
            if let row = pendingDelete {
                Text(row.deleteWarning)
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
                ForEach(rows) { row in
                    Button { inspecting = row } label: {
                        FieldLogRow(row: row, unitSystem: settings.unitSystem)
                    }
                    .buttonStyle(.plain)
                    .listRowBackground(ForestixPalette.surface)
                    .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                        Button(role: .destructive) {
                            requestDelete(row)
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

    /// One reading goes immediately; a tree carrying several asks first.
    private func requestDelete(_ row: FieldLogRowModel) {
        if row.entries.count == 1 {
            history.delete(id: row.entries[0].id)
        } else {
            pendingDelete = row
        }
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

// MARK: - Row model

/// One row of the log: everything recorded against one tree, or a single
/// reading that was never attached to one.
///
/// GROUPING IS BY (plot, tree number), not by tree number alone. Tree
/// numbering restarts on each plot, so keying on the number by itself would
/// have merged plot 1's tree 4 with plot 2's tree 4 into a row claiming a
/// diameter and a height that came off two different trees.
public struct FieldLogRowModel: Identifiable, Equatable {

    public enum Subject: Equatable {
        case tree(number: Int)
        /// A reading with no tree number — a sampling-plot record, or a
        /// standalone crown / distance measurement.
        case loose(kind: QuickMeasureEntry.Kind)
    }

    public let id: String
    public let subject: Subject
    /// Newest diameter and height on this tree. Earlier re-measurements
    /// stay in `entries` and are listed in the detail sheet.
    public let dbh: QuickMeasureEntry?
    public let height: QuickMeasureEntry?
    /// Every reading behind this row, newest first.
    public let entries: [QuickMeasureEntry]
    /// Sort key — the most recent reading in the group.
    public let latest: Date

    public var title: String {
        switch subject {
        case .tree(let n):     return "Tree #\(n)"
        case .loose(let kind): return FieldLogRowModel.kindWord(kind)
        }
    }

    /// What a destructive swipe is actually about to remove.
    public var deleteWarning: String {
        let kinds = entries.map { FieldLogRowModel.kindWord($0.kind).lowercased() }
        let listed = Array(Set(kinds)).sorted().joined(separator: " and ")
        return "This removes the \(listed) recorded against it. It cannot be undone."
    }

    static func kindWord(_ kind: QuickMeasureEntry.Kind) -> String {
        switch kind {
        case .dbh:          return "DBH"
        case .height:       return "Height"
        case .crown:        return "Crown"
        case .distance:     return "Dist"
        case .samplingPlot: return "Plot"
        }
    }

    /// Collapses the flat entry list into rows, newest tree first.
    ///
    /// `entries` arrives newest-first from the history, and that order is
    /// preserved inside each group, so `dbh` / `height` pick up the latest
    /// reading of each kind without a second sort.
    public static func rows(from entries: [QuickMeasureEntry]) -> [FieldLogRowModel] {
        var order: [String] = []
        var grouped: [String: [QuickMeasureEntry]] = [:]

        for entry in entries {
            let key: String
            if let n = entry.treeNumber {
                key = "t|\(entry.plotID?.uuidString ?? "-")|\(n)"
            } else {
                // Never merged with anything: one row, this reading.
                key = "e|\(entry.id.uuidString)"
            }
            if grouped[key] == nil { order.append(key) }
            grouped[key, default: []].append(entry)
        }

        return order.compactMap { key in
            guard let group = grouped[key], let first = group.first else { return nil }
            let subject: Subject = first.treeNumber
                .map { Subject.tree(number: $0) } ?? .loose(kind: first.kind)
            return FieldLogRowModel(
                id: key,
                subject: subject,
                dbh: group.first { $0.kind == .dbh },
                height: group.first { $0.kind == .height },
                entries: group,
                latest: group.map(\.createdAt).max() ?? first.createdAt)
        }
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
/// THREE columns now, not four. Dropping ± RANGE and QUALITY gave back
/// roughly 110 pt on a 360 pt phone, which is why every cell here sits well
/// inside its column instead of scaling to fit as the four-column table did:
/// at 288 pt of content the shares are TREE 58.3 / DBH 106.9 / HEIGHT 106.9,
/// against measured demands of "#128" 32, "150.0 cm" 82 and "150.00 ft" 88.
/// The scale floors below are a backstop for an unusually long value, not
/// the layout.
private enum FieldLogTable {
    /// TREE · DBH · HEIGHT. The two measurement columns are equal — either
    /// can carry the longest string depending on the unit system.
    static let weights: [CGFloat] = [3.0, 5.5, 5.5]
    /// 8 pt, not the 12 pt row default: gaps are width the numbers could
    /// have had.
    static let gap: CGFloat = ForestixSpace.xs
    /// ALL-CAPS header tracking. The house `sectionHead` value is 1.2, which
    /// adds ~8 pt to a seven-letter header. 0.8 keeps the spaced-caps look
    /// and leaves the headings comfortably inside their columns.
    static let headerTracking: CGFloat = 0.8
    /// A cell shrinks a little rather than truncating, and never below this.
    static let labelScaleFloor: CGFloat = 0.72
    /// Values may run long — a sampling-plot row's "5.6 m radius · 98.5 m²"
    /// is wider than any phone column, and a truncated measurement is a
    /// wrong measurement. That row spans two columns (see `FieldLogRow`), so
    /// this floor is only ever reached by an extreme reading.
    static let valueScaleFloor: CGFloat = 0.35
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
/// Android uses the identical strings.
private struct FieldLogColumnHeader: View {
    var body: some View {
        WeightedColumns(weights: FieldLogTable.weights,
                        spacing: FieldLogTable.gap) {
            cell("TREE", alignment: .leading)
            cell("DBH")
            cell("HEIGHT")
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
    let row: FieldLogRowModel
    let unitSystem: UnitSystem

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            // Same weights as the column header — the two are one table.
            WeightedColumns(weights: FieldLogTable.weights,
                            spacing: FieldLogTable.gap) {
                FieldLogCell(text: row.title.replacingOccurrences(of: "Tree ", with: ""),
                             font: ForestixType.data,
                             color: ForestixPalette.textPrimary,
                             alignment: .leading)

                if case .loose(let kind) = row.subject {
                    // A plot record or a standalone crown / distance has no
                    // diameter-and-height shape to fill. Its reading spans
                    // the two measurement columns rather than being forced
                    // into one of them under a heading it does not match.
                    FieldLogCell(text: looseValue(kind),
                                 font: ForestixType.data,
                                 color: ForestixPalette.textPrimary,
                                 scaleFloor: FieldLogTable.valueScaleFloor)
                    Color.clear.frame(height: 0)
                } else {
                    FieldLogCell(text: row.dbh.map {
                                    MeasurementFormatter.diameter(
                                        cm: $0.value, in: unitSystem)
                                 } ?? "—",
                                 font: ForestixType.data,
                                 color: row.dbh == nil
                                    ? ForestixPalette.textTertiary
                                    : ForestixPalette.textPrimary,
                                 scaleFloor: FieldLogTable.valueScaleFloor)

                    FieldLogCell(text: row.height.map {
                                    MeasurementFormatter.height(
                                        m: $0.value, in: unitSystem)
                                 } ?? "—",
                                 font: ForestixType.data,
                                 color: row.height == nil
                                    ? ForestixPalette.textTertiary
                                    : ForestixPalette.textPrimary,
                                 scaleFloor: FieldLogTable.valueScaleFloor)
                }
            }
            HStack(spacing: 6) {
                if case .loose = row.subject {} else {
                    // The kind words that used to be the TYPE column, kept
                    // here only when a row carries something other than the
                    // two named columns (a crown on the same tree).
                    ForEach(extraKinds, id: \.self) { word in
                        Text(word)
                            .font(ForestixType.dataSmall)
                            .foregroundStyle(ForestixPalette.textTertiary)
                    }
                }
                if let species = speciesName {
                    Text(species)
                        .font(ForestixType.dataSmall)
                        .foregroundStyle(ForestixPalette.textSecondary)
                        .lineLimit(1)
                }
                Text(compactRelativeAgo(row.latest))
                    .font(ForestixType.dataSmall)
                    .foregroundStyle(ForestixPalette.textTertiary)
                    .lineLimit(1)
                Spacer(minLength: 0)
                // The standard "there is more behind this" affordance —
                // the row is a button and this says so.
                Image(systemName: "chevron.right")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(ForestixPalette.textTertiary)
            }
        }
        .padding(.vertical, 2)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityText)
        .accessibilityHint("Opens the full record")
    }

    /// Kinds on this tree beyond the two the table names.
    private var extraKinds: [String] {
        let extras = row.entries
            .filter { $0.kind != .dbh && $0.kind != .height }
            .map { FieldLogRowModel.kindWord($0.kind) }
        return Array(Set(extras)).sorted()
    }

    private var speciesName: String? {
        guard let code = row.entries.compactMap(\.speciesCode)
            .first(where: { !$0.isEmpty }) else { return nil }
        return RegionalSpecies.name(forCode: code)
    }

    private func looseValue(_ kind: QuickMeasureEntry.Kind) -> String {
        guard let entry = row.entries.first else { return "—" }
        switch kind {
        case .dbh:
            return MeasurementFormatter.diameter(cm: entry.value, in: unitSystem)
        case .height:
            return MeasurementFormatter.height(m: entry.value, in: unitSystem)
        case .crown:
            return String(format: "%.1f × %.1f m",
                          entry.value, entry.secondaryValue ?? 0)
        case .distance:
            return MeasurementFormatter.distance(m: entry.value, in: unitSystem)
        case .samplingPlot:
            let area = entry.secondaryValue ?? (.pi * entry.value * entry.value)
            return String(format: "%.1f m radius · %.1f m²", entry.value, area)
        }
    }

    private var accessibilityText: String {
        var parts: [String] = [row.title]
        if let d = row.dbh {
            parts.append("DBH " + MeasurementFormatter.diameter(
                cm: d.value, in: unitSystem))
        }
        if let h = row.height {
            parts.append("Height " + MeasurementFormatter.height(
                m: h.value, in: unitSystem))
        }
        if case .loose(let kind) = row.subject {
            parts.append(looseValue(kind))
        }
        if let species = speciesName { parts.append(species) }
        return parts.joined(separator: ", ")
    }
}

// MARK: - Detail sheet

/// The whole record behind one row.
///
/// FIELD REPORT 5 asked for this: the log table now shows the two numbers a
/// cruiser reads while walking, and everything else — what was typed into
/// the details sheet at capture time, the species, the ± band, where the
/// reading was taken, and in developer mode the ground truth entered against
/// this tree — lives one tap away instead of being squeezed into columns.
private struct FieldLogDetailSheet: View {

    let row: FieldLogRowModel
    let unitSystem: UnitSystem

    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                measurementsSection
                detailsSection
                contextSection
                if settings.developerMode, !groundTruths.isEmpty {
                    groundTruthSection
                }
            }
            .navigationTitle(row.title)
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

    // MARK: Measurements

    private var measurementsSection: some View {
        Section("Measurements") {
            ForEach(row.entries) { entry in
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text(FieldLogRowModel.kindWord(entry.kind))
                            .foregroundStyle(ForestixPalette.textSecondary)
                        Spacer(minLength: 8)
                        Text(value(entry))
                            .font(ForestixType.data)
                            .foregroundStyle(ForestixPalette.textPrimary)
                    }
                    // The ± band the table used to carry. It is the
                    // measurement's own precision, so it belongs with the
                    // measurement rather than in a column being scaled to
                    // fit on a phone.
                    if let band = sigma(entry) {
                        Text(band)
                            .font(ForestixType.dataSmall)
                            .foregroundStyle(ForestixPalette.textTertiary)
                    }
                }
            }
        }
    }

    private func value(_ entry: QuickMeasureEntry) -> String {
        switch entry.kind {
        case .dbh:
            return MeasurementFormatter.diameter(cm: entry.value, in: unitSystem)
        case .height:
            return MeasurementFormatter.height(m: entry.value, in: unitSystem)
        case .crown:
            return String(format: "%.1f × %.1f m",
                          entry.value, entry.secondaryValue ?? 0)
        case .distance:
            return MeasurementFormatter.distance(m: entry.value, in: unitSystem)
        case .samplingPlot:
            let area = entry.secondaryValue ?? (.pi * entry.value * entry.value)
            return String(format: "%.1f m radius · %.1f m²", entry.value, area)
        }
    }

    private func sigma(_ entry: QuickMeasureEntry) -> String? {
        guard let s = entry.sigma, s > 0 else { return nil }
        switch entry.kind {
        case .dbh:    return MeasurementFormatter.diameterSigma(mm: s, in: unitSystem)
        case .height: return MeasurementFormatter.heightSigma(m: s, in: unitSystem)
        case .crown, .distance, .samplingPlot:
            return String(format: "±%.2f m", s)
        }
    }

    // MARK: What the cruiser typed

    private var detailsSection: some View {
        Section("Details") {
            row(label: "Species", value: speciesText)
            if let position = row.entries.compactMap(\.position).first {
                self.row(label: "Stem position", value: position.displayName)
            }
            if !damageCodes.isEmpty {
                self.row(label: "Damage", value: damageCodes.joined(separator: ", "))
            }
            if let note = notes {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Note")
                        .foregroundStyle(ForestixPalette.textSecondary)
                    Text(note)
                        .font(ForestixType.body)
                        .foregroundStyle(ForestixPalette.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            if speciesText == "—" && damageCodes.isEmpty && notes == nil
                && row.entries.allSatisfy({ $0.position == nil }) {
                Text("Nothing was attached to this reading.")
                    .font(ForestixType.caption)
                    .foregroundStyle(ForestixPalette.textTertiary)
            }
        }
    }

    private var speciesText: String {
        guard let code = row.entries.compactMap(\.speciesCode)
            .first(where: { !$0.isEmpty }) else { return "—" }
        return "\(RegionalSpecies.name(forCode: code)) · \(code)"
    }

    private var damageCodes: [String] {
        Array(Set(row.entries.flatMap(\.damageCodes))).sorted()
    }

    private var notes: String? {
        let all = row.entries.compactMap(\.note)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        return all.isEmpty ? nil : all.joined(separator: "\n")
    }

    // MARK: Where and when

    private var contextSection: some View {
        Section("Recorded") {
            row(label: "When", value: timestampText)
            if let fix = row.entries.first(where: {
                $0.latitude != nil && $0.longitude != nil
            }) {
                self.row(label: "Position",
                         value: String(format: "%.5f, %.5f",
                                       fix.latitude ?? 0, fix.longitude ?? 0))
            } else {
                // Said out loud rather than left blank: a reading with no
                // fix is a different thing from one whose fix was not shown.
                self.row(label: "Position", value: "not recorded")
            }
            if let mode = row.entries.compactMap(\.captureMode).first {
                self.row(label: "Capture",
                         value: mode == "manual" ? "Adjusted by hand" : "Automatic")
            }
            if let photo = row.entries.compactMap(\.photoPath).first {
                FieldLogPhotoRow(name: photo)
            }
        }
    }

    private var timestampText: String {
        let fmt = DateFormatter()
        fmt.locale = Locale(identifier: "en_US")
        fmt.dateFormat = "MMM d, HH:mm"
        return fmt.string(from: row.latest)
    }

    // MARK: Ground truth (developer mode)

    /// Hand-measured values typed against this tree number, read back from
    /// the raw-capture bundles — which is where the truth is actually
    /// stored. Developer-mode only, because that is the only mode in which
    /// the field exists to type into.
    private var groundTruths: [(kind: String, value: Double)] {
        guard case .tree(let number) = row.subject else { return [] }
        return RawCaptureStore.list().compactMap { summary in
            guard summary.manifest.context.treeNumber == number,
                  let truth = summary.manifest.truth.value else { return nil }
            return (summary.manifest.kind, truth)
        }
    }

    private var groundTruthSection: some View {
        Section("Ground truth") {
            ForEach(groundTruths.indices, id: \.self) { index in
                let item = groundTruths[index]
                row(label: item.kind == "dbh" ? "Tape diameter" : "Measured height",
                    value: item.kind == "dbh"
                        ? String(format: "%.1f cm", item.value)
                        : String(format: "%.2f m", item.value))
            }
        }
    }

    // MARK: Row helper

    private func row(label: String, value: String) -> some View {
        HStack {
            Text(label)
                .foregroundStyle(ForestixPalette.textSecondary)
            Spacer(minLength: 8)
            Text(value)
                .foregroundStyle(ForestixPalette.textPrimary)
                .multilineTextAlignment(.trailing)
        }
    }
}

/// The capture photo, if the file is still there. A missing file says so
/// rather than leaving an empty box — the container can move between
/// installs, and a blank row would read as "no photo was taken".
private struct FieldLogPhotoRow: View {
    let name: String

    var body: some View {
        #if canImport(UIKit)
        if let image = UIImage(contentsOfFile:
                                MeasurePhotoStore.url(for: name).path) {
            Image(uiImage: image)
                .resizable()
                .scaledToFit()
                .frame(maxHeight: 220)
                .clipShape(RoundedRectangle(cornerRadius: ForestixRadius.control,
                                            style: .continuous))
                .accessibilityLabel("Capture photo")
        } else {
            HStack {
                Text("Photo")
                    .foregroundStyle(ForestixPalette.textSecondary)
                Spacer(minLength: 8)
                Text("file missing")
                    .foregroundStyle(ForestixPalette.textTertiary)
            }
        }
        #else
        EmptyView()
        #endif
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
