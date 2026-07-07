// MAP-FIRST HOME — the app's root screen, built to
// design/forestix-redesign-v2-maphome.html (screens ① map home,
// ② pin selected, ③ measure sheet, ⑤ photo detail).
//
// The map is the home because a cruiser's data IS spatial: every quick-
// measure entry with a GPS fix appears as a tree pin (one tree = one pin,
// D/H/C badges say what's been measured), the pulsing blue dot is you,
// and the single primary action is the big (+) capture button. Cruise
// and Log are the side circles. Tapping a pin slides up a peek card —
// the map never disappears under the cruiser.
//
// Tiles come in two layers: a built-in satellite base (Esri World
// Imagery — imagery out of the box whenever online, attribution label
// bottom-left) and an optional user XYZ overlay from Settings (contour /
// forest-service tiles) drawn on top. The canvas colour + faint grid
// only show through where a base tile is neither cached nor fetchable.
// The layers sheet handles both layers' status, the overlay on/off
// toggle, "download visible area" (OfflineBasemap.planJob per layer →
// sequential fetch into TileCache) and cache stats/clearing.
//
// Measurement flows are the SAME fullScreenCover wiring as
// TreeMeasurementHubScreen — Accept persists into QuickMeasureHistory
// with the ScanMetadata GPS fix + auto-photo, which is exactly what
// feeds the pins and peek cards here.

import SwiftUI
import Common
import Models
import Sensors
import Positioning
import Geo
import Basemap

// MARK: - Shared tile-cache factories

/// Built-in satellite base layer — always available, no setup and no
/// acknowledgement gate (it ships with the app). nil only if the cache
/// directory itself cannot be created.
@MainActor
fileprivate func makeBaseTileCache() -> TileCache? {
    try? TileCache(rootURL: TileCache.defaultBasemapRoot(),
                   provider: .esriWorldImagery)
}

/// User overlay layer from Settings, drawn ON TOP of the satellite
/// base. nil until the cruiser has pasted a template AND acknowledged
/// the provider's usage policy. (The layers-sheet on/off toggle is
/// applied at the call sites, not here, so the sheet can still show
/// status + cache stats for a toggled-off overlay.)
@MainActor
fileprivate func makeOverlayTileCache(settings: AppSettings) -> TileCache? {
    guard settings.providerUsageAcknowledged,
          let template = settings.tileURLTemplate
    else { return nil }
    return try? TileCache(rootURL: TileCache.defaultBasemapRoot(),
                          provider: .fromUserTemplate(template))
}

// MARK: - Screen

public struct MapHomeScreen: View {

    @EnvironmentObject private var environment: AppEnvironment
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var history: QuickMeasureHistory

    /// The home owns its LocationService: the GPS chip must be live from
    /// the moment the screen appears, independent of any scan screen.
    @StateObject private var location = LocationService()

    /// Seoul-ish fallback — used only when there is no fix and no
    /// located entry (fresh install, indoors).
    private static let fallbackCamera = BasemapCamera(
        latitude: 37.5665, longitude: 126.9780, zoom: 16)

    @State private var camera = MapHomeScreen.fallbackCamera
    @State private var cameraInitialised = false
    /// True while the camera still sits on the hardcoded fallback — the
    /// first real GPS fix recenters exactly once.
    @State private var awaitingFirstFix = false
    @State private var visibleRegion: BasemapRegion?
    @State private var selectedPinID: String?

    // Sheets / covers.
    @State private var presentingChooser = false
    @State private var presentingLayers = false
    @State private var photoViewer: PhotoViewerContext?
    /// Chooser row picked — launched from the sheet's onDismiss so the
    /// fullScreenCover doesn't fight the sheet dismissal animation.
    @State private var pendingChoice: MeasureChoice?

    // Measurement covers — same state layout as TreeMeasurementHubScreen.
    @State private var presentingDBHScan = false
    @State private var presentingHeightScan = false
    @State private var presentingDistance = false
    @State private var presentingSampling = false
    @State private var pendingTreeNumber: Int?

    public init() {}

    private enum MeasureChoice {
        case dbh, height, distance, sampling
    }

    public var body: some View {
        NavigationStack {
            ZStack {
                map
                attributionBadge
                VStack(spacing: ForestixSpace.xs) {
                    topChrome
                    Spacer()
                }
                VStack {
                    Spacer()
                    if let pin = selectedPin {
                        peekCard(for: pin)
                    } else {
                        actionCluster
                    }
                }
            }
            .background(ForestixPalette.canvas.ignoresSafeArea())
            .onAppear { startUp() }
            .onDisappear { location.stop() }
            .onChange(of: location.latestSnapshot) { _, snap in
                recenterOnFirstFix(snap)
            }
            #if os(iOS)
            .navigationBarHidden(true)
            .fullScreenCover(isPresented: $presentingDBHScan) { dbhCover }
            .fullScreenCover(isPresented: $presentingHeightScan) { heightCover }
            .fullScreenCover(isPresented: $presentingDistance) {
                NavigationStack {
                    DistanceMeasureScreen()
                        .environmentObject(history)
                        .environmentObject(settings)
                        .toolbar {
                            ToolbarItem(placement: .cancellationAction) {
                                Button("Close") { presentingDistance = false }
                            }
                        }
                }
            }
            .fullScreenCover(isPresented: $presentingSampling) {
                NavigationStack {
                    SamplingPlotScreen()
                        .environmentObject(history)
                        .environmentObject(settings)
                        .toolbar {
                            ToolbarItem(placement: .cancellationAction) {
                                Button("Close") { presentingSampling = false }
                            }
                        }
                }
            }
            .fullScreenCover(item: $photoViewer) { context in
                MeasurePhotoDetailView(context: context,
                                       unitSystem: settings.unitSystem)
            }
            #endif
            .sheet(isPresented: $presentingChooser,
                   onDismiss: launchPendingChoice) { measureChooser }
            .sheet(isPresented: $presentingLayers) {
                BasemapLayersSheet(visibleRegion: visibleRegion)
                    .environmentObject(settings)
            }
        }
    }

