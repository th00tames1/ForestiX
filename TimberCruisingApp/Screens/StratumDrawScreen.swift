// Phase 7.4 — draw a stratum on the map.
//
// The Phase 1 flow required a prepared GeoJSON/KML file; this screen
// gives the cruiser a self-sufficient alternative: open the map, tap
// the corners of the harvest block, type a name, save.
//
// Uses iOS 17+ `Map` + `MapReader` so tapping the screen converts back
// to a WGS84 coordinate via `MapProxy.convert(_:from:)`. On macOS (test
// runner) the map is a neutral placeholder — the VM layer is fully
// platform-agnostic so the save path is still exercisable from
// previews and unit tests.

import SwiftUI
import Models
#if canImport(MapKit)
import MapKit
#endif

public struct StratumDrawScreen: View {

    @EnvironmentObject private var environment: AppEnvironment
    @StateObject private var viewModel: StratumDrawViewModel
    @Environment(\.dismiss) private var dismiss

    public init(project: Project) {
        _viewModel = StateObject(wrappedValue:
            StratumDrawViewModel(project: project))
    }

    public var body: some View {
        VStack(spacing: 0) {
            helpBanner
            mapArea
            controlPanel
        }
        .navigationTitle("구역 그리기")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .task { viewModel.configure(with: environment) }
        .alert("오류",
               isPresented: Binding(
                get: { viewModel.errorMessage != nil },
                set: { if !$0 { viewModel.errorMessage = nil } })
        ) {
            Button("확인", role: .cancel) { viewModel.errorMessage = nil }
        } message: {
            Text(viewModel.errorMessage ?? "")
        }
        .onChange(of: viewModel.didSave) { _, saved in
            if saved { dismiss() }
        }
    }

    // MARK: - Help banner

    @ViewBuilder private var helpBanner: some View {
        VStack(alignment: .leading, spacing: 4) {
            Label("사용법", systemImage: "hand.tap.fill")
                .font(.subheadline.bold())
            Text("지도 위에서 벌채 구역의 **모서리를 순서대로 탭**하세요. 최소 3 개의 점을 찍으면 자동으로 면적이 계산됩니다. 실수하면 [되돌리기] 로 마지막 점을 지울 수 있어요.")
                .font(.caption)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.tint.opacity(0.10))
    }

    // MARK: - Map area

    #if canImport(MapKit) && os(iOS)
    @ViewBuilder private var mapArea: some View {
        MapReader { proxy in
            Map {
                // Render tapped vertices as annotations.
                ForEach(Array(viewModel.vertices.enumerated()), id: \.offset) { idx, v in
                    Annotation("\(idx + 1)", coordinate: v) {
                        ZStack {
                            Circle().fill(.red).frame(width: 22, height: 22)
                            Text("\(idx + 1)")
                                .font(.caption.bold())
                                .foregroundStyle(.white)
                        }
                    }
                }
                // Closed polygon preview (fill + stroke) if ≥3 points.
                if viewModel.vertices.count >= 3 {
                    MapPolygon(coordinates: viewModel.vertices)
                        .foregroundStyle(.green.opacity(0.20))
                        .stroke(.green, lineWidth: 2)
                }
            }
            .mapControls {
                MapCompass()
                MapScaleView()
            }
            .onTapGesture { screenPoint in
                if let coord = proxy.convert(screenPoint, from: .local) {
                    viewModel.addVertex(coord)
                }
            }
        }
        .frame(maxHeight: .infinity)
    }
    #else
    @ViewBuilder private var mapArea: some View {
        VStack(spacing: 12) {
            Image(systemName: "map")
                .font(.system(size: 40))
                .foregroundStyle(.secondary)
            Text("지도는 iOS 에서만 표시됩니다")
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(white: 0.95))
    }
    #endif

    // MARK: - Control panel

    @ViewBuilder private var controlPanel: some View {
        VStack(spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(viewModel.vertexCountLabel)
                        .font(.subheadline.bold())
                    if viewModel.areaAcres > 0 {
                        Text(String(format: "면적: %.3f 에이커", viewModel.areaAcres))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
                Button {
                    viewModel.removeLast()
                } label: {
                    Label("되돌리기", systemImage: "arrow.uturn.backward")
                }
                .disabled(viewModel.vertices.isEmpty)
                Button(role: .destructive) {
                    viewModel.clear()
                } label: {
                    Label("지우기", systemImage: "trash")
                }
                .disabled(viewModel.vertices.isEmpty)
            }

            TextField("구역 이름 (예: 1구획, 북쪽블록)",
                      text: $viewModel.name)
                .textFieldStyle(.roundedBorder)
                #if os(iOS)
                .textInputAutocapitalization(.words)
                #endif
                .accessibilityIdentifier("stratumDraw.name")

            Button {
                viewModel.save()
            } label: {
                HStack {
                    if viewModel.isSaving { ProgressView() }
                    Text(viewModel.isSaving ? "저장 중…" : "구역 저장")
                        .bold()
                }
                .frame(maxWidth: .infinity, minHeight: 56)
            }
            .buttonStyle(.borderedProminent)
            .disabled(!viewModel.canSave)
            .accessibilityIdentifier("stratumDraw.save")
        }
        .padding()
        .background(Material.regular)
    }
}
