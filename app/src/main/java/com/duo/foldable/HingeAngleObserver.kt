package com.duo.foldable

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Hinge angle observer (design-doc API name).
 *
 * Wraps [Sensor.TYPE_HINGE_ANGLE]: 0° = fully folded, 180° = fully flat.
 * When only the outer-visible range matters, callers should clamp to [0, 90].
 *
 * Functionally equivalent to [HingeSensorAngleSource]; closer to the design-doc call style.
 */
class HingeAngleObserver(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val hingeSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)

    var onAngleChanged: ((Float) -> Unit)? = null

    val isAvailable: Boolean get() = hingeSensor != null

    fun start() {
        val sensor = hingeSensor ?: return
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_HINGE_ANGLE) return
        // Raw 0~180; consumers typically coerceIn(0, 90)
        val angle = event.values[0].coerceIn(0f, 180f)
        onAngleChanged?.invoke(angle)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
