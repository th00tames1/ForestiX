// RE-DERIVING CRUISE DIAMETERS FROM THE CAPTURES THAT PRODUCED THEM.
//
// CROSS-PLATFORM: the selection rule, the match tolerance, the skip reasons and
// every user-visible string are byte-identical to the Android sibling
// (data/cruise/DbhEpochRecompute.kt).
//
// WHAT MOVED. `DBHEstimator.estimatorEpoch` counts what the estimators compute.
// Epoch 3 solves the silhouette as a CYLINDER — the bracket edges are tangent
// points, and the depth median sits behind the near face — where epoch 2 solved
// it as a chord through the axis. That is a few per cent on every stem, and a
// diameter measured before it is a different quantity from one measured after.
//
// WHY THE ROW CANNOT BE FIXED IN PLACE. A cruise `Tree` stores `dbhCm` and
// nothing the identity needs: no span, no depth, no focal length. There is no
// arithmetic that turns an epoch-2 diameter into an epoch-3 one. The raw-capture
// bundle, however, holds the depth frames, the intrinsics, the bracket and the
// calibration, so the ONLY honest recomputation is to run the current estimator
// over the stored bytes — which is what `RawCaptureReplay` already does, and
// what this calls. No maths lives in this file.
//
// WHAT THAT MEANS FOR A NUMBER THAT DOES NOT MOVE. The live diameter is a
// trimmed mean over the burst's five sub-samples; the bundle keeps ONE
// representative frame per sub-sample and the replay takes the estimator's
// median over those five. Two honest computations of one capture, so they land
// about a per cent apart even when the estimator has not changed at all. A tree
// already stamped at the current epoch is therefore SKIPPED rather than
// re-derived: re-running it would replace a burst aggregate with a five-frame
// median and call the difference a correction.
//
// HEIGHTS ARE NOT TOUCHED. Nothing here reads a height bundle.

import Foundation
import Models
import Persistence
import Sensors

public enum DBHEpochRecompute {

    // MARK: - Which bundle produced the stored number

    /// How close a bundle's recorded live result must sit to the tree's stored
    /// diameter before that bundle is accepted as the capture the number came
    /// from.
    ///
    /// It cannot be exact. `result_live` is the estimator over the five frames
    /// the bundle keeps; the stored diameter is the trimmed mean over the five
    /// sub-samples of the whole burst window (see `DBHScanViewModel
    /// .finalizeCapture`). The same reasoning `CaptureReadingMatch` records
    /// applies here — the two land "a percent or so apart" on the validation
    /// corpus — and 2 % is that with room for the burst noise on top.
    ///
    /// It is deliberately NOT wide. The job of this number is to separate the
    /// capture that produced the reading from the RETAKES sitting beside it,
    /// and a window wide enough to admit two of them admits the discarded one.
    /// When it does admit two, that is reported as ambiguous and the tree is
    /// left alone — never resolved by taking the newest.
    public static let relativeMatchTolerance: Double = 0.02

    // MARK: - Why a tree was left alone

    /// Every tree the sweep saw is either a `Change` or carries one of these,
    /// so the counts on screen add up to the trees on the device.
    public enum SkipReason: Sendable, Equatable {
        /// Already carries the epoch this recompute would stamp. Makes the
        /// action idempotent, and keeps a burst aggregate from being replaced
        /// by a five-frame median for no gain (see the file header).
        case alreadyCurrentEpoch
        /// Hand-entered — `dbhCaptureMode == "typed"`, or a manual
        /// `dbhMethod` on a row from before that field existed. The cruiser
        /// looked at the stem and typed a number; no capture stands behind it
        /// and replaying one cannot overturn it.
        case typedDiameter
        /// Another row in the same plot carries the same tree number, so the
        /// triple a bundle names does not pick out one tree. Both rows would
        /// otherwise be rewritten from the one capture.
        case duplicateTreeNumber
        /// Nothing on disk carries this tree's (project, plot, tree number).
        case noBundle
        /// Bundles exist, but none holds the value the row holds — so which
        /// capture the row came from is not known.
        case ambiguousNoneMatches(Int)
        /// More than one bundle holds the row's value. A retake that agrees
        /// with the reading it replaced is exactly the case where guessing
        /// would be invisible.
        case ambiguousManyMatch(Int)
        /// The bytes would not reconstruct — a missing depth file, a
        /// truncated write, an estimator that returns nothing.
        case replayFailed
        /// The replay ran and the current estimator REJECTED the fit. A
        /// rejected fit is not a diameter and must not be written as one.
        case replayRejected

