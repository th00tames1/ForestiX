// Spec §5.3 HeightScan layout + §4.4 state machine.
//
// Five stages render distinct chrome over a black AR placeholder:
//   1. anchorSet        — "Touch phone to tree base" + [Anchor Here]
//   2. walking          — live d_h + "Move back X m" hint + [Continue]
//   3. aimBaseArmed     — crosshair on the base + [Aim Base]   (base-first)
//   4. aimTopArmed      — crosshair on the top  + [Aim Top]
//   5. computed         — H ± σ_H panel + [Retake] / [Accept]
//
// Per Phase 2 Decision #5 (carried over) the AR view is a deterministic
// black placeholder so snapshot tests compare only the overlay chrome.
// The real ARView is layered in when the Phase 3 device path lands.

import SwiftUI
import Common
import Models
import Sensors
import Positioning
import AR
import simd

public struct HeightScanScreen: View {

    @StateObject private var viewModel: HeightScanViewModel
    @StateObject private var raycaster = ARCenterRaycaster()
    @Environment(\.scenePhase) private var scenePhase
    @EnvironmentObject private var settings: AppSettings
    /// App-scoped active sampling plot — rendered as a subdued ring +
    /// pole under the measurement markers so the cruiser keeps the plot
    /// boundary in view while measuring. Changes only on place/reset.
    @ObservedObject private var activePlot = ActiveSamplingPlot.shared
    public var onResult: (HeightResult) -> Void = { _ in }
    /// Fires when the cruiser explicitly accepts the result shown on
    /// screen (state → .accepted). Hosts that want to persist only on
    /// user confirmation should use this instead of `onResult`.
    /// `metadata` carries optional species / damage / note attached
    /// via `ScanMetadataSheet`.
    public var onAccept: (HeightResult, ScanMetadata) -> Void = { _, _ in }
    /// Fires when the cruiser measures the crown inside this Height
    /// session and accepts. `widthM` / `heightM` are at real scale because
    /// the canopy points are forward-projected to the tree's walk-off
    /// distance d_h. No-op for hosts that don't want crown.
    public var onCrown: (Double, Double) -> Void = { _, _ in }

    public struct ScanMetadata {
        public var speciesCode: String?
        public var damageCodes: [String]
        public var note: String
        /// Auto-capture at Accept (map home): window snapshot + GPS fix.
        public var photoPath: String?
        public var latitude: Double?
        public var longitude: Double?
        public init(speciesCode: String? = nil,
                    damageCodes: [String] = [],
                    note: String = "",
                    photoPath: String? = nil,
                    latitude: Double? = nil,
                    longitude: Double? = nil) {
            self.speciesCode = speciesCode
            self.damageCodes = damageCodes
            self.note = note
            self.photoPath = photoPath
            self.latitude = latitude
            self.longitude = longitude
        }
    }

    @State private var metaSpecies: String?
    @State private var metaDamage: [String] = []
    @State private var metaNote: String = ""
    @State private var presentingMetadata = false
    /// True while the Accept-time window snapshot is being taken — hides
    /// every piece of 2D chrome so the captured JPEG shows only the AR
    /// feed and the measurement overlays (crosshair + scene markers).
    @State private var hidingChromeForCapture = false
    /// Developer-mode research capture: true height (m) from a clinometer /
    /// Vertex, typed before Accept; logged to the research CSV.
    @State private var researchTrueM: String = ""
    /// Live camera→crosshair raycast distance sampled while the anchor
    /// stage is active — drives the ≤ 4 m anchor range gate's status
    /// line. nil while the raycast misses (sky, no mesh/plane yet,
    /// tracking not ready), which also counts as "gate not satisfied".
    @State private var anchorAimDistanceM: Float?
    /// (mesh overlay removed — the Height scan uses the plane/tangent walk-off
    /// and the LiDAR reconstruction wireframe was just visual noise.)
    /// PLACEHOLDER-COMMENT

    /// CRUISE MODE — the active cruise plot's mini-map payload; nil on
    /// the quick-measure path, where the widget falls back to the
    /// quick ActiveSamplingPlot ring or stays hidden. See DBHScanScreen.
    private let cruisePlotInfo: PlotMiniMapInfo?

    public init(viewModel: @autoclosure @escaping () -> HeightScanViewModel,
                onResult: @escaping (HeightResult) -> Void = { _ in },
                onAccept: @escaping (HeightResult, ScanMetadata) -> Void = { _, _ in },
                onCrown: @escaping (Double, Double) -> Void = { _, _ in },
                cruisePlotInfo: PlotMiniMapInfo? = nil) {
        _viewModel = StateObject(wrappedValue: viewModel())
        self.onResult = onResult
        self.onAccept = onAccept
        self.onCrown = onCrown
        self.cruisePlotInfo = cruisePlotInfo
    }

