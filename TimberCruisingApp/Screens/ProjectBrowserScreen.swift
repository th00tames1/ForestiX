// Browse projects — the cruise entry point's second door.
//
// FIELD REPORT (item 4) — "there is no way to delete a cruise project, or
// to see the ones that exist". The project sheet does list projects, but it
// lists them as radio rows to switch between: no dates, no way out of a
// project the cruiser no longer wants, and the list shares the sheet with
// export, setup and the footer tools. This screen is the list itself —
// every project, what is in it, when it was last worked, and the only place
// in the app that can delete one.
//
// WHAT DELETING REMOVES. Neither store cascades on its own: the Core Data
// model has no relationships (flat entities joined by UUID), and the Room
// entities carry no foreign keys. Deleting the ProjectEntity row alone
// would leave its plots, trees, planned plots, strata, design and H-D fits
// on disk forever — invisible, unexportable, and still counted by nothing.
// So the cascade is explicit and it is a HARD delete, which is the rule the
// plot peek's "Delete plot" already follows (trees hard-deleted with their
// photos, then the plot row). Tree rows are the one soft-deletable thing in
// cruise, and `includeDeleted: true` sweeps those too — a soft-deleted tree
// whose plot is gone can never be restored, so leaving it is just litter.
//
// Order matters: children first, project row last. A storage error part way
// through then leaves a project that still owns whatever survived, rather
// than orphans with no project to belong to.
//
// The Android sibling is
// android/.../ui/screens/cruise/ProjectBrowserScreen.kt; every user-visible
// string here is duplicated there byte for byte.

import SwiftUI
import Models
import Common
import Persistence

/// One project as this screen shows it: the row plus the counts the
/// confirmation has to quote. Counts are taken from the same repository
/// calls the project sheet's row already makes — no new query shapes.
struct ProjectBrowserRow: Identifiable {
    let project: Project
    /// Plots in the project, and how many of them are closed.
    let plotCount: Int
    let closedPlotCount: Int
    /// LIVE trees (soft-deleted ones excluded), which is what every other
    /// cruise surface counts.
    let treeCount: Int
    /// Newest plot start / close or tree edit in the project — the "last
    /// activity" line. nil when nothing has been measured yet; the row then
    /// says so rather than repeating the creation date under a second name.
    let lastActivity: Date?

    var id: UUID { project.id }
}

public struct ProjectBrowserScreen: View {

    @EnvironmentObject private var environment: AppEnvironment
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss

    @State private var rows: [ProjectBrowserRow] = []
    /// The row whose delete confirmation is up. Held (rather than deleting
    /// on tap) because a cruise project is days of fieldwork.
    @State private var deleteCandidate: ProjectBrowserRow?
    /// Set when the cascade could not finish. The screen says so in plain
    /// language instead of pretending the project is gone.
    @State private var deleteFailure: String?
    /// Project whose Stand summary is pushed from here.
    @State private var summaryProjectID: UUID?

    public init() {}

    public var body: some View {
        List {
            if rows.isEmpty {
                emptyState
            } else {
                ForEach(rows) { row in
                    projectRow(row)
                }
            }
        }
        .navigationTitle("Projects")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .onAppear { reload() }
        .navigationDestination(item: $summaryProjectID) { id in
            standSummary(forProjectID: id)
        }
        .alert("Delete this project?",
               isPresented: Binding(
                   get: { deleteCandidate != nil },
                   set: { if !$0 { deleteCandidate = nil } }),
               presenting: deleteCandidate) { row in
            Button("Delete project", role: .destructive) {
                delete(row)
                deleteCandidate = nil
            }
            Button("Cancel", role: .cancel) { deleteCandidate = nil }
        } message: { row in
            Text(Self.deleteMessage(for: row, isActive: isActive(row.project)))
        }
        .alert("Project not deleted",
               isPresented: Binding(
                   get: { deleteFailure != nil },
                   set: { if !$0 { deleteFailure = nil } })) {
            Button("OK", role: .cancel) { deleteFailure = nil }
        } message: {
            Text(deleteFailure ?? "")
        }
    }

    // MARK: Rows

    private var emptyState: some View {
        VStack(alignment: .leading, spacing: ForestixSpace.xxs) {
            Text("No projects yet")
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(ForestixPalette.textPrimary)
            Text("Start one with New project on the map's project sheet.")
                .font(.system(size: 12))
                .foregroundStyle(ForestixPalette.textTertiary)
        }
        .padding(.vertical, ForestixSpace.xs)
    }

