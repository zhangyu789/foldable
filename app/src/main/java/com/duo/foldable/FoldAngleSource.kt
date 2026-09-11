package com.duo.foldable

import androidx.lifecycle.Lifecycle

/**
 * Unified hinge angle data source abstraction.
 *
 * Normalizes fold-state input from different brands/APIs into one [onAngleChanged] callback
 * aligned with Android [Sensor.TYPE_HINGE_ANGLE]: 0° (folded) ~ 180° (flat).
 * [FoldTransitionController] only consumes 0°~90° (outer-visible range) and does not care
 * whether data comes from Sensor or Jetpack WindowManager.
 */
interface FoldAngleSource {
    /** Whether this source is available on the device. */
    val isAvailable: Boolean

    /** Angle change callback in degrees (0 = folded, 180 = flat). */
    var onAngleChanged: ((Float) -> Unit)?

    /** Start listening. */
    fun start()

    /** Stop listening and release resources. */
    fun stop()
}

/**
 * Source factory: prefer raw hinge angle sensor (continuous, best UX);
 * fall back to Jetpack WindowManager discrete fold states when unsupported.
 */
object FoldAngleSourceFactory {
    fun create(context: android.content.Context, lifecycle: Lifecycle): FoldAngleSource {
        val hinge = HingeSensorAngleSource(context)
        return if (hinge.isAvailable) hinge else WindowManagerAngleSource(context, lifecycle)
    }
}
