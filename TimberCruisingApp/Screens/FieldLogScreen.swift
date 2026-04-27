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
                    Button {
                        shareURL = history.exportCSV()
                    } label: {
                        Label("Export CSV", systemImage: "square.and.arrow.up")
                            .foregroundStyle(ForestixPalette.primary)
                    }
                    .accessibilityIdentifier("fieldLog.exportCSV")
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
        let lastAgo = history.entries.first.map { entry -> String in
            let fmt = RelativeDateTimeFormatter()
            fmt.unitsStyle = .abbreviated
            return fmt.localizedString(for: entry.createdAt, relativeTo: now)
        } ?? "—"

        return HStack(alignment: .firstTextBaseline, spacing: ForestixSpace.lg) {
            summaryCell(value: "\(history.entries.count)", label: "TOTAL")
            summaryCell(value: "\(todayCount)",            label: "TODAY")
            summaryCell(value: lastAgo,                     label: "LAST")
            Spacer(minLength: 0)
        }
    }

    private func summaryCell(value: String, label: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value)
                .font(ForestixType.dataLarge)
                .foregroundStyle(ForestixPalette.textPrimary)
            Text(label)
                .font(ForestixType.sectionHead)
                .tracking(1.2)
                .foregroundStyle(ForestixPalette.textTertiary)
        }
    }

    // MARK: - Capacity banner

    private var capacityBanner: some View {
        HStack(spacing: ForestixSpace.xs) {
            Image(systemName: "exclamationmark.triangle")
                .foregroundStyle(ForestixPalette.confidenceWarn)
            Text("Log nearing capacity — export soon to archive older readings.")
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
            Text("Accept a scan in the Quick Measure instrument and it'll land here.")
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

// MARK: - Column header

/// The column header lives as the List `Section` header, which gets
/// inset-grouped styling for free. It's a separate view so the column
/// widths match the row below — update both or neither.
private struct FieldLogColumnHeader: View {
    var body: some View {
        HStack(spacing: ForestixSpace.sm) {
            Text("TYPE").frame(width: 52, alignment: .leading)
            Text("VALUE").frame(width: 96, alignment: .trailing)
            Text("PREC").frame(width: 64, alignment: .trailing)
            Spacer(minLength: 0)
            Text("QUAL")
        }
        .font(ForestixType.sectionHead)
        .tracking(1.2)
        .foregroundStyle(ForestixPalette.textTertiary)
    }
}

// MARK: - Row

private struct FieldLogRow: View {
    let entry: QuickMeasureEntry
    let unitSystem: UnitSystem

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: ForestixSpace.sm) {
                Text(typeLabel)
                    .font(ForestixType.dataSmall)
                    .foregroundStyle(ForestixPalette.textSecondary)
                    .frame(width: 52, alignment: .leading)

                Text(valueText)
                    .font(ForestixType.data)
                    .foregroundStyle(ForestixPalette.textPrimary)
                    .frame(width: 96, alignment: .trailing)

                Text(sigmaText)
                    .font(ForestixType.dataSmall)
                    .foregroundStyle(ForestixPalette.textTertiary)
                    .frame(width: 64, alignment: .trailing)

                Spacer(minLength: 0)

                TierChip(rawTier: entry.confidenceRaw)
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
            }
            .padding(.leading, 52 + ForestixSpace.sm)
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(
            (entry.treeNumber.map { "Tree \($0). " } ?? "") +
            "\(typeLabel) \(valueText), precision \(sigmaText), \(entry.confidenceRaw)")
    }

    private var typeLabel: String {
        switch entry.kind {
        case .dbh:    return "DIA"
        case .height: return "HGT"
        }
    }

    private var valueText: String {
        switch entry.kind {
        case .dbh:    return MeasurementFormatter.diameter(cm: entry.value, in: unitSystem)
        case .height: return MeasurementFormatter.height(m:  entry.value, in: unitSystem)
        }
    }

    private var sigmaText: String {
        guard let s = entry.sigma, s > 0 else { return "—" }
        switch entry.kind {
        case .dbh:    return MeasurementFormatter.diameterSigma(mm: s, in: unitSystem)
        case .height: return MeasurementFormatter.heightSigma(m:  s, in: unitSystem)
        }
    }

    private var timestampText: String {
        let rel = RelativeDateTimeFormatter()
        rel.unitsStyle = .abbreviated
        return rel.localizedString(for: entry.createdAt, relativeTo: Date())
    }
}

// MARK: - Tier chip (shared pattern)

private struct TierChip: View {
    let rawTier: String
    var body: some View {
        let d = ConfidenceStyle.descriptor(for: rawTier)
        return Text(d.label.uppercased())
            .font(ForestixType.sectionHead)
            .tracking(0.8)
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
