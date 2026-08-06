// WHETHER A PROJECT'S ROUND-POST SCAN IS ACTUALLY CORRECTING ITS WIDTHS —
// asked once, here, by everything that reports it.
//
// This used to be re-derived at every surface that wanted to say something
// about calibration, and the copies drifted apart the moment the epoch guard
// arrived:
//
//   • the Calibration screen         alpha != 0 || beta != 1, then the epoch
//   • the iOS PDF report             alpha == 0 && beta == 1        (no epoch)
//   • the Android PDF report         lidarBiasMm != 0 || beta != 1  (no epoch)
//
// Three predicates for one question, and two of them wrong: a project whose
// coefficients the estimator is REFUSING still had "Wall and round-post
// calibration applied" printed in a document that goes to a landowner. A
// report may not claim a correction the estimator declined to make.
//
// The epoch lives here rather than on `DBHEstimator` because `Export` cannot
// see `Sensors` — which is exactly why the PDF invented its own answer instead
// of asking. `Models` is the deepest module all three surfaces share, and it
// already owns `Project.dbhCalibrationEpoch` and `Tree.dbhEstimatorEpoch`, so
// the generation counter and the fields that record it now sit together.
// `DBHEstimator.estimatorEpoch` reads from here and keeps its name.

import Foundation

public enum DBHCalibration {

    /// THE ESTIMATOR GENERATION. Bump this whenever a change to the diameter
    /// estimator moves the numbers it produces — a fitted calibration absorbs
    /// whatever systematic error the estimator had on the day it was fitted,
    /// so under a different estimator the two corrections stack and the
    /// project under-reads silently.
    ///
    /// Android's `DBHEstimator.ESTIMATOR_EPOCH` MUST move with it. A cruise
    /// measured on one phone and finished on the other is one cruise.
    ///
    ///   3 — 2026-07-30  chord tangent + near-face depth sampling
    ///   4 — 2026-07-31  one diameter from an ADJUST bracket, not two
    ///   5 — 2026-08-02  Android bracket rework; both phones read a bracket
    ///                   the same way
    public static let currentEpoch = 5

    /// What a project's stored coefficients are actually doing right now.
    public enum State: Equatable, Sendable {
        /// No round-post scan has been fitted. Widths are the estimator's own.
        case none
        /// Coefficients fitted against THIS estimator; they are being applied.
        case applied
        /// Coefficients fitted against an earlier estimator. They are on file
        /// and are being IGNORED — reporting them as applied is a false claim,
        /// and reporting them as absent hides a scan the cruiser remembers
        /// doing, so this case has to stay distinguishable from both.
        case ignoredStale
    }

    /// The one test. `alpha`/`beta` decide whether a scan exists at all, and
    /// the epoch decides whether it still answers the current estimator.
    ///
    /// A never-calibrated project reads `.none` rather than `.ignoredStale`
    /// even though its epoch is 0: the identity correction is the identity at
    /// every epoch, so there is nothing being refused.
    public static func state(alpha: Float,
                             beta: Float,
                             epoch: Int) -> State {
        guard alpha != 0 || beta != 1 else { return .none }
        return epoch == currentEpoch ? .applied : .ignoredStale
    }

    public static func state(of project: Project) -> State {
        state(alpha: project.dbhCorrectionAlpha,
              beta: project.dbhCorrectionBeta,
              epoch: project.dbhCalibrationEpoch)
    }
}

extension DBHCalibration.State {

    /// What a CLIENT reads in the PDF. No instruction — a landowner cannot
    /// re-run a round-post scan — but no false claim either.
    public var reportPhrase: String {
        switch self {
        case .none:
            return "Not calibrated on this device"
        case .applied:
            return "Wall and round-post calibration applied"
        case .ignoredStale:
            return "Round-post calibration on file but NOT applied — it was "
                 + "measured with an earlier version of the width estimator"
        }
    }
}
