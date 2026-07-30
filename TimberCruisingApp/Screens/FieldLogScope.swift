// Field-log scope — which project / which plot the log is showing.
//
// FIELD REPORT 5 (second half). The cruiser asked to read the field log
// "per project and per plot", and wrote "서브플롯? 스탠드?? 플롯??" — they are
// not sure what the app calls these levels. Neither was the code, so this
// file fixes ONE vocabulary and states, in the type system, the thing that
// makes the request delicate:
//
//   THIS APP HAS TWO SEPARATE WORLDS, AND THEY DO NOT SHARE A PLOT.
//
//   • CRUISE      Project → Plot → Tree.  (Stratum and PlannedPlot hang off
//                 the project too, but no measurement is filed under them.)
//                 Stored in Core Data. A cruise Tree ALWAYS has a plot, and
//                 that plot ALWAYS has a project.
//
//   • QUICK       QuickMeasurePlot → QuickMeasureEntry.  Stored in the
//                 project-less QuickMeasureHistory. A quick plot has NO
//                 project — there is no column for one and no screen that
//                 could set it. The default plot is literally named
//                 "Quick measurements".
//
// `QuickMeasureEntry.plotID` and `Plot.id` are both UUIDs and they are NOT
// interchangeable: a quick id will never match a cruise plot row and vice
// versa. Every scan screen's Accept branches on cruise-vs-quick and writes
// to exactly one of the two stores, so a tree can never be in both.
//
// Which is why this scope is a CLOSED enum over both worlds rather than a
// pair of loose UUIDs, and why the screen renders the two in separate
// sections and never interleaves them. Claiming a quick reading belongs to
// the project the cruiser happens to have open would be inventing a fact the
// data does not carry.
//
// The Android sibling is ui/screens/FieldLogScope.kt — same cases, same
// user-visible strings.

import Foundation
import SwiftUI
import Models

public enum FieldLogScope: Equatable, Hashable {
    /// Both worlds, every plot.
    case everything
    /// One cruise project — all of its plots.
    case cruiseProject(UUID)
    /// One cruise plot.
    case cruisePlot(UUID)
    /// One quick-measure plot.
    case quickPlot(UUID)
}

// MARK: - Fixed vocabulary

/// The words this feature uses, in one place, so the two platforms and the
/// two worlds cannot drift apart. Android holds the identical strings in
/// `FieldLogWords`.
public enum FieldLogWords {
    /// Filter sheet title.
    public static let filterTitle = "Show"
    public static let everything = "Everything"
    public static let cruiseProjectsHeader = "Cruise projects"
    public static let quickPlotsHeader = "Quick measurement plots"
    /// A project row that means "every plot in it".
    public static let allPlots = "All plots"
    /// What the PROJECT half of a quick-measure row's heading says. Not a
    /// placeholder — it is the fact: quick measurements are filed under no
    /// project at all.
    public static let noProject = "No project"
    /// Explains the split, in the cruiser's terms, above the list.
    public static let worldsCaption =
        "Cruise trees belong to a project and a plot. Quick measurements belong to a plot only — they are not part of any project."
    /// Scope has no rows (as opposed to the whole log being empty).
    public static let emptyScope = "Nothing recorded in this plot yet."
    /// The cruise store could not be read. Never silently show an empty
    /// cruise section instead — an empty plot and an unreadable database
    /// look the same to the cruiser and only one of them means "no trees".
    public static let cruiseUnreadable =
        "Cruise trees could not be read from this device's database, so none are shown here."
    /// Separator between the project and the plot in a section heading.
    public static let headingSeparator = " · "
    /// A reading whose plot row is gone. It is still a real reading, so it
    /// keeps its section — labelled for what is actually known about it
    /// rather than quietly re-homed under some other plot's heading.
    public static let unknownPlot = "Plot not found"

    /// How a cruise plot is named everywhere in this feature.
    public static func plotName(number: Int) -> String { "Plot \(number)" }

    /// Where a recorded coordinate came from, in words. The raws are
    /// `PositionSource` values, shared with the cruise `Plot` record.
    ///
    /// A coordinate the cruiser typed MUST read differently from one the
    /// satellites produced — otherwise a corrected position is
    /// indistinguishable from a measured one on screen and in the export,
    /// which is the same class of error as a typed diameter carrying a
    /// sensor σ. Unknown raws print themselves rather than being folded
    /// into "Device GPS": a wrong provenance is worse than an opaque one.
    public static func positionSourceText(_ raw: String) -> String {
        switch raw {
        case "manual":      return "Typed by hand"
        case "gpsSingle":   return "Device GPS"
        case "gpsAveraged": return "Device GPS, averaged"
        case "externalRTK": return "External RTK receiver"
        case "vioOffset", "vioChain": return "Walked from a known point"
        default:            return raw
        }
    }
}

// MARK: - Cruise-side rows

/// One cruise tree, flattened for the log's three-column table.
///
/// READ-ONLY here. The quick rows beside it can be edited, re-measured and
/// swiped away, because the field log owns that store. A cruise tree is
/// owned by the cruise flow and edited on TreeDetailScreen — duplicating
/// that editing here would give the same row two save paths.
public struct FieldLogCruiseRow: Identifiable, Equatable {
    public let id: UUID
    public let plotID: UUID
    public let treeLabel: String
    /// cm, as stored. nil is impossible for a cruise tree (dbhCm is
    /// non-optional) but height genuinely can be absent.
    public let dbhCm: Double
    public let heightM: Double?
    public let speciesCode: String
    public let recordedAt: Date

