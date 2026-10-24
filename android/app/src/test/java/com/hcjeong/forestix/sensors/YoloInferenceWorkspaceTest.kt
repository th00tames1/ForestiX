package com.hcjeong.forestix.sensors

import java.nio.ByteOrder
import java.nio.FloatBuffer
import org.junit.Assert.*
import org.junit.Test

class YoloInferenceWorkspaceTest {
    // Frozen pre-optimization rotation: no new helper is used by this oracle.
    private fun legacy(chw: FloatArray, turns: Int): FloatArray {
        val image = FloatArray(3 * 640 * 640)
        for (yy in 0 until 640) for (xx in 0 until 640) {
            var x = xx; var y = yy
            repeat(turns) { val t = x; x = 639 - y; y = t }
            for (c in 0..2) image[c * 409600 + y * 640 + x] = chw[c * 409600 + yy * 640 + xx]
        }
        return image
    }

    private fun bits(buffer: FloatBuffer): IntArray {
        val floats = FloatArray(buffer.remaining()); buffer.get(floats)
        return IntArray(floats.size) { floats[it].toRawBits() }
    }

    @Test fun allFourRotationsMatchOriginalEveryBit() {
        val workspace = YoloInferenceWorkspace()
        val source = FloatArray(3 * 640 * 640) { i ->
            when (i % 997) {
                0 -> -0.0f
                1 -> Float.fromBits(0x7fc01234)
                else -> ((i * 73 + 19) % 256) / 255f
            }
        }
        for (turns in 0..3) {
            val expected = legacy(source, turns)
            assertArrayEquals(IntArray(expected.size) { expected[it].toRawBits() }, bits(workspace.prepareInput(source, turns)))
        }
    }

    @Test fun directNativeInputIsReusedAndPositionIsReset() {
        val workspace = YoloInferenceWorkspace()
        val source = FloatArray(YoloInferenceWorkspace.INPUT_COUNT) { 0.25f }
        val first = workspace.prepareInput(source, 1)
        assertTrue(first.isDirect)
        assertEquals(ByteOrder.nativeOrder(), first.order())
        first.limit(10); first.position(7)
        val next = workspace.prepareInput(source, 0)
        assertSame(first, next)
        assertEquals(0, next.position())
        assertEquals(source.size, next.remaining())
    }

    @Test fun nextFrameFullyReplacesOldInputAndNeverAliasesCameraArray() {
        val workspace = YoloInferenceWorkspace()
        val source = FloatArray(YoloInferenceWorkspace.INPUT_COUNT) { 1f }
        workspace.prepareInput(source, 3)
        source.fill(0f)
        val buffer = workspace.prepareInput(source, 2)
        source.fill(1f)
        assertArrayEquals(IntArray(source.size), bits(buffer))
    }

    private fun outputs(): Pair<FloatArray, FloatArray> {
        val head = FloatArray(37 * 8400)
        head[0] = 320f; head[8400] = 320f; head[2 * 8400] = 160f
        head[3 * 8400] = 640f; head[4 * 8400] = .8f; head[5 * 8400] = 1f
        val prototypes = FloatArray(32 * 25600)
        for (i in 0 until 25600) prototypes[i] = if (i % 160 < 80) -1f else 1f
        return head to prototypes
    }

    @Test fun reusedOutputsMatchOriginalDecoderAndOldMaskRemainsIndependent() {
        val workspace = YoloInferenceWorkspace()
        val (head, prototypes) = outputs()
        val expected = requireNotNull(YoloAlignmentMask.select(head, prototypes, 640, 640))
        val actual = requireNotNull(workspace.decodeMask(FloatBuffer.wrap(head), FloatBuffer.wrap(prototypes), 640, 640))
        val before = BooleanArray(640) { actual.contains(it, 320) }
        assertArrayEquals(BooleanArray(640) { expected.contains(it, 320) }, before)
        assertEquals(expected.score, actual.score, 0f)
        for (i in 0 until 25600) prototypes[i] = -prototypes[i]
        workspace.decodeMask(FloatBuffer.wrap(head), FloatBuffer.wrap(prototypes), 640, 640)
        assertArrayEquals(before, BooleanArray(640) { actual.contains(it, 320) })
    }

    @Test fun malformedAndNoTargetOutputsDoNotReusePreviousDetection() {
        val workspace = YoloInferenceWorkspace()
        val (head, prototypes) = outputs()
        assertNotNull(workspace.decodeMask(FloatBuffer.wrap(head), FloatBuffer.wrap(prototypes), 640, 640))
        assertNull(workspace.decodeMask(FloatBuffer.allocate(0), FloatBuffer.wrap(prototypes), 640, 640))
        assertNull(workspace.decodeMask(FloatBuffer.wrap(FloatArray(head.size)), FloatBuffer.wrap(prototypes), 640, 640))
        assertNotNull(workspace.decodeMask(FloatBuffer.wrap(head), FloatBuffer.wrap(prototypes), 640, 640))
    }
}
