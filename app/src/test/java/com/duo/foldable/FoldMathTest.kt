package com.duo.foldable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Angle convention: 0° = folded (outer covers), 90° = unfolded (outer gone).
 * Hinge on the left: left axis fixed, right recedes; blur on the right (away from hinge).
 */
class FoldMathTest {

    @Test
    fun angleToProgress_mapsEndpoints() {
        assertEquals(0f, FoldMath.angleToProgress(0f), 1e-6f)
        assertEquals(1f, FoldMath.angleToProgress(90f), 1e-6f)
        assertEquals(1f, FoldMath.angleToProgress(180f), 1e-6f)
    }

    @Test
    fun angleToProgress_clampsOutOfRange() {
        assertTrue(FoldMath.angleToProgress(-30f) >= 0f)
        assertTrue(FoldMath.angleToProgress(300f) <= 1f)
        assertEquals(0.5f, FoldMath.angleToProgress(45f), 1e-6f)
    }

    @Test
    fun progressToBlurStrength_scalesLinearly() {
        assertEquals(0f, FoldMath.progressToBlurStrength(0f), 1e-6f)
        assertEquals(0.5f, FoldMath.progressToBlurStrength(0.5f), 1e-6f)
        assertEquals(1f, FoldMath.progressToBlurStrength(1f), 1e-6f)
        assertTrue(FoldMath.progressToBlurStrength(2f) <= 1f)
    }

    @Test
    fun progressToBlurEdge_movesRightward() {
        // Left hinge: blur grows from the right, left boundary 0 → 0.7
        assertEquals(0f, FoldMath.progressToBlurEdge(0f), 1e-6f)
        assertEquals(0.35f, FoldMath.progressToBlurEdge(0.5f), 1e-6f)
        assertEquals(0.7f, FoldMath.progressToBlurEdge(1f), 1e-6f)
    }

    @Test
    fun progressToRotationY_rotatesPositiveForLeftHinge() {
        assertEquals(0f, FoldMath.progressToRotationY(0f), 1e-6f)
        assertEquals(90f, FoldMath.progressToRotationY(1f), 1e-6f)
        assertTrue(FoldMath.progressToRotationY(0.5f) > 0f)
    }

    @Test
    fun cosShrink_atQuarterAngle() {
        assertEquals(1f, FoldMath.cosShrink(0f), 1e-6f)
        assertEquals(0.7071f, FoldMath.cosShrink(45f), 1e-3f)
        assertEquals(0f, FoldMath.cosShrink(90f), 1e-6f)
    }

    @Test
    fun outerAlpha_matchesCosShrink() {
        assertEquals(1f, FoldMath.outerAlpha(0f), 1e-6f)
        assertEquals(0.7071f, FoldMath.outerAlpha(45f), 1e-3f)
        assertEquals(0f, FoldMath.outerAlpha(90f), 1e-6f)
    }

    @Test
    fun computeBlurAtPosition_rightStrongLeftClear() {
        // Fully unfolded (90°): strongest blur on the right, ~0 on the left
        assertEquals(FoldMath.MAX_BLUR_RADIUS, FoldMath.computeBlurAtPosition(1f, 90f), 1e-3f)
        assertEquals(0f, FoldMath.computeBlurAtPosition(0f, 90f), 1e-3f)
        assertEquals(0f, FoldMath.computeBlurAtPosition(1f, 0f), 1e-6f)
        assertEquals(FoldMath.MAX_BLUR_RADIUS * 0.5f, FoldMath.computeBlurAtPosition(1f, 45f), 1e-3f)
    }

    @Test
    fun rightEdgeX_extendsBeyondWidth() {
        FoldMath.rightStretchMax = FoldMath.DEFAULT_RIGHT_STRETCH
        assertEquals(1000f, FoldMath.rightEdgeX(1000f, 0f), 1e-3f)
        assertEquals(
            1000f * (1f + FoldMath.DEFAULT_RIGHT_STRETCH),
            FoldMath.rightEdgeX(1000f, 90f),
            1e-3f
        )
        val x45 = FoldMath.rightEdgeX(1000f, 45f)
        assertTrue(x45 > 1000f)
        assertTrue(x45 < 1000f * (1f + FoldMath.DEFAULT_RIGHT_STRETCH))
        // Zero stretch: no extension
        assertEquals(1000f, FoldMath.rightEdgeX(1000f, 90f, stretchMax = 0f), 1e-3f)
        // Stretch 2: up to 3W
        assertEquals(3000f, FoldMath.rightEdgeX(1000f, 90f, stretchMax = 2f), 1e-3f)
    }

    @Test
    fun rightEdgeHalfHeight_foreshortens() {
        assertEquals(500f, FoldMath.rightEdgeHalfHeight(1000f, 0f), 1e-3f)
        assertTrue(FoldMath.rightEdgeHalfHeight(1000f, 45f) < 500f)
        assertTrue(FoldMath.rightEdgeHalfHeight(1000f, 90f) < 50f)
    }

    @Test
    fun shrinkOffset_matchesCosModel() {
        assertEquals(0f, FoldMath.shrinkOffset(1000f, 0f), 1e-3f)
        assertEquals(1000f * (1f - 0.7071f), FoldMath.shrinkOffset(1000f, 45f), 1f)
        assertEquals(1000f, FoldMath.shrinkOffset(1000f, 90f), 1e-3f)
    }

    @Test
    fun expectedVisualTable_matchesCos() {
        val cases = listOf(
            0f to 1.0f,
            15f to 0.966f,
            30f to 0.866f,
            45f to 0.707f,
            60f to 0.5f,
            75f to 0.259f,
            90f to 0.0f
        )
        for ((angle, expected) in cases) {
            assertEquals(expected, FoldMath.cosShrink(angle), 1e-2f)
            assertEquals(expected, FoldMath.outerAlpha(angle), 1e-2f)
        }
    }
}
