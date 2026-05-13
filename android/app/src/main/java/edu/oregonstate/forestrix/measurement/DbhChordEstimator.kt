package edu.oregonstate.forestrix.measurement

import edu.oregonstate.forestrix.models.DbhMethod
import edu.oregonstate.forestrix.sensors.DepthFrame
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

sealed interface GuideAxis {
    data class Row(val y: Int) : GuideAxis
    data class Column(val x: Int) : GuideAxis
}

data class DbhPreviewFit(
    val guideAxis: GuideAxis,
    val diameterCm: Double,
    val stripLeftFraction: Double,
    val stripRightFraction: Double,
    val confidence: ConfidenceTier,
    val usableRows: Int,
    val medianPixelWidth: Int,
    val effectiveTapDepthM: Double
)

object DbhChordEstimator {
    private const val MinConfidence = 32

    fun estimate(
        frames: List<DepthFrame>,
        tapX: Double,
        tapY: Double,
        guideAxis: GuideAxis,
        calibration: ProjectCalibration = ProjectCalibration.Identity
    ): DbhResult? {
        if (frames.size < 5) return null

        val fits = frames.mapNotNull {
            previewFit(
                frame = it,
                tapX = tapX,
                tapY = tapY,
                guideAxis = guideAxis,
                discontinuityThresholdM = calibration.depthDiscontinuityM
            )
        }
        if (fits.size < 3) {
            return DbhResult(
                diameterCm = 0f,
                confidence = ConfidenceTier.RED,
                method = DbhMethod.RAW_DEPTH_CHORD_SILHOUETTE,
                rejectionReason = "Not enough usable depth frames; hold steadier or move closer"
            )
        }

        val sortedDiameters = fits.map { it.diameterCm }.sorted()
        val medianRawCm = sortedDiameters[sortedDiameters.size / 2]
        val mean = sortedDiameters.average()
        val spreadRatio = if (mean > 0.0) {
            (sortedDiameters.last() - sortedDiameters.first()) / mean
        } else {
            1.0
        }
        val tier = if (spreadRatio <= 0.15) ConfidenceTier.GREEN else ConfidenceTier.YELLOW
        val correctedCm = calibration.dbhCorrectionAlpha + calibration.dbhCorrectionBeta * medianRawCm

        return DbhResult(
            diameterCm = correctedCm.toFloat(),
            nInliers = fits.sumOf { it.medianPixelWidth },
            confidence = tier,
            method = DbhMethod.RAW_DEPTH_CHORD_SILHOUETTE
        )
    }

    fun previewFit(
        frame: DepthFrame,
        tapX: Double,
        tapY: Double,
        guideAxis: GuideAxis,
        rowSpan: Int = 10,
        silhouetteJumpM: Float = 0.30f,
        discontinuityThresholdM: Float = 0.30f
    ): DbhPreviewFit? {
        val dTapMm = medianDepthAround(frame, tapX, tapY, radius = 2) ?: return null
        val dTapM = dTapMm / 1000.0
        if (dTapM !in 0.3..5.0) return null

        val centerAlong = when (guideAxis) {
            is GuideAxis.Row -> tapX.toIntRounded()
            is GuideAxis.Column -> tapY.toIntRounded()
        }

        val widths = mutableListOf<Int>()
        var firstExtent: IntRange? = null
        for (offset in -rowSpan..rowSpan) {
            val neighborAxis = when (guideAxis) {
                is GuideAxis.Row -> GuideAxis.Row(guideAxis.y + offset)
                is GuideAxis.Column -> GuideAxis.Column(guideAxis.x + offset)
            }
            val strip = extractChordSilhouetteStrip(
                frame = frame,
                axis = neighborAxis,
                tapAlongAxis = centerAlong,
                silhouetteJumpM = silhouetteJumpM.coerceAtLeast(discontinuityThresholdM)
            )
            val left = strip.firstOrNull() ?: continue
            val right = strip.lastOrNull() ?: continue
            if (right <= left) continue
            val width = right - left + 1
            if (width < 5) continue
            widths += width
            if (firstExtent == null) firstExtent = left..right
        }

        if (widths.size < 5) return null
        val extent = firstExtent ?: return null
        val sortedWidths = widths.sorted()
        val medianWidth = sortedWidths[sortedWidths.size / 2]
        val focal = focalForAxis(frame, guideAxis).toDouble()
        val diameterM = cylinderDiameterFromChord(
            pixelWidth = medianWidth.toDouble(),
            surfaceDepthM = dTapM,
            focalPx = focal
        )
        if (diameterM.isNaN() || diameterM.isInfinite()) return null
        val diameterCm = diameterM * 100.0
        if (diameterCm !in 2.5..200.0) return null

        val mean = widths.average()
        val std = sqrt(widths.sumOf { (it - mean) * (it - mean) } / widths.size)
        val cov = if (mean > 0.0) std / mean else 1.0
        val tier = if (cov <= 0.10) ConfidenceTier.GREEN else ConfidenceTier.YELLOW

        val frameExtent = when (guideAxis) {
            is GuideAxis.Row -> frame.width
            is GuideAxis.Column -> frame.height
        }.toDouble()

        return DbhPreviewFit(
            guideAxis = guideAxis,
            diameterCm = diameterCm,
            stripLeftFraction = extent.first / frameExtent,
            stripRightFraction = extent.last / frameExtent,
            confidence = tier,
            usableRows = widths.size,
            medianPixelWidth = medianWidth,
            effectiveTapDepthM = dTapM
        )
    }

