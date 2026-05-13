package edu.oregonstate.forestrix

import edu.oregonstate.forestrix.measurement.ConfidenceTier
import edu.oregonstate.forestrix.measurement.DbhChordEstimator
import edu.oregonstate.forestrix.measurement.GuideAxis
import edu.oregonstate.forestrix.sensors.ArCoreDepthBridge
import edu.oregonstate.forestrix.sensors.DepthFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

class DbhChordEstimatorTest {
    @Test
    fun chordEstimatorRecoversSyntheticStemDiameter() {
        val frames = syntheticDepthBurst(
            width = 320,
            height = 240,
            trunkDiameterCm = 42.0,
            depthM = 2.0,
            fx = 280f
        )

        val result = DbhChordEstimator.estimate(
            frames = frames,
            tapX = 160.0,
            tapY = 120.0,
            guideAxis = GuideAxis.Row(120)
        )

        assertNotNull(result)
        requireNotNull(result)
        assertTrue(result.diameterCm in 39.0f..45.5f)
        assertTrue(result.confidence == ConfidenceTier.GREEN || result.confidence == ConfidenceTier.YELLOW)
    }

    @Test
    fun arcoreIntrinsicsAreScaledToRawDepthResolution() {
        val scaled = ArCoreDepthBridge.scaleIntrinsicsToDepthMap(
            focal = floatArrayOf(2400f, 2400f),
            principal = floatArrayOf(960f, 720f),
            imageDimensions = intArrayOf(1920, 1440),
            depthWidth = 160,
            depthHeight = 120
        )

        assertEquals(200f, scaled.fx, 0.001f)
        assertEquals(200f, scaled.fy, 0.001f)
        assertEquals(80f, scaled.cx, 0.001f)
        assertEquals(60f, scaled.cy, 0.001f)
    }

    @Test
    fun sharedGoldenDbhCasesMatchAndroidEstimator() {
        val cases = loadSharedDbhCases()
        assertTrue("Shared DBH fixture must contain cases", cases.isNotEmpty())

        for (case in cases) {
            val frame = rayTracedCylinderFrame(
                width = case.width,
                height = case.height,
                radiusM = case.radiusM,
                axisDistanceM = case.axisDistanceM,
                fx = case.focalPx.toFloat()
            )
            val fit = DbhChordEstimator.previewFit(
                frame = frame,
                tapX = frame.width / 2.0,
                tapY = frame.height / 2.0,
                guideAxis = GuideAxis.Row(frame.height / 2)
            )

            assertNotNull("Shared DBH case ${case.id} did not fit", fit)
            requireNotNull(fit)
            assertEquals(case.id, case.expectedDbhCm, fit.diameterCm, case.toleranceCm)
        }
    }
    @Test
    fun chordEstimatorRecoversRayTracedCylinderDiameterAcrossSizes() {
        val cases = listOf(
            0.05 to 1.0,
            0.10 to 1.5,
            0.20 to 1.5,
            0.30 to 2.0,
            0.40 to 2.5
        )

        for ((radiusM, axisDistanceM) in cases) {
            val frame = rayTracedCylinderFrame(
                width = 320,
                height = 240,
                radiusM = radiusM,
                axisDistanceM = axisDistanceM,
                fx = 260f
            )

            val fit = DbhChordEstimator.previewFit(
                frame = frame,
                tapX = frame.width / 2.0,
                tapY = frame.height / 2.0,
                guideAxis = GuideAxis.Row(frame.height / 2)
            )

            assertNotNull("Expected fit for radius=$radiusM distance=$axisDistanceM", fit)
            requireNotNull(fit)
            val expectedCm = radiusM * 200.0
            assertEquals(expectedCm, fit.diameterCm, expectedCm * 0.07)
        }
    }

    private data class SharedDbhCase(
        val id: String,
        val radiusM: Double,
        val axisDistanceM: Double,
        val focalPx: Double,
        val width: Int,
        val height: Int,
        val expectedDbhCm: Double,
        val toleranceCm: Double
    )

    private fun loadSharedDbhCases(): List<SharedDbhCase> {
        val fixture = sharedDbhFixtureFile()
        return fixture.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("id,") }
            .map { line ->
                val parts = line.split(',')
                require(parts.size == 8) { "Invalid shared DBH fixture row: $line" }
                SharedDbhCase(
                    id = parts[0],
                    radiusM = parts[1].toDouble(),
                    axisDistanceM = parts[2].toDouble(),
                    focalPx = parts[3].toDouble(),
                    width = parts[4].toInt(),
                    height = parts[5].toInt(),
                    expectedDbhCm = parts[6].toDouble(),
                    toleranceCm = parts[7].toDouble()
                )
            }
    }

    private fun sharedDbhFixtureFile(): File {
        val relative = File("fixtures/dbh_golden_cases.csv")
        val envRoot = System.getenv("FORESTIX_SHARED_DIR")?.takeIf { it.isNotBlank() }?.let { File(it, relative.path) }
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOfNotNull(
            envRoot,
            File(cwd, "../shared/${relative.path}"),
            File(cwd, "shared/${relative.path}"),
            cwd.parentFile?.let { File(it, "shared/${relative.path}") },
            cwd.parentFile?.parentFile?.let { File(it, "shared/${relative.path}") }
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Shared DBH fixture not found. Set FORESTIX_SHARED_DIR or keep shared/ in the monorepo root.")
    }

    private fun rayTracedCylinderFrame(
        width: Int,
        height: Int,
        radiusM: Double,
        axisDistanceM: Double,
        fx: Float
    ): DepthFrame {
        val depth = IntArray(width * height)
        val confidence = ByteArray(width * height)
        val cx = width / 2.0
        val cy = height / 2.0
        for (x in 0 until width) {
            val u = (x - cx) / fx
            val disc = radiusM * radiusM * (1.0 + u * u) - u * u * axisDistanceM * axisDistanceM
            if (disc < 0.0) continue
            val z = (axisDistanceM - sqrt(disc)) / (1.0 + u * u)
            if (z <= 0.0) continue
            for (y in 0 until height) {
                val idx = y * width + x
                depth[idx] = (z * 1000.0).toInt()
                confidence[idx] = 255.toByte()
            }
        }
        return DepthFrame(
            width = width,
            height = height,
            depthMm = depth,
            confidence = confidence,
            fx = fx,
            fy = fx,
            cx = cx.toFloat(),
            cy = cy.toFloat(),
            timestampNanos = 0
        )
    }

    private fun syntheticDepthBurst(
        width: Int,
        height: Int,
        trunkDiameterCm: Double,
        depthM: Double,
        fx: Float
    ): List<DepthFrame> {
        val diameterM = trunkDiameterCm / 100.0
        val pixelWidth = (diameterM * fx / (depthM + diameterM / 2.0)).toInt().coerceAtLeast(6)
        val centerX = width / 2
        val centerY = height / 2
        val left = centerX - pixelWidth / 2
        val right = centerX + pixelWidth / 2
        return (0 until 8).map { frameIndex ->
            val depth = IntArray(width * height)
            val confidence = ByteArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val idx = y * width + x
                    val onStem = x in left..right && y in (centerY - 22)..(centerY + 22)
                    if (onStem) {
                        depth[idx] = (depthM * 1000).toInt() + frameIndex
                        confidence[idx] = 255.toByte()
                    }
                }
            }
            DepthFrame(
                width = width,
                height = height,
                depthMm = depth,
                confidence = confidence,
                fx = fx,
                fy = fx,
                cx = centerX.toFloat(),
                cy = centerY.toFloat(),
                timestampNanos = frameIndex.toLong()
            )
        }
    }
}
