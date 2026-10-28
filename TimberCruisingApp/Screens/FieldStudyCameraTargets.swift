import SwiftUI
import Models
import Common
import Positioning
import AR
import simd

struct FieldScopeFailureAlert: ViewModifier {
    @Binding var message: String?
    func body(content: Content) -> some View {
        content.alert("Measurement target unavailable",isPresented: Binding(get: { message != nil },set: { if !$0 { message = nil } })) {
            Button("OK",role: .cancel) { message = nil }
        } message: { Text(message ?? "") }
    }
}

struct FieldTargetSwitchPresentation: ViewModifier {
    @ObservedObject var coordinator: FieldTargetSwitchCoordinator
    func body(content: Content) -> some View {
        let choice = coordinator.pending,failure = coordinator.error,generation = coordinator.presentationGeneration
        return content.overlay {
            if coordinator.busy {
                ZStack {
                    Color.black.opacity(0.3).ignoresSafeArea().contentShape(Rectangle()).onTapGesture { }
                    VStack(spacing: 12) {
                        ProgressView().tint(.white)
                        Text("Switching measurement target…").font(.callout).foregroundStyle(.white)
                        Button("Cancel") { coordinator.cancel() }.buttonStyle(.borderedProminent)
                    }.padding(24).background(.black.opacity(0.85),in: RoundedRectangle(cornerRadius: 16))
                }.accessibilityIdentifier("fieldTarget.switching")
            }
        }
        .alert(choice == nil ? "Measurement target unavailable" : "Change measurement target?",
            isPresented: Binding(get: { coordinator.pending != nil || coordinator.error != nil },
                set: { if !$0 { coordinator.dismissPresentation(generation: generation) } })) {
            if choice != nil {
                Button("Change target") { coordinator.confirm() }
                Button("Cancel",role: .cancel) { coordinator.dismissPresentation(generation: generation) }
            } else {
                Button("OK",role: .cancel) { coordinator.dismissPresentation(generation: generation) }
            }
        } message: {
            Text(choice.map { "Measure Tree ID \($0.label)? Current setup and unsaved inputs will be cleared." } ?? failure ?? "")
        }
        .onDisappear { coordinator.cancel() }
    }
}

/// Advisory, horizontal target locations. With 2-D SHP coordinates the marker
/// is placed at camera elevation, not a claimed surveyed stem-base elevation.
/// No position/heading decision is ever passed into a measurement estimator.
struct FieldStudyCameraTargets: View {
    @Environment(\.fieldStudyStore) private var store
    let raycaster: ARCenterRaycaster
    let canSelect: Bool
    let capture: FieldCaptureCoordinator
    var onSelect: ((String) -> Void)? = nil
    var body: some View {
        if let store { FieldStudyCameraTargetContent(store: store,raycaster: raycaster,canSelect: canSelect,capture: capture,onSelect: onSelect) }
    }
}

