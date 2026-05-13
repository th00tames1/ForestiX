package edu.oregonstate.forestrix

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import edu.oregonstate.forestrix.measurement.ConfidenceTier
import edu.oregonstate.forestrix.measurement.HeightEstimator
import edu.oregonstate.forestrix.measurement.HeightMeasureInput
import edu.oregonstate.forestrix.measurement.ImuPitchBuffer
import edu.oregonstate.forestrix.measurement.ProjectCalibration
import edu.oregonstate.forestrix.measurement.Vec3
import edu.oregonstate.forestrix.models.HeightMethod
import edu.oregonstate.forestrix.sensors.ArCoreCameraRenderer
import edu.oregonstate.forestrix.sensors.ArCoreSessionHelper
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

class HeightScanActivity : Activity(), GLSurfaceView.Renderer {
    private val logTag = "ForestiX.HeightScan"
    private val primary = Color.rgb(45, 95, 74)
    private val glass = Color.argb(214, 20, 28, 24)
    private val glassStroke = Color.argb(58, 255, 255, 255)

    private enum class Stage {
        ANCHOR,
        WALKING,
        AIM_TOP,
        AIM_BASE,
        RESULT
    }

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var overlay: HeightOverlayView
    private lateinit var statusText: TextView
    private lateinit var readoutText: TextView
    private lateinit var primaryButton: Button
    private lateinit var retakeButton: Button
    private lateinit var acceptButton: Button

    private val backgroundRenderer = ArCoreCameraRenderer()
    private lateinit var pitchBuffer: ImuPitchBuffer
    private var session: Session? = null
    private var installRequested = false
    private var viewportChanged = false
    private var viewportWidth = 1
    private var viewportHeight = 1

