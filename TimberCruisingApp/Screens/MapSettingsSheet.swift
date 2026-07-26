// MAP SETTINGS — the one sheet behind the map's layers button.
//
// Replaces the old scattered basemap controls (a "Basemap" layers sheet
// here, an offline download group there) with three groups in the order
// a cruiser reaches for them:
//
//   1. Map type        — Normal (OpenStreetMap standard) vs Satellite
//                        (Esri World Imagery), two selectable cards.
//                        Persisted as `tc.mapType`; the on-map
//                        attribution swaps with it.
//   2. Survey boundary — current-state row, Import, Remove, and the
//                        supported-format hint. One boundary at a time.
//   3. Offline maps    — the EXISTING download engine, moved here
//                        wholesale (plan per layer over the last visible
//                        bbox → one sequential queue with combined
//                        progress + cancel), plus cache stats and clear.
//                        NOT offered for the built-in Normal base:
//                        OpenStreetMap's Tile Usage Policy forbids bulk
//                        downloading, so that case shows the reason
//                        instead of the button. Satellite and a
//                        cruiser-configured provider are unaffected, and
//                        so is viewing OSM tiles live.
//
// NOT here on purpose: the custom XYZ tile overlay — both its template
// and its "Show overlay on the map" switch stay in Settings › Advanced
// (Basemap tiles), where the switch sits beside the template it belongs
// to. This sheet keeps to map type, boundary and offline maps.

import SwiftUI
import UniformTypeIdentifiers
import Common
import Geo
import Basemap

struct MapSettingsSheet: View {

    @EnvironmentObject private var settings: AppSettings
    @ObservedObject private var boundaryModel = SurveyBoundaryModel.shared
    @Environment(\.dismiss) private var dismiss

    let visibleRegion: BasemapRegion?

    @StateObject private var downloader = OfflineTileDownloader()
    @State private var baseStats: TileCache.Stats?
    @State private var overlayStats: TileCache.Stats?
    @State private var presentingImporter = false
    /// The refusal / failure sentence, shown inline under the row. Never
    /// a toast: a boundary that was refused must stay refused on screen
    /// until the cruiser has read why.
    @State private var importFailure: String?
    @State private var confirmingRemove = false

    private var baseCache: TileCache? {
        makeBaseTileCache(settings: settings)
    }

    /// Overlay cache regardless of the on/off toggle — the sheet still
    /// shows status + cache stats for a toggled-off overlay.
    private var overlayCache: TileCache? {
        makeOverlayTileCache(settings: settings)
    }

    /// Whether bulk offline download may run at all.
    ///
    /// It may for the built-in SATELLITE base, and for a tile provider
    /// the cruiser configured themselves — their provider, their terms.
    /// It may NOT for the built-in Normal base: those are
    /// OpenStreetMap's tiles, and OSM's Tile Usage Policy prohibits
    /// bulk / systematic downloading outright. A correct User-Agent
    /// buys no exemption — it just makes the app easy to identify and
    /// block, and a few thousand tiles per cruiser is precisely the
    /// traffic the policy exists to stop. Viewing OSM tiles live is
    /// unaffected; only the mass fetch is off.
    private var offlineDownloadAllowed: Bool {
        settings.mapType != .normal
    }

    /// What "Download visible area" fetches: the SELECTED base always,
    /// overlay only when configured AND switched on. Empty whenever the
    /// download is not permitted, so no caller can start one by
    /// accident.
    private var downloadCaches: [TileCache] {
        guard offlineDownloadAllowed else { return [] }
        var out: [TileCache] = []
        if let baseCache { out.append(baseCache) }
        if settings.overlayEnabled, let overlayCache { out.append(overlayCache) }
        return out
    }

    var body: some View {
        NavigationStack {
            List {
                mapTypeSection
                boundarySection
                offlineSection
            }
            .navigationTitle("Map settings")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
            .confirmationDialog("Remove the imported boundary?",
                                isPresented: $confirmingRemove,
                                titleVisibility: .visible) {
                Button("Remove", role: .destructive) {
                    boundaryModel.remove()
                    importFailure = nil
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("The boundary stops drawing on the map. Your cruise data is not affected.")
            }
        }
        // Fully expanded from the start — field report: opening at
        // .medium hid half the sheet and needed a scroll-up.
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .onAppear { refreshStats() }
        .onChange(of: downloader.phase) { _, phase in
            if case .finished = phase { refreshStats() }
        }
        // Switching the base layer switches the cache directory too.
        .onChange(of: settings.mapType) { _, _ in refreshStats() }
        .fileImporter(isPresented: $presentingImporter,
                      allowedContentTypes: Self.boundaryContentTypes,
                      allowsMultipleSelection: false) { result in
            handleImport(result)
        }
    }
}

// MARK: - 1. Map type

private extension MapSettingsSheet {

