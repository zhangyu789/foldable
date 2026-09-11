package com.duo.foldable

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.lifecycle.LifecycleOwner

private const val TAG = "FoldCompositorView"

/**
 * Dual-screen image demo (fully separate layers):
 *  - Fold/transition: show outer image only (perspective), inner hidden
 *  - Fully unfolded: show inner image only, outer hidden
 *  - No semi-transparent inner/outer compositing
 */
class FoldCompositorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val innerScreen: FrameLayout
    private val outerScreen: FoldableScreenView
    private val controller: FoldTransitionController

    private var lastW = 0
    private var lastH = 0

    /** Fold progress 0~1, updated from hinge or slider. */
    var onProgressChanged: ((Float) -> Unit)?
        get() = controller.onProgressChanged
        set(value) {
            controller.onProgressChanged = value
        }
    init {
        setBackgroundColor(0xFF000000.toInt())
        // Outer screen may draw beyond this View's right edge
        clipChildren = false
        clipToPadding = false

        innerScreen = FrameLayout(context).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.inner_screen)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    adjustViewBounds = false
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
        }
        addView(innerScreen, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        outerScreen = FoldableScreenView(context).apply {
            setContentResource(R.drawable.outer_screen)
        }
        addView(outerScreen, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Initial: outer only, inner hidden (fully separate)
        innerScreen.visibility = View.GONE
        outerScreen.visibility = View.VISIBLE

        val activity = context as Activity
        controller = FoldTransitionController(
            activity = activity,
            lifecycleOwner = activity as LifecycleOwner,
            innerScreen = innerScreen,
            outerScreen = outerScreen
        )
    }

    fun setProgress(progress: Float) = controller.previewProgress(progress)

    /** Set max right-edge stretch ratio (0~2) and redraw at current angle immediately. */
    fun setRightStretchMax(stretch: Float) {
        FoldMath.rightStretchMax = stretch.coerceIn(0f, 2f)
        controller.refreshWithCurrentAngle()
    }

    /** Image rotation/perspective strength factor (0~2). */
    fun setRotationFactor(factor: Float) {
        FoldMath.rotationFactor = factor.coerceIn(0f, 2f)
        controller.refreshWithCurrentAngle()
    }

    /** Hinge angle mapping factor (0~2): animation angle = raw angle × factor. */
    fun setHingeAngleFactor(factor: Float) {
        FoldMath.hingeAngleFactor = factor.coerceIn(0f, 2f)
        controller.refreshWithCurrentAngle()
    }

    fun setBlurEnabled(enabled: Boolean) {
        controller.blurEnabled = enabled
        outerScreen.setBlurEnabled(enabled)
    }

    fun resyncFromHinge() = controller.resyncNow()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val sizeJump = lastW > 0 && (
            kotlin.math.abs(w - lastW) > lastW * 0.15f ||
                kotlin.math.abs(h - lastH) > lastH * 0.15f
            )
        lastW = w
        lastH = h
        if (sizeJump) {
            Log.i(TAG, "display size jump ${oldw}x$oldh → ${w}x$h, resync")
            if (Build.VERSION.SDK_INT >= 31) outerScreen.setRenderEffect(null)
            controller.onDisplaySizeChanged(w, h)
        }
    }
}

typealias DualScreenCompositor = FoldCompositorView

object OuterBlurEffect {
    /**
     * Note: RenderEffect rasterizes the View off-screen and clips to bounds,
     * which conflicts with right-edge stretch beyond the screen, so RenderEffect is not used
     * on the outer screen here. Blur can be added later via a larger FBO / separate overlay.
     */
    fun apply(view: View, progress: Float, enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= 31) {
            view.setRenderEffect(null)
        }
    }
}