    /// What the top-right mini-map shows: the cruise plot when the
    /// add-tree chain supplied one, else the quick sampling ring, else
    /// nothing.
    private var miniMapInfo: PlotMiniMapInfo? {
        if let cruisePlotInfo { return cruisePlotInfo }
        guard let plot = activePlot.plot else { return nil }
        return PlotMiniMapInfo(plotID: nil,
                               plotNumber: nil,
                               radiusM: plot.radiusM,
                               centerLat: nil,
                               centerLon: nil,
                               treeCount: 0,
                               trees: [])
    }

    // MARK: - Crown sub-flow state

    enum CrownStep { case none, left, right, top, bottom, done }

    @State private var crownStep: CrownStep = .none
    @State private var crownLeft: SIMD3<Float>?
    @State private var crownRight: SIMD3<Float>?
    @State private var crownTop: SIMD3<Float>?
    @State private var crownBottom: SIMD3<Float>?
    @State private var crownWidthM: Double?
    @State private var crownHeightM: Double?

    public var body: some View {
        ZStack {
            // Live AR camera feed shared with the HeightScanViewModel's
            // session. The scene markers come from the VM and pin the
            // anchor / top / base reference points in world space so
            // the cruiser can pan away and come back without losing
            // track of where the measurement started. The raycaster
            // captures a weak ref to the ARView so button handlers can
            // turn "cruiser tapped while aiming here" into a world hit.
            ARCameraView(manager: viewModel.session,
                         debugMeshOverlay: false,
                         sceneMarkers: plotOverlayMarkers
                             + viewModel.sceneMarkers + crownMarkers,
                         raycaster: raycaster)
                .ignoresSafeArea()
            overlayChrome
            // Everything below is 2D chrome — hidden as one block while
            // the Accept-time window snapshot is captured so the JPEG
            // shows only the AR feed + measurement overlays.
            if !hidingChromeForCapture {
                VStack(spacing: 0) {
                    // Same GPS-accuracy strip as the Diameter scan — gives
                    // the cruiser a single-glance read on canopy quality
                    // before they anchor. Leading 72 / top 22 clears the
                    // floating back button (same offsets as Android).
                    HStack {
                        GPSAccuracyBadge()
                        Spacer()
                    }
                    .padding(.leading, 72)
                    .padding(.top, 22)
                    Spacer()
                }

                // Plot mini-map — top-right, same row as the GPS badge.
                // Non-interactive; hidden with the rest of the 2D chrome
                // during the Accept snapshot blackout.
                if let info = miniMapInfo {
                    VStack(spacing: 0) {
                        HStack {
                            Spacer()
                            PlotMiniMapWidget(info: info)
                                .padding(.trailing, ForestixSpace.md)
                        }
                        .padding(.top, 22)
                        Spacer()
                    }
                }

                // Floating back button — full-bleed chrome exit (the system
                // nav bar is hidden on the AR screens).
                MeasureBackButtonRow()

                // Top-centre instruction banner (U1) — the stage strings
                // moved out of the bottom panel (the crown aim prompts
                // live on the crosshair label); the orange anchor-failure
                // banner travels with it (tap to clear, as before).
                MeasureTopBanner(statusText) {
                    if let reason = viewModel.anchorFailureReason {
                        bannerView(reason, tint: .orange)
                            .accessibilityIdentifier("heightScan.anchorFailureBanner")
                            .onTapGesture { viewModel.clearAnchorFailure() }
                    }
                }

                // Bottom-right LiDAR/AR toggle — Developer-mode research
                // control only; field mode pins LiDAR devices to the mesh
                // path (AppSettings.measurementSource).
                if settings.developerMode {
                    VStack {
                        Spacer()
                        HStack {
                            Spacer()
                            MeasureSourceToggleButton()
                                .padding(.trailing, 18)
                                .padding(.bottom, 96)
                        }
                    }
                }

                // Bottom block (U2): capture stages get the camera-app
                // shutter bottom-centre — "Manual" flanks left while
                // anchoring, Retake flanks right once a walk/aim stage
                // (or crown capture) could need restarting — with the
                // live walk readout as the value strip above. RESULT
                // states drop the shutter and show the existing
                // value/action panel.
                VStack(spacing: 12) {
                    Spacer()
                    if hasPrimaryCapture {
                        heightValueStrip
                        MeasureShutterRow(
                            capture: primaryCapture,
                            leading: showsManualFlank
                                ? .init(systemImage: "keyboard",
                                        caption: "Manual") {
                                    viewModel.enterManualEntry()
                                }
                                : nil,
                            trailing: showsRetakeFlank
                                ? .init(systemImage: "arrow.counterclockwise",
                                        caption: "Retake") {
                                    viewModel.retake()
                                    resetCrown()
                                }
                                : nil)
                    } else if showsResultPanel {
                        bottomPanel
                    }
                }
            }
        }
        .devHUDOverlay(settings.developerMode && !hidingChromeForCapture,
                       title: "HEIGHT", lines: devHUDLines)
        // Full-bleed AR chrome — no system nav bar; the floating back
        // button is the exit affordance for both presentation paths.
        #if os(iOS)
        .toolbar(.hidden, for: .navigationBar)
        #endif
        // While the anchor stage is live, sample the screen-centre raycast
        // distance at ~10 Hz (same cadence as the DBH caliper smoother) so
        // the status line can flip between the aim prompt and the "Move
        // closer" range-gate message as the cruiser approaches the trunk.
        // The tap itself re-raycasts, so the gate enforced by the view
        // model always uses a fresh hit — this sampler only drives chrome.
        .task(id: anchorAiming) {
            guard anchorAiming else {
                anchorAimDistanceM = nil
                return
            }
            while !Task.isCancelled {
                raycaster.preferLiDARMesh = settings.measurementSource == .lidar
                if let cam = raycaster.cameraWorldPosition,
                   let hit = raycaster.screenCenterHit() {
                    anchorAimDistanceM = simd_distance(cam, hit)
                } else {
                    anchorAimDistanceM = nil
                }
                try? await Task.sleep(nanoseconds: 100_000_000)
            }
        }
        .onAppear { viewModel.onAppear() }
        .onDisappear { viewModel.onDisappear() }
        .onChange(of: viewModel.result?.heightM) { _, newValue in
            if newValue != nil, let r = viewModel.result {
                onResult(r)
            }
        }
        .onChange(of: viewModel.state) { _, newState in
            if newState == .accepted, let r = viewModel.result {
                // Auto-capture at Accept — the 2D chrome is hidden first
                // (a short sleep lets SwiftUI commit the chrome-less
                // frame) so the JPEG shows only the AR feed + overlays,
                // not buttons/panels that read as live controls in the
                // photo viewer. The entry must get its photoPath before
                // it is appended, hence the await before onAccept.
                Task { @MainActor in
                    hidingChromeForCapture = true
                    try? await Task.sleep(for: .milliseconds(80))
                    let photo = MeasurePhotoStore.captureWindow()
                    hidingChromeForCapture = false
                    let fix = LocationService.lastGlobalFix
                    let meta = ScanMetadata(
                        speciesCode: metaSpecies,
                        damageCodes: metaDamage,
                        note: metaNote,
                        photoPath: photo,
                        latitude: fix?.latitude,
                        longitude: fix?.longitude)
                    onAccept(r, meta)
                    recordResearchRow(r)
                }
            }
        }
        .sheet(isPresented: $presentingMetadata) {
            ScanMetadataSheet(
                kind: .height,
                speciesCode: $metaSpecies,
                position: .constant(nil),
                damageCodes: $metaDamage,
                note: $metaNote)
        }
        .onChange(of: scenePhase) { _, phase in
            // Same rationale as DBH scan: without this the ARKit
            // session, CoreMotion pitch buffer, and depth subscription
            // all keep running while the app is backgrounded.
            switch phase {
            case .active:     viewModel.onAppear()
            case .inactive, .background: viewModel.onDisappear()
            @unknown default: break
            }
        }
    }