    // MARK: Map + pins

    private var baseTileCache: TileCache? {
        makeBaseTileCache()
    }

    private var overlayTileCache: TileCache? {
        settings.overlayEnabled ? makeOverlayTileCache(settings: settings) : nil
    }

    private var map: some View {
        BasemapMapView(
            camera: $camera,
            baseTileCache: baseTileCache,
            overlayTileCache: overlayTileCache,
            markers: markers,
            selectedMarkerID: selectedPinID,
            youLocation: location.latestSnapshot.map {
                CoordinateConversions.LatLon(latitude: $0.latitude,
                                             longitude: $0.longitude)
            },
            style: BasemapStyle(
                canvas: ForestixPalette.canvas,
                grid: ForestixPalette.divider.opacity(0.55),
                pinStroke: ForestixPalette.surface,
                pinInk: ForestixPalette.primaryInk,
                badgeBackground: ForestixPalette.surface,
                badgeBorder: ForestixPalette.divider,
                badgeText: ForestixPalette.textSecondary,
                selectionHalo: ForestixPalette.primaryMuted),
            onMarkerTap: { id in
                withAnimation(.easeOut(duration: 0.18)) {
                    selectedPinID = (selectedPinID == id) ? nil : id
                }
            },
            onMapTap: {
                withAnimation(.easeOut(duration: 0.18)) { selectedPinID = nil }
            },
            onCameraChange: { _, region in
                visibleRegion = region
            })
        .ignoresSafeArea()
        .accessibilityIdentifier("mapHome.map")
    }

    /// One pin per tree number (entries WITH coordinates only); entries
    /// without a tree number stand alone, titled by their kind initial.
    private struct MapPin: Identifiable {
        let id: String
        let treeNumber: Int?
        let title: String
        /// Newest-first, mirroring `history.entries` order.
        let entries: [QuickMeasureEntry]
        let latitude: Double
        let longitude: Double
    }

    private var pins: [MapPin] {
        var byTree: [Int: [QuickMeasureEntry]] = [:]
        var singles: [QuickMeasureEntry] = []
        for entry in history.entries {
            guard entry.latitude != nil, entry.longitude != nil else { continue }
            if let n = entry.treeNumber {
                byTree[n, default: []].append(entry)
            } else {
                singles.append(entry)
            }
        }
        var out: [MapPin] = byTree.map { number, group in
            let latest = group[0]
            return MapPin(id: "tree-\(number)",
                          treeNumber: number,
                          title: "T\(number)",
                          entries: group,
                          latitude: latest.latitude ?? 0,
                          longitude: latest.longitude ?? 0)
        }
        out += singles.map { entry in
            MapPin(id: "entry-\(entry.id.uuidString)",
                   treeNumber: nil,
                   title: kindInitial(entry.kind),
                   entries: [entry],
                   latitude: entry.latitude ?? 0,
                   longitude: entry.longitude ?? 0)
        }
        return out.sorted { $0.id < $1.id }
    }

    private var markers: [BasemapMarker] {
        pins.map { pin in
            let needsAttention = pin.entries.contains {
                $0.confidenceRaw == "red" || $0.confidenceRaw == "yellow"
            }
            return BasemapMarker(
                id: pin.id,
                latitude: pin.latitude,
                longitude: pin.longitude,
                title: pin.title,
                tint: needsAttention ? ForestixPalette.confidenceWarn
                                     : ForestixPalette.primary,
                badge: pin.treeNumber == nil ? nil : badgeLetters(pin.entries))
        }
    }

    private var selectedPin: MapPin? {
        guard let id = selectedPinID else { return nil }
        return pins.first { $0.id == id }
    }

    /// D/H/C — which of the three tree metrics this tree already has.
    private func badgeLetters(_ entries: [QuickMeasureEntry]) -> String? {
        var letters = ""
        if entries.contains(where: { $0.kind == .dbh }) { letters += "D" }
        if entries.contains(where: { $0.kind == .height }) { letters += "H" }
        if entries.contains(where: { $0.kind == .crown }) { letters += "C" }
        return letters.isEmpty ? nil : letters
    }

    private func kindInitial(_ kind: QuickMeasureEntry.Kind) -> String {
        String(kind.rawValue.prefix(1)).uppercased()
    }

    // MARK: Lifecycle

    private func startUp() {
        location.requestAuthorization()
        location.start()
        guard !cameraInitialised else { return }
        cameraInitialised = true
        if let fix = LocationService.lastGlobalFix ?? location.latestSnapshot {
            camera = BasemapCamera(latitude: fix.latitude,
                                   longitude: fix.longitude, zoom: 16)
        } else if let entry = history.entries.first(where: {
            $0.latitude != nil && $0.longitude != nil
        }), let lat = entry.latitude, let lon = entry.longitude {
            camera = BasemapCamera(latitude: lat, longitude: lon, zoom: 16)
        } else {
            camera = Self.fallbackCamera
            awaitingFirstFix = true
        }
    }

    /// Fresh install in the field: nothing to centre on at launch, so
    /// the first GPS fix pulls the camera home — once.
    private func recenterOnFirstFix(_ snap: CLLocationSnapshot?) {
        guard awaitingFirstFix, let snap else { return }
        awaitingFirstFix = false
        withAnimation(.easeOut(duration: 0.3)) {
            camera = BasemapCamera(latitude: snap.latitude,
                                   longitude: snap.longitude, zoom: 16)
        }
    }

    // MARK: Top chrome