    var mapTypeSection: some View {
        Section {
            HStack(spacing: ForestixSpace.sm) {
                mapTypeCard(.normal, title: "Normal", symbol: "map")
                mapTypeCard(.satellite, title: "Satellite", symbol: "globe.americas.fill")
            }
            .listRowInsets(EdgeInsets(top: ForestixSpace.xs, leading: ForestixSpace.md,
                                      bottom: ForestixSpace.xs, trailing: ForestixSpace.md))
        } header: {
            Text("Map type")
        }
    }

    func mapTypeCard(_ type: BasemapType, title: String, symbol: String) -> some View {
        let selected = settings.mapType == type
        return Button {
            guard settings.mapType != type else { return }
            settings.mapType = type
            refreshStats()
        } label: {
            VStack(spacing: ForestixSpace.xs) {
                Image(systemName: symbol)
                    .font(.system(size: 26, weight: .regular))
                    .foregroundStyle(selected ? ForestixPalette.primary
                                              : ForestixPalette.textSecondary)
                Text(title)
                    .font(ForestixType.bodyBold)
                    .foregroundStyle(selected ? ForestixPalette.textPrimary
                                              : ForestixPalette.textSecondary)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, ForestixSpace.md)
            .background(
                RoundedRectangle(cornerRadius: ForestixRadius.card, style: .continuous)
                    .fill(selected ? ForestixPalette.primaryMuted : ForestixPalette.surface))
            .overlay(
                RoundedRectangle(cornerRadius: ForestixRadius.card, style: .continuous)
                    .stroke(selected ? ForestixPalette.primary : ForestixPalette.divider,
                            lineWidth: selected ? 2 : 1))
            .contentShape(RoundedRectangle(cornerRadius: ForestixRadius.card,
                                           style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
        .accessibilityIdentifier("mapSettings.mapType.\(type.rawValue)")
    }
}

// MARK: - 2. Survey boundary

private extension MapSettingsSheet {

    /// File types the picker offers. Deliberately wide — the system does
    /// not declare `.shp`/`.geojson`, and a valid boundary greyed out in
    /// the picker is a worse failure than a clear refusal after the fact.
    static var boundaryContentTypes: [UTType] {
        let candidates: [UTType?] = [
            .zip, .json, .xml,
            UTType("com.google.earth.kml"),
            UTType("com.google.earth.kmz"),
            UTType(filenameExtension: "shp"),
            UTType(filenameExtension: "kml"),
            UTType(filenameExtension: "kmz"),
            UTType(filenameExtension: "geojson")
        ]
        return candidates.compactMap { $0 }
    }

    var boundarySection: some View {
        Section {
            currentBoundaryRow

            if let failure = importFailure {
                // The refusal stays on screen — a boundary the app could
                // not confirm as WGS84 must never fail quietly.
                Label {
                    Text(failure)
                        .font(ForestixType.caption)
                        .foregroundStyle(ForestixPalette.confidenceBad)
                        .fixedSize(horizontal: false, vertical: true)
                } icon: {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(ForestixPalette.confidenceBad)
                }
                .accessibilityIdentifier("mapSettings.boundary.error")
            }

            if let stale = boundaryModel.loadFailure {
                Text("The stored boundary could not be read (\(stale)). Import it again.")
                    .font(ForestixType.caption)
                    .foregroundStyle(ForestixPalette.confidenceWarn)
            }

            Button {
                importFailure = nil
                presentingImporter = true
            } label: {
                Label("Import boundary file", systemImage: "square.and.arrow.down")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(ForestixProminentButtonStyle())
            .listRowInsets(EdgeInsets(top: ForestixSpace.xs, leading: ForestixSpace.md,
                                      bottom: ForestixSpace.xs, trailing: ForestixSpace.md))
            .accessibilityIdentifier("mapSettings.boundary.import")

            if boundaryModel.boundary != nil {
                Button("Remove", role: .destructive) { confirmingRemove = true }
                    .accessibilityIdentifier("mapSettings.boundary.remove")
            }
        } header: {
            Text("Survey boundary")
        } footer: {
            Text(SurveyBoundaryImporter.formatHint)
                .font(ForestixType.caption)
        }
    }

    @ViewBuilder
    var currentBoundaryRow: some View {
        HStack(spacing: ForestixSpace.sm) {
            Image(systemName: "square.3.layers.3d")
                .font(.system(size: 20, weight: .regular))
                .foregroundStyle(boundaryModel.boundary == nil
                                 ? ForestixPalette.textTertiary
                                 : ForestixPalette.primary)
                .frame(width: 26)
            if let boundary = boundaryModel.boundary {
                VStack(alignment: .leading, spacing: 2) {
                    Text(boundary.displayName)
                        .font(ForestixType.bodyBold)
                        .foregroundStyle(ForestixPalette.textPrimary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Text(Self.detail(for: boundary))
                        .font(ForestixType.caption)
                        .foregroundStyle(ForestixPalette.textSecondary)
                }
            } else {
                Text("No boundary loaded")
                    .font(ForestixType.body)
                    .foregroundStyle(ForestixPalette.textSecondary)
            }
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("mapSettings.boundary.state")
    }

    static func detail(for boundary: SurveyBoundary) -> String {
        let count = boundary.featureCount
        let features = count == 1 ? "1 feature" : "\(count) features"
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return "\(features) · imported \(formatter.string(from: boundary.importedAt))"
    }

    func handleImport(_ result: Result<[URL], Error>) {
        switch result {
        case .failure(let error):
            importFailure = "That boundary could not be imported (\(error.localizedDescription))."
        case .success(let urls):
            guard let url = urls.first else { return }
            do {
                try boundaryModel.importBoundary(from: url)
                importFailure = nil
            } catch let error as BoundaryImportError {
                // The refusal sentence itself — quoted verbatim, never
                // wrapped or softened.
                importFailure = error.description
            } catch {
                importFailure = "That boundary could not be imported (\(error.localizedDescription))."
            }
        }
    }
}

// MARK: - 3. Offline maps

private extension MapSettingsSheet {

    @ViewBuilder
    var offlineSection: some View {
        Section {
            switch downloader.phase {
            case .idle:
                downloadControl
            case let .running(done, total, failed):
                VStack(alignment: .leading, spacing: ForestixSpace.xxs) {
                    ProgressView(value: Double(done), total: Double(max(total, 1)))
                        .tint(ForestixPalette.primary)
                    HStack {
                        Text("\(done) / \(total) tiles")
                            .font(ForestixType.dataSmall)
                            .foregroundStyle(ForestixPalette.textSecondary)
                        if failed > 0 {
                            Text("· \(failed) failed")
                                .font(ForestixType.dataSmall)
                                .foregroundStyle(ForestixPalette.confidenceWarn)
                        }
                    }
                }
                Button("Cancel download", role: .destructive) {
                    downloader.cancel()
                }
                .accessibilityIdentifier("mapSettings.cancelDownload")
            case let .tooLarge(planned):
                Text("Area too large (\(planned) tiles) — zoom in and try again.")
                    .font(ForestixType.caption)
                    .foregroundStyle(ForestixPalette.confidenceWarn)
                downloadControl
            case let .finished(fetched, failed, alreadyCached, cancelled):
                VStack(alignment: .leading, spacing: 2) {
                    Text(cancelled
                         ? "Cancelled — kept \(fetched) downloaded tiles."
                         : fetched == 0 && failed == 0
                             ? "Nothing to fetch — area already cached"
                             : "Downloaded \(fetched) tiles")
                        .font(ForestixType.bodyBold)
                        .foregroundStyle(ForestixPalette.textPrimary)
                    Text(Self.finishDetail(failed: failed, alreadyCached: alreadyCached))
                        .font(ForestixType.caption)
                        .foregroundStyle(failed > 0
                            ? ForestixPalette.confidenceWarn
                            : ForestixPalette.textSecondary)
                }
                downloadControl
            }

            cacheRow("Base map", stats: baseStats)
            if overlayCache != nil {
                cacheRow("Overlay", stats: overlayStats)
            }
            Button("Clear cache", role: .destructive) {
                try? baseCache?.clear()
                try? overlayCache?.clear()
                refreshStats()
            }
            .disabled(storedTileCount == 0)
            .accessibilityIdentifier("mapSettings.clearCache")
        } header: {
            Text("Offline maps")
        } footer: {
            // Describes the download control, so it goes when the
            // control does — otherwise the group promises a fetch it
            // has just explained it will not do.
            if offlineDownloadAllowed {
                Text("Downloads the visible area (the selected base plus any overlay) for offline use. Max \(OfflineTileDownloader.maxPlannedTiles) tiles — zoom in if the area is too large.")
            }
        }
    }

    /// The download control — or, for the built-in Normal base, the
    /// plain reason there isn't one. Never a disabled button with no
    /// explanation: a cruiser who cannot see why assumes a bug.
    @ViewBuilder
    var downloadControl: some View {
        if offlineDownloadAllowed {
            downloadButton
        } else {
            Text("Offline download isn't available for the Normal map — OpenStreetMap's tile policy doesn't allow bulk downloads. Switch to Satellite, or set your own tile provider in Settings › Basemap tiles.")
                .font(ForestixType.caption)
                .foregroundStyle(ForestixPalette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("mapSettings.downloadUnavailable")
        }
    }

    var downloadButton: some View {
        Button {
            guard let region = visibleRegion else { return }
            let caches = downloadCaches
            guard !caches.isEmpty else { return }
            downloader.start(region: region, caches: caches)
        } label: {
            Label("Download visible area", systemImage: "arrow.down.circle")
        }
        .disabled(downloadCaches.isEmpty || visibleRegion == nil)
        .accessibilityIdentifier("mapSettings.download")
    }

    static func finishDetail(failed: Int, alreadyCached: Int) -> String {
        var parts: [String] = []
        if failed > 0 { parts.append("\(failed) failed — try again in coverage") }
        parts.append("\(alreadyCached) were already cached")
        return parts.joined(separator: " · ")
    }

    func cacheRow(_ label: String, stats: TileCache.Stats?) -> some View {
        HStack {
            Text(label)
                .foregroundStyle(ForestixPalette.textPrimary)
            Spacer()
            Text(Self.statsText(stats))
                .font(ForestixType.dataSmall)
                .foregroundStyle(ForestixPalette.textSecondary)
        }
    }

    var storedTileCount: Int {
        (baseStats?.fileCount ?? 0) + (overlayStats?.fileCount ?? 0)
    }

    static func statsText(_ stats: TileCache.Stats?) -> String {
        guard let stats else { return "—" }
        let bytes = ByteCountFormatter.string(fromByteCount: stats.byteCount,
                                              countStyle: .file)
        return "\(stats.fileCount) tiles · \(bytes)"
    }

    func refreshStats() {
        baseStats = baseCache?.stats()
        overlayStats = overlayCache?.stats()
    }
}

// MARK: - Offline tile downloader

/// Sequential URLSession fetch of a planned OfflineBasemap job into the
/// TileCache. Sequential on purpose: field devices are usually on weak
/// cellular, and providers' usage policies frown on parallel hammering.
///
/// Every request comes from `TileCache.request(for:)`, so the OSM base
/// carries the identifying User-Agent its tile policy requires here as
/// well as on the interactive map — the downloader is the path that
/// hammers hardest, so it is the one that must not go anonymous.
@MainActor
final class OfflineTileDownloader: ObservableObject {

    /// Refuse plans bigger than this — a zoomed-out viewport at zoom
    /// 12–17 explodes into millions of tiles; field areas stay well
    /// under the cap. Applied to the COMBINED base + overlay count.
    static let maxPlannedTiles = 4_000

    enum Phase: Equatable {
        case idle
        case running(done: Int, total: Int, failed: Int)
        /// Plan exceeded `maxPlannedTiles` — nothing was fetched.
        case tooLarge(planned: Int)
        case finished(fetched: Int, failed: Int, alreadyCached: Int,
                      cancelled: Bool)
    }

    @Published private(set) var phase: Phase = .idle
    private var task: Task<Void, Never>?

    var isRunning: Bool {
        if case .running = phase { return true }
        return false
    }

    func start(region: BasemapRegion, caches: [TileCache]) {
        guard !isRunning, !caches.isEmpty else { return }
        // Visible bbox exactly (no AOI buffer) at the spec zoom range,
        // planned per layer, fetched as ONE combined sequential queue so
        // the progress line reads x / (base + overlay).
        var queue: [(cache: TileCache, key: TileCache.Key)] = []
        var cachedCount = 0
        for cache in caches {
            let job = OfflineBasemap.planJob(
                aoiRings: [region.ring],
                zoomRange: OfflineBasemap.defaultZoomRange,
                bufferMeters: 0,
                cache: cache)
            cachedCount += job.alreadyCached
            queue += job.tiles.map { (cache, $0) }
        }
        let work = queue
        let alreadyCached = cachedCount
        guard !work.isEmpty else {
            phase = .finished(fetched: 0, failed: 0,
                              alreadyCached: alreadyCached,
                              cancelled: false)
            return
        }
        guard work.count <= Self.maxPlannedTiles else {
            phase = .tooLarge(planned: work.count)
            return
        }
        phase = .running(done: 0, total: work.count, failed: 0)
        task = Task { [weak self] in
            var done = 0
            var failed = 0
            var cancelled = false
            for item in work {
                if Task.isCancelled { cancelled = true; break }
                do {
                    guard let request = item.cache.request(for: item.key) else {
                        throw URLError(.badURL)
                    }
                    let (data, response) = try await URLSession.shared.data(for: request)
                    let status = (response as? HTTPURLResponse)?.statusCode ?? 200
                    guard (200..<300).contains(status) else {
                        throw URLError(.badServerResponse)
                    }
                    try item.cache.store(data, for: item.key)
                } catch {
                    failed += 1
                }
                done += 1
                self?.phase = .running(done: done, total: work.count,
                                       failed: failed)
            }
            self?.phase = .finished(fetched: done - failed, failed: failed,
                                    alreadyCached: alreadyCached,
                                    cancelled: cancelled)
        }
    }

    func cancel() {
        task?.cancel()
    }
}
