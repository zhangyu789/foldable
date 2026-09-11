package com.duo.foldable

import kotlin.math.cos
import java.lang.Math.toRadians

/**
 * Pure math for fold animation.
 *
 * Geometry convention (left hinge):
 *  - Left axis fixed; right edge recedes with θ; visible width / alpha = cos(θ);
 *  - Blur concentrated on the side away from the hinge (right).
 */
object FoldMath {
    const val FOLD_MAX_ANGLE = 90f
    const val MAX_BLUR_RADIUS = 25f

    fun angleToProgress(angleDeg: Float): Float =
        (angleDeg.coerceIn(0f, FOLD_MAX_ANGLE) / FOLD_MAX_ANGLE).coerceIn(0f, 1f)

    fun progressToBlurStrength(progress: Float): Float =
        progress.coerceIn(0f, 1f)

    /**
     * Blur region left boundary (normalized x).
     * Blur covers x ∈ [blurEdge, 1] (right); boundary moves from 0 to 0.7 as unfold progresses.
     */
    fun progressToBlurEdge(progress: Float): Float =
        progress.coerceIn(0f, 1f) * 0.7f

    /**
     * Outer-screen perspective rotation angle: 0° → +90° (left hinge, right edge folds back).
     */
    fun progressToRotationY(progress: Float): Float =
        90f * progress.coerceIn(0f, 1f)

    fun cosShrink(angleDeg: Float): Float =
        cos(toRadians(angleDeg.toDouble())).toFloat()

    fun outerAlpha(angleDeg: Float): Float = cosShrink(angleDeg)

    /** Default max extra right-edge stretch ratio. */
    const val DEFAULT_RIGHT_STRETCH = 0.79f
    const val DEFAULT_ROTATION_FACTOR = 0.75f
    const val DEFAULT_HINGE_FACTOR = 0.7f

    @Volatile
    var rightStretchMax: Float = DEFAULT_RIGHT_STRETCH

    @Volatile
    var rotationFactor: Float = DEFAULT_ROTATION_FACTOR

    @Volatile
    var hingeAngleFactor: Float = DEFAULT_HINGE_FACTOR

    /** Map raw hinge angle to animation angle (caller clamps to 0~90). */
    fun mapHingeToAnimAngle(rawHingeDeg: Float): Float =
        rawHingeDeg * hingeAngleFactor.coerceAtLeast(0f)

    /** Map animation angle to visual angle used by the perspective mesh. */
    fun mapAnimToVisualAngle(animAngleDeg: Float): Float =
        (animAngleDeg * rotationFactor.coerceAtLeast(0f)).coerceIn(0f, FOLD_MAX_ANGLE)

    /**
     * Screen X of the right edge after perspective; may exceed W as angle increases.
     */
    fun rightEdgeX(
        width: Float,
        hingeAngleDeg: Float,
        stretchMax: Float = rightStretchMax
    ): Float {
        val s = kotlin.math.sin(toRadians(hingeAngleDeg.toDouble())).toFloat()
        return width * (1f + stretchMax.coerceAtLeast(0f) * s.coerceIn(0f, 1f))
    }

    /**
     * Right-edge half-height: shrinks with cos(θ), simulating vertical foreshortening as the right recedes.
     */
    fun rightEdgeHalfHeight(height: Float, hingeAngleDeg: Float): Float =
        height * 0.5f * cosShrink(hingeAngleDeg).coerceAtLeast(0.04f)

    /** @deprecated Legacy right-hinge semantics; kept as W·(1-cosθ) for test compatibility. */
    fun shrinkOffset(width: Float, hingeAngleDeg: Float): Float =
        width * (1f - cosShrink(hingeAngleDeg))

    /**
     * x: 0 = left/hinge side, 1 = right/far from hinge. Strongest blur on the right.
     */
    fun computeBlurAtPosition(
        x: Float,
        hingeAngleDeg: Float,
        maxRadius: Float = MAX_BLUR_RADIUS
    ): Float {
        val progress = angleToProgress(hingeAngleDeg)
        val blurEdge = progressToBlurEdge(progress)
        // Blur increases to the right of blurEdge
        val blurFactor = smoothstep(blurEdge, 1f, x)
        return maxRadius * progress * blurFactor
    }

    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
