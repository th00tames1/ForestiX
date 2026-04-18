// Home screen state. Spec §3.1 REQ-PRJ-001 — list/create/delete Projects.
//
// Initialized without a repository so the owning `View` can instantiate the
// VM via `@StateObject` before the environment is known, then call
// `configure(with:)` from `.task` once `@EnvironmentObject` is available.

import Foundation
import Models
import Persistence

@MainActor
public final class HomeViewModel: ObservableObject {

    @Published public private(set) var projects: [Project] = []
    @Published public var errorMessage: String?
    @Published public var isPresentingNewProject: Bool = false

    private var repository: (any ProjectRepository)?

    public init() {}

    public init(repository: any ProjectRepository) {
        self.repository = repository
    }

    public func configure(with environment: AppEnvironment) {
        if repository == nil { repository = environment.projectRepository }
    }

    public func refresh() {
        guard let repository else { return }
        do { projects = try repository.list() }
        catch { errorMessage = "Failed to load projects: \(error)" }
    }

    public func create(name: String, owner: String, units: UnitSystem) {
        guard let repository else { return }
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            errorMessage = "Project name is required."
            return
        }
        let now = Date()
        let project = Project(
            id: UUID(),
            name: trimmed,
            description: "",
            owner: owner.trimmingCharacters(in: .whitespacesAndNewlines),
            createdAt: now,
            updatedAt: now,
            units: units,
            breastHeightConvention: .uphill,
            slopeCorrection: true,
            lidarBiasMm: 0,
            depthNoiseMm: 0,
            dbhCorrectionAlpha: 0,
            dbhCorrectionBeta: 1,
            vioDriftFraction: 0.02
        )
        do {
            _ = try repository.create(project)
            isPresentingNewProject = false
            refresh()
        } catch {
            errorMessage = "Failed to create project: \(error)"
        }
    }

    public func delete(id: UUID) {
        guard let repository else { return }
        do {
            try repository.delete(id: id)
            refresh()
        } catch {
            errorMessage = "Failed to delete project: \(error)"
        }
    }
}