    private var topChrome: some View {
        HStack(alignment: .top, spacing: ForestixSpace.xs) {
            gpsChip
            Spacer()
            Button {
                presentingLayers = true
            } label: {
                Image(systemName: "square.stack.3d.up")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(ForestixPalette.textSecondary)
                    .frame(width: 44, height: 44)
                    .background(Circle().fill(ForestixPalette.surfaceRaised))
                    .overlay(Circle().stroke(ForestixPalette.divider, lineWidth: 1))
            }
            .buttonStyle(MapPressableStyle())
            .accessibilityLabel("Basemap layers")
            .accessibilityIdentifier("mapHome.layers")

            // Appearance toggle — same look as ModeSelectionScreen's.
            Button {
                settings.appearance = settings.appearance == "dark" ? "light" : "dark"
            } label: {
                Image(systemName: settings.appearance == "dark" ? "sun.max.fill" : "moon.fill")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(ForestixPalette.textSecondary)
                    .frame(width: 44, height: 44)
                    .background(Circle().fill(ForestixPalette.surfaceRaised))
                    .overlay(Circle().stroke(ForestixPalette.divider, lineWidth: 1))
            }
            .buttonStyle(MapPressableStyle())
            .accessibilityLabel(settings.appearance == "dark"
                ? "Switch to light appearance" : "Switch to dark appearance")
            .accessibilityIdentifier("mapHome.appearanceToggle")
        }
        .padding(.horizontal, 14)
        .padding(.top, ForestixSpace.xs)
    }

    /// A fix older than this reads as stale (dense canopy, canyon
    /// bottom) — the chip starts counting the age and its status dot
    /// flips to the warn colour.
    private static let staleFixAge: TimeInterval = 5

    /// Older than this the fix is effectively lost — the dot goes red.
    private static let lostFixAge: TimeInterval = 60

