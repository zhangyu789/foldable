package com.duo.foldable

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Interpolation curves for fold animation.
 *
 * The hinge is a damped physical mechanism; angle change is not uniform.
 * Interpolators make the angle→progress mapping feel more natural,
 * avoiding harsh linear tracking and improving flexible-display fusion.
 */
object FoldInterpolators {

    /** Standard decelerate curve (DecelerateInterpolator, factor=1.0): 1-(1-t)^2. */
    fun decelerate(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return 1f - (1f - x) * (1f - x)
    }

    /**
     * Damped spring curve: slight overshoot then settle, mimicking hinge rebound.
     * @param damping Damping ratio; lower values mean more overshoot (suggest 0.6~0.9).
     */
    fun dampedSpring(t: Float, damping: Float = 0.75f): Float {
        val x = t.coerceIn(0f, 1f)
        if (x >= 1f) return 1f
        val omega = 12f // Angular frequency; controls rebound speed
        val decay = exp(-damping * omega * x)
        return 1f - decay * (cos(omega * x) + (damping * sin(omega * x)))
    }

    /** Smooth step with physical damping feel (for frame-to-frame interpolation, reduces jitter). */
    fun approach(current: Float, target: Float, smoothing: Float = 0.2f): Float =
        current + (target - current) * smoothing.coerceIn(0f, 1f)
}
