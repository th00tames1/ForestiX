package com.hcjeong.forestix.ui.screens.dbh

/** Acquisition help only: a lock is not proof of absolute distance accuracy. */
internal fun shouldShowDepthMotionHint(
    active: Boolean,
    stalled: Boolean,
    depthSilent: Boolean,
    locked: Boolean,
    specificError: Boolean,
    failure: Boolean,
): Boolean = active && stalled && !failure &&
    (depthSilent || (!locked && !specificError))
