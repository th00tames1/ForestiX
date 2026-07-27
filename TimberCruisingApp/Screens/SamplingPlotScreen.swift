// Sampling plot — aim the centre crosshair at the plot centre and tap the
// "+" capture button to drop a short white stake plus a translucent ring
// marking the sampling boundary. Adjust the radius via the slider at the
// top. When the device moves outside the ring the border flashes red and
// the device vibrates until the cruiser walks back inside.
//
// Field round 8: the plot centre is pinned to a REAL ARKit ARAnchor on
// the app-shared AR session and recorded app-scoped in
// `ActiveSamplingPlot.shared` — so the plot survives screen switches
// (DBH / Height render it as a subdued overlay), benefits from ARKit's
// world-map drift corrections, and re-appears when this screen is
// reopened. Reset removes the anchor and clears the store; nothing is
// persisted across app restarts (the AR world map dies with the process).
//
// UI matches the shared camera-style capture layout: right-centre capture
// button and a compact centred status panel. (The bottom-right LiDAR/AR
// toggle was removed in field report F5 — the sensor path is decided by
// `AppSettings.measurementSource`, not by a control.)

import SwiftUI
import Common
import Models
import Sensors
import AR
import simd

#if canImport(UIKit)
import UIKit
#endif

public struct SamplingPlotScreen: View {

