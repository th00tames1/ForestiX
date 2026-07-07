// Spec §5.2 authoritative DBH scan layout + §4.3 state machine.
//
// Layout is the overlay chrome specified in §5.2:
//   • AR view (or black placeholder in previews/macOS) fills the screen.
//   • Fixed horizontal guide line at y = screen_height/2.
//   • Center crosshair, red until depth-stable then green.
//   • Status banner + result panel at the bottom.
//   • Action row: Retake / Manual / Dual-view / Accept — shown per state.
//
// Per Phase 2 decision #5, snapshot tests only render the overlay chrome;
// the AR view is Color.black so visuals are deterministic across hosts.

import SwiftUI
import Common
import Models
import Sensors
import Positioning
import AR
import simd

public struct DBHScanScreen: View {

    @StateObject private var viewModel: DBHScanViewModel
    @Environment(\.scenePhase) private var scenePhase
    @EnvironmentObject private var settings: AppSettings
    public var onResult: (DBHResult) -> Void = { _ in }
    /// Fired when the cruiser explicitly accepts the on-screen result
    /// (state → .accepted). Use this for flows that want to persist the
    /// reading only after the cruiser has confirmed it — Quick Measure
    /// doesn't record a measurement until Accept is tapped.
    /// `metadata` carries the species / position / damage / note the
    /// cruiser optionally attached via `ScanMetadataSheet`.
    public var onAccept: (DBHResult, ScanMetadata) -> Void = { _, _ in }

    public struct ScanMetadata {
        public var speciesCode: String?
        public var position: QuickMeasureEntry.StemPosition?
        public var damageCodes: [String]
        public var note: String
        /// Auto-capture at Accept (map home): window snapshot + GPS fix.
        public var photoPath: String?
        public var latitude: Double?
        public var longitude: Double?
        public init(speciesCode: String? = nil,
                    position: QuickMeasureEntry.StemPosition? = nil,
                    damageCodes: [String] = [],
                    note: String = "",
                    photoPath: String? = nil,
                    latitude: Double? = nil,
                    longitude: Double? = nil) {
            self.speciesCode = speciesCode
            self.position = position
            self.damageCodes = damageCodes
            self.note = note
            self.photoPath = photoPath
            self.latitude = latitude
            self.longitude = longitude
        }
    }

    @State private var metaSpecies: String?
    @State private var metaPosition: QuickMeasureEntry.StemPosition? = .dbh
    @State private var metaDamage: [String] = []
    @State private var metaNote: String = ""
    @State private var presentingMetadata = false

    /// Bridge that turns SwiftUI screen taps into world-space rays / hits
    /// against the live ARView — used by the AR-caliper two-tap flow.
    @StateObject private var raycaster = ARCenterRaycaster()
    /// Screen location of the first (LEFT) AR-caliper edge tap, kept so we
    /// can raycast the midpoint for distance and draw a marker.
    @State private var arLeftScreenPoint: CGPoint?

    /// Developer-mode research capture: tape-measured true diameter (cm)
    /// typed before Accept; logged with the scan context to the research
    /// CSV so error can be analysed against distance / aim angle.
    @State private var researchTrueCm: String = ""

    /// Hampel-style robust moving average over the screen-centre AR
    /// distance while the caliper is aiming — the estimated-plane raycast
    /// flickers frame-to-frame, and the caliper multiplies that distance
    /// straight into the diameter. Fed by the sampling task below.
    @State private var caliperSmoother = DistanceSmoother()

    /// Selected DBH sensing path (LiDAR depth / AR motion / AR caliper).
    private var methodSource: DBHMethodSource { settings.dbhMethodSource }
    /// Either AR (depth-free) path — depth chrome is hidden for both.
    private var isARMode: Bool { methodSource.isAR }
    private var isCaliperMode: Bool { methodSource == .arCaliper }
    private var isVIOMode: Bool { methodSource == .arMotion }
    /// Methods offered by the picker on this device (no LiDAR ⇒ AR only).
    private var availableMethods: [DBHMethodSource] {
        settings.deviceSupportsLiDAR
            ? [.lidarDepth, .arMotion, .arCaliper]
            : [.arMotion, .arCaliper]
    }

    public init(viewModel: @autoclosure @escaping () -> DBHScanViewModel,
                onResult: @escaping (DBHResult) -> Void = { _ in },
                onAccept: @escaping (DBHResult, ScanMetadata) -> Void = { _, _ in }) {
        _viewModel = StateObject(wrappedValue: viewModel())
        self.onResult = onResult
        self.onAccept = onAccept
    }

    public var body: some View {
        // Field mode is LiDAR-only for DBH: without the scanner the depth
        // pipeline can't run, and the AR-motion / AR-caliper alternatives
        // are Developer-mode research tools — so block the scan UI
        // outright instead of starting an AR session the flow can't use.
        if settings.deviceSupportsLiDAR || settings.developerMode {
            scanBody
        } else {
            lidarRequiredPanel
        }
    }

