package com.duo.foldable

import android.graphics.Matrix
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

    /**
     * Runtime tunable: max extra right-edge stretch (relative to screen width).
     * 0 = no extension, 2 = up to 2× screen width beyond the edge.
     * rightX = W · (1 + rightStretchMax · sin(θ))
     */
    @Volatile
    var rightStretchMax: Float = DEFAULT_RIGHT_STRETCH

    /**
     * Image rotation/perspective strength: visual angle = animation angle × rotationFactor.
     * 1 = unchanged, >1 folds faster, <1 folds slower.
     */
    @Volatile
    var rotationFactor: Float = DEFAULT_ROTATION_FACTOR

    /**
     * Hinge angle mapping factor: animation angle = raw hinge angle × hingeAngleFactor.
     * Used to calibrate sensor vs. on-screen sync.
     */
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

    /**
     * Visible width after right edge shrinks toward hinge: W·cos(θ).
     */
    fun visibleWidth(width: Float, hingeAngleDeg: Float): Float =
        width * cosShrink(hingeAngleDeg)

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

    /**
     * Left-hinge perspective quad map: left edge fixed; right edge extends right (may > W) and foreshortens vertically.
     */
    fun perspectiveMatrix(width: Int, height: Int, hingeAngleDeg: Float): Matrix {
        val m = Matrix()
        if (width <= 0 || height <= 0) return m

        val w = width.toFloat()
        val h = height.toFloat()
        val rightX = rightEdgeX(w, hingeAngleDeg)
        val half = rightEdgeHalfHeight(h, hingeAngleDeg)
        val midY = h * 0.5f

        if (half < 1f) {
            m.setScale(0.01f, 0.01f, 0f, midY)
            return m
        }

        val src = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
        val dst = floatArrayOf(
            0f, 0f,
            rightX, midY - half,
            rightX, midY + half,
            0f, h
        )
        if (!m.setPolyToPoly(src, 0, dst, 0, 4)) {
            m.setScale(cosShrink(hingeAngleDeg).coerceAtLeast(0.01f), 1f, 0f, midY)
        }
        return m
    }

    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