    public init(tree: Tree) {
        self.id = tree.id
        self.plotID = tree.plotId
        self.treeLabel = tree.treeName ?? "#\(tree.treeNumber)"
        self.dbhCm = Double(tree.dbhCm)
        self.heightM = tree.heightM.map(Double.init)
        self.speciesCode = tree.speciesCode
        self.recordedAt = tree.createdAt
    }
}

// MARK: - Sections

/// One heading plus the rows under it. A section is always ONE plot of ONE
/// world, so `projectLabel` / `plotLabel` are true of every row inside it —
/// which is how each row says where it belongs without the three-column
/// table growing two more columns it has no width for.
public struct FieldLogSection: Identifiable, Equatable {
    public let id: String
    public let projectLabel: String
    public let plotLabel: String
    /// Editable quick-measure rows. Empty on a cruise section.
    public let quickRows: [FieldLogRowModel]
    /// Read-only cruise rows. Empty on a quick section.
    public let cruiseRows: [FieldLogCruiseRow]
    /// Newest reading anywhere in the section — the sort key.
    public let latest: Date

    public var heading: String {
        projectLabel + FieldLogWords.headingSeparator + plotLabel
    }

    public var isEmpty: Bool { quickRows.isEmpty && cruiseRows.isEmpty }
}

// MARK: - Cruise feed

/// Loads the cruise half of the log. Kept out of `FieldLogScreen` because
/// the screen is a quick-measure screen and this is the only place it
/// touches Core Data.
///
/// Everything is read ONCE per appearance and held: the field log is a
/// read-back surface, and re-querying every plot on every body evaluation
/// would put a Core Data fetch inside SwiftUI's layout pass.
@MainActor
public final class FieldLogCruiseFeed: ObservableObject {

    @Published public private(set) var projects: [Project] = []
    @Published public private(set) var plotsByProject: [UUID: [Plot]] = [:]
    @Published public private(set) var rowsByPlot: [UUID: [FieldLogCruiseRow]] = [:]
    /// Non-nil when the store could not be read. The screen SAYS SO rather
    /// than rendering an empty cruise side, which would read as "you have
    /// measured no trees".
    @Published public private(set) var failure: String?
    @Published public private(set) var hasLoaded = false

    public init() {}

    /// Project a cruise plot belongs to, for a heading.
    public func project(ofPlot plotID: UUID) -> Project? {
        for (projectID, plots) in plotsByProject where plots.contains(where: { $0.id == plotID }) {
            return projects.first { $0.id == projectID }
        }
        return nil
    }

    public func plot(id: UUID) -> Plot? {
        for plots in plotsByProject.values {
            if let hit = plots.first(where: { $0.id == id }) { return hit }
        }
        return nil
    }

    public func load(environment: AppEnvironment) {
        do {
            let allProjects = try environment.projectRepository.list()
            var plots: [UUID: [Plot]] = [:]
            var rows: [UUID: [FieldLogCruiseRow]] = [:]
            for project in allProjects {
                let projectPlots = try environment.plotRepository.listByProject(project.id)
                plots[project.id] = projectPlots.sorted { $0.plotNumber < $1.plotNumber }
                for plot in projectPlots {
                    // Soft-deleted trees stay out: `listByPlot` already
                    // defaults to includeDeleted false, and a tree the
                    // cruiser deleted must not reappear in a read-back.
                    rows[plot.id] = try environment.treeRepository.listByPlot(plot.id)
                        .map(FieldLogCruiseRow.init)
                        .sorted { $0.recordedAt > $1.recordedAt }
                }
            }
            projects = allProjects.sorted { $0.name < $1.name }
            plotsByProject = plots
            rowsByPlot = rows
            failure = nil
        } catch {
            // Keep whatever was already loaded — a later failure must not
            // blank rows the cruiser is reading — but say the read failed.
            failure = FieldLogWords.cruiseUnreadable
        }
        hasLoaded = true
    }

    // MARK: Sections

    /// Cruise sections in scope, newest plot first. A plot with no trees is
    /// dropped from `.everything` and from a whole-project scope (there is
    /// nothing to read), but KEPT when the cruiser asked for that one plot —
    /// there the empty section is the answer to their question.
    public func sections(for scope: FieldLogScope) -> [FieldLogSection] {
        var out: [FieldLogSection] = []
        for project in projects {
            for plot in plotsByProject[project.id] ?? [] {
                let keep: Bool
                switch scope {
                case .everything:               keep = true
                case .cruiseProject(let id):    keep = id == project.id
                case .cruisePlot(let id):       keep = id == plot.id
                case .quickPlot:                keep = false
                }
                guard keep else { continue }
                let rows = rowsByPlot[plot.id] ?? []
                if rows.isEmpty {
                    // A plot the cruiser explicitly asked for keeps its
                    // heading, so "nothing here yet" is an answer rather
                    // than a section that silently isn't there.
                    guard scope == .cruisePlot(plot.id) else { continue }
                }
                out.append(FieldLogSection(
                    id: "c|\(plot.id.uuidString)",
                    projectLabel: project.name,
                    plotLabel: FieldLogWords.plotName(number: plot.plotNumber),
                    quickRows: [],
                    cruiseRows: rows,
                    latest: rows.first?.recordedAt ?? plot.startedAt))
            }
        }
        return out.sorted { $0.latest > $1.latest }
    }
}
