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
    /// Developer-mode research capture: true height (m) from a clinometer /
    /// Vertex, typed before Accept; logged to the research CSV.
    @State private var researchTrueM: String = ""
    /// (mesh overlay removed — the Height scan uses the plane/tangent walk-off
    /// and the LiDAR reconstruction wireframe was just visual noise.)
    /// PLACEHOLDER-COMMENT

    public init(viewModel: @autoclosure @escaping () -> HeightScanViewModel,
                onResult: @escaping (HeightResult) -> Void = { _ in },
                onAccept: @escaping (HeightResult, ScanMetadata) -> Void = { _, _ in },
                onCrown: @escaping (Double, Double) -> Void = { _, _ in }) {
        _viewModel = StateObject(wrappedValue: viewModel())
        self.onResult = onResult
        self.onAccept = onAccept
        self.onCrown = onCrown
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
                         sceneMarkers: viewModel.sceneMarkers + crownMarkers,
                         raycaster: raycaster)
                .ignoresSafeArea()
            overlayChrome
            VStack(spacing: 0) {
                // Same GPS-accuracy strip as the Diameter scan — gives
                // the cruiser a single-glance read on canopy quality
                // before they anchor.
                HStack {
                    GPSAccuracyBadge()
                    Spacer()
                }
                .padding(.horizontal, ForestixSpace.sm)
                .padding(.top, ForestixSpace.xs)
                Spacer()
            }

            // Right-centre "+" capture button — replaces the centre
            // Anchor Here / Aim Top / Aim Base buttons. It fires the
            // current stage's capture action.
            if hasPrimaryCapture {
                MeasureControlColumn(capture: primaryCapture)
            }

            // Bottom-right LiDAR/AR toggle.
            VStack {
                Spacer()
                HStack {
                    Spacer()
                    MeasureSourceToggleButton()
                        .padding(.trailing, 18)
                        .padding(.bottom, 96)
                }
            }

            // Bottom-centre status / value panel.
            VStack {
                Spacer()
                bottomPanel
            }
        }
        .devHUDOverlay(settings.developerMode, title: "HEIGHT", lines: devHUDLines)
        .navigationTitle("Height")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .onAppear { viewModel.onAppear() }
        .onDisappear { viewModel.onDisappear() }
        .onChange(of: viewModel.result?.heightM) { _, newValue in
            if newValue != nil, let r = viewModel.result {
                onResult(r)
            }
        }
        .onChange(of: viewModel.state) { _, newState in
            if newState == .accepted, let r = viewModel.result {
                let photo = MeasurePhotoStore.captureWindow()
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

    @ViewBuilder
    private var bottomPanel: some View {
        MeasureStatusPanel {
            if viewModel.trackingDroppedDuringMeasurement {
                bannerView(
                    "AR tracking dropped during measurement.",
                    tint: .orange)
                    .accessibilityIdentifier("heightScan.trackingBanner")
            }
            if let reason = viewModel.anchorFailureReason {
                bannerView(reason, tint: .orange)
                    .accessibilityIdentifier("heightScan.anchorFailureBanner")
                    .onTapGesture { viewModel.clearAnchorFailure() }
            }
            statusBanner
            stagePanel
            actionRow
        }
    }

    private var statusBanner: some View {
        Text(statusText)
            .font(.callout)
            .foregroundStyle(.white)
            .accessibilityIdentifier("heightScan.statusBanner")
    }

    private var statusText: String {
        switch viewModel.state {
        case .idle, .anchorSet:   return "Aim at the trunk at eye level, then tap +."
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
        case .walking:
            walkingReadout
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
                Text(String(format: "Crown  %.2f m wide · %.2f m tall", w, h))
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

    private var walkingReadout: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Walked back " + MeasurementFormatter.distance(
                m: Double(viewModel.dhMeters), in: settings.unitSystem))
                .font(ForestixType.dataLarge)
                .foregroundStyle(.white)
            Text(walkHintText)
                .font(ForestixType.caption)
                .foregroundStyle(ForestixPalette.confidenceWarn)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityIdentifier("heightScan.walkingReadout")
    }

    private var walkHintText: String {
        let delta = viewModel.walkHintMeters
        let expected = viewModel.expectedHeightM
        if delta > 0.1 {
            return "Move back \(String(format: "%.1f", delta)) m "
                   + "(target ≈ 0.6–1.0 · \(Int(expected)) m)"
        } else if delta < -0.1 {
            return "Move forward \(String(format: "%.1f", -delta)) m"
        } else {
            return "You're in the sweet-spot band. Continue."
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
                Text(MeasurementFormatter.height(
                    m: Double(r.heightM), in: settings.unitSystem))
                    .font(ForestixType.dataLarge)
                    .foregroundStyle(.white)
                if r.confidence != .red {
                    Text(MeasurementFormatter.heightSigma(
                        m: Double(r.sigmaHm), in: settings.unitSystem))
                        .font(ForestixType.dataSmall)
                        .foregroundStyle(.white.opacity(0.75))
                }
                Spacer()
                tierChip(r.confidence)
            }
            Text(tierHint(r.confidence))
                .font(ForestixType.caption)
                .foregroundStyle(.white.opacity(0.9))
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

    /// Actionable one-liner per tier — same pattern as the Diameter
    /// result panel so the cruiser gets consistent guidance.
    private func tierHint(_ tier: ConfidenceTier) -> String {
        switch tier {
        case .green:  return "Good — geometry in sweet spot."
        case .yellow: return "Fair — long walk-off or steep aim. Acceptable."
        case .red:    return "Check — retake, or enter a tape estimate manually."
        }
    }

    private func tierChip(_ tier: ConfidenceTier) -> some View {
        let d = ConfidenceStyle.descriptor(for: tier.rawValue)
        return Text(d.label.uppercased())
            .font(.system(size: 10, weight: .semibold, design: .default))
            .tracking(0.8)
            .padding(.horizontal, 8).padding(.vertical, 3)
            .overlay(
                RoundedRectangle(cornerRadius: ForestixRadius.chip,
                                 style: .continuous)
                    .stroke(d.color, lineWidth: 0.75)
            )
            .foregroundStyle(d.color)
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

    /// Secondary actions only — the primary capture moved to the "+"
    /// button on the trailing edge.
    @ViewBuilder
    private var actionRow: some View {
        switch viewModel.state {
        case .idle, .anchorSet:
            Button("Manual") { viewModel.enterManualEntry() }
                .buttonStyle(.bordered)
        case .walking, .aimTopArmed, .aimTopCaptured, .aimBaseArmed:
            Button("Retake") { viewModel.retake() }
                .buttonStyle(.bordered)
        case .computed:
            VStack(spacing: 8) {
                // Crown control: start it, or restart it once done.
                if crownStep == .none {
                    Button("Measure crown") { crownStep = .left }
                        .buttonStyle(.bordered)
                        .frame(maxWidth: .infinity)
                        .accessibilityIdentifier("heightScan.measureCrown")
                } else if crownStep == .done {
                    Button("Redo crown") { resetCrown() }
                        .buttonStyle(.bordered)
                        .frame(maxWidth: .infinity)
                }
                HStack(spacing: 12) {
                    Button("Retake") { viewModel.retake(); resetCrown() }
                        .buttonStyle(.bordered)
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
                    .buttonStyle(.bordered)
            }
        case .manualEntry:
            Button("Cancel") { viewModel.retake() }
                .buttonStyle(.bordered)
        case .accepted:
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
    /// the anchor. If the raycast misses, the view model surfaces
    /// `anchorFailureReason` and the screen banner explains how to
    /// reframe.
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
