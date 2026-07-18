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

import SwiftUI
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
                Text("RECORDING CENTRE")
                    .font(.system(size: 13, weight: .heavy))
                    .tracking(1.0)
                    .foregroundStyle(ForestixPalette.textTertiary)
                Text("· Plot \(plannedPlot.plotNumber)")
                    .font(.system(size: 13, weight: .heavy))
                    .foregroundStyle(ForestixPalette.textTertiary)
                Spacer(minLength: 4)
                tierChip
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

            VStack(spacing: ForestixSpace.xs) {
                Button {
                    if let result = readyResult { save(result) }
                } label: {
                    Text("Save centre")
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
                .disabled(readyResult == nil)
                .opacity(readyResult == nil ? 0.45 : 1)
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

    private var accuracyText: String {
        if let r = liveResult {
            return String(format: "±%.1f m", r.medianHAccuracyM)
        }
        if let s = viewModel.latestSample {
            return String(format: "±%.1f m", s.horizontalAccuracyM)
        }
        return "±— m"
    }

    private var liveTier: PositionTier? {
        if let r = liveResult { return r.tier }
        if let s = viewModel.latestSample {
            return LocationService.tier(
                forHorizontalAccuracyM: s.horizontalAccuracyM)
        }
        return nil
    }

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
            return "\(r.nSamples) fixes · median centre locked\nσxy "
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

    /// A/B/C/D collapsed onto the instrument hues the rest of the app
    /// uses: A → ok, B/C → warn, D → bad.
    private var tierChip: some View {
        let tier = liveTier
        let color: Color = {
            switch tier {
            case .A:        return ForestixPalette.confidenceOk
            case .B, .C:    return ForestixPalette.confidenceWarn
            case .D:        return ForestixPalette.confidenceBad
            case nil:       return ForestixPalette.textTertiary
            }
        }()
        return HStack(spacing: 4) {
            Circle().fill(color).frame(width: 6, height: 6)
            Text(tier.map { "TIER \($0.rawValue)" } ?? "NO FIX")
                .font(.system(size: 10, weight: .bold))
                .tracking(0.6)
        }
        .foregroundStyle(color)
        .padding(.horizontal, 7)
        .padding(.vertical, 2)
        .background(
            RoundedRectangle(cornerRadius: ForestixRadius.chip)
                .fill(color.opacity(0.12)))
        .accessibilityIdentifier("recordCentre.tier")
    }

    // MARK: Save (planned → real Plot, the old conversion)

    private func save(_ result: PlotCenterResult) {
        let design = (try? environment.cruiseDesignRepository
            .forProject(project.id))?.first
        let areaAcres = design?.plotAreaAcres ?? 0.1
        let plot = Plot(
            id: UUID(),
            projectId: project.id,
            plannedPlotId: plannedPlot.id,
            plotNumber: plannedPlot.plotNumber,
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
            plotAreaAcres: areaAcres,
            startedAt: Date(),
            closedAt: nil,
            closedBy: nil,
            notes: "",
            coverPhotoPath: nil,
            panoramaPath: nil)
        do {
            let created = try environment.plotRepository.create(plot)
            var planned = plannedPlot
            planned.visited = true
            _ = try environment.plannedPlotRepository.update(planned)
            ForestixLogger.log(.plotOpened(plotId: created.id,
                                           projectId: project.id))
            HapticFeedback.play(.success)
            onSaved(created)
            dismiss()
        } catch {
            saveErrorMessage =
                "Storage error: \(error.localizedDescription). The centre was not saved — try again."
        }
    }
}