    /// THE GPS chip — one chip, coordinates of the last known fix with
    /// a freshness dot: green < 5 s, yellow 5–60 s, red past 60 s or
    /// before the first fix ever lands ("no fix", no coords). Once the
    /// fix goes stale the age counts up live (1 s tick, minutes past
    /// 60 s).
    private var gpsChip: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let snap = location.latestSnapshot
            let age = snap.map { max(0, context.date.timeIntervalSince($0.timestamp)) }
            let stale = (age ?? 0) > Self.staleFixAge
            let dot: Color = {
                guard snap != nil, let age else { return ForestixPalette.confidenceBad }
                if age > Self.lostFixAge { return ForestixPalette.confidenceBad }
                if age > Self.staleFixAge { return ForestixPalette.confidenceWarn }
                return ForestixPalette.confidenceOk
            }()
            let a11y: String = {
                guard let snap else { return "No GPS fix" }
                var out = String(format: "GPS fix %.5f, %.5f",
                                 snap.latitude, snap.longitude)
                if stale, let age { out += ", " + Self.fixAgeText(age) }
                return out
            }()
            HStack(spacing: 6) {
                Circle().fill(dot).frame(width: 7, height: 7)
                if let snap {
                    Text(String(format: "%.5f, %.5f",
                                snap.latitude, snap.longitude))
                        .font(.system(size: 11, weight: .semibold, design: .monospaced))
                        .foregroundStyle(ForestixPalette.textPrimary)
                    if stale, let age {
                        Text("· \(Self.fixAgeText(age))")
                            .font(.system(size: 11, weight: .medium, design: .monospaced))
                            .foregroundStyle(ForestixPalette.textTertiary)
                    }
                } else {
                    Text("no fix")
                        .font(.system(size: 11, weight: .semibold, design: .monospaced))
                        .foregroundStyle(ForestixPalette.textTertiary)
                }
            }
            .lineLimit(1)
            .padding(.horizontal, 11)
            .padding(.vertical, 7)
            .background(
                RoundedRectangle(cornerRadius: ForestixRadius.control, style: .continuous)
                    .fill(ForestixPalette.surface))
            .overlay(
                RoundedRectangle(cornerRadius: ForestixRadius.control, style: .continuous)
                    .stroke(ForestixPalette.divider, lineWidth: 1))
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(a11y)
            .accessibilityIdentifier("mapHome.gps")
        }
    }

    private static func fixAgeText(_ age: TimeInterval) -> String {
        age < 60 ? "\(Int(age)) s ago" : "\(Int(age / 60)) min ago"
    }

    /// Esri imagery attribution — required by the terms whenever the
    /// built-in base layer is on screen, so it is always on. Fixed
    /// colours on purpose: it sits on satellite imagery, not on an app
    /// surface (same rationale as the photo viewer's dark chrome).
    private var attributionBadge: some View {
        Text(TileCache.ProviderConfig.esriWorldImageryAttribution)
            .font(.system(size: 9, design: .monospaced))
            .foregroundStyle(Color.white.opacity(0.78))
            .padding(.horizontal, 6)
            .padding(.vertical, 2.5)
            .background(
                RoundedRectangle(cornerRadius: 4, style: .continuous)
                    .fill(Color.black.opacity(0.28)))
            .padding(.leading, 10)
            .padding(.bottom, 2)
            .frame(maxWidth: .infinity, maxHeight: .infinity,
                   alignment: .bottomLeading)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }

    // MARK: Bottom action cluster (mock ①)

    private var actionCluster: some View {
        HStack(alignment: .bottom, spacing: 26) {
            sideCircle(label: "Cruise", icon: "map",
                       accessibilityID: "mapHome.cruise") {
                TimberCruisingHubScreen()
            }

            VStack(spacing: 6) {
                Button {
                    presentingChooser = true
                } label: {
                    ZStack {
                        Circle().fill(ForestixPalette.primary)
                        Image(systemName: "plus")
                            .font(.system(size: 30, weight: .semibold))
                            .foregroundStyle(ForestixPalette.primaryInk)
                    }
                    .frame(width: 74, height: 74)
                    .overlay(Circle().stroke(ForestixPalette.surface, lineWidth: 4))
                    .shadow(color: Color.black.opacity(0.28), radius: 10, y: 6)
                }
                .buttonStyle(MapPressableStyle())
                .accessibilityLabel("New measurement")
                .accessibilityIdentifier("mapHome.measure")
                clusterLabel("Measure")
            }

            sideCircle(label: "Log", icon: "list.bullet",
                       accessibilityID: "mapHome.log") {
                FieldLogScreen()
            }
        }
        .padding(.bottom, ForestixSpace.sm)
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }

    private func sideCircle<Destination: View>(
        label: String,
        icon: String,
        accessibilityID: String,
        @ViewBuilder destination: @escaping () -> Destination
    ) -> some View {
        VStack(spacing: 5) {
            NavigationLink {
                destination()
            } label: {
                ZStack {
                    Circle().fill(ForestixPalette.surface)
                    Image(systemName: icon)
                        .font(.system(size: 20, weight: .medium))
                        .foregroundStyle(ForestixPalette.textPrimary)
                }
                .frame(width: 54, height: 54)
                .overlay(Circle().stroke(ForestixPalette.divider, lineWidth: 1))
                .shadow(color: Color.black.opacity(0.18), radius: 6, y: 3)
            }
            .buttonStyle(MapPressableStyle())
            .accessibilityLabel(label)
            .accessibilityIdentifier(accessibilityID)
            clusterLabel(label)
        }
    }

    /// Dark-glass pill so the label stays legible over satellite
    /// imagery. Fixed colours on purpose — the backdrop is photography
    /// regardless of the app theme (same rationale as the attribution
    /// badge and the photo viewer's chrome).
    private func clusterLabel(_ text: String) -> some View {
        Text(text.uppercased())
            .font(.system(size: 10.5, weight: .semibold, design: .monospaced))
            .tracking(0.6)
            .foregroundStyle(Color(red: 0.949, green: 0.961, blue: 0.953)) // #F2F5F3
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(
                RoundedRectangle(cornerRadius: 5, style: .continuous)
                    .fill(Color(red: 6 / 255, green: 9 / 255, blue: 10 / 255)
                        .opacity(0.65)))
    }

    // MARK: Peek card (mock ②)

    private func peekCard(for pin: MapPin) -> some View {
        let photos = pin.entries.compactMap(\.photoPath)
        return VStack(spacing: 0) {
            RoundedRectangle(cornerRadius: 2)
                .fill(ForestixPalette.divider)
                .frame(width: 36, height: 4)
                .padding(.bottom, 10)

            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text(peekTitle(pin))
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(ForestixPalette.textPrimary)
                    .lineLimit(1)
                Spacer(minLength: 4)
                Text(peekSubtitle(pin))
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(ForestixPalette.textTertiary)
                    .lineLimit(1)
            }
            .padding(.bottom, 10)

            HStack(alignment: .top, spacing: ForestixSpace.sm) {
                photoThumb(photos: photos)
                VStack(spacing: 0) {
                    let rows = peekRows(pin)
                    ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                        measurementRow(row)
                            .overlay(alignment: .bottom) {
                                if index < rows.count - 1 {
                                    Rectangle()
                                        .fill(ForestixPalette.divider)
                                        .frame(height: 0.5)
                                }
                            }
                    }
                }
                .frame(maxWidth: .infinity)
            }

            HStack(spacing: ForestixSpace.xs) {
                Button {
                    openPhotoViewer(pin)
                } label: {
                    Text("View photo")
                        .font(ForestixType.bodyBold)
                        .foregroundStyle(ForestixPalette.textPrimary)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(
                            RoundedRectangle(cornerRadius: ForestixRadius.control,
                                             style: .continuous)
                                .stroke(ForestixPalette.divider, lineWidth: 1))
                        .contentShape(Rectangle())
                }
                .buttonStyle(MapPressableStyle())
                .disabled(photos.isEmpty)
                .opacity(photos.isEmpty ? 0.45 : 1)
                .accessibilityIdentifier("mapHome.peek.viewPhoto")

                Button(pin.treeNumber != nil ? "Measure this tree"
                                             : "New measurement") {
                    measureAgain(pin)
                }
                .buttonStyle(.forestixProminent)
                .accessibilityIdentifier("mapHome.peek.measureAgain")
            }
            .padding(.top, ForestixSpace.sm)
        }
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(ForestixPalette.surface))
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(ForestixPalette.divider, lineWidth: 1))
        .shadow(color: Color.black.opacity(0.22), radius: 14, y: -4)
        .padding(.horizontal, ForestixSpace.sm)
        .padding(.bottom, ForestixSpace.xs)
        .transition(.move(edge: .bottom).combined(with: .opacity))
        .accessibilityIdentifier("mapHome.peek")
    }

    private static let peekDateFormatter: DateFormatter = {
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = "d MMM · HH:mm"
        return df
    }()

    private func peekTitle(_ pin: MapPin) -> String {
        let species = pin.entries.compactMap(\.speciesCode).first
        let base: String
        if let n = pin.treeNumber {
            base = "Tree \(n)"
        } else {
            base = kindLabel(pin.entries[0].kind)
        }
        if let species, !species.isEmpty {
            return "\(base) · \(species.uppercased())"
        }
        return base
    }

    private func peekSubtitle(_ pin: MapPin) -> String {
        let latest = pin.entries[0]
        var parts = [Self.peekDateFormatter.string(from: latest.createdAt)]
        if let plotID = latest.plotID,
           let plot = history.plot(id: plotID),
           !plot.isDefault {
            parts.append(plot.name)
        }
        return parts.joined(separator: " · ")
    }

    private func kindLabel(_ kind: QuickMeasureEntry.Kind) -> String {
        switch kind {
        case .dbh:          return "DBH"
        case .height:       return "Height"
        case .crown:        return "Crown"
        case .distance:     return "Distance"
        case .samplingPlot: return "Plot"
        }
    }

    // One row per measurement kind — the LATEST entry of that kind.
    private struct PeekRow: Identifiable {
        let id: String
        let label: String
        let value: String
        let sigma: String?
        let confidenceRaw: String
    }

    private func peekRows(_ pin: MapPin) -> [PeekRow] {
        let order: [QuickMeasureEntry.Kind] = [.dbh, .height, .crown,
                                               .distance, .samplingPlot]
        let system = settings.unitSystem
        return order.compactMap { kind in
            guard let entry = pin.entries.first(where: { $0.kind == kind })
            else { return nil }
            let value: String
            var sigma: String?
            switch kind {
            case .dbh:
                value = MeasurementFormatter.diameter(cm: entry.value, in: system)
                sigma = entry.sigma.map { MeasurementFormatter.diameterSigma(mm: $0, in: system) }
            case .height:
                value = MeasurementFormatter.height(m: entry.value, in: system)
                sigma = entry.sigma.map { MeasurementFormatter.heightSigma(m: $0, in: system) }
            case .crown:
                value = String(format: "%.1f × %.1f m",
                               entry.value, entry.secondaryValue ?? 0)
            case .distance:
                value = MeasurementFormatter.distance(m: entry.value, in: system)
                sigma = entry.sigma.map { String(format: "±%.2f m", $0) }
            case .samplingPlot:
                value = String(format: "r %.1f m", entry.value)
            }
            return PeekRow(id: kind.rawValue,
                           label: peekRowLabel(kind),
                           value: value,
                           sigma: sigma,
                           confidenceRaw: entry.confidenceRaw)
        }
    }

    private func peekRowLabel(_ kind: QuickMeasureEntry.Kind) -> String {
        switch kind {
        case .dbh:          return "DBH"
        case .height:       return "HEIGHT"
        case .crown:        return "CROWN"
        case .distance:     return "DIST"
        case .samplingPlot: return "PLOT"
        }
    }

    private func measurementRow(_ row: PeekRow) -> some View {
        HStack(spacing: ForestixSpace.xs) {
            Text(row.label)
                .font(.system(size: 10, weight: .bold))
                .tracking(0.7)
                .foregroundStyle(ForestixPalette.textTertiary)
                .frame(width: 52, alignment: .leading)
            Text(row.value)
                .font(.system(size: 14.5, weight: .semibold, design: .monospaced))
                .foregroundStyle(ForestixPalette.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            if let sigma = row.sigma {
                Text(sigma)
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(ForestixPalette.textTertiary)
                    .lineLimit(1)
            }
            Spacer(minLength: 4)
            tierChip(row.confidenceRaw)
        }
        .frame(minHeight: 32)
    }

    private func tierChip(_ raw: String) -> some View {
        let descriptor = ConfidenceStyle.descriptor(for: raw)
        return HStack(spacing: 4) {
            Circle().fill(descriptor.color).frame(width: 6, height: 6)
            Text(descriptor.label.uppercased())
                .font(.system(size: 10, weight: .bold))
                .tracking(0.6)
        }
        .foregroundStyle(descriptor.color)
        .padding(.horizontal, 7)
        .padding(.vertical, 2)
        .background(
            RoundedRectangle(cornerRadius: ForestixRadius.chip)
                .fill(descriptor.color.opacity(0.12)))
    }

    private func photoThumb(photos: [String]) -> some View {
        ZStack {
            RoundedRectangle(cornerRadius: ForestixRadius.card, style: .continuous)
                .fill(ForestixPalette.surfaceRaised)
            if let name = photos.first {
                MeasurePhotoThumbnail(name: name)
            } else {
                Image(systemName: "photo")
                    .font(.system(size: 22, weight: .regular))
                    .foregroundStyle(ForestixPalette.textTertiary)
            }
        }
        .frame(width: 96, height: 96)
        .clipShape(RoundedRectangle(cornerRadius: ForestixRadius.card,
                                    style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: ForestixRadius.card, style: .continuous)
                .stroke(ForestixPalette.divider, lineWidth: 1))
        .overlay(alignment: .topTrailing) {
            if photos.count > 1 {
                Text("×\(photos.count)")
                    .font(.system(size: 9, weight: .bold, design: .monospaced))
                    .foregroundStyle(Color(red: 0.949, green: 0.961, blue: 0.953))
                    .padding(.horizontal, 5)
                    .padding(.vertical, 2)
                    .background(
                        RoundedRectangle(cornerRadius: 4)
                            .fill(Color.black.opacity(0.7)))
                    .padding(4)
            }
        }
    }

    private func openPhotoViewer(_ pin: MapPin) {
        guard let entry = pin.entries.first(where: { $0.photoPath != nil })
        else { return }
        photoViewer = PhotoViewerContext(entry: entry, title: peekTitle(pin))
    }

    /// "Measure this tree" — opens the DBH cover pre-locked to the pin's
    /// tree number via the same pendingTreeNumber pattern the hub uses.
    private func measureAgain(_ pin: MapPin) {
        pendingTreeNumber = pin.treeNumber ?? history.suggestedNextTreeNumber
        presentingDBHScan = true
    }

    // MARK: Measure chooser (mock ③)

    private var measureChooser: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("MEASURE · TREE \(history.suggestedNextTreeNumber) (NEXT)")
                .font(.system(size: 13, weight: .heavy))
                .tracking(1.0)
                .foregroundStyle(ForestixPalette.textTertiary)
                .padding(.top, ForestixSpace.md)
                .padding(.bottom, ForestixSpace.xs)

            chooserRow("Diameter (DBH)", "Depth · AR motion · AR caliper",
                       icon: "ruler", accessibilityID: "mapHome.choose.dbh",
                       divided: true) {
                pendingTreeNumber = history.suggestedNextTreeNumber
                pendingChoice = .dbh
                presentingChooser = false
            }
            chooserRow("Height", "Walk-off tangent · crown add-on",
                       icon: "arrow.up.and.down", accessibilityID: "mapHome.choose.height",
                       divided: true) {
                pendingTreeNumber = history.suggestedNextTreeNumber
                pendingChoice = .height
                presentingChooser = false
            }
            chooserRow("Distance", "Live · two-point",
                       icon: "arrow.left.and.right", accessibilityID: "mapHome.choose.distance",
                       divided: true) {
                pendingChoice = .distance
                presentingChooser = false
            }
            chooserRow("Sampling plot", "Centre stake · boundary ring",
                       icon: "scope", accessibilityID: "mapHome.choose.sampling",
                       divided: false) {
                pendingChoice = .sampling
                presentingChooser = false
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, ForestixSpace.md)
        .presentationDetents([.height(400)])
        .presentationDragIndicator(.visible)
        .presentationBackground(ForestixPalette.surface)
    }

    private func chooserRow(_ title: String,
                            _ subtitle: String,
                            icon: String,
                            accessibilityID: String,
                            divided: Bool,
                            action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                ZStack {
                    RoundedRectangle(cornerRadius: 9, style: .continuous)
                        .fill(ForestixPalette.primaryMuted)
                        .frame(width: 44, height: 44)
                    Image(systemName: icon)
                        .font(.system(size: 20, weight: .medium))
                        .foregroundStyle(ForestixPalette.primary)
                }
                VStack(alignment: .leading, spacing: 1) {
                    Text(title)
                        .font(.system(size: 15.5, weight: .bold))
                        .foregroundStyle(ForestixPalette.textPrimary)
                    Text(subtitle)
                        .font(ForestixType.caption)
                        .foregroundStyle(ForestixPalette.textSecondary)
                }
                Spacer()
                Image(systemName: "chevron.forward")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(ForestixPalette.textTertiary)
            }
            .padding(.vertical, 13)
            .padding(.horizontal, 6)
            .frame(minHeight: 56)
            .contentShape(Rectangle())
        }
        .buttonStyle(MapPressableStyle())
        .accessibilityIdentifier(accessibilityID)
        .overlay(alignment: .bottom) {
            if divided {
                Rectangle().fill(ForestixPalette.divider).frame(height: 0.5)
            }
        }
    }

    /// Runs from the chooser sheet's onDismiss so the cover presents
    /// only after the sheet has fully gone.
    private func launchPendingChoice() {
        guard let choice = pendingChoice else { return }
        pendingChoice = nil
        switch choice {
        case .dbh:      presentingDBHScan = true
        case .height:   presentingHeightScan = true
        case .distance: presentingDistance = true
        case .sampling: presentingSampling = true
        }
    }

    // MARK: Measurement covers — same wiring as TreeMeasurementHubScreen

    #if os(iOS)
    private var dbhCover: some View {
        NavigationStack {
            DBHScanScreen(
                viewModel: DBHScanViewModel(calibration: .identity),
                onAccept: { result, meta in
                    history.append(QuickMeasureEntry(
                        kind: .dbh,
                        value: Double(result.diameterCm),
                        sigma: Double(result.sigmaRmm),
                        confidenceRaw: result.confidence.rawValue,
                        method: result.method.rawValue,
                        treeNumber: pendingTreeNumber,
                        plotID: history.activePlotID,
                        speciesCode: meta.speciesCode,
                        position: meta.position ?? .dbh,
                        damageCodes: meta.damageCodes,
                        note: meta.note.isEmpty ? nil : meta.note,
                        latitude: meta.latitude,
                        longitude: meta.longitude,
                        photoPath: meta.photoPath))
                    presentingDBHScan = false
                })
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { presentingDBHScan = false }
                }
            }
        }
    }

    private var heightCover: some View {
        NavigationStack {
            HeightScanScreen(
                viewModel: HeightScanViewModel(calibration: .identity),
                onAccept: { result, meta in
                    history.append(QuickMeasureEntry(
                        kind: .height,
                        value: Double(result.heightM),
                        sigma: Double(result.sigmaHm),
                        confidenceRaw: result.confidence.rawValue,
                        method: result.method.rawValue,
                        treeNumber: pendingTreeNumber,
                        plotID: history.activePlotID,
                        speciesCode: meta.speciesCode,
                        damageCodes: meta.damageCodes,
                        note: meta.note.isEmpty ? nil : meta.note,
                        latitude: meta.latitude,
                        longitude: meta.longitude,
                        photoPath: meta.photoPath))
                    presentingHeightScan = false
                },
                onCrown: { widthM, heightM in
                    // Crown is measured inside the Height session — it
                    // reuses the walk-off distance d_h so the canopy points
                    // land at real scale. Logged against the same tree.
                    history.append(QuickMeasureEntry(
                        kind: .crown,
                        value: widthM,
                        secondaryValue: heightM,
                        sigma: nil,
                        confidenceRaw: "green",
                        method: "ar.crown.dh",
                        treeNumber: pendingTreeNumber,
                        plotID: history.activePlotID))
                })
            .environmentObject(history)
            .environmentObject(settings)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { presentingHeightScan = false }
                }
            }
        }
    }
    #endif
}