    // MARK: - Overlay chrome per stage

    /// Always-visible centre crosshair so the cruiser can see exactly
    /// which world point each button will capture. Label changes with
    /// state to explain what the next tap will do.
    @ViewBuilder
    private var overlayChrome: some View {
        if let label = crosshairLabel {
            crosshair(label: label)
                .accessibilityIdentifier(crosshairIdentifier)
        }
    }

    private var crosshairLabel: String? {
        // Crown capture takes over the crosshair prompt once started.
        switch crownStep {
        case .left:   return "Aim at crown's LEFT edge"
        case .right:  return "Aim at crown's RIGHT edge"
        case .top:    return "Aim at HIGHEST branch"
        case .bottom: return "Aim at LOWEST branch"
        case .done:   return nil
        case .none:   break
        }
        switch viewModel.state {
        case .idle, .anchorSet: return "Aim at trunk (eye level)"
        case .walking:          return "Walk back — aim stays on tree"
        case .aimTopArmed:      return "Aim at treetop"
        case .aimBaseArmed:     return "Aim at trunk + ground"
        case .aimTopCaptured,
             .computed,
             .rejected,
             .accepted,
             .manualEntry:
            return nil
        }
    }

    private var crosshairIdentifier: String {
        switch viewModel.state {
        case .aimTopArmed:  return "heightScan.crosshair.top"
        case .aimBaseArmed: return "heightScan.crosshair.base"
        default:            return "heightScan.crosshair"
        }
    }

