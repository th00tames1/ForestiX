// External RTK receiver — 1:1 port of iOS
// Positioning/ExternalRTKReceiver.swift (v1.5 §7.3 strategy D,
// REQ-CTR-004).
//
// v0.4 scope: interface surface + a non-available stub so screen /
// evaluator code can reference externalRTK as a strategy source today
// without platform-gated imports. Real NTRIP / Bluetooth / serial RTK
// integration ships in v1.5 when we have hardware to test against.

package com.hcjeong.forestix.positioning

import com.hcjeong.forestix.data.cruise.PlotCenterResult

/// A source of high-accuracy RTK/RTK-float fixes. Implementations
/// stream `CLLocationSnapshot`-equivalent fixes with sub-metre
/// accuracy. The evaluator treats these as highest-priority when
/// available (see `PositionTierEvaluator.sourcePriority`).
interface ExternalRTKReceiver {
    /// True when the receiver is connected AND reporting RTK-fixed
    /// (not RTK-float or standalone GPS) fixes. The UI should only
    /// show the "external RTK" tier when this is true.
    val isAvailable: Boolean

    /// Latest fix, if one has arrived within the staleness budget.
    /// Returns null when the receiver is disconnected or all cached
    /// fixes are older than `maxAgeS`.
    suspend fun latestFix(maxAgeS: Double): PlotCenterResult?
}

/// v0.4 placeholder: always unavailable, never returns a fix.
/// Wired into the positioning pipeline so the evaluator can ignore
/// RTK entirely until v1.5 replaces this with a real adapter.
class UnavailableRTKReceiver : ExternalRTKReceiver {
    override val isAvailable: Boolean get() = false
    override suspend fun latestFix(maxAgeS: Double): PlotCenterResult? = null
}