    fun medianDepthAround(frame: DepthFrame, tapX: Double, tapY: Double, radius: Int): Int? {
        val cx = tapX.toIntRounded()
        val cy = tapY.toIntRounded()
        if (cx !in 0 until frame.width || cy !in 0 until frame.height) return null

        val values = mutableListOf<Int>()
        for (y in (cy - radius)..(cy + radius)) {
            if (y !in 0 until frame.height) continue
            for (x in (cx - radius)..(cx + radius)) {
                if (x !in 0 until frame.width) continue
                val d = frame.depthMmAt(x, y)
                if (d > 0) values += d
            }
        }
        if (values.isEmpty()) return null
        values.sort()
        return values[values.size / 2]
    }

    fun extractChordSilhouetteStrip(
        frame: DepthFrame,
        axis: GuideAxis,
        tapAlongAxis: Int,
        silhouetteJumpM: Float = 0.30f
    ): List<Int> {
        val walkLength = when (axis) {
            is GuideAxis.Row -> {
                if (axis.y !in 0 until frame.height) return emptyList()
                frame.width
            }
            is GuideAxis.Column -> {
                if (axis.x !in 0 until frame.width) return emptyList()
                frame.height
            }
        }
        val seedCandidate = tapAlongAxis.coerceIn(0, walkLength - 1)

        fun depthAt(idx: Int): Int {
            val (x, y) = pixelCoords(axis, idx)
            return frame.depthMmAt(x, y)
        }

        fun isValid(idx: Int): Boolean {
            val (x, y) = pixelCoords(axis, idx)
            return frame.confidenceAt(x, y) >= MinConfidence && frame.depthMmAt(x, y) > 0
        }

        var seed = seedCandidate
        if (!isValid(seed)) {
            var found: Int? = null
            for (offset in 1..10) {
                val left = seedCandidate - offset
                if (left >= 0 && isValid(left)) {
                    found = left
                    break
                }
                val right = seedCandidate + offset
                if (right < walkLength && isValid(right)) {
                    found = right
                    break
                }
            }
            seed = found ?: return emptyList()
        }

        val accepted = mutableListOf(seed)
        var lastDepth = depthAt(seed)
        var i = seed - 1
        while (i >= 0 && isValid(i)) {
            val d = depthAt(i)
            if (abs(d - lastDepth) / 1000f > silhouetteJumpM) break
            accepted += i
            lastDepth = d
            i--
        }

        lastDepth = depthAt(seed)
        i = seed + 1
        while (i < walkLength && isValid(i)) {
            val d = depthAt(i)
            if (abs(d - lastDepth) / 1000f > silhouetteJumpM) break
            accepted += i
            lastDepth = d
            i++
        }

        accepted.sort()
        return accepted
    }

    private fun focalForAxis(frame: DepthFrame, axis: GuideAxis): Float =
        when (axis) {
            is GuideAxis.Row -> frame.fx
            is GuideAxis.Column -> frame.fy
        }

    internal fun cylinderDiameterFromChord(
        pixelWidth: Double,
        surfaceDepthM: Double,
        focalPx: Double
    ): Double {
        val halfWidth = pixelWidth / 2.0
        if (pixelWidth <= 0.0 || surfaceDepthM <= 0.0 || focalPx <= 0.0) return Double.NaN
        return (2.0 * surfaceDepthM * halfWidth * (halfWidth + sqrt(focalPx * focalPx + halfWidth * halfWidth))) /
            (focalPx * focalPx)
    }

    private fun pixelCoords(axis: GuideAxis, idx: Int): Pair<Int, Int> =
        when (axis) {
            is GuideAxis.Row -> idx to axis.y
            is GuideAxis.Column -> axis.x to idx
        }

    private fun Double.toIntRounded(): Int = roundToInt()
}