    /// Centred blocker for non-LiDAR devices with Developer mode off —
    /// shown in place of the whole scan UI so the AR session never
    /// spins up.
    private var lidarRequiredPanel: some View {
        VStack(spacing: ForestixSpace.sm) {
            Image(systemName: "cube.transparent")
                .font(.system(size: 44, weight: .light))
                .foregroundStyle(ForestixPalette.textTertiary)
            Text("DBH scanning requires a LiDAR-equipped iPhone")
                .font(ForestixType.bodyBold)
                .foregroundStyle(ForestixPalette.textPrimary)
                .multilineTextAlignment(.center)
            Text("Alternative AR methods (motion sweep, two-tap caliper) are available in Developer mode.")
                .font(ForestixType.caption)
                .foregroundStyle(ForestixPalette.textSecondary)
                .multilineTextAlignment(.center)
        }
        .padding(ForestixSpace.lg)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(ForestixPalette.canvas.ignoresSafeArea())
        .navigationTitle("Diameter")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .accessibilityIdentifier("dbhScan.lidarRequired")
    }

    private var scanBody: some View {
        ZStack {
            // Live AR camera feed wired to the same ARSession the
            // DBHScanViewModel is consuming depth frames from. Snapshot
            // tests (macOS host) fall back to a black background via
            // ARCameraView's #else branch. The cylinder marker renders
            // the live single-frame fit as a translucent blue cylinder
            // at the trunk's world position — world-anchored, so it
            // stays locked to the tree as the phone moves.
            // Mesh debug overlay OFF — the rainbow scene-reconstruction
            // wireframe added visual noise without helping the cruiser aim
            // (the live Ø readout + guide line already signal lock).
            ARCameraView(manager: viewModel.session,
                         debugMeshOverlay: false,
                         sceneMarkers: cylinderMarkers,
                         raycaster: raycaster)
                .ignoresSafeArea()

            GeometryReader { geo in
                ZStack {
                    if isCaliperMode {
                        // AR caliper: depth chrome (guide line, stability
                        // ring, fit chord, live ø badge) is depth-derived
                        // and meaningless here — show a minimal two-tap aid.
                        arOverlay(in: geo.size)
                    } else if isVIOMode {
                        vioOverlay(in: geo.size)
                    } else {
                        guideLine(height: geo.size.height)
                        fitChord(in: geo.size)
                        // Crosshair ring is now positioned by GeometryReader
                        // at exactly (centerX, midY) so the guide line
                        // passes through the centre of the ring, not above
                        // or below it. The live preview pills sit below;
                        // the TiltBadge sits above so the cruiser sees
                        // device level at the same focal point as the
                        // trunk circle they're aiming at.
                        TiltBadge()
                            .position(x: geo.size.width / 2,
                                      y: geo.size.height / 2
                                           - Self.crosshairOuterRadius
                                           - 22)
                        crosshairRing
                            .position(x: geo.size.width / 2,
                                      y: geo.size.height / 2)
                        livePreviewBadge
                            .position(x: geo.size.width / 2,
                                      y: geo.size.height / 2
                                           + Self.crosshairOuterRadius
                                           + 28)
                    }
                }
                .frame(width: geo.size.width, height: geo.size.height)
            }
            .accessibilityElement(children: .ignore)

            // Screen-wide tap catcher — the primary input for the two AR
            // methods (caliper edge taps, VIO sweep start); the depth method
            // also captures via the right-centre "+" button below.
            tapCatcher

            VStack(spacing: 0) {
                topStrip
                Spacer()
            }

            // Right-centre "+" capture button — active once armed (green
            // crosshair) or while VIO-aiming, so the cruiser taps a fixed
            // control instead of the whole screen.
            if dbhCanCapture {
                MeasureControlColumn(capture: onCaptureButton)
            }

            // Bottom-right DBH method picker — Developer-mode research
            // control cycling LiDAR depth / AR motion / AR caliper (the
            // within-device comparison). Field mode always scans with the
            // LiDAR depth path, so the picker is hidden there.
            if settings.developerMode {
                VStack {
                    Spacer()
                    HStack {
                        Spacer()
                        dbhMethodPickerButton
                            .padding(.trailing, 18)
                            .padding(.bottom, 96)
                    }
                }
            }

            // Bottom-centre status / result panel.
            VStack {
                Spacer()
                bottomPanel
            }
        }
        .devHUDOverlay(settings.developerMode, title: "DBH", lines: devHUDLines)
        // While the caliper is aiming, sample the screen-centre AR distance
        // at ~10 Hz into the robust smoother; the edge taps then read the
        // spike-free average instead of a single flickery raycast. The task
        // is keyed on the state so it starts/stops with the caliper stages.
        .task(id: caliperAiming) {
            guard caliperAiming else { return }
            caliperSmoother.reset()
            while !Task.isCancelled {
                raycaster.preferLiDARMesh = false
                if let cam = raycaster.cameraWorldPosition,
                   let hit = raycaster.screenCenterHit() {
                    caliperSmoother.add(Double(simd_distance(cam, hit)))
                }
                try? await Task.sleep(nanoseconds: 100_000_000)
            }
        }
        .navigationTitle("Diameter")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .onAppear {
            // Phase 19 — pull the cruiser's chosen DBH method off
            // AppSettings every time the screen comes back into view
            // so flipping the picker in Settings takes effect on the
            // next return without leaving the scan screen.
            viewModel.dbhMeasurementMethod = settings.dbhMeasurementMethod
            viewModel.dbhMethodSource = settings.dbhMethodSource
            viewModel.onAppear()
        }
        .onDisappear { viewModel.onDisappear() }
        .onChange(of: settings.dbhMeasurementMethod) { _, m in
            viewModel.dbhMeasurementMethod = m
        }
        .onChange(of: settings.dbhMethodSource) { _, s in
            // Switching method (LiDAR / AR motion / AR caliper) re-enters
            // the correct DBH flow.
            viewModel.dbhMethodSource = s
            viewModel.retake()
        }
        .onChange(of: viewModel.result?.diameterCm) { _, newValue in
            // Fire the host callback when the VM publishes a NON-red result.
            // The host (e.g. AddTreeFlowScreen) records it and dismisses the
            // cover, so a red/rejected fit (diameter 0 cm) must NOT be
            // forwarded — the cruiser stays on screen to retake instead.
            if newValue != nil, let r = viewModel.result, r.confidence != .red {
                onResult(r)
            }
        }
        .onChange(of: viewModel.state) { _, newState in
            // Separate "accept" hook so callers that want to persist
            // only on an explicit user confirmation (Quick Measure) can
            // distinguish a fitted preview from a committed reading.
            if newState == .accepted, let r = viewModel.result {
                // Auto-capture (map home): snapshot the AR view + overlay
                // as evidence of what was measured, plus the latest GPS
                // fix from the badge's running location service.
                let photo = MeasurePhotoStore.captureWindow()
                let fix = LocationService.lastGlobalFix
                let meta = ScanMetadata(
                    speciesCode: metaSpecies,
                    position: metaPosition,
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
                kind: .diameter,
                speciesCode: $metaSpecies,
                position: $metaPosition,
                damageCodes: $metaDamage,
                note: $metaNote)
        }
        .onChange(of: scenePhase) { _, phase in
            // Stop the AR session + depth subscription when the user
            // backgrounds the app — without this the camera, LiDAR,
            // and Combine chain keep running inside a screen that's
            // no longer on screen, drain battery fast, and return
            // from background with a stale tracking state.
            switch phase {
            case .active:     viewModel.onAppear()
            case .inactive, .background: viewModel.onDisappear()
            @unknown default: break
            }
        }
    }

    // MARK: - Top status strip

    /// Thin row pinned just under the nav bar. GPS accuracy on the
    /// left — TiltBadge moved to float right above the crosshair so
    /// the cruiser sees device level at the same focal point as the
    /// trunk circle they're aiming at.
    private var topStrip: some View {
        HStack(spacing: ForestixSpace.xs) {
            GPSAccuracyBadge()
            Spacer()
        }
        .padding(.horizontal, ForestixSpace.sm)
        .padding(.top, ForestixSpace.xs)
    }

    // MARK: - Tap capture

    /// Transparent overlay that forwards taps on the AR region to the
    /// view model. The bottom action panel sits above this on the
    /// Z-axis, so button taps continue to work — this catcher only
    /// receives taps that miss the panel.
    private var tapCatcher: some View {
        Color.clear
            .contentShape(Rectangle())
            .accessibilityIdentifier("dbhScan.tapCatcher")
            // SpatialTapGesture carries the tap location, which the AR
            // caliper needs (the other paths ignore it).
            .gesture(SpatialTapGesture().onEnded { ev in
                if isCaliperMode { captureAREdge(at: ev.location) }
                else if isVIOMode { startVIOSweep() }
                else { captureTap() }
            })
    }

    /// Whether the current state shows the floating "+" capture button.
    /// LiDAR aim/armed → capture; AR-motion aiming → start the sweep. The
    /// AR caliper taps the trunk edges directly, so no "+".
    /// True while the AR caliper is waiting for either edge tap — the
    /// window during which the centre-distance smoother samples.
    private var caliperAiming: Bool {
        viewModel.state == .arAwaitingLeft || viewModel.state == .arAwaitingRight
    }

    private var dbhCanCapture: Bool {
        switch viewModel.state {
        // `.aligning` is intentionally excluded: `viewModel.tap` requires
        // `.armed` (a stable green crosshair), so enabling "+" in `.aligning`
        // gave a live-but-dead button. VIO has no depth gate, so `.vioAiming`
        // stays enabled.
        case .armed, .vioAiming: return true
        default:                 return false
        }
    }

    /// The "+" button — starts a VIO sweep in AR-motion mode, otherwise
    /// captures the depth burst at centre.
    private func onCaptureButton() {
        if isVIOMode { startVIOSweep() } else { captureTap() }
    }

    /// Begin an AR-motion sweep. The aim anchor is the centre AR hit (or a
    /// rough forward point if no surface is found) used to centre the
    /// trunk-band ROI. Pure AR — no LiDAR mesh.
    private func startVIOSweep() {
        guard viewModel.state == .vioAiming else { return }
        raycaster.preferLiDARMesh = false
        guard let anchor = raycaster.screenCenterHit()
                ?? raycaster.forwardPointAtHorizontalDistance(1.5)
        else { return }
        viewModel.startVIOCapture(anchor: anchor)
    }

    /// Capture the trunk at the depth-map centre — the crosshair the
    /// cruiser lined up on the trunk. Fired by both the "+" button and a
    /// stray screen tap.
    private func captureTap() {
        let frame = viewModel.session.latestDepthFrame
        let width  = Double(frame?.width  ?? 256)
        let height = Double(frame?.height ?? 192)
        viewModel.tap(at: SIMD2(width / 2.0, height / 2.0))
    }

    /// AR-caliper edge tap. First tap caches the LEFT trunk-edge ray; the
    /// second supplies the RIGHT ray + an AR-estimated distance and triggers
    /// the diameter computation. Screen points flow straight to the
    /// raycaster — ARKit handles screen→world internally.
    private func captureAREdge(at point: CGPoint) {
        raycaster.preferLiDARMesh = false   // caliper is the AR (depth-free) arm
        guard let dir = raycaster.rayDirection(at: point) else { return }
        switch viewModel.state {
        case .arAwaitingLeft:
            arLeftScreenPoint = point
            viewModel.captureAREdgeLeft(dir: dir)
        case .arAwaitingRight:
            guard let d = arEstimatedDistance(rightPoint: point) else {
                // No plane/mesh hit yet — stay put; the cruiser repositions
                // so a surface is visible and taps the right edge again.
                return
            }
            viewModel.captureAREdgeRight(dir: dir, distanceM: d)
            arLeftScreenPoint = nil
        default:
            break
        }
    }

    /// AR distance to the trunk: prefers the smoothed screen-centre distance
    /// accumulated while aiming (matching the Android caliper), falling back
    /// to a spot centre raycast, then the tap midpoint / edge-hit average.
    /// Returns nil when no surface is found — never substitutes the camera
    /// position (the Phase 8 anchor-bias rule).
    private func arEstimatedDistance(rightPoint: CGPoint) -> Float? {
        guard let cam = raycaster.cameraWorldPosition else { return nil }
        // Distance is sampled at the screen-centre crosshair (the trunk the
        // cruiser lined up on), matching the Android caliper — the two edge
        // taps supply only the subtended angle, not the distance. Prefer the
        // robust moving average accumulated while aiming (AR raycasts
        // flicker); fall back to a spot centre sample, then the tap
        // midpoint / edge average if the centre ray misses.
        if let smoothed = caliperSmoother.value() { return Float(smoothed) }
        if let c = raycaster.screenCenterHit() { return simd_distance(cam, c) }
        guard let left = arLeftScreenPoint else {
            return raycaster.hit(at: rightPoint).map { simd_distance(cam, $0) }
        }
        let mid = CGPoint(x: (left.x + rightPoint.x) / 2,
                          y: (left.y + rightPoint.y) / 2)
        if let h = raycaster.hit(at: mid) { return simd_distance(cam, h) }
        if let lh = raycaster.hit(at: left), let rh = raycaster.hit(at: rightPoint) {
            return (simd_distance(cam, lh) + simd_distance(cam, rh)) / 2
        }
        return nil
    }

    /// Minimal on-screen aid for the AR two-tap flow: a centre aiming mark
    /// plus a dot on the captured left edge while awaiting the right.
    @ViewBuilder
    private func arOverlay(in size: CGSize) -> some View {
        Image(systemName: "plus")
            .font(.system(size: 22, weight: .light))
            .foregroundStyle(.white.opacity(0.85))
            .position(x: size.width / 2, y: size.height / 2)
        if let lp = arLeftScreenPoint, viewModel.state == .arAwaitingRight {
            Circle()
                .fill(ForestixPalette.confidenceOk)
                .frame(width: 12, height: 12)
                .overlay(Circle().stroke(.white, lineWidth: 1.5))
                .position(lp)
        }
    }

    /// Minimal aid for the AR-motion sweep: a centre aiming mark; the ring
    /// pulses while accumulating to cue the cruiser to sweep the phone.
    @ViewBuilder
    private func vioOverlay(in size: CGSize) -> some View {
        let sweeping = viewModel.state == .vioCapturing
        Circle()
            .stroke(sweeping ? ForestixPalette.confidenceOk : .white.opacity(0.85),
                    lineWidth: 2)
            .frame(width: 46, height: 46)
            .position(x: size.width / 2, y: size.height / 2)
        Image(systemName: "plus")
            .font(.system(size: 16, weight: .regular))
            .foregroundStyle(.white.opacity(0.9))
            .position(x: size.width / 2, y: size.height / 2)
    }

    /// Corner button cycling the DBH method (LiDAR / AR motion / AR caliper).
    private var dbhMethodPickerButton: some View {
        Button(action: cycleDBHMethod) {
            HStack(spacing: 5) {
                Image(systemName: "camera.metering.matrix")
                    .font(.system(size: 12, weight: .semibold))
                Text(methodSource.displayName)
                    .font(.system(size: 13, weight: .semibold))
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 12).padding(.vertical, 8)
            .background(.black.opacity(0.55), in: Capsule())
            .overlay(Capsule().stroke(.white.opacity(0.3), lineWidth: 0.5))
        }
        .accessibilityIdentifier("dbhScan.methodPicker")
    }

    private func cycleDBHMethod() {
        let methods = availableMethods
        let next: DBHMethodSource
        if let i = methods.firstIndex(of: methodSource) {
            next = methods[(i + 1) % methods.count]
        } else {
            next = methods.first ?? .arCaliper
        }
        settings.dbhMethodSource = next
    }

    // MARK: - Chrome

    private var devHUDLines: [(String, String)] {
        var out: [(String, String)] = [
            ("method", methodSource.shortTag),
            ("fit", isARMode ? "—" : viewModel.dbhMeasurementMethod.rawValue),
        ]
        if let f = viewModel.session.latestDepthFrame {
            out.append(("depthMap", "\(f.width)×\(f.height)"))
            out.append(("fx/fy", String(format: "%.0f/%.0f", f.intrinsics[0, 0], f.intrinsics[1, 1])))
            out.append(("cx/cy", String(format: "%.0f/%.0f", f.intrinsics[2, 0], f.intrinsics[2, 1])))
        }
        out.append(("Ø live", viewModel.previewDbhCm.map { String(format: "%.1f cm", $0) } ?? "—"))
        out.append(("dist", viewModel.distanceToStemCenterM.map { String(format: "%.2f m", Double($0)) } ?? "—"))
        if let r = viewModel.result {
            out.append(("Ø saved", String(format: "%.1f ±%.0fmm", r.diameterCm, r.sigmaRmm)))
            out.append(("arc/n", String(format: "%.0f°/%d", r.arcCoverageDeg, r.nInliers)))
            out.append(("tier", r.confidence.rawValue))
        }
        return out
    }

    private func guideLine(height: CGFloat) -> some View {
        // Dual-stroke line for sun-glare readability: a thin dark halo
        // under a bright white line. On either a bright sky or dark
        // foliage background, at least one of the two strokes has
        // enough contrast to stay visible.
        ZStack {
            Rectangle()
                .fill(Color.black.opacity(0.55))
                .frame(height: 3)
            Rectangle()
                .fill(Color.white.opacity(0.9))
                .frame(height: 1.5)
        }
        .frame(height: 3)
        .position(x: UIScreenWidth() / 2, y: height / 2)
        .accessibilityIdentifier("dbhScan.guideLine")
    }

    /// Outer radius of the crosshair ring (including the dark halo).
    /// Used as a layout anchor for the live preview pills so they sit
    /// just below the ring without overlapping it.
    private static let crosshairOuterRadius: CGFloat = 36
    /// Vertical extent (above + below the chord) of the side
    /// indicators. Tall, deliberately visible bars — the cruiser
    /// asked for more pop on these so a "fit locked" reads at a
    /// glance even with the trunk only partly in frame.
    private static let chordIndicatorHalfHeight: CGFloat = 22

    /// Bright green horizontal segment spanning the trunk edges the
    /// single-frame fit identified along the guide row — a visual
    /// "this is what I'm measuring" so the cruiser can tell when the
    /// fit has locked onto the trunk vs wandered into the background.
    /// Only drawn once the crosshair has stabilised; hidden during
    /// capture / fit / accept so it doesn't distract from the result.
    @ViewBuilder
    private func fitChord(in size: CGSize) -> some View {
        if let fit = viewModel.previewFit,
           viewModel.crosshairIsStable,
           fit.stripRightFraction > fit.stripLeftFraction {
            let x0 = size.width * CGFloat(fit.stripLeftFraction)
            let x1 = size.width * CGFloat(fit.stripRightFraction)
            let y  = size.height / 2
            let half = Self.chordIndicatorHalfHeight
            ZStack(alignment: .topLeading) {
                // Main chord line — slightly thicker so it reads
                // even when overdrawn on top of the LiDAR mesh.
                Rectangle()
                    .fill(ForestixPalette.confidenceOk.opacity(0.95))
                    .frame(width: x1 - x0, height: 4)
                    .position(x: (x0 + x1) / 2, y: y)
                // Left side indicator — tall vertical bar with a dark
                // halo for sun-readability. Length is 2 × half so it
                // pops well above and below the chord line.
                sideIndicator(x: x0, y: y, half: half)
                // Right side indicator
                sideIndicator(x: x1, y: y, half: half)
            }
            .accessibilityIdentifier("dbhScan.fitChord")
            .allowsHitTesting(false)
        }
    }

    /// Trunk-side indicator: dark halo bar with a coloured bar on
    /// top, centred at (x, y) and 2×half tall.
    private func sideIndicator(x: CGFloat, y: CGFloat, half: CGFloat) -> some View {
        ZStack {
            Rectangle()
                .fill(Color.black.opacity(0.55))
                .frame(width: 5, height: 2 * half)
            Rectangle()
                .fill(ForestixPalette.confidenceOk)
                .frame(width: 3, height: 2 * half)
        }
        .position(x: x, y: y)
    }

    private var crosshairRing: some View {
        let color: Color = viewModel.crosshairIsStable
            ? ForestixPalette.confidenceOk
            : ForestixPalette.confidenceBad
        let outer = Self.crosshairOuterRadius * 2     // 72 pt total
        let inner = outer - 8                          // ring inset
        return ZStack {
            // Dual-stroke ring — dark halo underneath the coloured
            // ring so the crosshair stays visible against both sky
            // and foliage. Sized to match the cruiser's request for
            // a clearly-visible target ring on the AR feed.
            Circle()
                .strokeBorder(Color.black.opacity(0.6), lineWidth: 5)
                .frame(width: outer, height: outer)
            Circle()
                .strokeBorder(color, lineWidth: 2.5)
                .frame(width: inner, height: inner)
        }
        .accessibilityIdentifier("dbhScan.crosshair")
        .accessibilityLabel(viewModel.crosshairIsStable
                            ? "Depth stable — tap to capture"
                            : "Aligning — move closer or steadier")
    }

    /// Two pills floating below the crosshair:
    ///   • "DBH: 34.5 cm" + tier chip — diameter estimate (bold, primary)
    ///   • "Distance: 1.25 m" — camera-to-stem-axis distance (dimmer)
    /// When the fit fails the §7.1 sanity tree (red) or hasn't settled
    /// yet, the numeric pill is replaced with a status string so the
    /// cruiser never reads a value the burst would later reject. Phase
    /// 14.4 made the published preview match the burst's quality bar —
    /// the value on screen is the value you can record.
    @ViewBuilder
    private var livePreviewBadge: some View {
        if let cm = viewModel.previewDbhCm {
            VStack(spacing: 3) {
                HStack(spacing: 6) {
                    Text("DBH: " + MeasurementFormatter.diameter(
                        cm: cm, in: settings.unitSystem))
                        .font(ForestixType.data)
                        .foregroundStyle(.white)
                    // Phase 18.2: yellow now publishes the same as
                    // green, so we only surface a chip when the fit
                    // is unambiguously good. Yellow stays silent —
                    // the cruiser sees a bare DBH digit.
                    if let tier = viewModel.previewTier, tier == .green {
                        previewTierChip(tier)
                    }
                }
                .padding(.horizontal, 8).padding(.vertical, 4)
                .background(Color.black.opacity(0.65))
                .clipShape(Capsule())
                .accessibilityIdentifier("dbhScan.livePreview")
                if let d = viewModel.distanceToStemCenterM {
                    Text("Distance: " + MeasurementFormatter.distance(
                        m: Double(d), in: settings.unitSystem))
                        .font(ForestixType.dataSmall)
                        .foregroundStyle(.white.opacity(0.85))
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color.black.opacity(0.45))
                        .clipShape(Capsule())
                        .accessibilityIdentifier("dbhScan.distanceBadge")
                }
            }
        } else if let status = viewModel.previewStatusText {
            // Phase 19 — only the legacy partial-arc method ever sets
            // `previewStatusText` (the chord method returns nil for
            // unmeasurable frames instead of producing a red fit). On
            // the chord path the badge area stays empty until the
            // chord actually locks, which is exactly what the cruiser
            // expects from an Arboreal-style HUD.
            Text(status)
                .font(ForestixType.dataSmall)
                .foregroundStyle(.white)
                .padding(.horizontal, 8).padding(.vertical, 4)
                .background(Color.black.opacity(0.65))
                .clipShape(Capsule())
                .accessibilityIdentifier("dbhScan.previewStatus")
        } else {
            Color.clear.frame(height: 40)
        }
    }

