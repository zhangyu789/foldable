package com.duo.foldable

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.view.drawToBitmap
import androidx.lifecycle.lifecycleScope
import com.duo.foldable.ui.theme.FoldableTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings screen: after overlay and accessibility are enabled, [HingeCaptureService]
 * captures the inner display on fold via takeScreenshot and runs perspective/blur transition.
 */
class MainActivity : ComponentActivity() {

    private val statusText = mutableStateOf("Initializing…")
    private val stretchAmount = mutableStateOf(FoldMath.DEFAULT_RIGHT_STRETCH)
    private val rotationFactor = mutableStateOf(FoldMath.DEFAULT_ROTATION_FACTOR)
    private val hingeFactor = mutableStateOf(FoldMath.DEFAULT_HINGE_FACTOR)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Sync runtime tunables to FoldMath (service overlay reads these values)
        FoldMath.rightStretchMax = stretchAmount.value
        FoldMath.rotationFactor = rotationFactor.value
        FoldMath.hingeAngleFactor = hingeFactor.value

        HingeCaptureService.status = { s ->
            statusText.value = s
        }

        setContent {
            FoldableTheme {
                val status by statusText
                val stretch by stretchAmount
                val rot by rotationFactor
                val hingeF by hingeFactor
                val overlayOk = remember { mutableStateOf(Settings.canDrawOverlays(this)) }
                val a11yOk = remember { mutableStateOf(HingeCaptureService.running) }

                Column(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF121212))
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Fold Transition (Accessibility Screenshot)",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White
                    )
                    Text(
                        "Flow: grant overlay → enable accessibility → unfold phone → on fold, " +
                            "inner display is captured automatically with perspective stretch and right-edge blur.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f)
                    )

                    Text(
                        "Overlay: ${if (overlayOk.value) "granted" else "not granted"}\n" +
                            "Accessibility: ${if (a11yOk.value) "running" else "disabled"}\n" +
                            "Status: $status",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White
                    )

                    Button(
                        onClick = {
                            startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("1. Grant overlay permission") }

                    Button(
                        onClick = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("2. Enable accessibility service (fold demo)") }

                    Button(
                        onClick = { previewLocal() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Preview effect (page screenshot, no fold needed)") }

                    Text(
                        "Right stretch  ${"%.2f".format(stretch)}  (default 0.79)",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = stretch,
                        onValueChange = {
                            stretchAmount.value = it
                            FoldMath.rightStretchMax = it
                        },
                        valueRange = 0f..2f
                    )

                    Text(
                        "Rotation factor  ${"%.2f".format(rot)}  (default 0.75)",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = rot,
                        onValueChange = {
                            rotationFactor.value = it
                            FoldMath.rotationFactor = it
                        },
                        valueRange = 0f..2f
                    )

                    Text(
                        "Hinge factor  ${"%.2f".format(hingeF)}  (default 0.7)",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = hingeF,
                        onValueChange = {
                            hingeFactor.value = it
                            FoldMath.hingeAngleFactor = it
                        },
                        valueRange = 0f..2f
                    )
                }

                // Refresh permission state when returning
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    while (true) {
                        overlayOk.value = Settings.canDrawOverlays(this@MainActivity)
                        a11yOk.value = HingeCaptureService.running
                        delay(800)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        statusText.value = if (HingeCaptureService.running) {
            "Service running"
        } else {
            "Enable accessibility service"
        }
    }

    /** Simulate outer-screen perspective animation using this page without folding. */
    private fun previewLocal() {
        val root = window.decorView as ViewGroup
        val shot = root.drawToBitmap()
        val host = FrameLayout(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            clipChildren = false
        }
        val view = FoldableScreenView(this).apply {
            setBlurEnabled(true)
            setContentBitmap(shot)
            updateFoldState(0f)
        }
        host.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            host,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        statusText.value = "Previewing: 0° → 90°"
        lifecycleScope.launch {
            val steps = 36
            for (i in 0..steps) {
                view.updateFoldState(i * 90f / steps)
                delay(28)
            }
            delay(400)
            root.removeView(host)
            shot.recycle()
            statusText.value = if (HingeCaptureService.running) "Service running" else "Enable accessibility service"
        }
    }
}
