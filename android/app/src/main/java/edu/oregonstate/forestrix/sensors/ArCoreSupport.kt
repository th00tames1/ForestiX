package edu.oregonstate.forestrix.sensors

import android.content.Context
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session

data class ArCoreCapability(
    val availability: String,
    val isSupported: Boolean,
    val depthModeSupported: Boolean,
    val recommendation: String
)

object ArCoreSupport {
    fun probe(context: Context): ArCoreCapability {
        val availability = ArCoreApk.getInstance().checkAvailability(context)
        val supported = availability.isSupported
        var depthSupported = false
        var note = when {
            !supported -> "ARCore is not supported on this device. Manual DBH and tape-tangent height remain available."
            availability.isTransient -> "ARCore support is still being checked. Try again after Google Play Services responds."
            else -> "ARCore is available. Depth support will be checked by opening a lightweight session."
        }

        if (supported && !availability.isTransient) {
            var session: Session? = null
            try {
                session = Session(context)
                depthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                note = if (depthSupported) {
                    "ARCore Depth is supported. ForestiX can use Raw Depth for DBH chord scans."
                } else {
                    "ARCore works, but Depth is not supported. Use AR walk-off height and manual DBH."
                }
            } catch (t: Throwable) {
                note = "ARCore is present but could not open a session yet: ${t.javaClass.simpleName}."
            } finally {
                session?.close()
            }
        }

        return ArCoreCapability(
            availability = availability.name,
            isSupported = supported,
            depthModeSupported = depthSupported,
            recommendation = note
        )
    }
}