    /// Ring + cross mark — the cross explicitly pinpoints the world
    /// pixel a raycast will sample from, making "what am I actually
    /// tagging" unambiguous.
    private func crosshair(label: String) -> some View {
        VStack(spacing: 8) {
            // Dual-stroke + dark halo for sun-glare readability: a
            // plain yellow ring disappears against sky. The black
            // halo underneath gives the chrome contrast against any
            // background.
            ZStack {
                Circle()
                    .strokeBorder(Color.black.opacity(0.6), lineWidth: 4)
                    .frame(width: 40, height: 40)
                Circle()
                    .strokeBorder(ForestixPalette.confidenceWarn, lineWidth: 2)
                    .frame(width: 36, height: 36)
                Rectangle()
                    .fill(Color.black.opacity(0.6))
                    .frame(width: 16, height: 3.5)
                Rectangle()
                    .fill(Color.black.opacity(0.6))
                    .frame(width: 3.5, height: 16)
                Rectangle()
                    .fill(ForestixPalette.confidenceWarn)
                    .frame(width: 14, height: 1.5)
                Rectangle()
                    .fill(ForestixPalette.confidenceWarn)
                    .frame(width: 1.5, height: 14)
            }
            Text(label)
                .font(ForestixType.dataSmall)
                .foregroundStyle(.white)
                .padding(.horizontal, 8).padding(.vertical, 4)
                .background(Color.black.opacity(0.65))
                .cornerRadius(4)
        }
    }

    private var devHUDLines: [(String, String)] {
        var out: [(String, String)] = [
            ("source", settings.measurementSource.displayName),
            ("d_h live", String(format: "%.1f m", Double(viewModel.dhMeters))),
        ]
        if let r = viewModel.result {
            out.append(("H", String(format: "%.1f ±%.1f m", Double(r.heightM), Double(r.sigmaHm))))
            out.append(("α_top", String(format: "%+.1f°", Double(r.alphaTopRad) * 180 / .pi)))
            out.append(("α_base", String(format: "%+.1f°", Double(r.alphaBaseRad) * 180 / .pi)))
            out.append(("d_h", String(format: "%.1f m", Double(r.dHm))))
            out.append(("tier", r.confidence.rawValue))
        }
        return out
    }

    // MARK: - Bottom panel

    /// RESULT states that render the bottom value/action panel (U2) —
    /// capture stages render the shutter row instead. Crown-capture
    /// steps count as capture stages (`hasPrimaryCapture` wins first).
    private var showsResultPanel: Bool {
        switch viewModel.state {
        case .computed, .rejected, .manualEntry: return true
        default: return false
        }
    }

    /// Crown capture in progress — the shutter drives the four corner
    /// taps; the crosshair label carries the aim prompt.
    private var crownActive: Bool {
        crownStep != .none && crownStep != .done
    }

    /// Manual flanks left during the anchor stage only.
    private var showsManualFlank: Bool {
        showsManualRailButton && !crownActive
    }

    /// Retake flanks right once there is a measurement in progress to
    /// restart — the walk/aim stages plus crown capture.
    private var showsRetakeFlank: Bool {
        if crownActive { return true }
        switch viewModel.state {
        case .walking, .aimTopArmed, .aimTopCaptured, .aimBaseArmed:
            return true
        default:
            return false
        }
    }

    /// Live value strip above the shutter (U2): the walk stage's three
    /// distance lines — Initial dist, Walked back (starts at 0.00), and
    /// the primary Total distance d_h — plus the computed height while
    /// the crown sub-flow is capturing so the value stays on screen.
    @ViewBuilder
    private var heightValueStrip: some View {
        if viewModel.state == .walking {
            VStack(spacing: 4) {
                MeasureValuePill(
                    "Initial dist " + MeasurementFormatter.distance(
                        m: Double(viewModel.initialDistanceM),
                        in: settings.unitSystem),
                    dimmed: true)
                MeasureValuePill(
                    "Walked back " + MeasurementFormatter.distance(
                        m: Double(viewModel.walkedBackMeters),
                        in: settings.unitSystem),
                    dimmed: true)
                MeasureValuePill(
                    "Total distance " + MeasurementFormatter.distance(
                        m: Double(viewModel.dhMeters),
                        in: settings.unitSystem),
                    large: true)
            }
            .accessibilityIdentifier("heightScan.walkingReadout")
        } else if crownActive, let r = viewModel.result {
            // Crown capture: keep the computed height on screen while
            // the canopy taps run.
            MeasureValuePill(
                MeasurementFormatter.height(m: Double(r.heightM),
                                            in: settings.unitSystem),
                large: true)
        }
    }