// MARK: - Pressed feedback

/// Shared pressed treatment for the map chrome — same opacity + timing
/// language as ForestixProminentButtonStyle, applied to arbitrary labels
/// (circles, chips, rows) instead of the full-width green fill.
private struct MapPressableStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .opacity(configuration.isPressed ? 0.78 : 1)
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.easeOut(duration: 0.15), value: configuration.isPressed)
    }
}

// MARK: - Photo thumbnail loader

/// Loads a MeasurePhotoStore JPEG off the main thread, then hands the
/// decoded image to SwiftUI. Grey placeholder shows through until then.
private struct MeasurePhotoThumbnail: View {
    let name: String

    #if canImport(UIKit)
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                Color.clear
            }
        }
        .task(id: name) {
            let url = MeasurePhotoStore.url(for: name)
            let data = await Task.detached { try? Data(contentsOf: url) }.value
            if let data { image = UIImage(data: data) }
        }
    }
    #else
    var body: some View { Color.clear }
    #endif
}

// MARK: - Photo detail (mock ⑤)

private struct PhotoViewerContext: Identifiable {
    let entry: QuickMeasureEntry
    let title: String
    var id: UUID { entry.id }
}

/// Full-screen AR-snapshot viewer: the photo (feed + overlay, captured
/// at Accept) with the reading's identity along the bottom. The chrome
/// is fixed dark regardless of appearance — it sits on a photograph.
private struct MeasurePhotoDetailView: View {
    let context: PhotoViewerContext
    let unitSystem: UnitSystem

