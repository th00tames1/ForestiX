// Home screen — list Projects + "New Project" entry point. Spec §3.1 REQ-PRJ-001.

import SwiftUI
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
        VStack(spacing: 16) {
            Image(systemName: "tree.fill")
                .font(.system(size: 48))
                .foregroundStyle(.green.opacity(0.8))
            Text("No projects yet")
                .font(.title3)
                .bold()
            Text("Create a project to define strata, generate a sampling plan, and export the plan as CSV or GeoJSON.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            Button {
                viewModel.isPresentingNewProject = true
            } label: {
                Text("Create first project")
                    .bold()
                    .frame(minHeight: 44)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .padding(.horizontal, 32)
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
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
