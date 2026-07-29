// INLINE GPS-AVERAGING SHEET (mock ⑧) — Phase B replacement for the
// old full-screen PlotCenterScreen. A compact sheet over the cruise
// map: progress ring counting the averaging window, live ±accuracy +
// tier chip, elapsed seconds, one 54 pt "Save centre". The engine is
// unchanged — PlotCenterViewModel still owns the 60 s wall clock and
// hands the buffer to GPSAveraging.compute (≥30 samples, A/B/C/D tier
// table). Saving converts the planned plot into a real active Plot at
// the averaged centre — the exact conversion the retired cruise-flow
// coordinator performed — and marks the PlannedPlot visited.
//
// The weak-GPS escape hatch is one line: "GPS weak? Use offset" pushes
// the kept OffsetFlowScreen (its own AR session, run while pushed);
// its computed PlotCenterResult lands the centre through the same
// save path.
//
// FIELD REPORT 17 — averaging is no longer a GATE. The 60 s window was
// the only way out of this sheet, and a cruiser who has already walked to
// the plot spends that minute standing still under a canopy that is not
// going to give them a better fix anyway. "Start plot now" takes whatever
// fix is live at that instant and opens the plot immediately; "Save
// centre" still arms at the bell for anyone who wants the median. The two
// produce DIFFERENT DATA and are recorded as such — `gpsSingle` /
// nSamples 1 for the instant one, `gpsAveraged` / nSamples ≥ 30 for the
// window — so nothing downstream has to guess which it was reading.

import SwiftUI
import Combine
import Common
import Models
import Persistence
import Positioning
import Sensors

public struct RecordCentreSheet: View {

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    public let plannedPlot: PlannedPlot
    public let project: Project
    public var onSaved: (Plot) -> Void

    /// PRIVATE LocationService — deliberately NOT `LocationService.shared`.
    /// The averaging engine owns the rolling buffer: it clearBuffer()s on
    /// start and hands the buffer to GPSAveraging.compute at the end of
    /// the window, and it start()/stop()s outside any subscriber
    /// ref-count. Sharing would let the map chip / badge / mini-map race
    /// the buffer (or a release() elsewhere stop the averager mid-window).
    @StateObject private var viewModel = PlotCenterViewModel(
        location: LocationService())
    /// Private AR session for the offset fallback (the manager's
    /// single-owner legacy lifecycle — run on push, pause on pop).
    @StateObject private var offsetSession = ARKitSessionManager()
    @State private var pushingOffset = false
    @State private var saveErrorMessage: String?
    /// A save is in flight (see `save(_:)`) — the sheet's buttons are deaf
    /// and dimmed until it lands or fails.
    @State private var saving = false
    /// Wall clock the FRESHNESS test reads. Freshness changes with TIME, not
    /// with the arrival of a fix: the view model's own tick stops at the 60 s
    /// bell, and a cruiser under canopy gets no new snapshot to redraw from,
    /// so without this "Start plot now" would stay armed on a fix that aged
    /// out while they were reading the sheet.
    @State private var now = Date()

    public init(plannedPlot: PlannedPlot,
                project: Project,
                onSaved: @escaping (Plot) -> Void = { _ in }) {
        self.plannedPlot = plannedPlot
        self.project = project
        self.onSaved = onSaved
    }