    /// Compact tier chip rendered next to the live DBH digit. Uses the
    /// same palette the result panel uses post-capture so the cruiser
    /// reads the same colour language pre- and post-tap.
    @ViewBuilder
    private func previewTierChip(_ tier: ConfidenceTier) -> some View {
        let d = ConfidenceStyle.descriptor(for: tier.rawValue)
        Text(d.label.uppercased())
            .font(.system(size: 9, weight: .semibold))
            .tracking(0.6)
            .foregroundStyle(d.color)
            .padding(.horizontal, 5).padding(.vertical, 1)
            .overlay(
                RoundedRectangle(cornerRadius: ForestixRadius.chip,
                                 style: .continuous)
                    .stroke(d.color, lineWidth: 0.75)
            )
    }

    // MARK: - AR markers

    /// Blue translucent cylinder rendered at the live preview fit.
    /// The cylinder is 1 m tall centred on the guide-row world Y so
    /// it visually "sleeves" the trunk at DBH height. Empty when no
    /// preview is available.
    private var cylinderMarkers: [ARSceneMarker] {
        guard let fit = viewModel.previewFit,
              let y = viewModel.guideRowWorldY
        else { return [] }
        let pos = SIMD3<Float>(
            Float(fit.centerWorldXZ.x),
            y,
            Float(fit.centerWorldXZ.y))   // SIMD2.y = world Z
        return [
            ARSceneMarker(
                id: Self.cylinderMarkerId,
                worldPosition: pos,
                shape: .cylinder(radiusM: Float(fit.radiusM), heightM: 1.0),
                colorRGBA: SIMD4(0.30, 0.65, 1.00, 0.45))
        ]
    }

