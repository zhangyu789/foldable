package com.duo.foldable

import android.content.Context
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val TAG = "HingeAngle"

/**
 * Discrete fold-state source based on Jetpack WindowManager [FoldingFeature].
 *
 * Fallback when [HingeSensorAngleSource] is unavailable; compatible with Pixel Fold,
 * Huawei Mate X series (when raw angle sensor is not exposed), etc.
 *
 * Note: WindowManager only provides discrete states (FLAT / HALF_OPENED),
 * not continuous angles, so this source outputs stepped angles aligned with [FoldMath]:
 *  - No fold feature / not visible → 0° (folded, outer covers)
 *  - HALF_OPENED → 90° (half open, outer just disappears)
 *  - FLAT → 180° (fully flat, inner fully visible)
 * Less smooth than the sensor path, but enables basic fold linkage.
 */
class WindowManagerAngleSource(
    private val context: Context,
    private val lifecycle: Lifecycle
) : FoldAngleSource {

    private val tracker = WindowInfoTracker.getOrCreate(context)
    private var job: Job? = null

    /** Always available as a fallback data source. */
    override val isAvailable: Boolean = true

    override var onAngleChanged: ((Float) -> Unit)? = null

    override fun start() {
        job = CoroutineScope(Dispatchers.Main.immediate).launch {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                tracker.windowLayoutInfo(context).collect { layoutInfo ->
                    val feature = layoutInfo.displayFeatures
                        .filterIsInstance<FoldingFeature>()
                        .firstOrNull()

                    val angle = when (feature?.state) {
                        FoldingFeature.State.FLAT -> 180f
                        FoldingFeature.State.HALF_OPENED -> 90f
                        else -> 0f
                    }
                    Log.i(
                        TAG,
                        "WindowManager fold state=${feature?.state} → angle=%.1f°".format(angle)
                    )
                    onAngleChanged?.invoke(angle)
                }
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }
}
