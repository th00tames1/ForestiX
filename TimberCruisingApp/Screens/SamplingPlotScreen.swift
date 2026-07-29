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
    // Ghost twins of the four, drawn while aiming (FIELD REPORT 8).
    private static let ghostBaseId  = UUID(uuidString: "00000000-5A11-0000-0000-000000000011") ?? UUID()
    private static let ghostPoleId  = UUID(uuidString: "00000000-5A11-0000-0000-000000000012") ?? UUID()
    private static let ghostTopId   = UUID(uuidString: "00000000-5A11-0000-0000-000000000013") ?? UUID()
    private static let ghostRingId  = UUID(uuidString: "00000000-5A11-0000-0000-000000000014") ?? UUID()

    /// Live crosshair hit while aiming — where the pillar would land.
    /// nil once the plot is placed, or before the first successful ray.
    @State private var previewCentre: SIMD3<Float>?

    /// CRUISE MODE — when set, Save hands the placed ring (radius in
    /// metres) to the caller instead of appending a QuickMeasureEntry;
    /// the caller persists it as a cruise `Plot`. The quick-measure
    /// path passes nothing and keeps today's behaviour byte-identical.
    /// The AR anchor + `ActiveSamplingPlot` are placed the same way on
    /// both paths, so the scan screens' ring overlay comes for free.
    ///
    /// It RETURNS the host's refusal, or nil when the plot was saved. The
    /// refusal has to come back here because this screen is presented as a
    /// full-screen cover — over the map, and from the cruise scan screens
    /// over ANOTHER cover — and an alert bound to the map body cannot appear
    /// while a cover is up. So the message goes in this screen's own banner
    /// and the screen stays open, exactly as the Android sibling
    /// (`CruiseStartPlotScreen.save()`) has always done.
    private let onSaveCruisePlot: ((Double) -> String?)?

    public init(onSaveCruisePlot: ((Double) -> String?)? = nil) {
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
                            trees: [],
                            unitSystem: settings.unitSystem))
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
                .foregroundStyle(statusTint)
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

    /// Guidance for the top banner: capture failures first, then the
    /// tracking-loss explanation, else the aim instruction while no centre
    /// is placed.
    private var topBannerText: String? {
        if let reason = captureFailureReason { return reason }
        if trackingLost { return MeasurementCopy.plotTrackingLostHint }
        return hasPlot ? nil : "Set the radius, aim at the plot centre, tap +"
    }

    /// A plot is placed but its centre is not being tracked, so the ring is
    /// hidden and every readout derived from that centre is unknown.
    private var trackingLost: Bool { hasPlot && activePlot.trackingLost }

    private var statusTitle: String {
        if trackingLost { return MeasurementCopy.plotTrackingLostStatus }
        return isOutside ? "OUTSIDE — walk back inside" : "INSIDE sampling area"
    }

    private var statusTint: Color {
        if trackingLost { return ForestixPalette.confidenceWarn }
        return isOutside
            ? ForestixPalette.confidenceBad
            : ForestixPalette.confidenceOk
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
            captureFailureReason = MeasurementCopy.plotGroundNotSeen
            return
        }
        // Pin the centre to a real ARKit anchor on the shared session —
        // ARKit's world-map corrections keep it fixed to the physical
        // ground from here on; every marker derives from its live
        // transform. Replaces any earlier plot's anchor.
        guard let anchorID = session.addWorldAnchor(
            at: hit, name: "forestix.samplingPlot.center")
        else {
            captureFailureReason = MeasurementCopy.plotGroundNotSeen
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
            // A refusal keeps the screen UP: it is shown in the top banner
            // (the same place a failed centre-placement is reported) so the
            // cruiser reads why the ring they just placed is not the plot's
            // centre, and can retry from here the moment a fix arrives.
            // Dismissing first put the message on a host that was still
            // covered, and it was never seen at all.
            if let refusal = onSaveCruisePlot(radiusM) {
                captureFailureReason = refusal
                return
            }
            captureFailureReason = nil
            dismiss()
            return
        }
        let area = .pi * radiusM * radiusM
        // FIELD REPORT 12 — "Edit plot" re-opens this screen on the ring that
        // is already placed, so Save is now BOTH "record this ring" and
        // "change the ring I recorded". Appending on the second one wrote a
        // second `.samplingPlot` row for one physical ring, and those rows go
        // to the field log and the CSV the validation study reads: three
        // radius tweaks read as three plots. One ring, one row. Same
        // update-not-create rule the cruise path got.
        if let id = activePlot.linkedQuickEntryID,
           let existing = history.entries.first(where: { $0.id == id }) {
            history.update(QuickMeasureEntry(
                id: existing.id,
                kind: .samplingPlot,
                value: radiusM,
                secondaryValue: area,
                sigma: nil,
                confidenceRaw: "green",
                method: "ar.tap",
                // The ring was recorded when it was PLACED — an edit changes
                // its radius, not when the cruiser stood at its centre.
                createdAt: existing.createdAt,
                plotID: existing.plotID))
        } else {
            let entry = QuickMeasureEntry(
                kind: .samplingPlot,
                value: radiusM,
                secondaryValue: area,
                sigma: nil,
                confidenceRaw: "green",
                method: "ar.tap",
                plotID: history.activePlotID)
            history.append(entry)
            activePlot.link(quickEntryID: entry.id)
        }
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
                tickPreviewCentre()
                hapticTick.toggle()
            }
        }
    }

    private func stopPolling() {
        pollTimer?.invalidate()
        pollTimer = nil
    }

    /// Where the pillar WOULD be planted if the cruiser tapped now.
    ///
    /// FIELD REPORT 8 — the aiming state used to be a crosshair and
    /// nothing else, so the cruiser was placing the plot centre blind and
    /// only found out where it had actually landed after the tap. This
    /// raycasts the same point `placePlotIfNeeded` will, on the poll that
    /// is already running, and the ghost assembly below draws there.
    ///
    /// Only while aiming: once the plot is placed, the real pillar is on
    /// screen and a second translucent one would just be noise.
    @MainActor
    private func tickPreviewCentre() {
        guard !hasPlot else {
            previewCentre = nil
            return
        }
        raycaster.preferLiDARMesh = settings.measurementSource == .lidar
        // The SAME fallback the tap uses (a hit, else 3 m along the aim),
        // so the ghost cannot promise a placement the tap would refuse or
        // put somewhere else.
        previewCentre = raycaster.screenCenterHit()
            ?? raycaster.forwardPointAtHorizontalDistance(3.0)
    }

    @MainActor
    private func tickOutsideCheck() {
        // The centre is the plot anchor's LIVE transform, re-read here and
        // republished for the markers — ARKit's world-map corrections move
        // it with the physical ground. It is nil once tracking has been lost
        // past the grace window, and then the ring is gone and this readout
        // has to go with it: an in/out call from an uncorrected pose is the
        // one thing this screen must never print.
        guard activePlot.plot != nil,
              let center = activePlot.refreshTrackedCentre(using: session)
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
        guard activePlot.plot != nil else { return previewMarkers }
        // Nothing is drawn while the centre is not being tracked — see
        // ActiveSamplingPlot.refreshTrackedCentre. The panel and the banner
        // say so; a ring left on screen at an uncorrected pose would be
        // telling the cruiser they are somewhere they are not.
        guard let centre = activePlot.centreWorld else { return [] }
        // Stable ids → ARCameraView only rebuilds a marker when its shape
        // changes (e.g. radius), never on the flash timer. The ring colour
        // is kept constant (in/out is shown by the red border flash + the
        // panel) so an in/out flip doesn't churn the geometry either.
        return [
            // Exact-centre red sphere so the tapped point is unmistakable.
            ARSceneMarker(id: Self.baseSphereId,
                          worldPosition: centre,
                          shape: .sphere(radiusM: 0.07),
                          colorRGBA: SIMD4<Float>(1, 0.25, 0.25, 1)),
            // Tall white pole rising from the tapped point. FIELD REPORT:
            // 5 cm read as a fence post through the AR view and hid the
            // trunk behind it — 3 cm still reads at plot distance without
            // blocking anything. Kept identical to the Android sibling.
            ARSceneMarker(id: Self.poleId,
                          worldPosition: centre + SIMD3<Float>(0, 0.6, 0),
                          shape: .cylinder(radiusM: 0.03, heightM: 1.2),
                          colorRGBA: SIMD4<Float>(1, 1, 1, 1)),
            // Bright top sphere — visible from across the plot.
            ARSceneMarker(id: Self.topSphereId,
                          worldPosition: centre + SIMD3<Float>(0, 1.2, 0),
                          shape: .sphere(radiusM: 0.12),
                          colorRGBA: SIMD4<Float>(1, 0.85, 0.15, 1)),
            // Thick boundary ring (constant cyan).
            ARSceneMarker(id: Self.ringId,
                          worldPosition: centre + SIMD3<Float>(0, 0.02, 0),
                          shape: .ring(radiusM: Float(radiusM), thicknessM: 0.4),
                          colorRGBA: SIMD4<Float>(0.2, 0.85, 1, 1)),
        ]
    }

    /// The pillar the tap WOULD plant, drawn translucent at the live
    /// crosshair hit (FIELD REPORT 8).
    ///
    /// Same four pieces, same sizes, same colours as the placed assembly —
    /// a preview that looked different would be teaching the cruiser the
    /// wrong thing. Only the alpha changes, and the ring is included
    /// because the radius slider is right there: the cruiser can see how
    /// much ground a 5.6 m plot actually covers before committing to a
    /// centre, which is the part that is hardest to judge by eye.
    ///
    /// NOT anchored. There is no ARAnchor yet — that is what the tap
    /// creates — so these are plain world positions that move with the aim.
    private var previewMarkers: [ARSceneMarker] {
        guard let centre = previewCentre else { return [] }
        let ghost: Float = 0.35
        return [
            ARSceneMarker(id: Self.ghostBaseId,
                          worldPosition: centre,
                          shape: .sphere(radiusM: 0.07),
                          colorRGBA: SIMD4<Float>(1, 0.25, 0.25, ghost)),
            ARSceneMarker(id: Self.ghostPoleId,
                          worldPosition: centre + SIMD3<Float>(0, 0.6, 0),
                          shape: .cylinder(radiusM: 0.03, heightM: 1.2),
                          colorRGBA: SIMD4<Float>(1, 1, 1, ghost)),
            ARSceneMarker(id: Self.ghostTopId,
                          worldPosition: centre + SIMD3<Float>(0, 1.2, 0),
                          shape: .sphere(radiusM: 0.12),
                          colorRGBA: SIMD4<Float>(1, 0.85, 0.15, ghost)),
            ARSceneMarker(id: Self.ghostRingId,
                          worldPosition: centre + SIMD3<Float>(0, 0.02, 0),
                          shape: .ring(radiusM: Float(radiusM), thicknessM: 0.4),
                          colorRGBA: SIMD4<Float>(0.2, 0.85, 1, ghost)),
        ]
    }
}