        /// What the console prints. Byte-identical to Android.
        public var text: String {
            switch self {
            case .alreadyCurrentEpoch:
                return "already at estimator epoch \(DBHEstimator.estimatorEpoch)"
            case .typedDiameter:
                return "typed diameter — no capture behind it"
            case .duplicateTreeNumber:
                return "ambiguous: two trees share this plot and tree number"
            case .noBundle:
                return "no raw capture for this tree"
            case .ambiguousNoneMatches(let n):
                return "ambiguous: \(n) bundles, none matches the stored value"
            case .ambiguousManyMatch(let n):
                return "ambiguous: \(n) bundles match the stored value"
            case .replayFailed:
                return "bundle could not be replayed"
            case .replayRejected:
                return "replayed fit was rejected"
            }
        }

        /// Fixed display order for the grouped skip list, so the same corpus
        /// reads the same way on both platforms.
        var groupOrder: Int {
            switch self {
            case .alreadyCurrentEpoch:      return 0
            case .typedDiameter:            return 1
            case .duplicateTreeNumber:      return 2
            case .noBundle:                 return 3
            case .ambiguousNoneMatches:     return 4
            case .ambiguousManyMatch:       return 5
            case .replayFailed:             return 6
            case .replayRejected:           return 7
            }
        }
    }

    // MARK: - One planned rewrite

    public struct Change: Sendable, Equatable {
        public let treeID: UUID
        /// What every cruise surface calls this tree — `TreeLabel.title`.
        public let treeLabel: String
        public let treeNumber: Int
        /// The bundle the stored number was matched to, and the one replayed.
        public let bundleID: String
        public let storedCm: Double
        public let recomputedCm: Double

        public var deltaCm: Double { recomputedCm - storedCm }
    }

    public struct Skip: Sendable, Equatable {
        public let treeID: UUID
        public let treeLabel: String
        public let treeNumber: Int
        public let reason: SkipReason
    }

    /// What a run WOULD do, computed before anything is written.
    public struct Plan: Sendable {
        public var changes: [Change] = []
        public var skips: [Skip] = []

        public var isEmpty: Bool { changes.isEmpty }
        public var treesSeen: Int { changes.count + skips.count }

        /// Signed mean of the diameter changes, cm. Zero when nothing moves.
        public var meanDeltaCm: Double {
            guard !changes.isEmpty else { return 0 }
            return changes.reduce(0) { $0 + $1.deltaCm } / Double(changes.count)
        }

        /// The single largest move, by magnitude — reported SIGNED, because
        /// the direction is the whole point of looking at it.
        public var largestChange: Change? {
            changes.min { abs($0.deltaCm) > abs($1.deltaCm) }
        }

        /// Skips grouped by reason, in `SkipReason.groupOrder`. The counted
        /// forms (`ambiguous: N bundles…`) group by their whole text, so two
        /// trees with different bundle counts are two lines.
        public var skipGroups: [(reason: SkipReason, count: Int)] {
            var byText: [String: (reason: SkipReason, count: Int)] = [:]
            for s in skips {
                let key = s.reason.text
                byText[key] = (s.reason, (byText[key]?.count ?? 0) + 1)
            }
            return byText.values.sorted { a, b in
                a.reason.groupOrder == b.reason.groupOrder
                    ? a.reason.text < b.reason.text
                    : a.reason.groupOrder < b.reason.groupOrder
            }
        }
    }

    // MARK: - One project's trees, read on the main actor

    /// A project and the trees under it. The repositories are main-actor work
    /// and the planning is not, so the read is a separate, explicit step.
    public struct ProjectTrees: Sendable {
        public let projectID: UUID
        public let projectName: String
        public let trees: [Tree]

        public init(projectID: UUID, projectName: String, trees: [Tree]) {
            self.projectID = projectID
            self.projectName = projectName
            self.trees = trees
        }
    }