    @ViewBuilder
    private var bottomPanel: some View {
        MeasureStatusPanel {
            stagePanel
            actionRow
        }
    }

    /// True while the anchor stage's crosshair rests on a raycast hit
    /// within the ≤ 4 m range gate — the only condition under which the
    /// "+" will actually capture the anchor.
    private var anchorWithinGate: Bool {
        guard let d = anchorAimDistanceM else { return false }
        return d <= HeightScanViewModel.anchorMaxRangeM
    }

    /// True for the pre-anchor aiming states — the window in which the
    /// range-gate sampler runs.
    private var anchorAiming: Bool {
        viewModel.state == .idle || viewModel.state == .anchorSet
    }

    private var statusText: String {
        switch viewModel.state {
        case .idle, .anchorSet:
            return anchorWithinGate
                ? "Aim at the trunk at eye level, then tap +."
                : "Move closer — anchor within 4 m of the trunk."
        case .walking:            return "Walk back, then tap + to continue."
        case .aimTopArmed:        return "Aim at the treetop, then tap +."
        case .aimTopCaptured:     return "Top captured."
        case .aimBaseArmed:       return "Aim at where the trunk meets the ground, then tap +."
        case .computed:           return "Height computed."
        case .accepted:           return "Saved."
        case .rejected:           return viewModel.result?.rejectionReason
                                       ?? "Rejected."
        case .manualEntry:        return "Enter height manually in metres."
        }
    }

    // MARK: - Stage-specific content

    @ViewBuilder
    private var stagePanel: some View {
        switch viewModel.state {
        case .computed:
            if let r = viewModel.result { resultPanel(r) }
            crownSection
        case .rejected:
            if let r = viewModel.result { resultPanel(r) }
        case .manualEntry:
            manualEntryPanel
        default:
            EmptyView()
        }
    }

    /// Crown extension shown once the height is computed. Reuses the
    /// walk-off distance d_h so canopy taps land at real scale.
    @ViewBuilder
    private var crownSection: some View {
        switch crownStep {
        case .none:
            EmptyView()
        case .left, .right, .top, .bottom:
            Text(crownPrompt)
                .font(ForestixType.caption)
                .foregroundStyle(ForestixPalette.confidenceWarn)
        case .done:
            if let w = crownWidthM, let h = crownHeightM {
                Text(String(format: "Crown %.2f m wide · %.2f m tall", w, h))
                    .font(ForestixType.data)
                    .foregroundStyle(.white)
            }
        }
    }

    private var crownPrompt: String {
        switch crownStep {
        case .left:   return "Crown: aim at the LEFT edge, tap +"
        case .right:  return "Crown: aim at the RIGHT edge, tap +"
        case .top:    return "Crown: aim at the HIGHEST branch, tap +"
        case .bottom: return "Crown: aim at the LOWEST branch, tap +"
        default:      return ""
        }
    }

    @ViewBuilder
    /// Developer-mode research CSV row — walk-off distance + both aim
    /// angles alongside the value so σ_H and the angle terms can be
    /// validated against ground truth.
    private func recordResearchRow(_ r: HeightResult) {
        guard settings.developerMode else { return }
        var f: [String: String] = [
            "measure_type": "height",
            "method": r.method.rawValue,
            "depth_source": settings.measurementSource.rawValue,
            "measured_value": String(format: "%.2f", r.heightM),
            "unit": "m",
            "sigma": String(format: "%.2f", r.sigmaHm),
            "confidence_tier": r.confidence.rawValue,
            "distance_m": String(format: "%.2f", r.dHm),
            "alpha_top_deg": String(format: "%.2f", r.alphaTopRad * 180 / .pi),
            "alpha_base_deg": String(format: "%.2f", r.alphaBaseRad * 180 / .pi),
            "species": metaSpecies ?? "",
            "note": metaNote,
        ]
        if !settings.researchTreeId.isEmpty {
            f["tree_id"] = settings.researchTreeId   // repeat auto-filled by record()
        }
        if let t = Double(researchTrueM), t > 0 {
            f["true_value"] = String(format: "%.2f", t)
            f["error"] = String(format: "%.2f", Double(r.heightM) - t)
        }
        ResearchLog.shared.record(f)
        researchTrueM = ""
    }

