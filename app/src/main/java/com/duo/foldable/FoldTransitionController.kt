package com.duo.foldable

import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.view.View
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlin.math.abs

private const val TAG = "FoldTransitionController"

/**
 * Dual-screen compositor controller: image effects **linearly synced** to hinge angle (0°~90°).
 *
 *  - 0°: outer fully visible; 90°+: outer gone, inner fully visible
 *  - Hinge takes priority; slider is preview-only when no sensor, reverts to hinge on first callback
 */
class FoldTransitionController(
    private val activity: Activity,
    private val lifecycleOwner: LifecycleOwner,
    private val innerScreen: View,
    private val outerScreen: FoldableScreenView
) : DefaultLifecycleObserver {

    private val source: FoldAngleSource =
        FoldAngleSourceFactory.create(activity, lifecycleOwner.lifecycle)

    var blurEnabled: Boolean = true

    /** Angle change threshold (degrees); smaller values track more finely. */
    var minAngleDelta: Float = 0.3f

    /** Progress callback 0~1 for UI slider sync. */
    var onProgressChanged: ((Float) -> Unit)? = null

    @Volatile
    private var rawAngle: Float = Float.NaN

    private var lastAppliedAngle: Float = Float.NaN
    private var layoutSettling: Boolean = false

    init {
        lifecycleOwner.lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        attach()
    }

    override fun onStop(owner: LifecycleOwner) {
        detach()
    }

    private fun attach() {
        source.onAngleChanged = { angle ->
            rawAngle = angle
            if (!layoutSettling) {
                applyHingeAngle(angle, fromSensor = true)
            }
        }
        source.start()
        sampleHingeOnce()
        Log.i(TAG, "attached, source=${source.javaClass.simpleName}")
    }

    private fun detach() {
        source.stop()
        lastAppliedAngle = Float.NaN
    }

    fun resyncNow() {
        layoutSettling = false
        sampleHingeOnce()
        val angle = when {
            !rawAngle.isNaN() -> rawAngle
            !lastAppliedAngle.isNaN() -> lastAppliedAngle
            else -> guessAngleByWindow()
        }
        applyHingeAngle(angle, fromSensor = false)
    }

    fun onDisplaySizeChanged(width: Int, height: Int) {
        Log.i(TAG, "onDisplaySizeChanged ${width}x$height")
        layoutSettling = true
        outerScreen.post {
            layoutSettling = false
            resyncNow()
        }
    }

    /** Slider preview: maps to 0°~90° using the same linear logic as hinge. */
    fun previewProgress(progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        applyHingeAngle(p * FoldMath.FOLD_MAX_ANGLE, fromSensor = false)
    }

    /**
     * Drive visuals from raw hinge angle (linear, no deceleration, keeps tracking responsive).
     * @param hingeDeg 0~180 (>90 treated as outer no longer visible)
     */
    private fun applyHingeAngle(hingeDeg: Float, fromSensor: Boolean) {
        val angle = hingeDeg.coerceIn(0f, 180f)
        // Hinge factor: animation angle = raw angle × hingeAngleFactor
        val mapped = FoldMath.mapHingeToAnimAngle(angle)
        val animAngle = mapped.coerceIn(0f, FoldMath.FOLD_MAX_ANGLE)
        if (!lastAppliedAngle.isNaN() && abs(animAngle - lastAppliedAngle) < minAngleDelta &&
            !(mapped >= 90f && lastAppliedAngle < 90f) &&
            !(mapped < 90f && lastAppliedAngle >= 90f)
        ) {
            return
        }
        lastAppliedAngle = animAngle
        rawAngle = angle

        val progress = FoldMath.angleToProgress(animAngle)

        Log.i(
            TAG,
            "sync raw=%.2f° mapped=%.2f° anim=%.2f° rotF=%.2f hingeF=%.2f stretch=%.2f progress=%.3f".format(
                angle,
                mapped,
                animAngle,
                FoldMath.rotationFactor,
                FoldMath.hingeAngleFactor,
                FoldMath.rightStretchMax,
                progress
            )
        )

        // Inner/outer fully separate: show one layer at a time, no semi-transparent blend
        val showOuter = mapped < FoldMath.FOLD_MAX_ANGLE - 0.5f &&
            FoldMath.cosShrink(animAngle) > 0.01f

        if (showOuter) {
            // Outer only: perspective + right stretch + right Gaussian blur; inner fully hidden
            innerScreen.visibility = View.GONE
            outerScreen.visibility = View.VISIBLE
            outerScreen.alpha = 1f
            outerScreen.setBlurEnabled(blurEnabled)
            outerScreen.updateFoldState(animAngle)
        } else {
            // Inner only: static image, no animation or blur
            outerScreen.setBlurEnabled(false)
            outerScreen.visibility = View.GONE
            outerScreen.alpha = 0f
            innerScreen.visibility = View.VISIBLE
            innerScreen.alpha = 1f
            // Reset outer matrix to avoid flashing stale transform on next show
            outerScreen.updateFoldState(0f)
        }

        onProgressChanged?.invoke(progress)
    }

    /** Redraw using the latest hinge angle after factor changes. */
    fun refreshWithCurrentAngle() {
        val angle = when {
            !rawAngle.isNaN() -> rawAngle
            !lastAppliedAngle.isNaN() -> lastAppliedAngle
            else -> 0f
        }
        lastAppliedAngle = Float.NaN // Force bypass threshold
        applyHingeAngle(angle, fromSensor = false)
    }

    private fun sampleHingeOnce() {
        val sm = activity.getSystemService(SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) ?: return
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                rawAngle = event.values[0]
                sm.unregisterListener(this)
                if (!layoutSettling) {
                    applyHingeAngle(rawAngle, fromSensor = true)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
        outerScreen.postDelayed({
            runCatching { sm.unregisterListener(listener) }
        }, 500)
    }

    private fun guessAngleByWindow(): Float {
        val decor = activity.window?.decorView ?: return 0f
        val w = decor.width
        val h = decor.height
        if (w <= 0 || h <= 0) return 0f
        val aspect = w.toFloat() / h.toFloat()
        return if (aspect >= 0.75f) 180f else 0f
    }
}