    public var body: some View {
        NavigationStack {
            content
                .toolbar(.hidden, for: .navigationBar)
                .navigationDestination(isPresented: $pushingOffset) {
                    OffsetFlowScreen(
                        viewModel: OffsetFlowViewModel(
                            location: viewModel.location,
                            session: offsetSession),
                        onDone: { result in
                            save(result)
                        })
                    .onAppear { offsetSession.run() }
                    .onDisappear { offsetSession.pause() }
                }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationBackground(ForestixPalette.surface)
        .onAppear { viewModel.start() }
        .onDisappear { viewModel.cancel() }
        .onReceive(Timer.publish(every: 1, on: .main, in: .common).autoconnect()) {
            now = $0
        }
        // The offset flow runs its own 30 s averaging over the SAME
        // location buffer — stop the 60 s window while it's up and
        // restart fresh if the cruiser backs out without landing it.
        .onChange(of: pushingOffset) { _, pushing in
            if pushing {
                viewModel.cancel()
            } else {
                viewModel.start()
            }
        }
        .alert("Couldn't save the centre",
               isPresented: Binding(
                   get: { saveErrorMessage != nil },
                   set: { if !$0 { saveErrorMessage = nil } })
        ) {
            Button("OK", role: .cancel) { saveErrorMessage = nil }
        } message: {
            Text(saveErrorMessage ?? "")
        }
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: ForestixSpace.xs) {
                Text("SET PLOT CENTRE")
                    .font(.system(size: 13, weight: .heavy))
                    .tracking(1.0)
                    .foregroundStyle(ForestixPalette.textTertiary)
                Text("· Plot \(plannedPlot.plotNumber)")
                    .font(.system(size: 13, weight: .heavy))
                    .foregroundStyle(ForestixPalette.textTertiary)
                Spacer(minLength: 4)
                // FIELD REPORT F9 — the A/B/C/D "TIER B" / "NO FIX" chip is
                // gone. It is a GPS-averaging grade the cruiser has no way
                // to act on and no way to interpret. The plain accuracy
                // figure (±x.x m) is still right there in the progress ring,
                // which is the one number that means something on a ridge.
                // `positionTier` is UNCHANGED on the Plot record and in every
                // export — see `save(_:)` below, which still stamps
                // `positionTier: result.tier`.
            }
            .padding(.top, ForestixSpace.md)
            .padding(.bottom, ForestixSpace.sm)

            HStack(spacing: 18) {
                progressRing
                VStack(alignment: .leading, spacing: 6) {
                    Text(metaTitle)
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(ForestixPalette.textPrimary)
                    Text(metaDetail)
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(ForestixPalette.textTertiary)
                        .lineSpacing(3)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
            }
            .padding(.vertical, ForestixSpace.xs)

            // How far the cruiser is from where this plot was PLANNED.
            // "Start plot now" binds Plot N to wherever they are standing,
            // so the one number that says whether that is the right place
            // has to be on the same screen as the button. Same wording,
            // same freshness gate as the planned pin's peek.
            HStack(spacing: ForestixSpace.xs) {
                Text("FROM YOU")
                    .font(.system(size: 10, weight: .bold))
                    .tracking(0.7)
                    .foregroundStyle(ForestixPalette.textTertiary)
                    .frame(width: 72, alignment: .leading)
                Text(rangeText)
                    .font(.system(size: 14.5, weight: .semibold,
                                  design: .monospaced))
                    .foregroundStyle(ForestixPalette.textPrimary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                Spacer(minLength: 4)
            }
            .padding(.vertical, 6)

            VStack(spacing: ForestixSpace.xs) {
                // The instant path (field report 17): armed the moment
                // there is a fix the app still believes, so the cruiser
                // never waits out the window to start measuring.
                Button {
                    if let result = instantCentre { save(result) }
                } label: {
                    Text("Start plot now")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(ForestixPalette.primaryInk)
                        .frame(maxWidth: .infinity, minHeight: 54)
                        .background(
                            RoundedRectangle(cornerRadius: ForestixRadius.card,
                                             style: .continuous)
                                .fill(ForestixPalette.primary))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(instantCentre == nil || saving)
                .opacity(instantCentre == nil || saving ? 0.45 : 1)
                .accessibilityIdentifier("recordCentre.startNow")

                // Says what the button above actually records, because the
                // difference between one fix and a 60 s median is the whole
                // reason the other button exists — and it is invisible once
                // the plot is on the map.
                Text("Records one GPS fix, not an average.")
                    .font(.system(size: 11))
                    .foregroundStyle(ForestixPalette.textTertiary)
                    .frame(maxWidth: .infinity, alignment: .center)

                // The averaged path — still here, still the better centre,
                // now something the cruiser chooses rather than sits out.
                Button {
                    if let result = readyResult { save(result) }
                } label: {
                    Text("Save centre")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(readyResult == nil
                                         ? ForestixPalette.textPrimary
                                         : ForestixPalette.primary)
                        .frame(maxWidth: .infinity, minHeight: 54)
                        .background(
                            RoundedRectangle(cornerRadius: ForestixRadius.card,
                                             style: .continuous)
                                .stroke(readyResult == nil
                                        ? ForestixPalette.divider
                                        : ForestixPalette.primary,
                                        lineWidth: readyResult == nil ? 1 : 1.5))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(readyResult == nil || saving)
                .opacity(readyResult == nil || saving ? 0.45 : 1)
                .accessibilityIdentifier("recordCentre.save")

                Button {
                    dismiss()
                } label: {
                    Text("Cancel")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(ForestixPalette.textPrimary)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(
                            RoundedRectangle(cornerRadius: ForestixRadius.control,
                                             style: .continuous)
                                .stroke(ForestixPalette.divider, lineWidth: 1))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("recordCentre.cancel")

                Button {
                    pushingOffset = true
                } label: {
                    Text("GPS weak? Use offset")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(ForestixPalette.textTertiary)
                        .frame(maxWidth: .infinity, minHeight: 32)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("recordCentre.offset")
            }
            .padding(.top, ForestixSpace.sm)

            Spacer(minLength: ForestixSpace.md)
        }
        .padding(.horizontal, ForestixSpace.md)
        .background(ForestixPalette.surface.ignoresSafeArea())
        .accessibilityIdentifier("recordCentre.sheet")
    }

    // MARK: Live readouts

    /// The averaging window's duration (the engine's 60 s default).
    private var windowS: Int { viewModel.averagingDurationS }

    private var elapsedS: Int {
        switch viewModel.phase {
        case .averaging(let secs, _): return secs
        case .good, .poor, .failed:   return windowS
        case .idle:                   return 0
        }
    }

    private var sampleCount: Int {
        switch viewModel.phase {
        case .averaging(_, let count): return count
        case .good(let r), .poor(let r): return r.nSamples
        default: return 0
        }
    }

    /// While averaging: the engine run live over the rolling buffer
    /// (nil until 30 accurate samples). After: the final result.
    private var liveResult: PlotCenterResult? {
        switch viewModel.phase {
        case .good(let r), .poor(let r):
            return r
        case .averaging:
            return GPSAveraging.compute(
                input: .init(samples: viewModel.location.buffer))
        case .idle, .failed:
            return nil
        }
    }

    private var readyResult: PlotCenterResult? {
        switch viewModel.phase {
        case .good(let r), .poor(let r): return r
        default: return nil
        }
    }

    /// The centre "Start plot now" would write — nil while there is no fix
    /// the app still believes, which is exactly when the button greys out.
    /// Independent of `viewModel.phase`: the window has nothing to do with
    /// whether a single fix is available.
    private var instantCentre: PlotCenterResult? {
        singleFixCentre(viewModel.latestSample, asOf: now)
    }

    /// Range + bearing from the cruiser to where this plot was PLANNED, or
    /// "no GPS fix". Same strings and same freshness gate as the planned
    /// pin's peek — a walking instruction measured from a position the
    /// cruiser has left is worse than none.
    private var rangeText: String {
        guard let fix = FixFreshness.usable(viewModel.latestSample, asOf: now)
        else { return "no GPS fix" }
        let d = GeoMath.distanceM(
            fromLat: fix.latitude, fromLon: fix.longitude,
            toLat: plannedPlot.plannedLat, toLon: plannedPlot.plannedLon)
        let b = GeoMath.bearingDeg(
            fromLat: fix.latitude, fromLon: fix.longitude,
            toLat: plannedPlot.plannedLat, toLon: plannedPlot.plannedLon)
        return String(format: "%.0f m · bearing %.0f°", d,
                      (b + 360).truncatingRemainder(dividingBy: 360))
    }

    private var accuracyText: String {
        if let r = liveResult {
            return String(format: "±%.1f m", r.medianHAccuracyM)
        }
        if let s = viewModel.latestSample {
            return String(format: "±%.1f m", s.horizontalAccuracyM)
        }
        return "±— m"
    }

    // (`liveTier` went with the chip — field report F9. `GPSAveraging.classify`
    // and `classifySingleFix` are untouched; the grade is still computed and
    // stored on the Plot, it just isn't shown to a cruiser. The badge's own
    // `LocationService.tier` helper is gone with the badge.)

    private var metaTitle: String {
        switch viewModel.phase {
        case .idle:      return "Waiting for GPS"
        case .averaging: return "Averaging position"
        case .good:      return "Centre ready"
        case .poor:      return "Centre ready — accuracy weak"
        case .failed:    return "Not enough good fixes"
        }
    }

    private var metaDetail: String {
        switch viewModel.phase {
        case .failed(let reason):
            return reason + "\nTry the offset link below."
        case .good(let r), .poor(let r):
            // σxy went the way of the TIER chip below: a Greek letter with
            // an algebraic subscript is not something a cruiser can read,
            // say or act on. Same number, named for what it measures —
            // how far apart the individual GPS fixes landed.
            return "\(r.nSamples) fixes · centre locked\nfixes spread ±"
                + String(format: "%.1f m", r.sampleStdXyM)
        default:
            return "\(sampleCount) fixes · needs 30 good ones\nmedian converges over \(windowS) s"
        }
    }

    // MARK: Ring + chip

    private var progressRing: some View {
        let progress = min(Double(elapsedS) / Double(windowS), 1)
        return ZStack {
            Circle()
                .stroke(ForestixPalette.surfaceRaised, lineWidth: 8)
            Circle()
                .trim(from: 0, to: progress)
                .stroke(ForestixPalette.primary,
                        style: StrokeStyle(lineWidth: 8, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .animation(.linear(duration: 0.4), value: progress)
            VStack(spacing: 2) {
                Text(accuracyText)
                    .font(.system(size: 17, weight: .heavy, design: .monospaced))
                    .foregroundStyle(ForestixPalette.textPrimary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                Text("\(elapsedS) s")
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundStyle(ForestixPalette.textTertiary)
            }
            .padding(.horizontal, 10)
        }
        .frame(width: 104, height: 104)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Averaging \(elapsedS) of \(windowS) seconds, accuracy \(accuracyText)")
    }

    // (The A/B/C/D `tierChip` — "TIER B" / "NO FIX" — was deleted per field
    // report F9. It graded GPS averaging on a scale nothing in the field
    // explains, and a cruiser could neither act on a "C" nor tell what would
    // make it a "B". The ±x.x m accuracy in the ring is the plain figure that
    // survives. Storage and exports are untouched.)

    // MARK: Save (planned → real Plot, the old conversion)

    /// ONE write per sheet. `convertPlannedToActivePlot` creates a Plot row
    /// and marks the planned pin visited, and `dismiss()` does not take the
    /// buttons away synchronously — a second tap landing before the sheet
    /// goes would write a SECOND Plot row carrying the same plotNumber and
    /// the same plannedPlotId, which nothing downstream can tell from a
    /// genuine duplicate. Three buttons reach this (instant, averaged, and
    /// the offset flow's onDone), so the guard belongs here rather than on
    /// any one of them. Android carries the same guard
    /// (`RecordCentreSheet.kt` `if (saving) return`).
    private func save(_ result: PlotCenterResult) {
        guard !saving else { return }
        saving = true
        do {
            let created = try convertPlannedToActivePlot(
                environment: environment,
                project: project,
                planned: plannedPlot,
                result: result)
            HapticFeedback.play(.success)
            onSaved(created)
            dismiss()
        } catch {
            // The row was not written, so the sheet re-arms: the cruiser can
            // retry from the same screen rather than backing out and losing
            // the averaging window they just sat out.
            saving = false
            saveErrorMessage =
                "Storage error: \(error.localizedDescription). The centre was not saved — try again."
        }
    }
}

// MARK: - Shared conversion + single-fix centre

/// Persist a Plot row at the recorded centre, mark the PlannedPlot visited,
/// and hand back the created plot (iOS derives the ACTIVE plot as the newest
/// open one, so a just-started plot wins with nothing else to write).
///
/// Free function rather than a method on the sheet because the planned pin's
/// peek now starts a plot WITHOUT opening the sheet — and a second copy of
/// this is how a plot ends up recorded but never marked visited, so its
/// dashed planned pin goes on standing next to the real ring it became.
func convertPlannedToActivePlot(
    environment: AppEnvironment,
    project: Project,
    planned: PlannedPlot,
    result: PlotCenterResult
) throws -> Plot {
    let design = (try? environment.cruiseDesignRepository
        .forProject(project.id))?.first
    let plot = Plot(
        id: UUID(),
        projectId: project.id,
        plannedPlotId: planned.id,
        plotNumber: planned.plotNumber,
        centerLat: result.lat,
        centerLon: result.lon,
        positionSource: result.source,
        positionTier: result.tier,
        gpsNSamples: result.nSamples,
        gpsMedianHAccuracyM: result.medianHAccuracyM,
        gpsSampleStdXyM: result.sampleStdXyM,
        offsetWalkM: result.offsetWalkM,
        slopeDeg: 0,
        aspectDeg: 0,
        plotAreaAcres: design?.plotAreaAcres ?? 0.1,
        startedAt: Date(),
        closedAt: nil,
        closedBy: nil,
        notes: "",
        coverPhotoPath: nil,
        panoramaPath: nil)
    let created = try environment.plotRepository.create(plot)
    var visited = planned
    visited.visited = true
    _ = try environment.plannedPlotRepository.update(visited)
    ForestixLogger.log(.plotOpened(plotId: created.id, projectId: project.id))
    return created
}

/// The centre a SINGLE instantaneous fix stands for, or nil when there is no
/// fix worth standing on (field report 17's "Start plot now").
///
/// `sampleStdXyM` is 0 because the field is not optional, NOT because the
/// fixes agreed: there is one sample and no spread to measure. The pair that
/// says so is `source == .gpsSingle` and `nSamples == 1`, and the tier comes
/// from `classifySingleFix`, which refuses the tiers that a spread bound
/// would have to earn.
func singleFixCentre(_ snapshot: CLLocationSnapshot?,
                     asOf now: Date = Date()) -> PlotCenterResult? {
    guard let fix = FixFreshness.usable(snapshot, asOf: now) else { return nil }
    let acc = Float(fix.horizontalAccuracyM)
    return PlotCenterResult(
        lat: fix.latitude,
        lon: fix.longitude,
        source: .gpsSingle,
        tier: GPSAveraging.classifySingleFix(horizontalAccuracyM: acc),
        nSamples: 1,
        medianHAccuracyM: acc,
        sampleStdXyM: 0,
        offsetWalkM: nil)
}