    /// Stable UUID so the cylinder anchor doesn't get torn down and
    /// rebuilt on every frame — just its position / radius updates.
    /// Must be a valid hex UUID: 0-9 / a-f only, no alphabetic filler.
    /// The `?? UUID()` fallback keeps a typo from crashing the scan
    /// screen — worst case the cylinder anchor gets rebuilt each
    /// frame instead of diffed, which is ugly but not fatal.
    private static let cylinderMarkerId: UUID =
        UUID(uuidString: "00DBC415-0000-0000-0000-000000000001") ?? UUID()

    // MARK: - Bottom panel

    @ViewBuilder
    private var bottomPanel: some View {
        MeasureStatusPanel {
            if let banner = viewModel.unsupportedBanner {
                bannerView(banner, tint: .orange)
            }
            statusBanner
            if let result = viewModel.result, viewModel.state != .manualEntry {
                resultPanel(result)
            }
            if viewModel.state == .manualEntry {
                manualEntryPanel
            }
            actionRow
        }
    }

    private var statusBanner: some View {
        Text(statusText)
            .font(.callout)
            .foregroundStyle(.white)
            .accessibilityIdentifier("dbhScan.statusBanner")
    }

    private var statusText: String {
        switch viewModel.state {
        case .idle:         return "Starting camera…"
        case .aligning:     return "Align the guide to the trunk's uphill side; hold steady."
        case .armed:        return "Hold steady, then tap + to capture."
        case .capturing:
            return "Capturing \(max(1, viewModel.captureSampleIndex))/\(viewModel.captureSampleTotal) — hold steady."
        case .fitted:       return "Scan complete. Accept, retake, or add a second view."
        case .accepted:     return "Saved."
        case .rejected:     return viewModel.result?.rejectionReason
                                 ?? "Scan rejected. Try again."
        case .manualEntry:  return "Enter diameter manually in cm."
        case .arAwaitingLeft:
            return "AR caliper — aim at breast height, tap the LEFT trunk edge."
        case .arAwaitingRight:
            return "Now tap the RIGHT trunk edge."
        case .vioAiming:
            return "AR motion — aim at the trunk, tap + then sweep the phone slowly across it."
        case .vioCapturing:
            return "Sweeping… keep the trunk centred and move side-to-side."
        }
    }

