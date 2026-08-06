// WHETHER A PROJECT'S ROUND-POST SCAN IS ACTUALLY CORRECTING ITS WIDTHS —
// asked once, here, by everything that reports it. Port of iOS
// Models/DBHCalibrationState.swift; the two must stay identical.
//
// This used to be re-derived at every surface that wanted to say something
// about calibration, and the copies drifted apart the moment the epoch guard
// arrived:
//
//   • the Calibration screen         alpha != 0 || beta != 1, then the epoch
//   • the Android PDF report         lidarBiasMm != 0 || beta != 1  (no epoch)
//   • the iOS PDF report             alpha == 0 && beta == 1        (no epoch)
//
// Three predicates for one question, and two of them wrong — and the Android
// PDF's was wrong twice over, because `lidarBiasMm` is the WALL scan's depth
// offset, not the round-post scan's width coefficients. A project could print
// "Wall and round-post calibration applied" on the strength of a wall scan
// alone. Worse, a project whose coefficients the estimator was REFUSING as
// stale printed the same sentence, in a document that goes to a landowner.
// A report may not claim a correction the estimator declined to make.
//
// The epoch lives here, beside `Project.dbhCalibrationEpoch`, for the reason
// iOS had to move it: the report and the screen need the same answer, and the
// report could not reach the estimator to get it.
// `DBHEstimator.ESTIMATOR_EPOCH` reads from here and keeps its name.

package com.hcjeong.forestix.data.cruise

object DBHCalibration {

    /// THE ESTIMATOR GENERATION. Bump whenever a change to the diameter
    /// estimator moves the numbers it produces — a fitted calibration absorbs
    /// whatever systematic error the estimator had on the day it was fitted,
    /// so under a different estimator the two corrections stack and the
    /// project under-reads silently.
    ///
    /// iOS `DBHCalibration.currentEpoch` MUST move with it. A cruise measured
    /// on one phone and finished on the other is one cruise.
    ///
    ///   3 — 2026-07-30  chord tangent + near-face depth sampling
    ///   4 — 2026-07-31  one diameter from an ADJUST bracket, not two
    ///   5 — 2026-08-02  Android bracket rework; both phones read a bracket
    ///                   the same way
    const val CURRENT_EPOCH = 5

    /// What a project's stored coefficients are actually doing right now.
    enum class State {
        /// No round-post scan has been fitted. Widths are the estimator's own.
        NONE,

        /// Coefficients fitted against THIS estimator; they are being applied.
        APPLIED,

        /// Coefficients fitted against an earlier estimator. They are on file
        /// and are being IGNORED — reporting them as applied is a false claim,
        /// and reporting them as absent hides a scan the cruiser remembers
        /// doing, so this case has to stay distinguishable from both.
        IGNORED_STALE,
        ;

        /// What a CLIENT reads in the PDF. No instruction — a landowner cannot
        /// re-run a round-post scan — but no false claim either.
        val reportPhrase: String
            get() = when (this) {
                NONE -> "Not calibrated on this device"
                APPLIED -> "Wall and round-post calibration applied"
                IGNORED_STALE ->
                    "Round-post calibration on file but NOT applied — it was " +
                        "measured with an earlier version of the width estimator"
            }
    }

    /// The one test. `alpha`/`beta` decide whether a scan exists at all, and
    /// the epoch decides whether it still answers the current estimator.
    ///
    /// A never-calibrated project reads NONE rather than IGNORED_STALE even
    /// though its epoch is 0: the identity correction is the identity at every
    /// epoch, so there is nothing being refused.
    fun state(alpha: Float, beta: Float, epoch: Int): State {
        if (alpha == 0f && beta == 1f) return State.NONE
        return if (epoch == CURRENT_EPOCH) State.APPLIED else State.IGNORED_STALE
    }

    fun state(project: Project): State =
        state(project.dbhCorrectionAlpha, project.dbhCorrectionBeta, project.dbhCalibrationEpoch)
}