    @EnvironmentObject private var history: QuickMeasureHistory
    @EnvironmentObject private var settings: AppSettings
    /// App-shared AR session. Deliberately NOT observed (@StateObject) —
    /// the manager publishes camera pose at 60 Hz, and observing it made
    /// this screen's whole body re-evaluate every ARKit frame (the
    /// sampling-screen lag). All per-frame state this screen needs comes
    /// from the 0.2 s poll below.
    private var session: ARKitSessionManager { .shared }
    /// App-scoped active plot {anchorID, radiusM, placedAt} — observed,
    /// but it only changes on place / radius change / reset.
    @ObservedObject private var activePlot = ActiveSamplingPlot.shared
    @StateObject private var raycaster = ARCenterRaycaster()
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dismiss) private var dismiss

    @State private var radiusM: Double = 8.0
    @State private var isOutside: Bool = false
    @State private var distanceFromCenterM: Double?
    @State private var pollTimer: Timer?
    /// Haptic cadence clock — flips every 0.2 s poll tick so the
    /// outside-warning buzz repeats every 0.4 s.
    @State private var hapticTick: Bool = false
    /// Border opacity for the outside warning — driven by a smooth
    /// repeating 350 ms animation (0.95 ↔ 0.35), not the poll timer.
    @State private var pulseAlpha: Double = 0.95
    @State private var captureFailureReason: String?
    #if canImport(UIKit)
    @State private var feedbackGen: UINotificationFeedbackGenerator?
    #endif

    /// Shared-session client token for this screen (one instance at a
    /// time, so a static token is safe and survives struct re-inits).
    private static let arClientID = UUID()

    // Stable marker ids so ARCameraView diffs (instead of tearing down and
    // rebuilding every marker on every body re-eval — the 0.2 s poll timer
    // re-evaluates `markers`, and fresh random UUIDs there made it rebuild
    // the 96-segment ring continuously, which tanked the frame rate).
    private static let baseSphereId = UUID(uuidString: "00000000-5A11-0000-0000-000000000001") ?? UUID()
    private static let poleId       = UUID(uuidString: "00000000-5A11-0000-0000-000000000002") ?? UUID()
    private static let topSphereId  = UUID(uuidString: "00000000-5A11-0000-0000-000000000003") ?? UUID()
    private static let ringId       = UUID(uuidString: "00000000-5A11-0000-0000-000000000004") ?? UUID()

    /// CRUISE MODE — when set, Save hands the placed ring (radius in
    /// metres) to the caller instead of appending a QuickMeasureEntry;
    /// the caller persists it as a cruise `Plot`. The quick-measure
    /// path passes nothing and keeps today's behaviour byte-identical.
    /// The AR anchor + `ActiveSamplingPlot` are placed the same way on
    /// both paths, so the scan screens' ring overlay comes for free.
    private let onSaveCruisePlot: ((Double) -> Void)?

    public init(onSaveCruisePlot: ((Double) -> Void)? = nil) {
        self.onSaveCruisePlot = onSaveCruisePlot
    }

    /// A plot is active (placed on this screen or an earlier visit).
    private var hasPlot: Bool { activePlot.plot != nil }

    public var body: some View {
        ZStack {
            // Real-world occlusion ON (this screen only): the boundary
            // ring and centre pole render BEHIND tree trunks and other
            // scanned geometry instead of floating over everything — the
            // ring reads as if painted on the forest floor. The DBH /
            // Height screens deliberately stay un-occluded so their
            // treetop spheres and subdued ring overlay remain visible
            // through canopy. (The `.samplingPlot` session preset keeps
            // sceneReconstruction running, which feeds the occlusion.)
            ARCameraView(manager: session,
                         debugMeshOverlay: false,
                         realWorldOcclusion: true,
                         sceneMarkers: markers,
                         raycaster: raycaster)
                .ignoresSafeArea()

            // Live red border pulse whenever the device is outside the
            // sampling area — a smooth 350 ms alpha breathe (0.95 ↔ 0.35)
            // matching Android's infinite tween, not a hard flash.
            if isOutside {
                Rectangle()
                    .stroke(Color.red.opacity(pulseAlpha), lineWidth: 12)
                    .ignoresSafeArea()
                    .allowsHitTesting(false)
                    .onAppear {
                        pulseAlpha = 0.95
                        withAnimation(.easeInOut(duration: 0.35)
                            .repeatForever(autoreverses: true)) {
                            pulseAlpha = 0.35
                        }
                    }
            }

            if !hasPlot { centerCrosshair }

            // Floating back button — full-bleed chrome exit (the system
            // nav bar is hidden on the AR screens).
            MeasureBackButtonRow()

            // Top radius slider, with the plot mini-map tucked under its
            // trailing edge once the centre is placed. (The locked
            // top-right slot from the scan screens is occupied by the
            // full-width slider card here, so the widget drops just
            // below it — still top-right, still clear of the centre
            // handles and the capture rail.)
            VStack(spacing: 0) {
                topControls
                if hasPlot {
                    HStack {
                        Spacer()
                        PlotMiniMapWidget(info: PlotMiniMapInfo(
                            plotID: nil,
                            plotNumber: nil,
                            radiusM: radiusM,
                            centerLat: nil,
                            centerLon: nil,
                            treeCount: 0,
                            trees: []))
                            .padding(.trailing, ForestixSpace.md)
                    }
                    .padding(.top, ForestixSpace.xs)
                }
                Spacer()
            }

            // Top-centre instruction banner (U1) — capture-failure hints
            // first, else the aim string while placing.
            MeasureTopBanner(topBannerText)

            // (The bottom-right LiDAR/AR toggle is GONE — field report F5.
            // `AppSettings.measurementSource` is untouched and still
            // decides which sensor path raycasts; only the control went.)

            // Bottom block (U2): the camera-app shutter sits bottom-
            // centre while aiming (no secondaries on this screen); once
            // the centre is placed the INSIDE/OUTSIDE value panel with
            // Reset / Save occupies the bottom instead.
            VStack {
                Spacer()
                if hasPlot {
                    bottomPanel
                } else {
                    MeasureShutterRow(capture: placePlotIfNeeded)
                }
            }
        }
        // Full-bleed AR chrome — no system nav bar; the floating back
        // button is the exit affordance for both presentation paths.
        #if os(iOS)
        .toolbar(.hidden, for: .navigationBar)
        #endif
        .onAppear {
            session.attach(client: Self.arClientID, configuration: .samplingPlot)
            // An earlier visit may have left a plot active — pick its
            // radius back up so the slider and ring agree with it.
            if let plot = activePlot.plot { radiusM = plot.radiusM }
            startPolling()
            #if canImport(UIKit)
            feedbackGen = UINotificationFeedbackGenerator()
            feedbackGen?.prepare()
            #endif
        }
        .onDisappear {
            session.detach(client: Self.arClientID)
            stopPolling()
        }
        .onChange(of: radiusM) { _, r in
            // Keep the app-scoped plot's radius in sync with the slider
            // (no-op until a plot is placed) so DBH / Height overlay the
            // same ring the cruiser last saw here.
            activePlot.updateRadius(r)
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active:
                session.attach(client: Self.arClientID,
                               configuration: .samplingPlot)
                startPolling()
            case .inactive, .background:
                session.detach(client: Self.arClientID)
                stopPolling()
            @unknown default:
                break
            }
        }
    }

    // MARK: - Top controls (radius slider)

    private var topControls: some View {
        VStack(spacing: 8) {
            HStack {
                Text("SAMPLING RADIUS")
                    .font(ForestixType.sectionHead)
                    .tracking(1.2)
                    .foregroundStyle(.white.opacity(0.85))
                Spacer()
                Text(String(format: "%.1f m", radiusM))
                    .font(ForestixType.data)
                    .foregroundStyle(.white)
            }
            Slider(value: $radiusM, in: 1.0...30.0, step: 0.5)
                .tint(ForestixPalette.confidenceWarn)
                .accessibilityIdentifier("samplingPlot.radius")
        }
        .padding(10)
        .background(Color.black.opacity(0.55))
        .cornerRadius(ForestixRadius.card)
        .padding(.horizontal, ForestixSpace.md)
        // Below the floating back row: 16 top inset + 44 button + 8 gap.
        .padding(.top, 68)
    }

    // MARK: - Bottom panel (centred, half-width; placed state only)

    private var bottomPanel: some View {
        MeasureStatusPanel {
            // Big tinted INSIDE / OUTSIDE readout — a boundary state the
            // cruiser can read at arm's length. (The pre-placement aim
            // instruction lives in the top banner.)
            Text(statusTitle)
                .font(ForestixType.dataLarge)
                .foregroundStyle(isOutside
                                 ? ForestixPalette.confidenceBad
                                 : ForestixPalette.confidenceOk)
            Text(distanceLine)
                .font(ForestixType.dataSmall)
                .foregroundStyle(.white.opacity(0.85))
            HStack(spacing: 12) {
                Button("Reset") { reset() }
                    .buttonStyle(.forestixARSecondary)
                    .frame(maxWidth: .infinity)
                Button("Save") { savePlot() }
                    .buttonStyle(.forestixProminent)
                    .frame(maxWidth: .infinity)
                    .accessibilityIdentifier("samplingPlot.save")
            }
            .padding(.top, 2)
        }
    }

    /// Guidance for the top banner: capture failures first, else the
    /// aim instruction while no centre is placed.
    private var topBannerText: String? {
        if let reason = captureFailureReason { return reason }
        return hasPlot ? nil : "Set the radius, aim at the plot centre, tap +"
    }

    private var statusTitle: String {
        isOutside ? "OUTSIDE — walk back inside" : "INSIDE sampling area"
    }

    private var distanceLine: String {
        guard let d = distanceFromCenterM else { return "—" }
        return String(format: "Centre: %.2f m · area: %.1f m²",
                      d, .pi * radiusM * radiusM)
    }

    // MARK: - Center crosshair

    private var centerCrosshair: some View {
        ZStack {
            Circle().strokeBorder(Color.black.opacity(0.5), lineWidth: 4).frame(width: 36, height: 36)
            Circle().strokeBorder(Color.white, lineWidth: 2).frame(width: 32, height: 32)
            Rectangle().fill(Color.white).frame(width: 12, height: 1.5)
            Rectangle().fill(Color.white).frame(width: 1.5, height: 12)
        }
    }

    // MARK: - Plot placement

    private func placePlotIfNeeded() {
        guard !hasPlot else { return }
        raycaster.preferLiDARMesh = settings.measurementSource == .lidar
        guard let hit = raycaster.screenCenterHit()
                ?? raycaster.forwardPointAtHorizontalDistance(3.0)
        else {
            captureFailureReason = "Couldn't see the ground here. Aim at the ground and try again."
            return
        }
        // Pin the centre to a real ARKit anchor on the shared session —
        // ARKit's world-map corrections keep it fixed to the physical
        // ground from here on; every marker derives from its live
        // transform. Replaces any earlier plot's anchor.
        guard let anchorID = session.addWorldAnchor(
            at: hit, name: "forestix.samplingPlot.center")
        else {
            captureFailureReason = "Couldn't see the ground here. Aim at the ground and try again."
            return
        }
        captureFailureReason = nil
        activePlot.place(anchorID: anchorID, radiusM: radiusM)
    }

    private func reset() {
        if let plot = activePlot.plot {
            session.removeWorldAnchor(id: plot.anchorID)
        }
        activePlot.clear()
        isOutside = false
        distanceFromCenterM = nil
        hapticTick = false
    }

    private func savePlot() {
        guard hasPlot else { return }
        // Cruise mode: the host persists the ring as a cruise Plot;
        // nothing is written to the quick-measure history.
        if let onSaveCruisePlot {
            onSaveCruisePlot(radiusM)
            dismiss()
            return
        }
        let area = .pi * radiusM * radiusM
        history.append(QuickMeasureEntry(
            kind: .samplingPlot,
            value: radiusM,
            secondaryValue: area,
            sigma: nil,
            confidenceRaw: "green",
            method: "ar.tap",
            plotID: history.activePlotID))
        // Saving is the end of the flow — exit the screen (both
        // platforms); staying here only invited a duplicate save.
        dismiss()
    }

    // MARK: - Polling (distance + haptics)

    private func startPolling() {
        pollTimer?.invalidate()
        pollTimer = Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true) { _ in
            Task { @MainActor in
                tickOutsideCheck()
                hapticTick.toggle()
            }
        }
    }

    private func stopPolling() {
        pollTimer?.invalidate()
        pollTimer = nil
    }

    @MainActor
    private func tickOutsideCheck() {
        // The centre is the plot anchor's LIVE transform — ARKit's
        // world-map corrections move it with the physical ground, so the
        // distance readout stays honest even after drift/relocalization.
        guard let plot = activePlot.plot,
              let center = session.worldAnchorPosition(id: plot.anchorID)
        else {
            isOutside = false
            distanceFromCenterM = nil
            return
        }
        guard let camPos = currentCameraPosition() else { return }
        let dx = camPos.x - center.x
        let dz = camPos.z - center.z
        let d = Double(sqrt(dx * dx + dz * dz))
        distanceFromCenterM = d
        let nowOutside = d > radiusM
        if nowOutside != isOutside {
            isOutside = nowOutside
            if nowOutside { triggerOutsideHaptics() }
        }
        // Every other 0.2 s tick → a buzz every 0.4 s while outside.
        if isOutside, hapticTick { triggerOutsideHaptics() }
    }

    private func currentCameraPosition() -> SIMD3<Float>? {
        #if canImport(ARKit) && os(iOS)
        guard let view = raycaster.arview,
              let frame = view.session.currentFrame
        else { return nil }
        let t = frame.camera.transform
        return SIMD3<Float>(t.columns.3.x, t.columns.3.y, t.columns.3.z)
        #else
        return nil
        #endif
    }

    private func triggerOutsideHaptics() {
        #if canImport(UIKit)
        feedbackGen?.notificationOccurred(.warning)
        feedbackGen?.prepare()
        #endif
    }

    // MARK: - Scene markers

    private var markers: [ARSceneMarker] {
        guard let plot = activePlot.plot else { return [] }
        // Stable ids → ARCameraView only rebuilds a marker when its shape
        // changes (e.g. radius), never on the flash timer. The ring colour
        // is kept constant (in/out is shown by the red border flash + the
        // panel) so an in/out flip doesn't churn the geometry either.
        //
        // Every marker is pinned to the plot's ARAnchor — positions are
        // anchor-LOCAL offsets, and RealityKit tracks the anchor's live
        // transform, so the whole assembly rides ARKit's world-map
        // corrections without any per-frame work here.
        let anchorID = plot.anchorID
        return [
            // Exact-centre red sphere so the tapped point is unmistakable.
            ARSceneMarker(id: Self.baseSphereId,
                          worldPosition: SIMD3<Float>(0, 0, 0),
                          shape: .sphere(radiusM: 0.07),
                          colorRGBA: SIMD4<Float>(1, 0.25, 0.25, 1),
                          worldAnchorID: anchorID),
            // Tall white pole rising from the tapped point. FIELD REPORT:
            // 5 cm read as a fence post through the AR view and hid the
            // trunk behind it — 3 cm still reads at plot distance without
            // blocking anything. Kept identical to the Android sibling.
            ARSceneMarker(id: Self.poleId,
                          worldPosition: SIMD3<Float>(0, 0.6, 0),
                          shape: .cylinder(radiusM: 0.03, heightM: 1.2),
                          colorRGBA: SIMD4<Float>(1, 1, 1, 1),
                          worldAnchorID: anchorID),
            // Bright top sphere — visible from across the plot.
            ARSceneMarker(id: Self.topSphereId,
                          worldPosition: SIMD3<Float>(0, 1.2, 0),
                          shape: .sphere(radiusM: 0.12),
                          colorRGBA: SIMD4<Float>(1, 0.85, 0.15, 1),
                          worldAnchorID: anchorID),
            // Thick boundary ring (constant cyan).
            ARSceneMarker(id: Self.ringId,
                          worldPosition: SIMD3<Float>(0, 0.02, 0),
                          shape: .ring(radiusM: Float(radiusM), thicknessM: 0.4),
                          colorRGBA: SIMD4<Float>(0.2, 0.85, 1, 1),
                          worldAnchorID: anchorID),
        ]
    }
}