    /// Every project on the device with its (non-deleted) trees. A soft-deleted
    /// tree is not offered: it is not in the cruise any more, and rewriting a
    /// row nothing reads is churn with a risk attached.
    @MainActor
    public static func loadProjects(environment: AppEnvironment) -> [ProjectTrees] {
        guard let projects = try? environment.projectRepository.list() else { return [] }
        return projects.compactMap { project in
            guard let plots = try? environment.plotRepository.listByProject(project.id)
            else { return nil }
            var trees: [Tree] = []
            for plot in plots {
                guard let t = try? environment.treeRepository.listByPlot(plot.id) else { continue }
                trees += t
            }
            guard !trees.isEmpty else { return nil }
            return ProjectTrees(projectID: project.id,
                                projectName: project.name,
                                trees: trees)
        }
    }

    // MARK: - The rule

    /// Work out what would change for one project, changing nothing.
    ///
    /// MATCHING. A bundle names the tree it was taken on: `context` carries the
    /// project, the plot and the tree number, and that triple is the join. A
    /// tree can hold SEVERAL bundles — a retake is a real thing, and the
    /// discarded attempt stays on disk — so the triple alone is not enough to
    /// say which capture the stored number came from. The bundle's own record
    /// of what the app published at capture time is: the one whose
    /// `result_live` still sits on the row's diameter is the one the row was
    /// written from. Anything else is a skip with a reason.
    public static func plan(project: ProjectTrees,
                            summaries: [RawCaptureSummary]) -> Plan {
        var plan = Plan()

        // Index the DBH bundles by the triple they name, once.
        var byTree: [TreeKey: [RawCaptureSummary]] = [:]
        for s in summaries where s.manifest.kind == "dbh" {
            guard let key = TreeKey(context: s.manifest.context) else { continue }
            byTree[key, default: []].append(s)
        }

        // How many ROWS each triple picks out. The triple is the only thing a
        // bundle records about which tree it was taken on, so if two rows share
        // one, no bundle under it identifies either — and matching per tree
        // would rewrite both rows from the same capture.
        var rowsPerKey: [TreeKey: Int] = [:]
        for tree in project.trees {
            let key = TreeKey(projectID: project.projectID,
                              plotID: tree.plotId,
                              treeNumber: tree.treeNumber)
            rowsPerKey[key, default: 0] += 1
        }

        for tree in project.trees.sorted(by: treeOrder) {
            let label = tree.displayTitle
            func skip(_ reason: SkipReason) {
                plan.skips.append(Skip(treeID: tree.id, treeLabel: label,
                                       treeNumber: tree.treeNumber, reason: reason))
            }

            // Idempotence first: a tree already at this epoch is finished,
            // whatever else is true of it.
            if tree.dbhEstimatorEpoch == DBHEstimator.estimatorEpoch {
                skip(.alreadyCurrentEpoch)
                continue
            }
            // `dbhCaptureMode` is the provenance field, and it only exists on
            // rows written since it landed. `dbhMethod` has always recorded a
            // hand-entered diameter, so an older typed row is recognised by
            // that instead of falling through to the bundle match and being
            // rewritten because the cruiser happened to type close to what the
            // screen was showing.
            if tree.dbhCaptureMode == "typed"
                || tree.dbhMethod == .manualVisual
                || tree.dbhMethod == .manualCaliper {
                skip(.typedDiameter)
                continue
            }
            let stored = Double(tree.dbhCm)
            let key = TreeKey(projectID: project.projectID,
                              plotID: tree.plotId,
                              treeNumber: tree.treeNumber)
            guard rowsPerKey[key] == 1 else {
                skip(.duplicateTreeNumber)
                continue
            }
            let candidates = byTree[key] ?? []
            guard !candidates.isEmpty else {
                skip(.noBundle)
                continue
            }
            // A stored diameter of zero has no ratio to match on, and no
            // capture produces one — treat it as unmatchable rather than
            // dividing by it.
            let matches = stored > 0
                ? candidates.filter {
                    abs($0.manifest.resultLive.value - stored)
                        <= relativeMatchTolerance * stored
                }
                : []
            guard matches.count == 1, let match = matches.first else {
                skip(matches.isEmpty
                     ? .ambiguousNoneMatches(candidates.count)
                     : .ambiguousManyMatch(matches.count))
                continue
            }
            guard let result = RawCaptureReplay.rerunDBH(manifest: match.manifest,
                                                         id: match.id) else {
                skip(.replayFailed)
                continue
            }
            guard result.confidence != .red else {
                skip(.replayRejected)
                continue
            }
            let recomputed = Double(result.diameterCm)
            guard recomputed.isFinite, recomputed > 0 else {
                skip(.replayFailed)
                continue
            }
            plan.changes.append(Change(treeID: tree.id, treeLabel: label,
                                       treeNumber: tree.treeNumber,
                                       bundleID: match.id,
                                       storedCm: stored,
                                       recomputedCm: recomputed))
        }
        return plan
    }

