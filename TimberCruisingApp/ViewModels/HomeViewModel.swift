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
    /// Plots that were left open in the last 24 h — surfaced to the
    /// home screen as a resume banner so force-quit / crash doesn't
    /// silently strand the cruiser's work. Populated by
    /// `CrashRecoveryService.openPlotsWithinLast`.
    @Published public private(set) var resumeCandidates: [ResumeCandidate] = []
    /// Candidate the cruiser chose to dismiss — hidden from the banner
    /// for this launch. Deliberately not persisted; next launch the
    /// banner reappears until they open the plot.
    @Published public var dismissedResumeIds: Set<UUID> = []

    private var repository: (any ProjectRepository)?
    private var plotRepository: (any PlotRepository)?
    private var treeRepository: (any TreeRepository)?

    public init() {}

    public init(repository: any ProjectRepository,
                plotRepository: (any PlotRepository)? = nil,
                treeRepository: (any TreeRepository)? = nil) {
        self.repository = repository
        self.plotRepository = plotRepository
        self.treeRepository = treeRepository
    }

    public func configure(with environment: AppEnvironment) {
        if repository == nil { repository = environment.projectRepository }
        if plotRepository == nil { plotRepository = environment.plotRepository }
        if treeRepository == nil { treeRepository = environment.treeRepository }
    }

    public func refresh() {
        guard let repository else { return }
        do { projects = try repository.list() }
        catch { errorMessage = "Failed to load projects: \(error)" }
        refreshResumeCandidates()
    }

    /// Recomputes the resume banner candidates. Swallows errors —
    /// a failed recovery scan shouldn't hide the project list.
    public func refreshResumeCandidates() {
        guard let repository,
              let plotRepo = plotRepository,
              let treeRepo = treeRepository
        else {
            resumeCandidates = []
            return
        }
        do {
            let all = try CrashRecoveryService.openPlotsWithinLast(
                24 * 3600,
                projectRepo: repository,
                plotRepo: plotRepo,
                treeRepo: treeRepo)
            resumeCandidates = all.filter { !dismissedResumeIds.contains($0.id) }
        } catch {
            resumeCandidates = []
        }
    }

    public func dismissResume(id: UUID) {
        dismissedResumeIds.insert(id)
        resumeCandidates.removeAll { $0.id == id }
    }

    public func create(name: String, owner: String, units: UnitSystem) {
        guard let repository else { return }
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            errorMessage = "Project name is required."
            return
        }
        let now = Date()
        // Calibration defaults match spec §7.10 "identity" values:
        // depthNoiseMm = 5 mm (typical iPhone LiDAR rated noise),
        // bias = 0, DBH correction is identity (α = 0, β = 1).
        // The cruiser can run the wall + cylinder calibration in
        // Settings → Run Calibration to refine these for their device.
        // Without these defaults the Pre-field check would block on a
        // calibration row that's purely clerical.
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
            depthNoiseMm: 5,
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
