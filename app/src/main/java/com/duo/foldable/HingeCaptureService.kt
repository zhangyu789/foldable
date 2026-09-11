package com.duo.foldable

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Presentation
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.widget.FrameLayout

/**
 * Inspired by Fold8HingeFade: uses accessibility [takeScreenshot] at fold start to capture
 * the inner display, then shows perspective stretch + right-edge Gaussian blur on an outer overlay;
 * hands off to the cover display and fades out when it lights up.
 */
class HingeCaptureService : AccessibilityService() {

    companion object {
        private const val TAG = "HingeCapture"
        const val FLAT_ANGLE = 168f
        const val CLOSED_ANGLE = 40f
        const val REARM_ANGLE = 172f
        const val FOLD_TIMEOUT_MS = 2500L
        const val COVER_HOLD_MS = 120L
        const val COVER_FADE_MS = 420L

        @Volatile
        var running = false

        @Volatile
        var status: (String) -> Unit = {}
    }

    private enum class State { WAIT_FLAT, FLAT, FOLDING, HANDED_OFF }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var sensors: SensorManager
    private lateinit var displays: DisplayManager
    private var state = State.WAIT_FLAT
    private var frame: Bitmap? = null
    private var innerArea = 0L
    private var capturing = false
    private var captureMs = 0L
    private var lastAngle = 180f

    private var overlayHost: FrameLayout? = null
    private var overlayView: FoldableScreenView? = null
    private var overlayWm: WindowManager? = null
    private var presentation: Presentation? = null
    private var coverAnim: ValueAnimator? = null