private struct FieldStudyCameraTargetContent: View {
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.scenePhase) private var scenePhase
    @ObservedObject var store: FieldStudyStore
    let raycaster: ARCenterRaycaster
    let canSelect: Bool
    @ObservedObject var capture: FieldCaptureCoordinator
    let onSelect: ((String) -> Void)?
    private var selectedId: String? {
        if let r = capture.displayRecord { return r.targetId }
        return capture.scopedTarget?.id ?? (capture.usesGlobalTargetSelection ? store.selectedTargetId : nil)
    }
    private struct Marker: Identifiable {
        var id: String
        var label: String
        var point: CGPoint
        var distance: Double
        var uncertainty: Double
    }
    @State private var markers: [Marker] = []
    @State private var status = FieldOverlayStatus.disabled
    @State private var originFixTime: Date?
    @State private var originPosition: SIMD3<Float>?
    @State private var previouslyVisible: Set<String> = []
    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .top) {
                if store.configuration.ar.enabled, status != .ready, status != .disabled {
                    Text("AR targets unavailable").font(.caption2).foregroundStyle(.white)
                        .padding(6).background(.black.opacity(0.55),in: Capsule())
                        .padding(.top,200).allowsHitTesting(false)
                }
                ForEach(markers) { marker in
                    VStack(spacing: 2) {
                        Button {
                            guard canSelect,capture.scopedTarget?.id != marker.id else { return }
                            onSelect?(marker.id)
                        } label: {
                            VStack(spacing: 1) {
                                Text(marker.label).font(.system(size: 12,weight: .semibold,design: .monospaced)).lineLimit(1)
                                if store.configuration.ar.showDistances { Text(MeasurementFormatter.navDistance(m: marker.distance, in: settings.unitSystem)).font(.caption2) }
                            }.foregroundStyle(.white).padding(.horizontal,8).frame(width: 72,height: store.configuration.ar.showDistances ? 42 : 30)
                                .background(marker.id == selectedId ? Color.green.opacity(0.8) : Color.black.opacity(0.55),in: Capsule())
                        }.buttonStyle(.plain).disabled(!canSelect || onSelect == nil)
                        .accessibilityIdentifier("fieldTarget.marker."+marker.id)
                        .accessibilityLabel("Target \(marker.label), approximate \(MeasurementFormatter.navDistance(m: marker.distance, in: settings.unitSystem)), angular uncertainty \(Int(marker.uncertainty)) degrees")
                        Rectangle().fill(.white.opacity(0.7)).frame(width: 1,height: 14)
                        Circle().fill(.white).frame(width: 4,height: 4)
                    }
                    .position(x: marker.point.x,y: marker.point.y-24)
                }
            }
            .frame(width: geometry.size.width,height: geometry.size.height)
            .task(id: store.configuration.protocolFingerprint+":"+(selectedId ?? "")) {
                while !Task.isCancelled {
                    if scenePhase == .active { refresh(size: geometry.size) }
                    else { markers = []; previouslyVisible = [] }
                    do { try await Task.sleep(for: .milliseconds(200)) } catch { return }
                }
            }
        }.ignoresSafeArea()
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { markers = []; previouslyVisible = []; originFixTime = nil; originPosition = nil }
        }
    }
    @MainActor private func refresh(size: CGSize) {
        let config = store.configuration.ar
        guard config.enabled else { markers = []; status = .disabled; previouslyVisible = []; return }
        let service = LocationService.shared
        let camera = raycaster.fieldTargetCameraGeometry()
        let fix = service.latestSnapshot; let heading = service.overlayHeading
        let now = Date()
        let location = fix.map { f in FieldOverlayLocation(latitude: f.latitude,longitude: f.longitude,
            horizontalAccuracyM: f.horizontalAccuracyM,fixAgeSeconds: now.timeIntervalSince(f.timestamp),
            headingTrueDegrees: heading == nil ? nil : camera?.headingTrueDegrees,
            headingAccuracyDegrees: heading?.accuracyDegrees,
            headingAgeSeconds: heading.map { now.timeIntervalSince($0.timestamp) },arTrackingNormal: camera != nil) }
        var candidatesConfig = config; candidatesConfig.maximumPoints = 20
        let decision = FieldTargetOverlayPolicy.evaluate(configuration: candidatesConfig,studyId: store.configuration.studyId,
            targets: store.overlayTargets,location: location,horizontalFieldOfViewDegrees: camera?.horizontalFieldOfViewDegrees ?? 0,
            previouslyVisible: previouslyVisible,selectedTargetId: selectedId)
        status = decision.status
        guard decision.status == .ready, let camera, let fix else { markers = []; previouslyVisible = []; originFixTime = nil; return }
        if originFixTime != fix.timestamp { originFixTime = fix.timestamp; originPosition = camera.position }
        guard let origin = originPosition else { markers = []; return }
        var next: [Marker] = []
        for candidate in decision.candidates {
            let angle = candidate.bearingDegrees*Double.pi/180
            let point = origin+SIMD3<Float>(Float(sin(angle)*candidate.distanceM),0,-Float(cos(angle)*candidate.distanceM))
            guard let screen = raycaster.projectToScreen(point), screen.x.isFinite, screen.y.isFinite,
                  screen.x >= 44, screen.x <= size.width-44, screen.y >= 260, screen.y <= size.height-230,
                  !next.contains(where: { abs($0.point.x-screen.x) < 82 && abs($0.point.y-screen.y) < 62 }) else { continue }
            next.append(.init(id: candidate.targetId,label: candidate.label,point: screen,distance: candidate.distanceM,uncertainty: candidate.uncertaintyDegrees))
            if next.count >= config.maximumPoints { break }
        }
        markers = next; previouslyVisible = Set(next.map(\.id))
    }
}
