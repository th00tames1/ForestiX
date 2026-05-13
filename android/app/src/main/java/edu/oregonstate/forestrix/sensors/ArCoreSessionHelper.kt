package edu.oregonstate.forestrix.sensors

import android.app.Activity
import android.content.Context
import android.util.Log
import android.widget.Toast
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnavailableException

object ArCoreSessionHelper {
    private const val TAG = "ForestiX.ARCore"

    fun requestInstallIfNeeded(activity: Activity, userRequestedInstall: Boolean): Boolean {
        return try {
            when (ArCoreApk.getInstance().requestInstall(activity, userRequestedInstall)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> false
                ArCoreApk.InstallStatus.INSTALLED -> true
            }
        } catch (e: UnavailableException) {
            Log.e(TAG, "ARCore install check failed", e)
            Toast.makeText(activity, installFailureMessage(e), Toast.LENGTH_LONG).show()
            true
        } catch (e: RuntimeException) {
            Log.e(TAG, "ARCore install check crashed", e)
            Toast.makeText(activity, "ARCore check failed: ${e.javaClass.simpleName}. Use Manual.", Toast.LENGTH_LONG).show()
            true
        }
    }

    fun createSession(context: Context, requireDepth: Boolean): Session? {
        return try {
            val session = Session(context)
            val config = session.config
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            config.focusMode = Config.FocusMode.AUTO
            if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                config.depthMode = Config.DepthMode.AUTOMATIC
            } else if (requireDepth) {
                Toast.makeText(context, "ARCore Depth is not supported on this device.", Toast.LENGTH_LONG).show()
            }
            session.configure(config)
            session
        } catch (e: SecurityException) {
            Log.e(TAG, "ARCore session blocked by permission", e)
            Toast.makeText(context, "Camera permission is needed for AR scanning.", Toast.LENGTH_LONG).show()
            null
        } catch (e: UnavailableException) {
            Log.e(TAG, "ARCore session unavailable", e)
            Toast.makeText(context, installFailureMessage(e), Toast.LENGTH_LONG).show()
            null
        } catch (e: RuntimeException) {
            Log.e(TAG, "ARCore session failed", e)
            Toast.makeText(context, "ARCore session failed: ${e.javaClass.simpleName}. Use Manual.", Toast.LENGTH_LONG).show()
            null
        }
    }

    fun supportFailureSummary(context: Context): String {
        val availability = ArCoreApk.getInstance().checkAvailability(context)
        return "ARCore availability: $availability"
    }

    fun installFailureMessage(error: Throwable): String =
        when (error.javaClass.simpleName) {
            "UnavailableDeviceNotCompatibleException" ->
                "This device is not certified for ARCore. Use Manual."
            "UnavailableUserDeclinedInstallationException" ->
                "Google Play Services for AR was not installed. Install/update it, or use Manual."
            "UnavailableApkTooOldException" ->
                "Google Play Services for AR is too old. Update it in Play Store."
            "UnavailableSdkTooOldException" ->
                "This app's ARCore SDK is too old for the installed AR service."
            "UnavailableArcoreNotInstalledException" ->
                "Google Play Services for AR is not installed. Install it from Play Store."
            else ->
                "ARCore unavailable: ${error.javaClass.simpleName}. Use Manual."
        }
}
