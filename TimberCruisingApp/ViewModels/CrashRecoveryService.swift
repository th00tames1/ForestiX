// Phase 7 — crash-recovery resume prompt.
//
// On app launch we look for any plots that were *opened* (closedAt ==
// nil) in the last 24 hours. If found, the home screen shows a
// three-option prompt:
//
//   • Yes, resume  — reopen the plot's tally screen where the cruiser left off
//   • View         — show plot #, tree count, last-edited time before deciding
//   • No, discard  — keep the plot in place but dismiss the prompt (nothing
//                    is deleted; cruiser can still open it later from the
//                    project dashboard)
//
// The 24-hour threshold strikes a balance: a week-old half-finished plot
// is almost certainly abandoned, but today's / yesterday's work is worth
// surfacing. The cutoff is configurable so unit tests can inject a
// deterministic clock.

import Foundation
import Models
import Persistence

public struct ResumeCandidate: Identifiable, Sendable {
    public var id: UUID { plot.id }
    public let plot: Plot
    public let projectName: String
    public let liveTreeCount: Int
    public let lastEditedAt: Date

    public var summary: String {
        "Plot \(plot.plotNumber) • \(liveTreeCount) trees • " +
        RelativeDateTimeFormatter().localizedString(
            for: lastEditedAt, relativeTo: Date())
    }
}

public enum CrashRecoveryService {

    /// Scan every project for open plots younger than `maxAge` whose last
    /// activity (max of `startedAt` and any contained tree's `updatedAt`)
    /// falls inside the window. Returns the candidates sorted most-recent
    /// first.
    public static func openPlotsWithinLast(
        _ maxAge: TimeInterval,
        projectRepo: any ProjectRepository,
        plotRepo: any PlotRepository,
        treeRepo: any TreeRepository,
        now: Date = Date()
    ) throws -> [ResumeCandidate] {
        let cutoff = now.addingTimeInterval(-maxAge)
        var candidates: [ResumeCandidate] = []
        for project in try projectRepo.list() {
            let plots = try plotRepo.listByProject(project.id)
            for plot in plots where plot.closedAt == nil {
                let trees = try treeRepo.listByPlot(plot.id, includeDeleted: false)
                let last = (trees.map { $0.updatedAt } + [plot.startedAt]).max()
                    ?? plot.startedAt
                guard last >= cutoff else { continue }
                candidates.append(ResumeCandidate(
                    plot: plot,
                    projectName: project.name,
                    liveTreeCount: trees.count,
                    lastEditedAt: last))
            }
        }
        return candidates.sorted { $0.lastEditedAt > $1.lastEditedAt }
    }
}
