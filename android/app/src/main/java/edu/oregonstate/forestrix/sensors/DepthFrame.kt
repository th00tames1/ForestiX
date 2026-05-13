package edu.oregonstate.forestrix.sensors

data class DepthFrame(
    val width: Int,
    val height: Int,
    val depthMm: IntArray,
    val confidence: ByteArray,
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val timestampNanos: Long
) {
    init {
        require(depthMm.size == width * height)
        require(confidence.size == width * height)
    }

    fun depthMmAt(x: Int, y: Int): Int = depthMm[y * width + x]

    fun confidenceAt(x: Int, y: Int): Int = confidence[y * width + x].toInt() and 0xff
}
