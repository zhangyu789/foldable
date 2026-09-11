package com.duo.foldable

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.abs

private const val TAG = "HingeAngle"

/**
 * Continuous angle source based on [Sensor.TYPE_HINGE_ANGLE].
 *
 * event.values[0]: 0° = fully folded, 180° = fully flat.
 */
class HingeSensorAngleSource(context: Context) : FoldAngleSource, SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val hingeSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)

    private var lastLoggedAngle: Float = Float.NaN

    override val isAvailable: Boolean
        get() = hingeSensor != null

    override var onAngleChanged: ((Float) -> Unit)? = null

    override fun start() {
        val sensor = hingeSensor ?: run {
            Log.w(TAG, "TYPE_HINGE_ANGLE not available on this device")
            return
        }
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        Log.i(TAG, "hinge sensor started: name=${sensor.name}, maxRange=${sensor.maximumRange}")
    }

    override fun stop() {
        sensorManager.unregisterListener(this)
        Log.i(TAG, "hinge sensor stopped")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_HINGE_ANGLE) return
        val angle = event.values[0]
        // Log only when change ≥ 0.5° to avoid spam
        if (lastLoggedAngle.isNaN() || abs(angle - lastLoggedAngle) >= 0.5f) {
            lastLoggedAngle = angle
            Log.i(
                TAG,
                "hingeAngle=%.2f°  (0=folded, 180=flat)  effective=%.2f°  progress=%.3f  cos=%.3f".format(
                    angle,
                    angle.coerceIn(0f, FoldMath.FOLD_MAX_ANGLE),
                    FoldMath.angleToProgress(angle),
                    FoldMath.cosShrink(angle.coerceIn(0f, FoldMath.FOLD_MAX_ANGLE))
                )
            )
        }
        onAngleChanged?.invoke(angle)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d(TAG, "hinge accuracy=$accuracy")
        if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            Log.w(TAG, "hinge angle sensor accuracy unreliable")
        }
    }
}
