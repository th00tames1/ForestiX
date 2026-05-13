package edu.oregonstate.forestrix.measurement

data class MeasurementStrategy(
    val primaryDbh: String,
    val fallbackDbh: String,
    val primaryHeight: String,
    val plotCenter: String,
    val boundary: String
)

object AndroidMeasurementStrategy {
    val ForestReady = MeasurementStrategy(
        primaryDbh = "ARCore Raw Depth chord/silhouette scan when Depth API is supported.",
        fallbackDbh = "Manual caliper or visual entry when depth confidence is poor.",
        primaryHeight = "ARCore VIO walk-off tangent with tape-distance fallback.",
        plotCenter = "GPS averaging first; offset-from-opening when canopy GPS is weak.",
        boundary = "AR ring when tracking is normal; numeric in/out/borderline calls always available."
    )
}