    @Environment(\.dismiss) private var dismiss
    #if canImport(UIKit)
    @State private var image: UIImage?
    #endif

    private let ink = Color(red: 0.949, green: 0.961, blue: 0.953)      // #F2F5F3
    private let inkDim = Color(red: 0.647, green: 0.682, blue: 0.659)   // #A5AEA8

    var body: some View {
        ZStack {
            Color(red: 0.039, green: 0.051, blue: 0.043).ignoresSafeArea() // #0A0D0B

            #if canImport(UIKit)
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ProgressView().tint(ink)
            }
            #endif

            VStack {
                HStack {
                    Spacer()
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(ink)
                            .frame(width: 44, height: 44)
                            .background(Circle().fill(Color.black.opacity(0.6)))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Close photo")
                    .accessibilityIdentifier("mapHome.photo.close")
                }
                .padding(.horizontal, 14)
                Spacer()
            }

            VStack {
                Spacer()
                meta
            }
        }
        #if canImport(UIKit)
        .task {
            let url = MeasurePhotoStore.url(for: context.entry.photoPath ?? "")
            let data = await Task.detached { try? Data(contentsOf: url) }.value
            if let data { image = UIImage(data: data) }
        }
        #endif
    }

    private var meta: some View {
        let entry = context.entry
        let descriptor = ConfidenceStyle.descriptor(for: entry.confidenceRaw)
        return VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text(bigValue)
                    .font(.system(size: 30, weight: .heavy, design: .monospaced))
                    .foregroundStyle(ink)
                Text([sigmaText, descriptor.label]
                    .compactMap { $0 }.joined(separator: " · "))
                    .font(.system(size: 13, design: .monospaced))
                    .foregroundStyle(inkDim)
            }
            HStack(alignment: .top, spacing: 18) {
                metaCell("TREE", treeText)
                metaCell("METHOD", entry.method)
                metaCell("GPS", gpsText)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 20)
        .padding(.top, 40)
        .padding(.bottom, 30)
        .background(
            LinearGradient(colors: [Color.black.opacity(0),
                                    Color.black.opacity(0.85)],
                           startPoint: .top, endPoint: .bottom))
    }

    private func metaCell(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(label)
                .font(.system(size: 11.5, design: .monospaced))
                .foregroundStyle(inkDim)
            Text(value)
                .font(.system(size: 13, weight: .bold, design: .monospaced))
                .foregroundStyle(ink)
                .lineLimit(1)
        }
    }

    private var bigValue: String {
        let entry = context.entry
        switch entry.kind {
        case .dbh:
            return "Ø " + MeasurementFormatter.diameter(cm: entry.value, in: unitSystem)
        case .height:
            return "H " + MeasurementFormatter.height(m: entry.value, in: unitSystem)
        case .crown:
            return String(format: "%.1f × %.1f m",
                          entry.value, entry.secondaryValue ?? 0)
        case .distance:
            return MeasurementFormatter.distance(m: entry.value, in: unitSystem)
        case .samplingPlot:
            return String(format: "r %.1f m", entry.value)
        }
    }

    private var sigmaText: String? {
        let entry = context.entry
        guard let sigma = entry.sigma, sigma > 0 else { return nil }
        switch entry.kind {
        case .dbh:
            return MeasurementFormatter.diameterSigma(mm: sigma, in: unitSystem)
        case .height:
            return MeasurementFormatter.heightSigma(m: sigma, in: unitSystem)
        case .crown, .distance, .samplingPlot:
            return String(format: "±%.2f m", sigma)
        }
    }

    private var treeText: String {
        let entry = context.entry
        var parts: [String] = []
        if let n = entry.treeNumber { parts.append("T\(n)") }
        if let species = entry.speciesCode, !species.isEmpty {
            parts.append(species.uppercased())
        }
        return parts.isEmpty ? "—" : parts.joined(separator: " · ")
    }

    /// The entry stores the fix itself (not its accuracy), so the GPS
    /// cell shows the coordinates the reading was anchored to.
    private var gpsText: String {
        guard let lat = context.entry.latitude,
              let lon = context.entry.longitude else { return "—" }
        return String(format: "%.5f, %.5f", lat, lon)
    }
}