    /// Developer-mode research CSV row — the diagnostic context the
    /// accuracy study needs (distance, aim pitch, n, σ, tier, true value).
    private func recordResearchRow(_ r: DBHResult) {
        guard settings.developerMode else { return }
        var f: [String: String] = [
            "measure_type": "dbh",
            "method": r.method.rawValue,
            "depth_source": settings.dbhMethodSource.rawValue,
            "measured_value": String(format: "%.2f", r.diameterCm),
            "unit": "cm",
            "sigma": String(format: "%.1f", r.sigmaRmm),
            "confidence_tier": r.confidence.rawValue,
            "n_points": "\(r.nInliers)",
            "arc_deg": String(format: "%.1f", r.arcCoverageDeg),
            "rmse_mm": String(format: "%.1f", r.rmseMm),
            "species": metaSpecies ?? "",
            "note": metaNote,
        ]
        if !settings.researchTreeId.isEmpty {
            f["tree_id"] = settings.researchTreeId   // repeat auto-filled by record()
        }
        if let d = viewModel.distanceToStemCenterM {
            f["distance_m"] = String(format: "%.2f", d)
        }
        if let p = raycaster.cameraPitchDeg {
            f["pitch_deg"] = String(format: "%.1f", p)
        }
        if let frame = viewModel.session.latestDepthFrame {
            f["fx"] = String(format: "%.1f", frame.intrinsics.columns.0.x)
            f["depth_w"] = "\(frame.width)"
            f["depth_h"] = "\(frame.height)"
        }
        if let t = Double(researchTrueCm), t > 0 {
            f["true_value"] = String(format: "%.2f", t)
            f["error"] = String(format: "%.2f", Double(r.diameterCm) - t)
        }
        ResearchLog.shared.record(f)
        researchTrueCm = ""
    }