    private func resultPanel(_ r: HeightResult) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                // Always show the computed H — the red tier chip and the
                // status-banner rejection reason carry the warning. The
                // root-cause fixes (base-angle guard + nil-pose guard)
                // keep H in a sensible range even on red, so showing
                // "0.8 m — Too close, step back" is more useful than
                // hiding the number.
                // Field fix: the value stands alone — no ±σ text and no
                // tier chip/hint on the scan screens. σ + tier are still
                // recorded with the entry (history / CSV / FieldLog
                // unchanged); a red result keeps its rejection reason in
                // the status banner.
                Text(MeasurementFormatter.height(
                    m: Double(r.heightM), in: settings.unitSystem))
                    .font(ForestixType.dataLarge)
                    .foregroundStyle(.white)
                Spacer()
            }
            // Phase 13.2 diagnostic — Bug B persists in real-device tests
            // (desk reads ~89.6 m). The math in HeightEstimator is correct
            // when fed sane α / d_h, so we surface the actual captured
            // values to find which input has gone bad on hardware.
            // Remove once root-caused.
            Text(diagnosticLine(r))
                .font(ForestixType.caption)
                .foregroundStyle(.white.opacity(0.55))
                .accessibilityIdentifier("heightScan.diagnosticLine")
            HStack {
                Spacer()
                Button {
                    presentingMetadata = true
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "tag")
                            .font(.system(size: 11, weight: .semibold))
                        Text(metadataChipLabel)
                            .font(ForestixType.dataSmall)
                    }
                    .padding(.horizontal, 10).padding(.vertical, 5)
                    .overlay(Capsule().stroke(.white.opacity(0.4), lineWidth: 0.5))
                    .foregroundStyle(.white)
                }
                .accessibilityIdentifier("heightScan.editMetadata")
            }
            .padding(.top, 2)
            if settings.developerMode {
                HStack(spacing: 6) {
                    Text("Target")
                        .font(ForestixType.caption)
                        .foregroundStyle(.white.opacity(0.8))
                    TextField("T1", text: Binding(
                        get: { settings.researchTreeId },
                        set: { settings.researchTreeId = $0 }))
                        .textFieldStyle(.roundedBorder)
                        .frame(width: 70)
                        .accessibilityIdentifier("heightScan.researchTarget")
                    Text("True H (m)")
                        .font(ForestixType.caption)
                        .foregroundStyle(.white.opacity(0.8))
                    TextField("clinometer", text: $researchTrueM)
                        .keyboardType(.decimalPad)
                        .textFieldStyle(.roundedBorder)
                        .frame(width: 90)
                        .accessibilityIdentifier("heightScan.researchTrue")
                }
            }
        }
        .foregroundStyle(.white)
        .accessibilityIdentifier("heightScan.resultPanel")
    }

    private var metadataChipLabel: String {
        var bits: [String] = []
        if let s = metaSpecies, !s.isEmpty { bits.append(s) }
        if !metaDamage.isEmpty { bits.append("\(metaDamage.count) tag") }
        if bits.isEmpty { return "Add details" }
        return bits.joined(separator: " · ")
    }

    /// Phase 13.2 diagnostic — prints the raw captured inputs that fed
    /// the §7.2 formula so we can see whether a bad pitch, bad d_h, or
    /// the formula itself is producing the inflated H.
    private func diagnosticLine(_ r: HeightResult) -> String {
        let topDeg  = Double(r.alphaTopRad)  * 180.0 / .pi
        let baseDeg = Double(r.alphaBaseRad) * 180.0 / .pi
        return String(
            format: "α_top %+.1f° · α_base %+.1f° · d_h %.2f m",
            topDeg, baseDeg, Double(r.dHm))
    }

    private var manualEntryPanel: some View {
        HStack {
            TextField(settings.unitSystem == .metric
                      ? "Height in metres"
                      : "Height in feet",
                      text: $viewModel.manualHeightM)
                .textFieldStyle(.roundedBorder)
                #if os(iOS)
                .keyboardType(.decimalPad)
                #endif
                .accessibilityIdentifier("heightScan.manualInput")
            Button("Save") { viewModel.submitManualEntry() }
                .buttonStyle(.forestixProminent)
        }
    }

    // MARK: - Actions

    /// True when the current stage has a "capture" action the right-edge
    /// "+" button should fire — either a height stage or an active crown
    /// capture step.
    private var hasPrimaryCapture: Bool {
        if crownStep != .none && crownStep != .done { return true }
        switch viewModel.state {
        case .idle, .anchorSet, .walking,
             .aimTopArmed, .aimTopCaptured, .aimBaseArmed:
            return true
        default:
            return false
        }
    }

    /// Dispatch the "+" capture button. While a crown step is active it
    /// captures crown points; otherwise it drives the height stages.
    private func primaryCapture() {
        if crownStep != .none && crownStep != .done {
            crownCapture()
            return
        }
        switch viewModel.state {
        case .idle, .anchorSet:              anchorTap()
        case .walking:                       viewModel.continueToAimTop()
        case .aimTopArmed, .aimTopCaptured:  aimTopTap()
        case .aimBaseArmed:                  aimBaseTap()
        default:                             break
        }
    }

    // MARK: - Crown capture (real-scale via walk-off distance d_h)

    /// Crown reference markers (yellow L/R, cyan top/bottom) merged into
    /// the height scene so the cruiser sees what they've tagged.
    private static let crownLeftId   = UUID(uuidString: "00000000-C0C0-0000-0000-000000000001") ?? UUID()
    private static let crownRightId  = UUID(uuidString: "00000000-C0C0-0000-0000-000000000002") ?? UUID()
    private static let crownTopId    = UUID(uuidString: "00000000-C0C0-0000-0000-000000000003") ?? UUID()
    private static let crownBottomId = UUID(uuidString: "00000000-C0C0-0000-0000-000000000004") ?? UUID()

    /// Subdued sampling-plot context (ring + centre pole at ~0.5 alpha,
    /// pinned to the plot's ARAnchor). Shown only while a plot is active
    /// AND its anchor is still alive in the shared session. Non-
    /// interactive and listed before the measurement markers.
    private var plotOverlayMarkers: [ARSceneMarker] {
        guard let plot = activePlot.plot,
              viewModel.session.worldAnchorExists(id: plot.anchorID)
        else { return [] }
        return ActiveSamplingPlot.subduedOverlayMarkers(for: plot)
    }

    private var crownMarkers: [ARSceneMarker] {
        // Stable ids so these aren't torn down + rebuilt on every frame.
        var out: [ARSceneMarker] = []
        if let p = crownLeft {
            out.append(ARSceneMarker(id: Self.crownLeftId, worldPosition: p, shape: .sphere(radiusM: 0.05),
                                     colorRGBA: SIMD4<Float>(1, 0.85, 0, 1),
                                     scalesWithDistance: true))
        }
        if let p = crownRight {
            out.append(ARSceneMarker(id: Self.crownRightId, worldPosition: p, shape: .sphere(radiusM: 0.05),
                                     colorRGBA: SIMD4<Float>(1, 0.85, 0, 1),
                                     scalesWithDistance: true))
        }
        if let p = crownTop {
            out.append(ARSceneMarker(id: Self.crownTopId, worldPosition: p, shape: .sphere(radiusM: 0.05),
                                     colorRGBA: SIMD4<Float>(0.2, 0.7, 1, 1),
                                     scalesWithDistance: true))
        }
        if let p = crownBottom {
            out.append(ARSceneMarker(id: Self.crownBottomId, worldPosition: p, shape: .sphere(radiusM: 0.05),
                                     colorRGBA: SIMD4<Float>(0.2, 0.7, 1, 1),
                                     scalesWithDistance: true))
        }
        return out
    }

    /// Horizontal distance used to forward-project canopy taps that miss
    /// the raycast (sky / foliage). This is the height session's measured
    /// walk-off distance d_h — that's what makes the crown real-scale
    /// rather than a guessed fixed distance.
    private var crownProjectionDistance: Float {
        viewModel.dhMeters > 0.5 ? viewModel.dhMeters : 8.0
    }

    private func crownCapture() {
        raycaster.preferLiDARMesh = settings.measurementSource == .lidar
        guard let hit = raycaster.screenCenterHit()
                ?? raycaster.forwardPointAtHorizontalDistance(crownProjectionDistance)
        else { return }
        switch crownStep {
        case .left:   crownLeft = hit;   crownStep = .right
        case .right:  crownRight = hit;  crownStep = .top
        case .top:    crownTop = hit;    crownStep = .bottom
        case .bottom: crownBottom = hit; computeCrown()
        case .none, .done: break
        }
    }

    private func computeCrown() {
        if let l = crownLeft, let r = crownRight {
            let dx = l.x - r.x
            let dz = l.z - r.z
            crownWidthM = Double((dx * dx + dz * dz).squareRoot())
        }
        if let t = crownTop, let b = crownBottom {
            crownHeightM = Double(abs(t.y - b.y))
        }
        crownStep = .done
    }

    private func resetCrown() {
        crownStep = .none
        crownLeft = nil; crownRight = nil; crownTop = nil; crownBottom = nil
        crownWidthM = nil; crownHeightM = nil
    }

    /// Anchor stage only — exactly the states where the old status-panel
    /// Manual text button showed. The rejected row keeps its own Manual
    /// button, and manual entry itself needs no rail affordance.
    private var showsManualRailButton: Bool {
        switch viewModel.state {
        case .idle, .anchorSet: return true
        default:                return false
        }
    }

    /// Secondary actions only — the primary capture is the bottom-centre
    /// shutter (the walk/aim stages' Retake is its right flank, the
    /// anchor-stage Manual escape hatch its left).
    @ViewBuilder
    private var actionRow: some View {
        switch viewModel.state {
        case .computed:
            VStack(spacing: 8) {
                // Crown control: start it, or restart it once done. Hidden
                // in cruise-scoped sessions — the cruise Tree model has no
                // crown fields, so a crown measured here would be silently
                // dropped (Android gates identically).
                if cruisePlotInfo == nil {
                    if crownStep == .none {
                        Button("Measure crown") { crownStep = .left }
                            .buttonStyle(.forestixARSecondary)
                            .frame(maxWidth: .infinity)
                            .accessibilityIdentifier("heightScan.measureCrown")
                    } else if crownStep == .done {
                        Button("Redo crown") { resetCrown() }
                            .buttonStyle(.forestixARSecondary)
                            .frame(maxWidth: .infinity)
                    }
                }
                HStack(spacing: 12) {
                    Button("Retake") { viewModel.retake(); resetCrown() }
                        .buttonStyle(.forestixARSecondary)
                        .frame(maxWidth: .infinity)
                    Button("Accept") {
                        if crownStep == .done, let w = crownWidthM, let h = crownHeightM {
                            onCrown(w, h)
                        }
                        viewModel.accept()
                    }
                    .buttonStyle(.forestixProminent)
                    .frame(maxWidth: .infinity)
                    .disabled(viewModel.result?.confidence == .red)
                    .accessibilityIdentifier("heightScan.acceptButton")
                }
            }
        case .rejected:
            HStack(spacing: 12) {
                Button("Retake") { viewModel.retake() }
                    .buttonStyle(.forestixProminent)
                Button("Manual") { viewModel.enterManualEntry() }
                    .buttonStyle(.forestixARSecondary)
            }
        case .manualEntry:
            Button("Cancel") { viewModel.retake() }
                .buttonStyle(.forestixARSecondary)
        case .idle, .anchorSet, .walking,
             .aimTopArmed, .aimTopCaptured, .aimBaseArmed, .accepted:
            EmptyView()
        }
    }

    private func bannerView(_ text: String, tint: Color) -> some View {
        Text(text)
            .font(.callout).bold()
            .foregroundStyle(.white)
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(tint.opacity(0.8))
            .cornerRadius(8)
    }

    // MARK: - Tap handlers with raycast

    /// Anchor tap — cruiser stands 1–3 m from the tree, aims the
    /// crosshair at the trunk's base, and taps. The screen-centre
    /// raycast (LiDAR mesh first, plane fallback) returns the 3D
    /// world point of that trunk-base; the view model stores it as
    /// the anchor. The view model's ≤ 4 m range gate makes the tap an
    /// inert no-op while the raycast misses or hits beyond the gate —
    /// the status line ("Move closer — anchor within 4 m of the
    /// trunk.") explains how to reframe.
    private func anchorTap() {
        raycaster.preferLiDARMesh = settings.measurementSource == .lidar
        viewModel.anchorHereNow(screenCenterHit: raycaster.screenCenterHit())
    }

    /// Aim Top — crosshair on treetop. The sky has no plane, so the
    /// raycast will almost always miss. Instead, project the camera's
    /// forward ray out to the known horizontal distance `d_h` so the
    /// yellow marker lands roughly at the treetop the cruiser aimed at.
    private func aimTopTap() {
        raycaster.preferLiDARMesh = settings.measurementSource == .lidar
        let hit = raycaster.screenCenterHit()
            ?? raycaster.forwardPointAtHorizontalDistance(viewModel.dhMeters)
        viewModel.captureTopNow(screenCenterHit: hit)
    }

    /// Aim Base — crosshair near the ground at the tree base. Ground
    /// raycast should nearly always hit. Fall back to the same forward-
    /// projection as Aim Top on the rare miss.
    private func aimBaseTap() {
        raycaster.preferLiDARMesh = settings.measurementSource == .lidar
        let hit = raycaster.screenCenterHit()
            ?? raycaster.forwardPointAtHorizontalDistance(viewModel.dhMeters)
        viewModel.captureBaseNow(screenCenterHit: hit)
    }
}