// MARK: - Layers / offline sheet

/// Basemap status + offline download. Two layer rows — the built-in
/// satellite base and the user overlay with its on/off toggle — then
/// "Download visible area" (planJob PER LAYER over the last visible
/// bbox at the spec zoom range, fetched as one sequential queue with
/// combined progress + cancel), per-layer cache stats, clear.
private struct BasemapLayersSheet: View {

    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss

    let visibleRegion: BasemapRegion?

    @StateObject private var downloader = OfflineTileDownloader()
    @State private var baseStats: TileCache.Stats?
    @State private var overlayStats: TileCache.Stats?

    private var baseCache: TileCache? {
        makeBaseTileCache()
    }

    /// Overlay cache regardless of the on/off toggle — the sheet still
    /// shows status + cache stats for a toggled-off overlay.
    private var overlayCache: TileCache? {
        makeOverlayTileCache(settings: settings)
    }

    /// What "Download visible area" fetches: base always, overlay only
    /// when configured AND switched on.
    private var downloadCaches: [TileCache] {
        var out: [TileCache] = []
        if let baseCache { out.append(baseCache) }
        if settings.overlayEnabled, let overlayCache { out.append(overlayCache) }
        return out
    }

    var body: some View {
        NavigationStack {
            List {
                layersSection
                downloadSection
                cacheSection
            }
            .navigationTitle("Basemap")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
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
    }

    private var layersSection: some View {
        Section {
            // Base layer — built-in, nothing to configure.
            VStack(alignment: .leading, spacing: 2) {
                Text("Satellite base · built-in")
                    .font(ForestixType.bodyBold)
                    .foregroundStyle(ForestixPalette.textPrimary)
                Text("Esri World Imagery — fetched as you pan while online, kept on disk for offline use.")
                    .font(ForestixType.caption)
                    .foregroundStyle(ForestixPalette.textSecondary)
            }

            // Overlay layer — the user template from Settings.
            Toggle(isOn: Binding(get: { settings.overlayEnabled },
                                 set: { settings.overlayEnabled = $0 })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Overlay · your template")
                        .font(ForestixType.bodyBold)
                        .foregroundStyle(ForestixPalette.textPrimary)
                    if let template = settings.tileURLTemplate {
                        Text(settings.tileProviderLabel ?? template)
                            .font(ForestixType.dataSmall)
                            .foregroundStyle(ForestixPalette.textTertiary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    } else {
                        Text("None set — add an XYZ template in Settings → Basemap tiles.")
                            .font(ForestixType.caption)
                            .foregroundStyle(ForestixPalette.textSecondary)
                    }
                }
            }
            .disabled(settings.tileURLTemplate == nil)
            .accessibilityIdentifier("mapHome.layers.overlayToggle")

            if settings.tileURLTemplate != nil,
               !settings.providerUsageAcknowledged {
                Text("Overlay tiles stay hidden until you confirm the provider's usage policy in Settings → Basemap tiles.")
                    .font(ForestixType.caption)
                    .foregroundStyle(ForestixPalette.confidenceWarn)
            }
        } header: {
            Text("Layers")
        } footer: {
            Text("The satellite base draws first; the overlay (contour or forest-service tiles) draws on top of it.")
        }
    }

    @ViewBuilder
    private var downloadSection: some View {
        Section {
            switch downloader.phase {
            case .idle:
                downloadButton
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
                .accessibilityIdentifier("mapHome.layers.cancelDownload")
            case let .finished(fetched, failed, alreadyCached):
                VStack(alignment: .leading, spacing: 2) {
                    Text(fetched == 0 && failed == 0
                         ? "Nothing to fetch — area already cached"
                         : "Downloaded \(fetched) tiles")
                        .font(ForestixType.bodyBold)
                        .foregroundStyle(ForestixPalette.textPrimary)
                    Text(finishDetail(failed: failed, alreadyCached: alreadyCached))
                        .font(ForestixType.caption)
                        .foregroundStyle(failed > 0
                            ? ForestixPalette.confidenceWarn
                            : ForestixPalette.textSecondary)
                }
                downloadButton
            }
        } header: {
            Text("Offline download")
        } footer: {
            Text("Fetches zoom \(OfflineBasemap.defaultZoomRange.lowerBound)–\(OfflineBasemap.defaultZoomRange.upperBound) tiles for the area the map is showing — satellite base plus the overlay when one is set — so the map keeps working with no signal.")
        }
    }

    private var downloadButton: some View {
        Button {
            guard let region = visibleRegion else { return }
            let caches = downloadCaches
            guard !caches.isEmpty else { return }
            downloader.start(region: region, caches: caches)
        } label: {
            Label("Download visible area", systemImage: "arrow.down.circle")
        }
        .disabled(downloadCaches.isEmpty || visibleRegion == nil)
        .accessibilityIdentifier("mapHome.layers.download")
    }

    private func finishDetail(failed: Int, alreadyCached: Int) -> String {
        var parts: [String] = []
        if failed > 0 { parts.append("\(failed) failed — try again in coverage") }
        parts.append("\(alreadyCached) were already cached")
        return parts.joined(separator: " · ")
    }

    private var cacheSection: some View {
        Section("Cache") {
            cacheRow("Satellite base", stats: baseStats)
            if overlayCache != nil {
                cacheRow("Overlay", stats: overlayStats)
            }
            Button("Clear cache", role: .destructive) {
                try? baseCache?.clear()
                try? overlayCache?.clear()
                refreshStats()
            }
            .disabled(storedTileCount == 0)
            .accessibilityIdentifier("mapHome.layers.clearCache")
        }
    }

    private func cacheRow(_ label: String, stats: TileCache.Stats?) -> some View {
        HStack {
            Text(label)
                .foregroundStyle(ForestixPalette.textPrimary)
            Spacer()
            Text(statsText(stats))
                .font(ForestixType.dataSmall)
                .foregroundStyle(ForestixPalette.textSecondary)
        }
    }

    private var storedTileCount: Int {
        (baseStats?.fileCount ?? 0) + (overlayStats?.fileCount ?? 0)
    }

    private func statsText(_ stats: TileCache.Stats?) -> String {
        guard let stats else { return "—" }
        let bytes = ByteCountFormatter.string(fromByteCount: stats.byteCount,
                                              countStyle: .file)
        return "\(stats.fileCount) tiles · \(bytes)"
    }

    private func refreshStats() {
        baseStats = baseCache?.stats()
        overlayStats = overlayCache?.stats()
    }
}

// MARK: - Offline tile downloader

/// Sequential URLSession fetch of a planned OfflineBasemap job into the
/// TileCache. Sequential on purpose: field devices are usually on weak
/// cellular, and providers' usage policies frown on parallel hammering.
@MainActor
private final class OfflineTileDownloader: ObservableObject {

    enum Phase: Equatable {
        case idle
        case running(done: Int, total: Int, failed: Int)
        case finished(fetched: Int, failed: Int, alreadyCached: Int)
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
                              alreadyCached: alreadyCached)
            return
        }
        phase = .running(done: 0, total: work.count, failed: 0)
        task = Task { [weak self] in
            var done = 0
            var failed = 0
            for item in work {
                if Task.isCancelled { break }
                do {
                    guard let url = item.cache.resolvedURL(for: item.key) else {
                        throw URLError(.badURL)
                    }
                    let (data, response) = try await URLSession.shared.data(from: url)
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
                                    alreadyCached: alreadyCached)
        }
    }

    func cancel() {
        task?.cancel()
    }
}
