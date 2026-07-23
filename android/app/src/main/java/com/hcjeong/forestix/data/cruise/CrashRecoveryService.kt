// Crash-recovery resume scan — Android counterpart of iOS
// ViewModels/CrashRecoveryService.swift (Phase 7).
//
// On the home screen's first appearance per launch we look for any plots that
// were *opened* (closedAt == null) whose last activity falls inside the last
// 24 hours. If found, MapHomeScreen shows a three-option prompt:
//
//   • Resume  — reopen the plot's active cruise/tally surface where the
//               cruiser left off (sets the current project + active plot and
//               flips the map into cruise mode)
//   • View    — plot #, tree count and last-edited are shown inline so the
//               cruiser can decide before acting (folded into the prompt)
//   • Discard — dismiss the prompt; NOTHING is deleted, the plot stays open
//               and reachable later from the cruise map
//
// The 24-hour threshold: a week-old half-finished plot is almost certainly
// abandoned, but today's / yesterday's work is worth surfacing. `now` and
// `maxAgeMs` are injectable so tests can pin a deterministic clock.

package com.hcjeong.forestix.data.cruise

object CrashRecoveryService {

    /// 24 hours, in milliseconds — the default resume window.
    const val DEFAULT_MAX_AGE_MS = 24L * 60 * 60 * 1000

    /// Process-wide "already checked this launch" guard. The home screen may
    /// recompose or be re-entered (Settings round-trip, mode toggle) many times
    /// per launch; the prompt must appear AT MOST once, and a dismiss/Discard
    /// must not re-prompt. Set true the first time the scan runs.
    @Volatile
    var checkedThisLaunch: Boolean = false

    /// A resumable open plot plus the display facts the prompt surfaces.
    data class ResumeCandidate(
        val plot: Plot,
        val projectName: String,
        val liveTreeCount: Int,
        /// max(plot.startedAt, newest live tree.updatedAt), epoch millis.
        val lastEditedAt: Long,
    ) {
        val id get() = plot.id
    }

    /// Scan every project for open plots (closedAt == null) whose last activity
    /// (max of `startedAt` and any contained live tree's `updatedAt`) falls
    /// inside `maxAgeMs` of `now`. Returns candidates sorted most-recent first.
    suspend fun openPlotsWithinLast(
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
        projectRepo: ProjectRepository,
        plotRepo: PlotRepository,
        treeRepo: TreeRepository,
        now: Long = System.currentTimeMillis(),
    ): List<ResumeCandidate> {
        val cutoff = now - maxAgeMs
        val candidates = mutableListOf<ResumeCandidate>()
        for (project in projectRepo.list()) {
            val plots = plotRepo.listByProject(project.id)
            for (plot in plots) {
                if (plot.closedAt != null) continue
                val trees = treeRepo.listByPlot(plot.id, includeDeleted = false)
                val lastEdited =
                    (trees.map { it.updatedAt } + plot.startedAt).maxOrNull() ?: plot.startedAt
                if (lastEdited < cutoff) continue
                candidates.add(
                    ResumeCandidate(
                        plot = plot,
                        projectName = project.name,
                        liveTreeCount = trees.size,
                        lastEditedAt = lastEdited,
                    )
                )
            }
        }
        return candidates.sortedByDescending { it.lastEditedAt }
    }
}