    private func projectRow(_ row: ProjectBrowserRow) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Text(row.project.name)
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(ForestixPalette.textPrimary)
                    .lineLimit(1)
                Spacer(minLength: 4)
                if isActive(row.project) { inUseChip }
            }
            Text(Self.countsLine(row))
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(ForestixPalette.textSecondary)
            Text(Self.datesLine(row))
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(ForestixPalette.textTertiary)
            rowActions(row)
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        // Tapping the row IS "use this project" — the same act as the
        // project sheet's radio row, and it drops straight back to the map
        // so the cruiser is one tap from tallying.
        .onTapGesture { open(row.project) }
        .accessibilityIdentifier("projectBrowser.row")
    }

    private var inUseChip: some View {
        Text("IN USE")
            .font(.system(size: 10, weight: .bold))
            .tracking(0.6)
            .foregroundStyle(ForestixPalette.accent)
            .padding(.horizontal, 7)
            .padding(.vertical, 2)
            .background(
                RoundedRectangle(cornerRadius: ForestixRadius.chip)
                    .fill(ForestixPalette.accent.opacity(0.12)))
    }

    /// `.borderless` on both: a plain Button inside a List row otherwise
    /// takes the whole row's tap, and the row's own tap is "use this
    /// project".
    private func rowActions(_ row: ProjectBrowserRow) -> some View {
        HStack(spacing: ForestixSpace.md) {
            Button("Stand summary") { summaryProjectID = row.project.id }
                .font(.system(size: 13, weight: .semibold))
                .buttonStyle(.borderless)
                .accessibilityIdentifier("projectBrowser.standSummary")
            Spacer(minLength: 4)
            Button("Delete") { deleteCandidate = row }
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(ForestixPalette.confidenceBad)
                .buttonStyle(.borderless)
                .accessibilityIdentifier("projectBrowser.delete")
        }
        .padding(.top, 2)
    }

    /// The KEPT StandSummaryScreen — the same screen, the same view model
    /// and the same arguments the project sheet's "Stand summary" row
    /// pushes, only scoped to the browsed project instead of the active
    /// one. There is no second summary screen.
    @ViewBuilder
    private func standSummary(forProjectID id: UUID) -> some View {
        if let project = rows.first(where: { $0.project.id == id })?.project {
            StandSummaryScreen(viewModel: StandSummaryViewModel(
                project: project,
                design: CruiseDesignFallback.effective(
                    forProjectID: project.id,
                    repository: environment.cruiseDesignRepository),
                plotRepo: environment.plotRepository,
                treeRepo: environment.treeRepository,
                speciesRepo: environment.speciesRepository,
                volRepo: environment.volumeEquationRepository,
                hdFitRepo: environment.hdFitRepository,
                stratumRepo: environment.stratumRepository,
                plannedRepo: environment.plannedPlotRepository),
                volumePending: settings.country.volumeStandard.isPending,
                areaUnit: settings.unitSystem.areaUnit)
        }
    }

    // MARK: Reading

    private func isActive(_ project: Project) -> Bool {
        activeProjectID == project.id
    }

    /// The project cruise is actually scoped to — the persisted pick when it
    /// still exists, else the most recently updated project. The SAME rule
    /// as `MapHomeScreen.currentProject`; a browser that marked a different
    /// row "IN USE" than the map's strip would be lying about which project
    /// the next tree lands in.
    private var activeProjectID: UUID? {
        if let id = settings.currentCruiseProjectID,
           rows.contains(where: { $0.project.id == id }) {
            return id
        }
        return rows.map(\.project).max(by: { $0.updatedAt < $1.updatedAt })?.id
    }

    private func reload() {
        let projects = (try? environment.projectRepository.list()) ?? []
        rows = projects.map { project in
            let plots = (try? environment.plotRepository
                .listByProject(project.id)) ?? []
            var trees = 0
            var activity: Date?
            for plot in plots {
                let live = (try? environment.treeRepository
                    .listByPlot(plot.id, includeDeleted: false)) ?? []
                trees += live.count
                activity = Self.newer(activity, plot.startedAt)
                activity = Self.newer(activity, plot.closedAt)
                for tree in live { activity = Self.newer(activity, tree.updatedAt) }
            }
            return ProjectBrowserRow(
                project: project,
                plotCount: plots.count,
                closedPlotCount: plots.filter { $0.closedAt != nil }.count,
                treeCount: trees,
                lastActivity: activity)
        }
    }

    private static func newer(_ current: Date?, _ candidate: Date?) -> Date? {
        guard let candidate else { return current }
        guard let current else { return candidate }
        return candidate > current ? candidate : current
    }

    // MARK: Acting

    /// Make this project the active one and go back to the map. The map's
    /// `pushed`-went-nil hook re-reads the repositories, so the pins, the
    /// strip and the AR ring all reconcile to the new project on arrival.
    private func open(_ project: Project) {
        settings.currentCruiseProjectID = project.id
        dismiss()
    }

    private func delete(_ row: ProjectBrowserRow) {
        let wasActive = isActive(row.project)
        do {
            try ProjectCascade.delete(projectID: row.project.id,
                                      environment: environment)
        } catch {
            // The cascade removes children before the project row, so a
            // storage error part way through leaves a REAL project holding
            // less than it did. Saying "deleted" here would be a lie, and
            // saying nothing would be worse.
            deleteFailure = Self.deleteFailureMessage(
                name: row.project.name, reason: error.localizedDescription)
            reload()
            return
        }
        reload()
        // A pointer at a project that no longer exists would leave the map
        // strip, the export button and the tally loop aimed at nothing. Name
        // the successor here rather than leaning on the fallback, so this
        // screen and the map agree on which project is next — and so the
        // Android sibling, whose fallback is "newest created", lands on the
        // same project as iOS's "most recently updated".
        if wasActive {
            settings.currentCruiseProjectID =
                rows.map(\.project).max(by: { $0.updatedAt < $1.updatedAt })?.id
        }
        if summaryProjectID == row.project.id { summaryProjectID = nil }
    }

    // MARK: Strings (byte-identical with the Android sibling)

    static func countsLine(_ row: ProjectBrowserRow) -> String {
        "Plots \(row.closedPlotCount)/\(row.plotCount) · Trees \(row.treeCount)"
    }

    static func datesLine(_ row: ProjectBrowserRow) -> String {
        let made = "Made \(dateFormatter.string(from: row.project.createdAt))"
        guard let activity = row.lastActivity else {
            return "\(made) · No measurements yet"
        }
        return "\(made) · Last activity \(dateFormatter.string(from: activity))"
    }

    /// Names the project AND the size of the loss — a cruise project is days
    /// of fieldwork, and "Delete?" alone does not tell the cruiser what they
    /// are about to spend.
    static func deleteMessage(for row: ProjectBrowserRow,
                              isActive: Bool) -> String {
        var text = "\(row.project.name) holds "
            + "\(count(row.plotCount, "plot", "plots")) and "
            + "\(count(row.treeCount, "tree", "trees")). "
            + "Deleting removes the project and every plot, tree and photo "
            + "in it. This can't be undone."
        if isActive {
            text += " Cruise is using this project now — the map will switch "
                + "to your most recently updated project."
        }
        return text
    }

    static func deleteFailureMessage(name: String, reason: String) -> String {
        "\(name) could not be fully deleted: \(reason). Some of it may "
            + "already be gone — open the project to see what is left, then "
            + "try again."
    }

    static func count(_ n: Int, _ one: String, _ many: String) -> String {
        "\(n) \(n == 1 ? one : many)"
    }

    /// Fixed POSIX format so the row reads the same on every device — the
    /// same choice the project sheet's "d MMM" row makes, widened with the
    /// year because a browse list spans seasons.
    static let dateFormatter: DateFormatter = {
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = "d MMM yyyy"
        return df
    }()
}

