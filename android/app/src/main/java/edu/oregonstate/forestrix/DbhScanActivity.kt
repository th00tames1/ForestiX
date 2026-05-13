package edu.oregonstate.forestrix

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
import com.google.ar.core.Camera
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import edu.oregonstate.forestrix.measurement.ConfidenceTier
import edu.oregonstate.forestrix.measurement.DbhChordEstimator
import edu.oregonstate.forestrix.measurement.DbhPreviewFit
import edu.oregonstate.forestrix.measurement.GuideAxis
import edu.oregonstate.forestrix.measurement.ProjectCalibration
import edu.oregonstate.forestrix.models.DbhMethod
import edu.oregonstate.forestrix.sensors.ArCoreCameraRenderer
import edu.oregonstate.forestrix.sensors.ArCoreDepthBridge
import edu.oregonstate.forestrix.sensors.ArCoreSessionHelper
import edu.oregonstate.forestrix.sensors.DepthFrame
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class DbhScanActivity : Activity(), GLSurfaceView.Renderer {
    private val logTag = "ForestiX.DBHScan"
    private val primary = Color.rgb(45, 95, 74)
    private val glass = Color.argb(214, 20, 28, 24)
    private val glassStroke = Color.argb(58, 255, 255, 255)

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var overlay: DbhOverlayView
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var captureButton: Button
    private lateinit var acceptButton: Button

    private val backgroundRenderer = ArCoreCameraRenderer()
    private var session: Session? = null
    private var installRequested = false
    private var viewportChanged = false
    private var viewportWidth = 1
    private var viewportHeight = 1

    @Volatile private var isCapturing = false
    private val burstFrames = mutableListOf<DepthFrame>()
    private var latestResultDiameterCm: Float? = null
    private var latestResultConfidence: ConfidenceTier? = null
    private var latestResultMethod: DbhMethod = DbhMethod.RAW_DEPTH_CHORD_SILHOUETTE
    private var lastPreviewAtNanos = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestCameraPermission()
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        surfaceView.onResume()
        startArSessionIfReady()
    }

    private fun startArSessionIfReady() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            statusText.text = "Camera permission is needed for DBH scanning. You can still use Manual."
            requestCameraPermission()
            return
        }
        if (!ArCoreSessionHelper.requestInstallIfNeeded(this, !installRequested)) {
            installRequested = true
            statusText.text = "Finishing ARCore setup. Return here after install."
            return
        }
        if (session == null) {
            session = ArCoreSessionHelper.createSession(this, requireDepth = true)
        }
        val arSession = session
        if (arSession == null) {
            statusText.text = "${ArCoreSessionHelper.supportFailureSummary(this)}\nUse Manual DBH for this tree."
            return
        }
        try {
            arSession.resume()
        } catch (e: CameraNotAvailableException) {
            Log.e(logTag, "Camera was not available for DBH scan", e)
            Toast.makeText(this, "Camera is not available. Reopen the scan.", Toast.LENGTH_LONG).show()
            arSession.close()
            session = null
            statusText.text = "Camera is not available. Use Manual or try reopening the scan."
        } catch (e: SecurityException) {
            Log.e(logTag, "Camera permission missing for DBH scan", e)
            Toast.makeText(this, "Camera permission is needed for AR scanning.", Toast.LENGTH_LONG).show()
            arSession.close()
            session = null
            statusText.text = "Camera permission is needed. Use Manual or grant camera permission."
        } catch (e: RuntimeException) {
            Log.e(logTag, "ARCore failed to start DBH scan", e)
            Toast.makeText(this, "ARCore failed: ${e.javaClass.simpleName}. Use Manual.", Toast.LENGTH_LONG).show()
            arSession.close()
            session = null
            statusText.text = "ARCore failed: ${e.javaClass.simpleName}\n${ArCoreSessionHelper.supportFailureSummary(this)}\nUse Manual DBH for this tree."
        }
    }

    override fun onPause() {
        surfaceView.onPause()
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

        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) {
            runOnUiThread {
                statusText.text = "Move slowly until AR tracking is ready."
                overlay.preview = null
                overlay.stable = false
                overlay.invalidate()
            }
            return
        }

        val depthFrame = ArCoreDepthBridge.acquireRawDepthFrame(frame) ?: run {
            runOnUiThread {
                statusText.text = "Waiting for ARCore Raw Depth. If this never appears, use Manual."
                overlay.preview = null
                overlay.stable = false
                overlay.invalidate()
            }
            return
        }
        handleDepthFrame(depthFrame, camera)
    }

    private fun handleDepthFrame(depthFrame: DepthFrame, camera: Camera) {
        val axis = guideAxis(depthFrame)
        val tapX = depthFrame.width / 2.0
        val tapY = depthFrame.height / 2.0

        if (isCapturing) {
            burstFrames += depthFrame
            runOnUiThread {
                statusText.text = "Capturing depth burst ${burstFrames.size}/12. Hold steady."
            }
            if (burstFrames.size >= 12) {
                val result = DbhChordEstimator.estimate(
                    frames = burstFrames.toList(),
                    tapX = tapX,
                    tapY = tapY,
                    guideAxis = axis,
                    calibration = ProjectCalibration.Identity
                )
                burstFrames.clear()
                isCapturing = false
                runOnUiThread {
                    if (result == null || result.confidence == ConfidenceTier.RED) {
                        latestResultDiameterCm = null
                        latestResultConfidence = ConfidenceTier.RED
                        resultText.text = result?.rejectionReason ?: "No DBH fit. Retake or enter manually."
                        acceptButton.isEnabled = false
                    } else {
                        latestResultDiameterCm = result.diameterCm
                        latestResultConfidence = result.confidence
                        latestResultMethod = DbhMethod.RAW_DEPTH_CHORD_SILHOUETTE
                        resultText.text = "DBH ${result.diameterCm.oneDecimal()} cm (${result.confidence.displayName})"
                        acceptButton.isEnabled = true
                    }
                    captureButton.isEnabled = true
                    captureButton.text = "Retake"
                }
            }
            return
        }

        val now = System.nanoTime()
        if (now - lastPreviewAtNanos < 100_000_000L) return
        lastPreviewAtNanos = now
        val preview = DbhChordEstimator.previewFit(
            frame = depthFrame,
            tapX = tapX,
            tapY = tapY,
            guideAxis = axis
        )
        runOnUiThread {
            overlay.guideAxis = axis
            overlay.preview = preview
            overlay.stable = preview != null
            overlay.invalidate()
            if (preview == null) {
                statusText.text = "Align guide line with DBH, center crosshair on trunk."
            } else {
                statusText.text = "Trunk locked. Green sleeve should wrap the stem. Tap Capture."
                resultText.text = "Live DBH ${preview.diameterCm.oneDecimal()} cm, distance ${preview.effectiveTapDepthM.oneDecimal()} m"
            }
        }
    }

    private fun startCapture() {
        if (isCapturing) return
        latestResultDiameterCm = null
        latestResultConfidence = null
        latestResultMethod = DbhMethod.RAW_DEPTH_CHORD_SILHOUETTE
        burstFrames.clear()
        isCapturing = true
        captureButton.isEnabled = false
        acceptButton.isEnabled = false
        captureButton.text = "Capturing"
        statusText.text = "Capturing. Hold steady."
    }

    private fun acceptResult() {
        val cm = latestResultDiameterCm ?: return
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_DIAMETER_CM, cm)
                putExtra(EXTRA_CONFIDENCE, latestResultConfidence?.name ?: ConfidenceTier.YELLOW.name)
                putExtra(EXTRA_METHOD, latestResultMethod.name)
            }
        )
        finish()
    }

    private fun showManualDialog() {
        val input = EditText(this)
        input.hint = "Diameter cm"
        AlertDialog.Builder(this)
            .setTitle("Manual DBH")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val cm = input.text.toString().toFloatOrNull()
                if (cm != null && cm > 0f) {
                    latestResultDiameterCm = cm
                    latestResultConfidence = ConfidenceTier.YELLOW
                    latestResultMethod = DbhMethod.MANUAL_CALIPER
                    resultText.text = "Manual DBH ${cm.oneDecimal()} cm"
                    acceptButton.isEnabled = true
                }
            }
            .show()
    }

    private fun guideAxis(frame: DepthFrame): GuideAxis {
        return if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            GuideAxis.Row(frame.height / 2)
        } else {
            GuideAxis.Column(frame.width / 2)
        }
    }

    private fun requestCameraPermission() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 77)
        }
    }

    @Deprecated("Android framework callback")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 77 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            statusText.text = "Camera permission granted. Starting ARCore DBH scan..."
            startArSessionIfReady()
        } else if (requestCode == 77) {
            statusText.text = "Camera permission denied. Enter DBH with Manual."
        }
    }

    private fun buildUi() {
        surfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(this@DbhScanActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setOnClickListener { startCapture() }
        }
        overlay = DbhOverlayView(this).apply {
            setOnClickListener { startCapture() }
        }
        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            text = "Starting ARCore DBH scan..."
            background = rounded(glass, dp(14), glassStroke)
            setPadding(dp(14), dp(11), dp(14), dp(11))
        }
        resultText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            text = "No measurement yet."
            includeFontPadding = false
        }
        captureButton = scanButton("Capture", primaryStyle = true) { startCapture() }
        acceptButton = scanButton("Accept", primaryStyle = true) { acceptResult() }.apply { isEnabled = false }
        val manualButton = scanButton("Manual") { showManualDialog() }
        val closeButton = scanButton("Close") { finish() }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(16))
            background = rounded(glass, dp(18), glassStroke)
            addView(resultText, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(12))
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(captureButton, scanButtonParams())
                addView(manualButton, scanButtonParams())
                addView(acceptButton, scanButtonParams())
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
        root.setOnClickListener { startCapture() }
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

    private fun Float.oneDecimal(): String = String.format("%.1f", this)
    private fun Double.oneDecimal(): String = String.format("%.1f", this)

    companion object {
        const val EXTRA_DIAMETER_CM = "diameter_cm"
        const val EXTRA_CONFIDENCE = "dbh_confidence"
        const val EXTRA_METHOD = "dbh_method"
    }
}

