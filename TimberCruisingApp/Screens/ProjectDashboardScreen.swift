// Spec §3.1 REQ-PRJ-002/003/004. Project dashboard — strata list, planned-plot
// summary, entry points into CruiseDesign / PlotMap / Export / Settings.
//
// Phase 7.4 redesign: whole screen now reads top-to-bottom as a
// step-by-step guide (①→②→③→④). Each step shows its own friendly
// description and a primary action. The strata step supports both
// "draw on map" (no file needed) and legacy "import from file" paths.

import SwiftUI
import Models
#if canImport(UniformTypeIdentifiers)
import UniformTypeIdentifiers
#endif

public struct ProjectDashboardScreen: View {

    @EnvironmentObject private var environment: AppEnvironment
    @StateObject private var viewModel: ProjectDashboardViewModel
    @State private var isPresentingImporter = false
    @State private var importFormat: ProjectDashboardViewModel.ImportFormat = .geoJSON

    public init(project: Project) {
        _viewModel = StateObject(wrappedValue: ProjectDashboardViewModel(project: project))
    }

    public var body: some View {
        List {
            gettingStartedSection
            summarySection
            strataSection
            planSection
            cruiseSection
            toolsSection
        }
        .navigationTitle(viewModel.project.name)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        #if canImport(UniformTypeIdentifiers)
        .fileImporter(
            isPresented: $isPresentingImporter,
            allowedContentTypes: allowedTypes,
            allowsMultipleSelection: false
        ) { result in
            guard case .success(let urls) = result, let url = urls.first else { return }
            let accessed = url.startAccessingSecurityScopedResource()
            defer { if accessed { url.stopAccessingSecurityScopedResource() } }
            viewModel.importStrata(fileURL: url, format: importFormat)
        }
        #endif
        .task {
            viewModel.configure(with: environment)
            viewModel.refresh()
        }
        .alert("문제가 발생했어요",
               isPresented: Binding(
                    get: { viewModel.errorMessage != nil },
                    set: { if !$0 { viewModel.errorMessage = nil } })
        ) {
            Button("확인", role: .cancel) { viewModel.errorMessage = nil }
        } message: {
            Text(viewModel.errorMessage ?? "")
        }
    }

    // MARK: - Getting started (progress guide)

    @ViewBuilder
    private var gettingStartedSection: some View {
        Section {
            VStack(alignment: .leading, spacing: 10) {
                Text("이렇게 진행하세요")
                    .font(.headline)
                stepRow(n: 1, done: !viewModel.strata.isEmpty,
                        title: "구역(strata) 정의",
                        hint: "지도에서 경계를 그리거나 GeoJSON/KML 파일을 불러옵니다.")
                stepRow(n: 2, done: viewModel.design != nil && !viewModel.plannedPlots.isEmpty,
                        title: "크루즈 디자인 + 플롯 생성",
                        hint: "플롯 크기와 간격을 정하면 샘플 플롯이 자동으로 생성됩니다.")
                stepRow(n: 3, done: false,
                        title: "현장 측정 (Go Cruise)",
                        hint: "플롯별로 걸어가서 나무를 하나씩 측정합니다. LiDAR 로 DBH, AR 로 높이.")
                stepRow(n: 4, done: false,
                        title: "결과 검토 + 내보내기",
                        hint: "임분 통계를 확인하고 PDF·CSV·GeoJSON 으로 export.")
            }
            .padding(.vertical, 4)
        } header: {
            Text("안내")
        }
    }

