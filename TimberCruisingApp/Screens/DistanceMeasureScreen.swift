// Distance measurement — two modes:
//
//   • Live distance — device-to-object distance at the crosshair, updated
//     every frame.
//   • Two-point distance — tap once to drop point A, tap again for B; the
//     overlay draws a thick white-bordered line with a yellow point at
//     each end and a centred distance label in a rounded white pill.
//
// Source (LiDAR / AR):
//   • LiDAR — scene-mesh raycast; always used on LiDAR devices.
//   • AR — estimated-plane raycast; the only path on non-LiDAR devices.
//   The on-screen toggle is Developer-mode research chrome only.
//
// The screen logs every accepted reading to `QuickMeasureHistory` as a
// `.distance` kind so it appears in Field Log and CSV / bundle exports.

import SwiftUI
import Common
import Models
import Sensors
import AR
import simd

#if canImport(UIKit)
import UIKit
#endif

public struct DistanceMeasureScreen: View {

    @EnvironmentObject private var history: QuickMeasureHistory
    @EnvironmentObject private var settings: AppSettings
    /// App-shared AR session. Deliberately NOT observed — the manager
    /// publishes camera pose at 60 Hz and observing it re-evaluated this
    /// whole body every ARKit frame; the 20 Hz live timer below is the
    /// screen's only clock. (No sampling-plot overlay here by design.)
    private var session: ARKitSessionManager { .shared }
    @StateObject private var raycaster = ARCenterRaycaster()
    @Environment(\.scenePhase) private var scenePhase

    /// Shared-session client token for this screen.
    private static let arClientID = UUID()

    public enum Mode: String, CaseIterable, Identifiable {
        case live = "Live"
        case twoPoint = "Two-point"
        public var id: String { rawValue }
    }

    @State private var mode: Mode = .live

    /// Live continuous distance from device to whatever the crosshair
    /// hits. Updated by a timer at ~20 Hz.
    @State private var liveDistanceM: Double?

    /// Two-point screen positions (in the AR view's screen space) +
    /// world positions. Screen positions drive the overlay drawing; the
    /// world positions are the source of truth for the actual distance.
    @State private var pointA: SIMD3<Float>?
    @State private var pointB: SIMD3<Float>?
    @State private var screenA: CGPoint?
    @State private var screenB: CGPoint?

    @State private var captureFailureReason: String?
    @State private var liveTimer: Timer?

    /// Hampel-style robust moving average for the AR (estimated-plane)
    /// path — the raw raycast distance flickers there. LiDAR stays raw.
    @State private var arSmoother = DistanceSmoother()

    /// Developer-mode research capture: the tape-measured true distance AS
    /// TYPED. Logged with source + aim pitch so distance accuracy can be
    /// analysed by range / angle / sensing path. The unit is `activeTruthUnit`
    /// below, never assumed.
    @State private var researchTrueText: String = ""
    /// The cruiser's per-entry unit choice, remembered with the unit system it
    /// was made under. Nil falls back to the ACTIVE system; the choice sticks
    /// for the rest of this screen session and is dropped if that system
    /// changes underneath it.
    @State private var truthUnitChoice: (unit: TruthInput.Unit, imperial: Bool)?
    /// The unit in force for the field right now: the per-entry choice, or the
    /// active system's default until one is made.
    private var activeTruthUnit: TruthInput.Unit {
        let imperial = settings.unitSystem == .imperial
        // A choice made under the OTHER system is discarded rather than
        // carried across: switching the project to imperial must not leave
        // the field sitting in centimetres.
        if let choice = truthUnitChoice, choice.imperial == imperial { return choice.unit }
        return TruthInput.defaultUnit(.distance, imperial: imperial)
    }

    public init() {}