class DbhOverlayView(context: android.content.Context) : View(context) {
    var preview: DbhPreviewFit? = null
    var guideAxis: GuideAxis = GuideAxis.Row(0)
    var stable: Boolean = false

    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 4f
        alpha = 220
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeWidth = 8f
        alpha = 150
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val chordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(50, 220, 120)
        strokeWidth = 7f
    }
    private val trunkFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 74, 180, 110)
        style = Paint.Style.FILL
    }
    private val trunkEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(82, 230, 130)
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }
    private val meshPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        strokeWidth = 1.5f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(185, 20, 28, 24)
        style = Paint.Style.FILL
    }
    private val sleeveRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val axis = preview?.guideAxis ?: guideAxis

        if (axis is GuideAxis.Row) {
            canvas.drawLine(0f, cy, width.toFloat(), cy, haloPaint)
            canvas.drawLine(0f, cy, width.toFloat(), cy, guidePaint)
        } else {
            canvas.drawLine(cx, 0f, cx, height.toFloat(), haloPaint)
            canvas.drawLine(cx, 0f, cx, height.toFloat(), guidePaint)
        }

        crossPaint.color = if (stable) Color.rgb(50, 220, 120) else Color.rgb(230, 70, 70)
        canvas.drawCircle(cx, cy, 46f, haloPaint)
        canvas.drawCircle(cx, cy, 42f, crossPaint)
        canvas.drawLine(cx - 18f, cy, cx + 18f, cy, crossPaint)
        canvas.drawLine(cx, cy - 18f, cx, cy + 18f, crossPaint)

        val fit = preview ?: return
        if (fit.guideAxis is GuideAxis.Row) {
            val x0 = (fit.stripLeftFraction * width).toFloat().coerceIn(0f, width.toFloat())
            val x1 = (fit.stripRightFraction * width).toFloat().coerceIn(0f, width.toFloat())
            val left = kotlin.math.min(x0, x1)
            val right = kotlin.math.max(x0, x1)
            val sleeveTop = cy - height * 0.20f
            val sleeveBottom = cy + height * 0.20f
            sleeveRect.set(left, sleeveTop, right, sleeveBottom)
            canvas.drawRoundRect(sleeveRect, 18f, 18f, trunkFillPaint)
            canvas.drawRoundRect(sleeveRect, 18f, 18f, trunkEdgePaint)
            var gx = left + 14f
            while (gx < right - 2f) {
                canvas.drawLine(gx, sleeveTop + 4f, gx, sleeveBottom - 4f, meshPaint)
                gx += 18f
            }
            var gy = sleeveTop + 18f
            while (gy < sleeveBottom - 2f) {
                canvas.drawLine(left + 4f, gy, right - 4f, gy, meshPaint)
                gy += 18f
            }
            canvas.drawLine(left, cy, right, cy, chordPaint)
            canvas.drawLine(left, cy - 32f, left, cy + 32f, chordPaint)
            canvas.drawLine(right, cy - 32f, right, cy + 32f, chordPaint)
            drawLockLabel(canvas, cx, (sleeveTop - 18f).coerceAtLeast(46f), fit)
        } else {
            val y0 = (fit.stripLeftFraction * height).toFloat().coerceIn(0f, height.toFloat())
            val y1 = (fit.stripRightFraction * height).toFloat().coerceIn(0f, height.toFloat())
            val top = kotlin.math.min(y0, y1)
            val bottom = kotlin.math.max(y0, y1)
            val sleeveLeft = cx - width * 0.20f
            val sleeveRight = cx + width * 0.20f
            sleeveRect.set(sleeveLeft, top, sleeveRight, bottom)
            canvas.drawRoundRect(sleeveRect, 18f, 18f, trunkFillPaint)
            canvas.drawRoundRect(sleeveRect, 18f, 18f, trunkEdgePaint)
            var gx = sleeveLeft + 18f
            while (gx < sleeveRight - 2f) {
                canvas.drawLine(gx, top + 4f, gx, bottom - 4f, meshPaint)
                gx += 18f
            }
            var gy = top + 14f
            while (gy < bottom - 2f) {
                canvas.drawLine(sleeveLeft + 4f, gy, sleeveRight - 4f, gy, meshPaint)
                gy += 18f
            }
            canvas.drawLine(cx, top, cx, bottom, chordPaint)
            canvas.drawLine(cx - 32f, top, cx + 32f, top, chordPaint)
            canvas.drawLine(cx - 32f, bottom, cx + 32f, bottom, chordPaint)
            drawLockLabel(canvas, cx, (top - 18f).coerceAtLeast(46f), fit)
        }
    }

    private fun drawLockLabel(canvas: Canvas, cx: Float, baselineY: Float, fit: DbhPreviewFit) {
        val label = "LOCK ${String.format("%.0f", fit.diameterCm)} cm"
        val labelWidth = labelPaint.measureText(label) + 36f
        val labelRect = RectF(cx - labelWidth / 2f, baselineY - 38f, cx + labelWidth / 2f, baselineY + 8f)
        canvas.drawRoundRect(labelRect, 16f, 16f, labelBgPaint)
        canvas.drawText(label, cx, baselineY - 6f, labelPaint)
    }
}