// MARK: - Cascade

/// The whole-project hard delete, in one place because it is the only
/// operation in cruise that can remove days of work and it must remove ALL
/// of it. See the file header for why nothing cascades on its own.
enum ProjectCascade {

    static func delete(projectID: UUID, environment: AppEnvironment) throws {
        let plots = (try? environment.plotRepository
            .listByProject(projectID)) ?? []
        for plot in plots {
            // includeDeleted: the soft-deleted rows are about to lose the
            // plot that gives them meaning, exactly as "Delete plot" does.
            let trees = (try? environment.treeRepository
                .listByPlot(plot.id, includeDeleted: true)) ?? []
            for tree in trees {
                if let photo = tree.photoPath { MeasurePhotoStore.delete(photo) }
                try environment.treeRepository.hardDelete(id: tree.id)
            }
            try environment.plotRepository.delete(id: plot.id)
        }
        // Project-scoped rows the plot cascade never touches. Each would
        // otherwise outlive its project with no screen able to reach it.
        for planned in (try? environment.plannedPlotRepository
            .listByProject(projectID)) ?? [] {
            try environment.plannedPlotRepository.delete(id: planned.id)
        }
        for stratum in (try? environment.stratumRepository
            .listByProject(projectID)) ?? [] {
            try environment.stratumRepository.delete(id: stratum.id)
        }
        for design in (try? environment.cruiseDesignRepository
            .forProject(projectID)) ?? [] {
            try environment.cruiseDesignRepository.delete(id: design.id)
        }
        for fit in (try? environment.hdFitRepository
            .listByProject(projectID)) ?? [] {
            try environment.hdFitRepository.delete(id: fit.id)
        }
        try environment.projectRepository.delete(id: projectID)
    }
}