    @ViewBuilder
    private func resultPanel(_ r: DBHResult) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                // Monospaced number so the value aligns with the FIELD
                // LOG on the home screen — a cruiser reading the log
                // expects the same glyph widths everywhere.
                Text(MeasurementFormatter.diameter(
                    cm: Double(r.diameterCm), in: settings.unitSystem))
                    .font(ForestixType.dataLarge)
                    .foregroundStyle(.white)
                Spacer()
                // Phase 18.2: yellow is treated as a normal record-able
                // fit, so we don't flag it. Only show the chip + hint
                // for green (good) or red (must retake / manual).
                if r.confidence != .yellow {
                    tierChip(r.confidence)
                }
            }
            if r.confidence != .yellow {
                Text(tierHint(r.confidence))
                    .font(ForestixType.caption)
                    .foregroundStyle(.white.opacity(0.9))
            }
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
                .accessibilityIdentifier("dbhScan.editMetadata")
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
                        .accessibilityIdentifier("dbhScan.researchTarget")
                    Text("True Ø (cm)")
                        .font(ForestixType.caption)
                        .foregroundStyle(.white.opacity(0.8))
                    TextField("tape", text: $researchTrueCm)
                        .keyboardType(.decimalPad)
                        .textFieldStyle(.roundedBorder)
                        .frame(width: 90)
                        .accessibilityIdentifier("dbhScan.researchTrue")
                }
            }
        }
        .foregroundStyle(.white)
        .accessibilityIdentifier("dbhScan.resultPanel")
    }

    /// Pill label for the metadata-edit chip — surfaces what's
    /// already attached so the cruiser doesn't have to open the
    /// sheet to remember.
    private var metadataChipLabel: String {
        var bits: [String] = []
        if let s = metaSpecies, !s.isEmpty { bits.append(s) }
        if let p = metaPosition, p != .dbh { bits.append(p.displayName) }
        if !metaDamage.isEmpty { bits.append("\(metaDamage.count) tag") }
        if bits.isEmpty { return "Add details" }
        return bits.joined(separator: " · ")
    }

    /// Short cruiser-actionable sentence matching the tier. The spec
    /// metrics below stay for diagnostics; this line tells the cruiser
    /// what to actually do next.
    private func tierHint(_ tier: ConfidenceTier) -> String {
        switch tier {
        case .green:  return "Good — wide arc, low scatter. Safe to record."
        case .yellow: return "Fair — narrow arc or noisier fit. Consider a second pass."
        case .red:    return "Check — step left 1 m and retake, or enter manually."
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

    @ViewBuilder
    private var manualEntryPanel: some View {
        HStack {
            TextField(settings.unitSystem == .metric
                      ? "Diameter in cm"
                      : "Diameter in inches",
                      text: $viewModel.manualDbhCm)
                .textFieldStyle(.roundedBorder)
                #if os(iOS)
                .keyboardType(.decimalPad)
                #endif
                .accessibilityIdentifier("dbhScan.manualInput")
            Button("Save") { viewModel.submitManualEntry() }
                .buttonStyle(.forestixProminent)
                .accessibilityIdentifier("dbhScan.manualSave")
        }
    }

    // MARK: - Actions

    @ViewBuilder
    private var actionRow: some View {
        switch viewModel.state {
        case .fitted:
            HStack(spacing: 12) {
                Button("Retake") { viewModel.retake() }
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity)
                Button("Manual") { viewModel.enterManualEntry() }
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity)
                Button("Accept") { viewModel.accept() }
                    .buttonStyle(.forestixProminent)
                    .frame(maxWidth: .infinity)
                    .disabled(viewModel.result?.confidence == .red)
            }
        case .rejected:
            HStack(spacing: 12) {
                Button("Retake") { viewModel.retake() }
                    .buttonStyle(.forestixProminent)
                    .frame(maxWidth: .infinity)
                Button("Manual") { viewModel.enterManualEntry() }
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity)
            }
        case .manualEntry:
            HStack(spacing: 12) {
                Button("Cancel") { viewModel.retake() }
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity)
            }
        case .idle, .aligning, .armed, .capturing, .accepted,
             .arAwaitingLeft, .arAwaitingRight, .vioAiming, .vioCapturing:
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
            .accessibilityIdentifier("dbhScan.unsupportedBanner")
    }

    // Cross-platform screen width accessor — UIScreen is iOS-only.
    private func UIScreenWidth() -> CGFloat {
        #if canImport(UIKit)
        return UIScreen.main.bounds.width
        #else
        return 400
        #endif
    }
}