    override fun onServiceConnected() {
        sensors = getSystemService(SensorManager::class.java)
        displays = getSystemService(DisplayManager::class.java)
        innerArea = displays.displays.maxOf {
            it.mode.physicalWidth.toLong() * it.mode.physicalHeight
        }
        sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)?.let {
            sensors.registerListener(hinge, it, SensorManager.SENSOR_DELAY_GAME)
        } ?: Log.w(TAG, "no hinge sensor")
        displays.registerDisplayListener(displayListener, main)
        running = true
        report("Ready, waiting for fold")
        Log.i(TAG, "connected, inner area $innerArea")
    }

    override fun onDestroy() {
        running = false
        try {
            sensors.unregisterListener(hinge)
        } catch (_: Exception) {
        }
        try {
            displays.unregisterDisplayListener(displayListener)
        } catch (_: Exception) {
        }
        hideAll()
        report("Stopped")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private val hinge = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        override fun onSensorChanged(e: SensorEvent) {
            onAngle(e.values[0])
        }
    }

    private fun onAngle(a: Float) {
        when (state) {
            State.WAIT_FLAT -> if (a >= REARM_ANGLE) {
                state = State.FLAT
                hideAll()
            }
            State.FLAT -> if (a < FLAT_ANGLE) beginFold(a)
            State.FOLDING -> {
                updateOverlayAngle(a)
                if (kotlin.math.abs(a - lastAngle) > 0.5f) {
                    main.removeCallbacks(foldTimeout)
                    main.postDelayed(foldTimeout, FOLD_TIMEOUT_MS)
                }
                if (a >= REARM_ANGLE) reset("Re-opened, re-arming")
            }
            State.HANDED_OFF -> if (a >= REARM_ANGLE) reset("Flattened, re-arming")
        }
        lastAngle = a
        report("Hinge %.0f°  %s".format(a, state.name))
    }

    /** Map hinge angle (flat~closed) to animation angle 0°~90°, then apply hinge factor. */
    private fun hingeToAnimAngle(a: Float): Float {
        val p = ((FLAT_ANGLE - a) / (FLAT_ANGLE - CLOSED_ANGLE)).coerceIn(0f, 1f)
        return FoldMath.mapHingeToAnimAngle(p * FoldMath.FOLD_MAX_ANGLE)
            .coerceIn(0f, FoldMath.FOLD_MAX_ANGLE)
    }

    private fun updateOverlayAngle(a: Float) {
        overlayView?.updateFoldState(hingeToAnimAngle(a))
    }

    private fun beginFold(a: Float) {
        if (capturing) return
        capturing = true
        state = State.FOLDING
        val t0 = SystemClock.elapsedRealtime()
        // Same as Fold8HingeFade: accessibility takeScreenshot, no MediaProjection consent dialog
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                capturing = false
                val hw = r.hardwareBuffer
                val b = Bitmap.wrapHardwareBuffer(hw, r.colorSpace)
                    ?.copy(Bitmap.Config.ARGB_8888, false)
                hw.close()
                if (b == null) {
                    Log.w(TAG, "screenshot gave no bitmap")
                    state = State.FLAT
                    return
                }
                captureMs = SystemClock.elapsedRealtime() - t0
                frame?.recycle()
                frame = b
                if (state != State.FOLDING) return
                showInnerOverlay(b, hingeToAnimAngle(lastAngle))
                main.removeCallbacks(foldTimeout)
                main.postDelayed(foldTimeout, FOLD_TIMEOUT_MS)
                Log.i(
                    TAG,
                    "fold began at %.1f°, frame ${b.width}x${b.height} in $captureMs ms".format(a)
                )
                checkCover()
            }

            override fun onFailure(code: Int) {
                capturing = false
                state = State.FLAT
                Log.w(TAG, "screenshot failed $code")
                report("Screenshot failed ($code)")
            }
        })
    }

    private val foldTimeout = Runnable {
        if (state == State.FOLDING) {
            Log.i(TAG, "no cover within ${FOLD_TIMEOUT_MS} ms, hiding")
            hideAll()
            state = State.HANDED_OFF
        }
    }

    private fun reset(why: String) {
        hideAll()
        state = State.FLAT
        Log.i(TAG, "reset: $why")
    }

    private fun showInnerOverlay(b: Bitmap, animAngle: Float) {
        overlayView?.let {
            it.setContentBitmap(b)
            it.updateFoldState(animAngle)
            return
        }
        val ctx = createDisplayContext(displays.getDisplay(Display.DEFAULT_DISPLAY))
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val wm = ctx.getSystemService(WindowManager::class.java)
        val host = FrameLayout(ctx).apply {
            setBackgroundColor(0x00000000)
            clipChildren = false
            clipToPadding = false
        }
        val view = FoldableScreenView(ctx).apply {
            setBlurEnabled(true)
            setContentBitmap(b)
            updateFoldState(animAngle)
        }
        host.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        try {
            wm.addView(host, overlayParams())
        } catch (e: Exception) {
            Log.w(TAG, "overlay refused: $e")
            report("Overlay denied; grant overlay permission")
            return
        }
        overlayHost = host
        overlayView = view
        overlayWm = wm
    }

    private fun overlayParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
    }

    private fun hideAll() {
        main.removeCallbacks(foldTimeout)
        coverAnim?.cancel()
        coverAnim = null
        overlayHost?.let { v ->
            try {
                overlayWm?.removeViewImmediate(v)
            } catch (_: Exception) {
            }
        }
        overlayHost = null
        overlayView = null
        overlayWm = null
        presentation?.dismiss()
        presentation = null
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = checkCover()
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) = checkCover()
    }

    /** Cover display: noticeably smaller than the largest display and powered on. */
    private fun checkCover() {
        if (state != State.FOLDING) return
        val cover = displays.displays.firstOrNull { d ->
            d.state == Display.STATE_ON &&
                d.mode.physicalWidth.toLong() * d.mode.physicalHeight < innerArea * 6 / 10
        } ?: return
        val b = frame ?: return
        state = State.HANDED_OFF
        Log.i(
            TAG,
            "cover ${cover.displayId} (${cover.mode.physicalWidth}x${cover.mode.physicalHeight})"
        )
        if (cover.displayId == Display.DEFAULT_DISPLAY) {
            val v = overlayView ?: run {
                showInnerOverlay(b, FoldMath.FOLD_MAX_ANGLE)
                overlayView
            } ?: return
            runCoverFinish(v)
        } else {
            hideAll()
            val pres = Presentation(createDisplayContext(cover), cover).apply {
                window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                window?.addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                )
                window?.setBackgroundDrawableResource(android.R.color.transparent)
            }
            val host = FrameLayout(pres.context).apply {
                clipChildren = false
                setBackgroundColor(0xFF000000.toInt())
            }
            val v = FoldableScreenView(pres.context).apply {
                setBlurEnabled(true)
                setContentBitmap(b)
                // Keep near-closed perspective on cover handoff, then fade out
                updateFoldState(FoldMath.FOLD_MAX_ANGLE * 0.85f)
            }
            host.addView(
                v,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            pres.setContentView(host)
            try {
                pres.show()
            } catch (e: Exception) {
                Log.w(TAG, "presentation refused: $e")
                return
            }
            presentation = pres
            runCoverFinish(v)
        }
    }

    private fun runCoverFinish(v: FoldableScreenView) {
        v.alpha = 1f
        coverAnim = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = COVER_FADE_MS
            startDelay = COVER_HOLD_MS
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { a -> v.alpha = a.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    hideAll()
                }

                override fun onAnimationCancel(animation: Animator) {
                    hideAll()
                }
            })
            start()
        }
        main.postDelayed({
            if (state == State.HANDED_OFF && (overlayHost != null || presentation != null)) {
                hideAll()
            }
        }, COVER_HOLD_MS + COVER_FADE_MS + 500)
        report("Handed off to cover display (capture ${captureMs} ms)")
    }

    private fun report(s: String) = main.post { status(s) }
}
