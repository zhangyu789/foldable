package com.duo.foldable

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Outer-screen content view: only the outer image is animated.
 *
 *  - Left hinge fixed; right edge foreshortens vertically (recedes) and stretches right (may exceed screen width)
 *  - Right region gets Gaussian blur, strength increases with unfold progress
 */
class FoldableScreenView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    private val matrix = Matrix()
    private var cacheBitmap: Bitmap? = null
    private var blurEnabled: Boolean = true
    private var contentImage: ImageView? = null

    @Volatile
    var currentAngle: Float = 0f
        private set

    @Volatile
    var foldProgress: Float = 0f
        private set

    init {
        clipChildren = false
        clipToPadding = false
        setBackgroundColor(Color.TRANSPARENT)
        contentImage = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            adjustViewBounds = false
            clipToOutline = false
        }
        addView(contentImage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Use accessibility screenshot / preview bitmap as outer-screen content. */
    fun setContentBitmap(bitmap: Bitmap?) {
        contentImage?.setImageBitmap(bitmap)
        invalidate()
    }

    fun setContentResource(resId: Int) {
        contentImage?.setImageResource(resId)
        invalidate()
    }

    fun setBlurEnabled(enabled: Boolean) {
        blurEnabled = enabled
        invalidate()
    }

    fun updateFoldState(hingeAngleDeg: Float) {
        val angle = hingeAngleDeg.coerceIn(0f, FoldMath.FOLD_MAX_ANGLE)
        currentAngle = angle
        foldProgress = FoldMath.angleToProgress(angle)
        applyPerspectiveTransform(angle)
        invalidate()
        (parent as? android.view.View)?.invalidate()
    }

    fun updateFoldProgress(progress: Float) {
        updateFoldState(progress.coerceIn(0f, 1f) * FoldMath.FOLD_MAX_ANGLE)
    }

    fun refreshTransform() {
        applyPerspectiveTransform(currentAngle)
        invalidate()
        (parent as? android.view.View)?.invalidate()
    }

    private fun applyPerspectiveTransform(hingeAngleDeg: Float) {
        if (width == 0 || height == 0) {
            matrix.reset()
            return
        }

        val visual = FoldMath.mapAnimToVisualAngle(hingeAngleDeg)
        val w = width.toFloat()
        val h = height.toFloat()
        val rightX = FoldMath.rightEdgeX(w, visual)
        val half = FoldMath.rightEdgeHalfHeight(h, visual)
        val midY = h * 0.5f

        val src = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
        val dst = floatArrayOf(
            0f, 0f,
            rightX, midY - half,
            rightX, midY + half,
            0f, h
        )

        if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) {
            matrix.reset()
            matrix.setScale(FoldMath.cosShrink(visual).coerceAtLeast(0.01f), 1f, 0f, midY)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (width == 0 || height == 0) {
            super.dispatchDraw(canvas)
            return
        }

        val visual = FoldMath.mapAnimToVisualAngle(currentAngle)
        val extendX = (FoldMath.rightEdgeX(width.toFloat(), visual) - width)
            .coerceAtLeast(0f)
            .toInt()
        val outW = width + extendX + 2
        val outH = height
        val blurRadius = FoldMath.progressToBlurStrength(foldProgress) * FoldMath.MAX_BLUR_RADIUS

        // No blur: draw directly with matrix (faster)
        if (!blurEnabled || blurRadius < 0.5f || foldProgress < 0.02f) {
            val save = canvas.save()
            canvas.clipRect(0f, -height * 0.2f, outW.toFloat(), height * 1.2f)
            canvas.concat(matrix)
            super.dispatchDraw(canvas)
            canvas.restoreToCount(save)
            return
        }

        // Render perspective off-screen, then apply Gaussian blur on the right
        var bmp = cacheBitmap
        if (bmp == null || bmp.width != outW || bmp.height != outH || bmp.isRecycled) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            cacheBitmap = bmp
        } else {
            bmp.eraseColor(Color.TRANSPARENT)
        }

        val off = Canvas(bmp)
        off.concat(matrix)
        super.dispatchDraw(off)

        val blurEdge = FoldMath.progressToBlurEdge(foldProgress)
        GaussianBlur.blurRightEdge(bmp, blurRadius, blurEdge)

        canvas.drawBitmap(bmp, 0f, 0f, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cacheBitmap?.recycle()
        cacheBitmap = null
        if (w > 0 && h > 0) {
            applyPerspectiveTransform(currentAngle)
        }
    }

    override fun onDetachedFromWindow() {
        cacheBitmap?.recycle()
        cacheBitmap = null
        super.onDetachedFromWindow()
    }
}

typealias FoldableOuterScreenView = FoldableScreenView