    private func stepRow(n: Int, done: Bool, title: String, hint: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            ZStack {
                Circle()
                    .fill(done ? Color.green : Color.accentColor.opacity(0.15))
                    .frame(width: 28, height: 28)
                if done {
                    Image(systemName: "checkmark")
                        .font(.caption.bold())
                        .foregroundStyle(.white)
                } else {
                    Text("\(n)")
                        .font(.caption.bold())
                        .foregroundStyle(Color.accentColor)
                }
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline.bold())
                Text(hint).font(.caption).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
        }
    }

    // MARK: - Summary

    private var summarySection: some View {
        Section("요약") {
            LabeledContent("단위", value: viewModel.project.units.rawValue.capitalized)
            LabeledContent("총 면적", value: formatAcres(viewModel.totalAcres))
            LabeledContent("구역 수", value: "\(viewModel.strata.count)")
            LabeledContent("계획된 플롯", value: "\(viewModel.plannedPlots.count)")
        }
    }

    // MARK: - Strata (step 1)

    @ViewBuilder
    private var strataSection: some View {
        Section {
            if viewModel.strata.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    Label("아직 구역이 없습니다", systemImage: "map")
                        .font(.subheadline.bold())
                    Text("구역(stratum)은 측정할 벌채 블록입니다. 지도에서 직접 모서리를 탭해 그리거나, 미리 준비한 GeoJSON·KML 파일을 불러올 수 있어요.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)

                    NavigationLink {
                        StratumDrawScreen(project: viewModel.project)
                    } label: {
                        Label("지도에서 구역 그리기", systemImage: "pencil.and.outline")
                            .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("dashboard.drawStratum")

                    Menu {
                        Button("GeoJSON 불러오기") {
                            importFormat = .geoJSON
                            isPresentingImporter = true
                        }
                        Button("KML 불러오기") {
                            importFormat = .kml
                            isPresentingImporter = true
                        }
                    } label: {
                        Label("파일에서 불러오기", systemImage: "square.and.arrow.down")
                            .frame(maxWidth: .infinity, minHeight: 36)
                    }
                    .buttonStyle(.bordered)
                    .accessibilityIdentifier("dashboard.importMenu")
                }
                .padding(.vertical, 4)
            } else {
                ForEach(viewModel.strata) { stratum in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(stratum.name)
                            Text(formatAcres(Double(stratum.areaAcres)))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                    }
                }
                .onDelete { idx in
                    for i in idx { viewModel.delete(stratumId: viewModel.strata[i].id) }
                }
                NavigationLink {
                    StratumDrawScreen(project: viewModel.project)
                } label: {
                    Label("새 구역 그리기", systemImage: "plus")
                }
                .accessibilityIdentifier("dashboard.drawStratumExtra")
            }
        } header: {
            Text("① 구역 (Strata)")
        } footer: {
            if !viewModel.strata.isEmpty {
                Text("삭제는 왼쪽으로 스와이프. 면적은 위경도 기반 자동 계산.")
            }
        }
    }

    // MARK: - Plan (step 2)

    private var planSection: some View {
        Section {
            NavigationLink {
                CruiseDesignScreen(project: viewModel.project)
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Label("크루즈 디자인", systemImage: "ruler")
                    Text("플롯 크기·샘플링 방식 선택 → 플롯 자동 생성")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .accessibilityIdentifier("dashboard.designCruise")
            .disabled(viewModel.strata.isEmpty)

            NavigationLink {
                PlotMapScreen(project: viewModel.project)
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Label("플롯 지도", systemImage: "map.fill")
                    Text("생성된 플롯 위치 확인")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .accessibilityIdentifier("dashboard.plotMap")
        } header: {
            Text("② 크루즈 계획")
        } footer: {
            if viewModel.strata.isEmpty {
                Text("먼저 ①에서 구역을 하나 이상 등록하세요.")
            }
        }
    }

    // MARK: - Cruise (step 3)

    @ViewBuilder
    private var cruiseSection: some View {
        Section {
            if let design = viewModel.design {
                NavigationLink {
                    CruiseFlowScreen(project: viewModel.project,
                                     design: design)
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Label("현장 측정 시작 (Go Cruise)",
                              systemImage: "figure.walk.circle.fill")
                            .font(.body.bold())
                        Text("플롯 네비게이션 → 중앙 기록 → AR 경계 → 나무 하나씩 추가")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
                .accessibilityIdentifier("dashboard.goCruise")
            } else {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "lock.circle")
                        .foregroundStyle(.secondary)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("②를 먼저 완료하세요")
                            .font(.subheadline.bold())
                        Text("크루즈 디자인이 저장되고 플롯이 생성되어야 현장 측정을 시작할 수 있어요.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
        } header: {
            Text("③ 현장 측정")
        }
    }

    // MARK: - Tools (step 4 + misc)

    private var toolsSection: some View {
        Section {
            NavigationLink("현장 투입 전 체크리스트") {
                PreFieldChecklistScreen(project: viewModel.project)
            }
            .accessibilityIdentifier("dashboard.preFieldCheck")
            NavigationLink("이 프로젝트 캘리브레이션") {
                CalibrationScreen(
                    viewModel: CalibrationViewModel(),
                    project: viewModel.project,
                    projectRepo: environment.projectRepository)
            }
            .accessibilityIdentifier("dashboard.calibrateProject")
            NavigationLink("결과 내보내기 (PDF·CSV·GeoJSON)") {
                ExportScreen(project: viewModel.project)
            }
            .accessibilityIdentifier("dashboard.export")
            NavigationLink("설정") {
                SettingsScreen()
            }
            .accessibilityIdentifier("dashboard.settings")
        } header: {
            Text("④ 도구")
        } footer: {
            Text("캘리브레이션은 처음 한 번만 하면 됩니다. 측정 정확도 향상에 도움이 됩니다.")
        }
    }

    // MARK: - Formatting

    private func formatAcres(_ value: Double) -> String {
        String(format: "%.2f 에이커", value)
    }

    #if canImport(UniformTypeIdentifiers)
    private var allowedTypes: [UTType] {
        switch importFormat {
        case .geoJSON:
            return [
                UTType(filenameExtension: "geojson") ?? .json,
                .json
            ]
        case .kml:
            return [UTType(filenameExtension: "kml") ?? .xml, .xml]
        }
    }
    #endif
}