    @Volatile private var latestPose: Vec3? = null
    @Volatile private var latestCenterHit: Vec3? = null
    @Volatile private var trackingReady = false
    private var stage = Stage.ANCHOR
    private var anchorPoint: Vec3? = null
    private var standingAtTop: Vec3? = null
    private var alphaTopRad: Float? = null
    private var trackingStayedNormal = true
    private var latestHeightM: Float? = null
    private var latestConfidence: ConfidenceTier? = null
    private var latestMethod: HeightMethod = HeightMethod.ARCORE_VIO_WALKOFF_TANGENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pitchBuffer = ImuPitchBuffer(this)
        requestPermissionsIfNeeded()
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        surfaceView.onResume()
        startArSessionIfReady()
    }

    private fun startArSessionIfReady() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            statusText.text = "Camera permission is needed for height scanning. You can still use Manual."
            requestPermissionsIfNeeded()
            return
        }
        if (!ArCoreSessionHelper.requestInstallIfNeeded(this, !installRequested)) {
            installRequested = true
            statusText.text = "Finishing ARCore setup. Return here after install."
            return
        }
        if (session == null) {
            session = ArCoreSessionHelper.createSession(this, requireDepth = false)
        }
        val arSession = session
        if (arSession == null) {
            statusText.text = "${ArCoreSessionHelper.supportFailureSummary(this)}\nUse Manual height for this tree."
            pitchBuffer.start()
            return
        }
        try {
            arSession.resume()
        } catch (e: CameraNotAvailableException) {
            Log.e(logTag, "Camera was not available for height scan", e)
            Toast.makeText(this, "Camera is not available. Reopen the scan.", Toast.LENGTH_LONG).show()
            arSession.close()
            session = null
            statusText.text = "Camera is not available. Use Manual or try reopening the scan."
        } catch (e: SecurityException) {
            Log.e(logTag, "Camera permission missing for height scan", e)
            Toast.makeText(this, "Camera permission is needed for AR scanning.", Toast.LENGTH_LONG).show()
            arSession.close()
            session = null
            statusText.text = "Camera permission is needed. Use Manual or grant camera permission."
        } catch (e: RuntimeException) {
            Log.e(logTag, "ARCore failed to start height scan", e)
            Toast.makeText(this, "ARCore failed: ${e.javaClass.simpleName}. Use Manual.", Toast.LENGTH_LONG).show()
            arSession.close()
            session = null
            statusText.text = "ARCore failed: ${e.javaClass.simpleName}\n${ArCoreSessionHelper.supportFailureSummary(this)}\nUse Manual height for this tree."
        }
        pitchBuffer.start()
    }

    override fun onPause() {
        surfaceView.onPause()
        pitchBuffer.stop()
        session?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        backgroundRenderer.createOnGlThread()
        session?.setCameraTextureName(backgroundRenderer.textureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportChanged = true
        viewportWidth = width
        viewportHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val arSession = session ?: return
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (viewportChanged) {
            arSession.setDisplayGeometry(windowManager.defaultDisplay.rotation, viewportWidth, viewportHeight)
            viewportChanged = false
        }
        arSession.setCameraTextureName(backgroundRenderer.textureId)

        val frame = try {
            arSession.update()
        } catch (_: Throwable) {
            return
        }
        backgroundRenderer.draw(frame)
        updatePoseAndHit(frame)
    }

    private fun updatePoseAndHit(frame: Frame) {
        val camera = frame.camera
        trackingReady = camera.trackingState == TrackingState.TRACKING
        if (!trackingReady && stage != Stage.ANCHOR) trackingStayedNormal = false
        if (!trackingReady) {
            runOnUiThread {
                statusText.text = "Move slowly until AR tracking is ready."
            }
            return
        }

        val pose = camera.pose
        latestPose = Vec3(pose.tx(), pose.ty(), pose.tz())
        val hit = frame.hitTest(viewportWidth / 2f, viewportHeight / 2f)
            .firstOrNull { it.trackable.trackingState == TrackingState.TRACKING }
        latestCenterHit = hit?.hitPose?.let { Vec3(it.tx(), it.ty(), it.tz()) }

        if (stage == Stage.WALKING) {
            val anchor = anchorPoint
            val standing = latestPose
            if (anchor != null && standing != null) {
                val dh = horizontalDistance(anchor, standing)
                runOnUiThread {
                    readoutText.text = "Walked back ${dh.oneDecimal()} m"
                    statusText.text = if (dh < 3f) {
                        "Step back. Need at least 3 m."
                    } else {
                        "Walk back, then Continue when the tree top is visible."
                    }
                }
            }
        }
    }

    private fun anchorHere() {
        if (!trackingReady) {
            Toast.makeText(this, "AR tracking is not ready yet.", Toast.LENGTH_SHORT).show()
            return
        }
        val hit = latestCenterHit
        if (hit == null) {
            Toast.makeText(this, "Aim the crosshair at the trunk surface and try again.", Toast.LENGTH_LONG).show()
            return
        }
        anchorPoint = hit
        standingAtTop = null
        alphaTopRad = null
        latestHeightM = null
        latestConfidence = null
        trackingStayedNormal = true
        stage = Stage.WALKING
        overlay.stageLabel = "Walk back"
        statusText.text = "Anchor set. Walk back from the tree."
        readoutText.text = "Walked back 0.0 m"
        primaryButton.text = "Continue"
        acceptButton.isEnabled = false
        overlay.invalidate()
    }

    private fun continueToAimTop() {
        stage = Stage.AIM_TOP
        overlay.stageLabel = "Aim top"
        statusText.text = "Aim at the treetop, then tap Aim Top."
        readoutText.text = "Top angle not captured."
        primaryButton.text = "Aim Top"
        overlay.invalidate()
    }

    private fun aimTop() {
        val standing = latestPose
        if (standing == null) {
            Toast.makeText(this, "AR pose is not ready.", Toast.LENGTH_SHORT).show()
            return
        }
        val pitch = pitchBuffer.medianPitch()
        if (pitch == null) {
            Toast.makeText(this, "Motion sensor is not ready.", Toast.LENGTH_SHORT).show()
            return
        }
        standingAtTop = standing
        alphaTopRad = -pitch
        stage = Stage.AIM_BASE
        overlay.stageLabel = "Aim base"
        statusText.text = "Top captured. Aim at the tree base, then tap Aim Base."
        readoutText.text = "Top angle ${Math.toDegrees(alphaTopRad!!.toDouble()).oneDecimal()} deg"
        primaryButton.text = "Aim Base"
        overlay.invalidate()
    }

    private fun aimBaseAndCompute() {
        val anchor = anchorPoint
        val standing = standingAtTop
        val top = alphaTopRad
        val pitch = pitchBuffer.medianPitch()
        if (anchor == null || standing == null || top == null || pitch == null) {
            Toast.makeText(this, "Missing height inputs. Retake.", Toast.LENGTH_SHORT).show()
            return
        }
        val base = -pitch
        val result = HeightEstimator.estimate(
            HeightMeasureInput(
                anchorPointWorld = anchor,
                standingPointWorld = standing,
                alphaTopRad = top,
                alphaBaseRad = base,
                trackingStateWasNormalThroughout = trackingStayedNormal,
                projectCalibration = ProjectCalibration.Identity
            )
        )
        latestHeightM = if (result.confidence == ConfidenceTier.RED) null else result.heightM
        latestConfidence = result.confidence
        latestMethod = HeightMethod.ARCORE_VIO_WALKOFF_TANGENT
        stage = Stage.RESULT
        overlay.stageLabel = "Result"
        statusText.text = result.rejectionReason ?: "Height computed."
        readoutText.text = "Height ${result.heightM.oneDecimal()} m, sigma ${result.sigmaHm.oneDecimal()} m (${result.confidence.displayName})"
        primaryButton.text = "Retake"
        acceptButton.isEnabled = result.confidence != ConfidenceTier.RED
        overlay.invalidate()
    }

    private fun primaryAction() {
        when (stage) {
            Stage.ANCHOR -> anchorHere()
            Stage.WALKING -> continueToAimTop()
            Stage.AIM_TOP -> aimTop()
            Stage.AIM_BASE -> aimBaseAndCompute()
            Stage.RESULT -> retake()
        }
    }

    private fun retake() {
        stage = Stage.ANCHOR
        anchorPoint = null
        standingAtTop = null
        alphaTopRad = null
        latestHeightM = null
        latestConfidence = null
        latestMethod = HeightMethod.ARCORE_VIO_WALKOFF_TANGENT
        trackingStayedNormal = true
        overlay.stageLabel = "Anchor"
        statusText.text = "Aim at trunk surface near eye level, then Anchor Here."
        readoutText.text = "No height measurement yet."
        primaryButton.text = "Anchor Here"
        acceptButton.isEnabled = false
        overlay.invalidate()
    }

    private fun acceptResult() {
        val height = latestHeightM ?: return
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_HEIGHT_M, height)
                putExtra(EXTRA_CONFIDENCE, latestConfidence?.name ?: ConfidenceTier.YELLOW.name)
                putExtra(EXTRA_METHOD, latestMethod.name)
            }
        )
        finish()
    }

    private fun showManualDialog() {
        val input = EditText(this)
        input.hint = "Height m"
        AlertDialog.Builder(this)
            .setTitle("Manual height")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val m = input.text.toString().toFloatOrNull()
                if (m != null && m > 1.3f) {
                    latestHeightM = m
                    latestConfidence = ConfidenceTier.YELLOW
                    latestMethod = HeightMethod.MANUAL_ENTRY
                    readoutText.text = "Manual height ${m.oneDecimal()} m"
                    statusText.text = "Manual height ready."
                    stage = Stage.RESULT
                    primaryButton.text = "Retake"
                    acceptButton.isEnabled = true
                }
            }
            .show()
    }

    private fun requestPermissionsIfNeeded() {
        val needed = arrayOf(Manifest.permission.CAMERA).filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 78)
    }

    @Deprecated("Android framework callback")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 78 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            statusText.text = "Camera permission granted. Starting ARCore height scan..."
            startArSessionIfReady()
        } else if (requestCode == 78) {
            statusText.text = "Camera permission denied. Enter height with Manual."
        }
    }

    private fun buildUi() {
        surfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(this@HeightScanActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        overlay = HeightOverlayView(this)
        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            text = "Aim at trunk surface near eye level, then Anchor Here."
            background = rounded(glass, dp(14), glassStroke)
            setPadding(dp(14), dp(11), dp(14), dp(11))
        }
        readoutText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            text = "No height measurement yet."
            includeFontPadding = false
        }
        primaryButton = scanButton("Anchor Here", primaryStyle = true) { primaryAction() }
        retakeButton = scanButton("Retake") { retake() }
        acceptButton = scanButton("Accept", primaryStyle = true) { acceptResult() }.apply { isEnabled = false }
        val manualButton = scanButton("Manual") { showManualDialog() }
        val closeButton = scanButton("Close") { finish() }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(16))
            background = rounded(glass, dp(18), glassStroke)
            addView(readoutText, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(12))
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(primaryButton, scanButtonParams())
                addView(acceptButton, scanButtonParams())
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(8))
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(retakeButton, scanButtonParams())
                addView(manualButton, scanButtonParams())
                addView(closeButton, scanButtonParams())
            })
        }

        val root = FrameLayout(this)
        root.addView(surfaceView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(overlay, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(statusText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ).apply {
            setMargins(dp(16), dp(16), dp(16), 0)
        })
        root.addView(bottom, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ).apply {
            setMargins(dp(16), 0, dp(16), dp(18))
        })
        setContentView(root)
    }

    private fun scanButton(label: String, primaryStyle: Boolean = false, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setAllCaps(false)
            textSize = 13f
            typeface = if (primaryStyle) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setTextColor(Color.WHITE)
            background = rounded(
                if (primaryStyle) primary else Color.argb(40, 255, 255, 255),
                dp(10),
                if (primaryStyle) primary else glassStroke
            )
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(6), 0, dp(6), 0)
            setOnClickListener { onClick() }
        }

    private fun scanButtonParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            setMargins(dp(3), 0, dp(3), 0)
        }

    private fun rounded(color: Int, radius: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radius.toFloat()
            setColor(color)
            setStroke(if (stroke == Color.TRANSPARENT) 0 else 1, stroke)
        }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun horizontalDistance(a: Vec3, b: Vec3): Float {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return sqrt(dx * dx + dz * dz)
    }

    private fun Float.oneDecimal(): String = String.format("%.1f", this)
    private fun Double.oneDecimal(): String = String.format("%.1f", this)

    companion object {
        const val EXTRA_HEIGHT_M = "height_m"
        const val EXTRA_CONFIDENCE = "height_confidence"
        const val EXTRA_METHOD = "height_method"
    }
}

class HeightOverlayView(context: android.content.Context) : View(context) {
    var stageLabel: String = "Anchor"

    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.rgb(235, 190, 60)
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 9f
        color = Color.argb(160, 0, 0, 0)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, 44f, haloPaint)
        canvas.drawCircle(cx, cy, 40f, crossPaint)
        canvas.drawLine(cx - 22f, cy, cx + 22f, cy, crossPaint)
        canvas.drawLine(cx, cy - 22f, cx, cy + 22f, crossPaint)
        canvas.drawText(stageLabel, cx, cy + 86f, textPaint)
    }
}
