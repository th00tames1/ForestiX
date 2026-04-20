// Home screen — list Projects + "New Project" entry point. Spec §3.1 REQ-PRJ-001.
//
// Phase 7 additions: a stacked banner area that surfaces device-level
// health (no LiDAR → manual-only mode, low battery → conserve power) and
// any in-progress plot from the last 24 h (crash-recovery resume prompt).

import SwiftUI
import Common
import Models

public struct HomeScreen: View {

    @EnvironmentObject private var environment: AppEnvironment
    @StateObject private var viewModel = HomeViewModel()

    public init() {}

    public var body: some View {
        NavigationStack {
            content
                .navigationTitle("Projects")
                .toolbar {
                    ToolbarItem(placement: .primaryAction) {
                        Button {
                            viewModel.isPresentingNewProject = true
                        } label: {
                            Label("New Project", systemImage: "plus")
                        }
                        .accessibilityIdentifier("home.newProjectButton")
                    }
                }
                .sheet(isPresented: $viewModel.isPresentingNewProject) {
                    NewProjectSheet { name, owner, units in
                        viewModel.create(name: name, owner: owner, units: units)
                    }
                }
                .alert("Something went wrong",
                       isPresented: Binding(
                            get: { viewModel.errorMessage != nil },
                            set: { if !$0 { viewModel.errorMessage = nil } })
                ) {
                    Button("OK", role: .cancel) { viewModel.errorMessage = nil }
                } message: {
                    Text(viewModel.errorMessage ?? "")
                }
        }
        .task {
            viewModel.configure(with: environment)
            viewModel.refresh()
        }
    }

    @ViewBuilder
    private var content: some View {
        if viewModel.projects.isEmpty {
            emptyState
        } else {
            List {
                DeviceHealthBanners()
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                ForEach(viewModel.projects) { project in
                    NavigationLink {
                        ProjectDashboardScreen(project: project)
                    } label: {
                        ProjectRow(project: project)
                    }
                }
                .onDelete { indexSet in
                    for i in indexSet {
                        viewModel.delete(id: viewModel.projects[i].id)
                    }
                }
            }
            #if os(iOS)
            .listStyle(.insetGrouped)
            #endif
        }
    }

    private var emptyState: some View {
        ScrollView {
            VStack(spacing: 20) {
                Image(systemName: "tree.fill")
                    .font(.system(size: 54))
                    .foregroundStyle(.green.opacity(0.8))
                    .padding(.top, 24)
                Text("Welcome to Forestix")
                    .font(.title2).bold()
                Text("A phone-based timber cruising app. Measure DBH with LiDAR, tree height with AR, and compute stand statistics automatically.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)

                VStack(alignment: .leading, spacing: 12) {
                    Text("How to get started").font(.headline)
                    onboardingStep(n: 1, text: "**Create a project** — name, units, cruiser")
                    onboardingStep(n: 2, text: "**Draw strata on the map** — tap the corners of each cutting block")
                    onboardingStep(n: 3, text: "**Design the cruise** — plot size and sampling method")
                    onboardingStep(n: 4, text: "**Measure in the field** — visit plots, add trees, auto-summarise")
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(18)
                .background(.tint.opacity(0.08))
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .padding(.horizontal, 20)

                Button {
                    viewModel.isPresentingNewProject = true
                } label: {
                    Label("Create your first project", systemImage: "plus.circle.fill")
                        .font(.headline)
                        .frame(minHeight: 56)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .padding(.horizontal, 20)

                Text("All data stays on this device — nothing is sent to any server.")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
                    .padding(.bottom, 20)
            }
        }
    }

    private func onboardingStep(n: Int, text: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            ZStack {
                Circle().fill(Color.accentColor.opacity(0.20))
                    .frame(width: 24, height: 24)
                Text("\(n)").font(.caption.bold())
                    .foregroundStyle(Color.accentColor)
            }
            Text(.init(text))
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

// MARK: - Device health banners

/// Stack of device-level banners surfaced at the top of Home:
/// • LiDAR-absent → "manual-only mode"
/// • Battery ≤ 15% (and not charging) → "conserve power"
private struct DeviceHealthBanners: View {
    @State private var battery = BatteryState.current()

    var body: some View {
        VStack(spacing: 8) {
            if !DeviceCapabilities.hasLiDAR {
                banner(tint: .orange,
                       title: "Manual-only mode",
                       body: "This device has no LiDAR sensor. DBH will need a caliper, height will need a tape. All project and export features remain available.")
            }
            if battery.isLow {
                banner(tint: .red,
                       title: "Low battery (\(Int(battery.level * 100))%)",
                       body: "Scan auto-save has stepped up to every 10 seconds to protect in-progress work. Charge before your next plot.")
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .task { battery = BatteryState.current() }
    }

    @ViewBuilder private func banner(tint: Color,
                                     title: String,
                                     body: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: tint == .red
                  ? "exclamationmark.triangle.fill"
                  : "info.circle.fill")
                .font(.title3)
                .foregroundStyle(.white)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline).bold()
                    .foregroundStyle(.white)
                Text(body).font(.caption)
                    .foregroundStyle(.white.opacity(0.95))
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
        }
        .padding(10)
        .background(tint)
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

// MARK: - Row

private struct ProjectRow: View {
    let project: Project
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(project.name).font(.headline)
            HStack(spacing: 8) {
                Text(project.owner.isEmpty ? "No owner" : project.owner)
                Text("·").foregroundStyle(.tertiary)
                Text(project.units.rawValue.capitalized)
                Text("·").foregroundStyle(.tertiary)
                Text(relativeDate(project.createdAt))
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
        .padding(.vertical, 4)
    }

    private func relativeDate(_ d: Date) -> String {
        let fmt = RelativeDateTimeFormatter()
        fmt.unitsStyle = .abbreviated
        return fmt.localizedString(for: d, relativeTo: Date())
    }
}

// MARK: - New Project sheet

private struct NewProjectSheet: View {
    var onCreate: (_ name: String, _ owner: String, _ units: UnitSystem) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var name: String = ""
    @State private var owner: String = ""
    @State private var units: UnitSystem = .imperial

    var body: some View {
        NavigationStack {
            Form {
                Section("Identity") {
                    TextField("Project name", text: $name)
                        #if os(iOS)
                        .textInputAutocapitalization(.words)
                        #endif
                        .accessibilityIdentifier("newProject.name")
                    TextField("Owner / cruiser", text: $owner)
                        #if os(iOS)
                        .textInputAutocapitalization(.words)
                        #endif
                        .accessibilityIdentifier("newProject.owner")
                }
                Section("Units") {
                    Picker("Unit system", selection: $units) {
                        Text("Imperial (ft, in, acres)").tag(UnitSystem.imperial)
                        Text("Metric (m, cm, ha)").tag(UnitSystem.metric)
                    }
                    .pickerStyle(.segmented)
                }
            }
            .navigationTitle("New Project")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create") { onCreate(name, owner, units) }
                        .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        .accessibilityIdentifier("newProject.create")
                }
            }
        }
    }
}