    /// Trees in tally order, ties broken on the id so two runs over the same
    /// corpus — and the two platforms — list them the same way.
    private static func treeOrder(_ a: Tree, _ b: Tree) -> Bool {
        a.treeNumber == b.treeNumber
            ? a.id.uuidString < b.id.uuidString
            : a.treeNumber < b.treeNumber
    }

    /// The (project, plot, tree number) triple, parsed once. Built from a
    /// manifest context or from a tree; a context missing any of the three
    /// belongs to no cruise tree (a quick-measure capture carries no plot) and
    /// yields nil rather than a partial key that could collide.
    struct TreeKey: Hashable {
        let projectID: UUID
        let plotID: UUID
        let treeNumber: Int

        init(projectID: UUID, plotID: UUID, treeNumber: Int) {
            self.projectID = projectID
            self.plotID = plotID
            self.treeNumber = treeNumber
        }

        /// Parsed as UUIDs rather than compared as strings: the two platforms
        /// write the same identifier with different letter case.
        init?(context: RawCaptureManifest.Context) {
            guard let p = context.projectId.flatMap(UUID.init(uuidString:)),
                  let plot = context.plotId.flatMap(UUID.init(uuidString:)),
                  let n = context.treeNumber
            else { return nil }
            self.init(projectID: p, plotID: plot, treeNumber: n)
        }
    }

    // MARK: - Running it

    /// What actually reached the store, so the sentence after the run agrees
    /// with the disk rather than with the plan.
    public struct Result: Sendable, Equatable {
        /// Rows rewritten and stamped.
        public var written = 0
        /// Rows whose diameter no longer held the value they were matched on,
        /// or which had been stamped in the meantime. Left exactly as found.
        public var stale = 0
        /// Rows the repository refused. Nothing is hidden: a partial run says
        /// which part failed and leaves the rest alone.
        public var failed = 0
    }

    /// Apply a plan the cruiser has just confirmed.
    ///
    /// RE-CHECKS EVERY ROW under the read it is about to write. The preview is
    /// a snapshot, and between it and the confirm the cruiser can have edited
    /// a diameter in Tree detail or measured the stem again. A row that no
    /// longer holds the value it was matched on is no longer the row the
    /// bundle was matched TO, so it is counted stale and skipped — the match
    /// evidence is gone and re-deriving it would be a guess.
    @MainActor
    public static func apply(_ changes: [Change],
                             repository: any TreeRepository) -> Result {
        var result = Result()
        let now = Date()
        for change in changes {
            // `read` excludes soft-deleted rows, so a tree deleted since the
            // preview counts stale and is not resurrected by a write.
            guard let stored = try? repository.read(id: change.treeID) else {
                result.stale += 1
                continue
            }
            var tree = stored
            guard tree.dbhEstimatorEpoch != DBHEstimator.estimatorEpoch,
                  abs(Double(tree.dbhCm) - change.storedCm) <= storedValueEpsilon
            else {
                result.stale += 1
                continue
            }
            tree.dbhCm = Float(change.recomputedCm)
            tree.dbhEstimatorEpoch = DBHEstimator.estimatorEpoch
            tree.updatedAt = now
            do {
                _ = try repository.update(tree)
                result.written += 1
            } catch {
                result.failed += 1
            }
        }
        return result
    }

    /// Two copies of one stored diameter are the same value inside this band.
    /// `dbhCm` is a Float that round-trips through Core Data untouched, so the
    /// only difference between the planned value and the one on disk is the
    /// Double↔Float conversion — a thousandth of a centimetre is orders of
    /// magnitude more than that and orders of magnitude less than any edit.
    static let storedValueEpsilon: Double = 0.001
}