    public var body: some View {
        GeometryReader { proxy in
            ZStack {
                // No 3D sphere markers — the points + line are drawn as a
                // crisp 2D overlay (below) that tracks the projected world
                // points, so nothing floats away from the markers.
                ARCameraView(manager: session,
                             debugMeshOverlay: false,
                             sceneMarkers: [],
                             raycaster: raycaster)
                    .ignoresSafeArea()

                // Center crosshair always visible — that's the active probe
                // the "+" button captures against.
                centerCrosshair

                // Two-point overlay (line + endpoints + label).
                twoPointOverlay(in: proxy.size)

                // Top-centre instruction banner (U1) — capture-failure
                // hints and the two-point placement hints; Live mode has
                // no stage guidance (the readout strip carries the state).
                MeasureTopBanner(topBannerText)

                // (The bottom-right LiDAR/AR toggle is GONE — field report
                // F5. It was developer-only chrome for a switch nobody
                // flips; `AppSettings.measurementSource` is untouched and
                // still decides which sensor path raycasts, so nothing
                // about the measurement changed.)

                // Floating back button — full-bleed chrome exit (the
                // system nav bar is hidden on the AR screens).
                MeasureBackButtonRow()

                // Bottom block (U2): while measuring, the camera-app
                // shutter sits bottom-centre with the Live/2-Pt mode
                // toggle as its left flank and the live readout as the
                // value strip above (the shutter saves in Live mode and
                // drops points in Two-point; dev research fields ride
                // their own scrim in the strip — typed BEFORE the
                // shutter logs the reading). A completed two-point pair
                // is the RESULT state: the shutter yields to the value
                // panel with Reset / Save.
                VStack(spacing: 12) {
                    Spacer()
                    if isTwoPointResult {
                        bottomPanel
                    } else {
                        valueStrip
                        MeasureShutterRow(
                            capture: { capture(in: proxy.size) },
                            // The caption names the mode you are IN. "2-Pt"
                            // was the last initialism left on this screen —
                            // the Android sibling already ships "2 points",
                            // and the words fit the 52 pt flank slot at
                            // 10 pt monospaced with room to spare.
                            leading: .init(systemImage: "arrow.left.and.right",
                                           caption: mode == .live ? "Live" : "2 points") {
                                mode = (mode == .live) ? .twoPoint : .live
                                resetTwoPoint()
                            })
                    }
                }
            }
        }
        #if os(iOS)
        .toolbar(.hidden, for: .navigationBar)
        #endif
        .onAppear {
            session.attach(client: Self.arClientID,
                           configuration: .distanceMeasure)
            startLiveTimer()
        }
        .onDisappear {
            session.detach(client: Self.arClientID)
            stopLiveTimer()
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active:
                session.attach(client: Self.arClientID,
                               configuration: .distanceMeasure)
                startLiveTimer()
            case .inactive, .background:
                session.detach(client: Self.arClientID)
                stopLiveTimer()
            @unknown default:
                break
            }
        }
    }

    // MARK: - Capture (+) button handler

    /// The "+" button captures against the centre crosshair. In Live mode
    /// it logs the current device→target distance; in Two-point mode it
    /// drops the next point (A, then B, then restarts).
    private func capture(in size: CGSize) {
        switch mode {
        case .live:
            saveLiveReading()
        case .twoPoint:
            let centre = CGPoint(x: size.width / 2, y: size.height / 2)
            placeTwoPointTap(at: centre, in: size)
        }
    }

    // MARK: - Centre crosshair

    private var centerCrosshair: some View {
        ZStack {
            Circle()
                .strokeBorder(Color.black.opacity(0.5), lineWidth: 4)
                .frame(width: 36, height: 36)
            Circle()
                .strokeBorder(Color.white, lineWidth: 2)
                .frame(width: 32, height: 32)
            Rectangle().fill(Color.black.opacity(0.6))
                .frame(width: 14, height: 3)
            Rectangle().fill(Color.black.opacity(0.6))
                .frame(width: 3, height: 14)
            Rectangle().fill(Color.white)
                .frame(width: 12, height: 1.5)
            Rectangle().fill(Color.white)
                .frame(width: 1.5, height: 12)
        }
        .accessibilityHidden(true)
    }

    // MARK: - Two-point overlay

    @ViewBuilder
    private func twoPointOverlay(in size: CGSize) -> some View {
        if mode == .twoPoint {
            ZStack {
                // Line A → B (thick white border).
                if let a = currentScreenPoint(for: pointA, fallback: screenA, in: size),
                   let b = currentScreenPoint(for: pointB, fallback: screenB, in: size) {
                    Path { p in
                        p.move(to: a); p.addLine(to: b)
                    }
                    .stroke(Color.white,
                            style: StrokeStyle(lineWidth: 7,
                                               lineCap: .round))
                    Path { p in
                        p.move(to: a); p.addLine(to: b)
                    }
                    .stroke(Color.yellow,
                            style: StrokeStyle(lineWidth: 3.5,
                                               lineCap: .round))
                }

                // Point markers.
                if let a = currentScreenPoint(for: pointA, fallback: screenA, in: size) {
                    pointDot.position(a)
                }
                if let b = currentScreenPoint(for: pointB, fallback: screenB, in: size) {
                    pointDot.position(b)
                }

                // Centred distance label between A and B.
                if let a = currentScreenPoint(for: pointA, fallback: screenA, in: size),
                   let b = currentScreenPoint(for: pointB, fallback: screenB, in: size),
                   let d = twoPointDistanceM {
                    distancePill(text: MeasurementFormatter.distance(
                        m: d, in: settings.unitSystem))
                        .position(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2)
                }
            }
            .allowsHitTesting(false)
        }
    }

    private var pointDot: some View {
        ZStack {
            Circle()
                .fill(Color.white)
                .frame(width: 22, height: 22)
            Circle()
                .fill(Color.yellow)
                .frame(width: 16, height: 16)
        }
    }

    private func distancePill(text: String) -> some View {
        Text(text)
            .font(.system(size: 16, weight: .bold, design: .monospaced))
            .foregroundStyle(Color.black)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Color.white))
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(Color.black.opacity(0.25), lineWidth: 1))
            .shadow(color: Color.black.opacity(0.25), radius: 3, x: 0, y: 1)
    }

    /// Project a stored world position to the current screen position
    /// when possible (so the points / line track the scene as the
    /// camera moves). Falls back to the screen position captured at
    /// tap time if projection isn't available.
    private func currentScreenPoint(for world: SIMD3<Float>?,
                                    fallback screen: CGPoint?,
                                    in size: CGSize) -> CGPoint? {
        if let projected = projectToScreen(world, viewSize: size) {
            return projected
        }
        return screen
    }

    // MARK: - Bottom block (U2)

    /// A completed A→B pair is this screen's RESULT state — the value
    /// panel with Reset / Save replaces the shutter. Live mode never has
    /// a result state (the shutter itself logs the reading).
    private var isTwoPointResult: Bool {
        mode == .twoPoint && pointA != nil && pointB != nil
    }

    /// Guidance for the top banner: capture failures first, else the
    /// two-point placement/save hints.
    private var topBannerText: String? {
        if let reason = captureFailureReason { return reason }
        return mode == .twoPoint ? twoPointHint : nil
    }

    /// Live readout strip directly above the shutter: mode label +
    /// current distance pills, with the Developer-mode research fields
    /// on their own dark scrim at the top.
    private var valueStrip: some View {
        VStack(spacing: 4) {
            if settings.developerMode {
                researchFieldsRow
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(Color.black.opacity(0.55),
                                in: RoundedRectangle(cornerRadius: 12,
                                                     style: .continuous))
            }
            MeasureValuePill(mode == .live ? "PHONE → TARGET"
                                           : "POINT A → POINT B",
                             dimmed: true)
            MeasureValuePill(currentDistanceString, large: true)
        }
    }

    /// Developer-mode research capture field: the typed true value and the
    /// unit it is being typed in.
    private var researchFieldsRow: some View {
        HStack(spacing: 6) {
            // Label and unit come from the SAME value, so the field can never
            // say m while the app reads feet.
            Text(TruthInput.fieldLabel(.distance, unit: activeTruthUnit))
                .font(ForestixType.caption)
                .foregroundStyle(.white.opacity(0.8))
            TextField("tape", text: $researchTrueText)
                .keyboardType(.decimalPad)
                .textFieldStyle(.roundedBorder)
                .frame(width: 90)
                .accessibilityIdentifier("distance.researchTrue")
            TruthUnitToggle(
                unit: activeTruthUnit,
                onToggle: {
                    truthUnitChoice = (TruthInput.toggled(activeTruthUnit),
                                       settings.unitSystem == .imperial)
                },
                identifier: "distance.researchTrueUnit")
        }
    }

    /// RESULT panel — a completed A→B pair: label + value + dev fields +
    /// Reset / Save, in the shared status panel.
    private var bottomPanel: some View {
        MeasureStatusPanel {
            Text("POINT A → POINT B")
                .font(ForestixType.sectionHead)
                .tracking(1.2)
                .foregroundStyle(.white.opacity(0.75))
            Text(currentDistanceString)
                .font(ForestixType.dataLarge)
                .foregroundStyle(.white)
            if settings.developerMode {
                researchFieldsRow
            }
            HStack(spacing: 12) {
                Button("Reset") { resetTwoPoint() }
                    .buttonStyle(.forestixARSecondary)
                    .frame(maxWidth: .infinity)
                Button("Save") { saveTwoPointReading() }
                    .buttonStyle(.forestixProminent)
                    .frame(maxWidth: .infinity)
                    .disabled(twoPointDistanceM == nil)
                    .accessibilityIdentifier("distance.saveTwoPoint")
            }
            .padding(.top, 2)
        }
    }

    private var currentDistanceString: String {
        let value = mode == .live ? liveDistanceM : twoPointDistanceM
        return value.map { MeasurementFormatter.distance(
            m: $0, in: settings.unitSystem) } ?? "—"
    }

    private var twoPointHint: String {
        switch (pointA, pointB) {
        case (nil, _):       return "Aim, tap + to place point A"
        case (_, nil):       return "Aim, tap + to place point B"
        default:             return "Tap Save to log this reading"
        }
    }

    // MARK: - Capture handlers

    private func placeTwoPointTap(at screenPoint: CGPoint,
                                  in size: CGSize) {
        guard let hit = hitForCurrentSource() else {
            captureFailureReason = "No surface in view. Move slightly so the camera picks up texture, then tap again."
            return
        }
        captureFailureReason = nil
        if pointA == nil {
            pointA = hit
            screenA = screenPoint
        } else if pointB == nil {
            pointB = hit
            screenB = screenPoint
        } else {
            // Third tap restarts: drop A, treat new point as A.
            pointA = hit
            screenA = screenPoint
            pointB = nil
            screenB = nil
        }
    }

    private func resetTwoPoint() {
        pointA = nil; pointB = nil; screenA = nil; screenB = nil
        captureFailureReason = nil
    }

    private func saveLiveReading() {
        guard let d = liveDistanceM else { return }
        history.append(QuickMeasureEntry(
            kind: .distance,
            value: d,
            sigma: nil,
            confidenceRaw: "green",
            method: "live.\(settings.measurementSource.displayName.lowercased())",
            plotID: history.activePlotID))
        recordResearchRow(measured: d, method: "live")
    }

    /// Developer-mode research CSV row for the distance-accuracy study:
    /// measured vs tape distance, sensing source, and aim pitch.
    private func recordResearchRow(measured: Double, method: String) {
        guard settings.developerMode else { return }
        var f: [String: String] = [
            "measure_type": "distance",
            "method": method,
            "depth_source": settings.measurementSource.rawValue,
            "measured_value": String(format: "%.3f", measured),
            "unit": "m",
            "distance_m": String(format: "%.3f", measured),
        ]
        // No tree_id: a distance reading is not taken against a tree, and the
        // retyped "Target" box that used to fill this column was as likely to
        // name the previous tree as this measurement's subject.
        if let p = raycaster.cameraPitchDeg {
            f["pitch_deg"] = String(format: "%.1f", p)
        }
        // Converted to the row's `unit` (m) so `error` stays subtractable
        // against `measured_value`; `truth_unit` records what was typed.
        if let t = TruthInput.parsePositiveBase(researchTrueText, unit: activeTruthUnit) {
            f["true_value"] = String(format: "%.3f", t)
            f["error"] = String(format: "%.3f", measured - t)
            f["truth_unit"] = activeTruthUnit.rawValue
        }
        ResearchLog.shared.record(f)
    }

    private func saveTwoPointReading() {
        guard let d = twoPointDistanceM else { return }
        history.append(QuickMeasureEntry(
            kind: .distance,
            value: d,
            sigma: nil,
            confidenceRaw: "green",
            method: "two-point.\(settings.measurementSource.displayName.lowercased())",
            plotID: history.activePlotID))
        recordResearchRow(measured: d, method: "two-point")
        resetTwoPoint()
    }

    // MARK: - Source-aware raycast

    private func hitForCurrentSource() -> SIMD3<Float>? {
        // Drive the raycaster's mesh preference from the LiDAR/AR toggle.
        // In LiDAR mode the mesh raycast runs first; in AR mode it's
        // skipped and we use the estimated-plane path (plus a forward-ray
        // fallback so a tap still lands somewhere on featureless aim).
        switch settings.measurementSource {
        case .lidar:
            raycaster.preferLiDARMesh = true
            return raycaster.screenCenterHit()
        case .ar:
            raycaster.preferLiDARMesh = false
            return raycaster.screenCenterHit()
                ?? raycaster.forwardPointAtHorizontalDistance(3.0)
        }
    }

    // MARK: - Live distance polling

    private func startLiveTimer() {
        liveTimer?.invalidate()
        liveTimer = Timer.scheduledTimer(withTimeInterval: 0.05,
                                          repeats: true) { _ in
            Task { @MainActor in
                let raw = currentDeviceToCenterDistance()
                if settings.measurementSource == .ar {
                    // AR estimated-plane distances flicker — publish the
                    // spike-rejecting moving average instead of the raw
                    // sample (real movement still tracks via the median).
                    if let raw { arSmoother.add(raw) }
                    liveDistanceM = arSmoother.value()
                } else {
                    arSmoother.reset()
                    liveDistanceM = raw
                }
            }
        }
    }

    private func stopLiveTimer() {
        liveTimer?.invalidate()
        liveTimer = nil
    }

    /// Distance from the AR camera (device) to whatever the screen-
    /// centre raycast hits. nil when no surface is in view.
    private func currentDeviceToCenterDistance() -> Double? {
        #if canImport(ARKit) && os(iOS)
        // Keep the live readout on the same path the toggle selects.
        raycaster.preferLiDARMesh = settings.measurementSource == .lidar
        guard let hit = raycaster.screenCenterHit(),
              let view = raycaster.arview,
              let frame = view.session.currentFrame
        else { return nil }
        let t = frame.camera.transform
        let cam = SIMD3<Float>(t.columns.3.x,
                                t.columns.3.y,
                                t.columns.3.z)
        return Double(simd_distance(cam, hit))
        #else
        return nil
        #endif
    }

    // MARK: - Derived two-point distance

    private var twoPointDistanceM: Double? {
        guard let a = pointA, let b = pointB else { return nil }
        return Double(simd_distance(a, b))
    }

    // MARK: - Helpers

    /// Project a stored world-space point onto the current camera's
    /// screen plane so two-point markers track the scene as the camera
    /// moves. Returns nil on non-iOS hosts or when projection fails.
    private func projectToScreen(_ p: SIMD3<Float>?,
                                 viewSize: CGSize) -> CGPoint? {
        guard let p else { return nil }
        #if canImport(ARKit) && os(iOS)
        guard let view = raycaster.arview else { return nil }
        let projected = view.project(p)
        guard let projected else { return nil }
        return CGPoint(x: projected.x, y: projected.y)
        #else
        _ = viewSize
        return nil
        #endif
    }

    // Scene markers intentionally omitted — the two-point points + line are
    // rendered as a 2D overlay (twoPointOverlay) that tracks the projected
    // world points, so there are no oversized 3D spheres drifting from the
    // drawn markers.
}
