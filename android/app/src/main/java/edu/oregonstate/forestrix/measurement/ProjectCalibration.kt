package edu.oregonstate.forestrix.measurement

data class ProjectCalibration(
    val depthNoiseMm: Float = 5f,
    val dbhCorrectionAlpha: Float = 0f,
    val dbhCorrectionBeta: Float = 1f,
    val vioDriftFraction: Float = 0.02f,
    val depthDiscontinuityM: Float = 0.04f
) {
    companion object {
        val Identity = ProjectCalibration()
    }
}
