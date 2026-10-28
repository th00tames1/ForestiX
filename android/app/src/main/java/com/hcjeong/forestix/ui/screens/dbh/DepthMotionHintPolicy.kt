package com.hcjeong.forestix.ui.screens.dbh

/** One-way latch for this scan screen, not a claim of absolute distance accuracy. */
internal fun hasAcquiredInitialDepth(
    alreadyAcquired: Boolean, active: Boolean, locked: Boolean, depthSilent: Boolean,
): Boolean = alreadyAcquired || (active && locked && !depthSilent)

/** Acquisition help only: a lock is not proof of absolute distance accuracy. */
internal fun shouldShowDepthMotionHint(
    active: Boolean,
    stalled: Boolean,
    depthSilent: Boolean,
    locked: Boolean,
    specificError: Boolean,
    failure: Boolean,
    initialDepthAcquired: Boolean = false,
): Boolean = !initialDepthAcquired && active && stalled && !failure &&
    (depthSilent || (!locked && !specificError))
