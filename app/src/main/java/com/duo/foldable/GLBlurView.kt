package com.duo.foldable

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.AttributeSet
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

private const val TAG = "GLBlurView"

/**
 * Fold animation track A: outer-screen Gaussian blur overlay (full-screen transparent layer).
 *
 * Design notes (see doc "blur region calculation"):
 *  - Blur concentrated on the side away from the hinge (left), smoothstep falloff toward hinge (right);
 *  - Radius increases with fold progress ([FoldMath.progressToBlurStrength]);
 *  - Overlay is transparent overall: only the left blur region has alpha, right is fully transparent,
 *    revealing the underlying (track B) clear, perspective-transformed content.
 *
 * Implemented with GLSurfaceView + OpenGL ES 2.0, full pipeline:
 *   1. Vertex/fragment shader compile and program link ([buildProgram])
 *   2. Full-screen quad VBO and texture creation
 *   3. Content texture upload ([setContentBitmap], auto 1/4 downsample)
 *   4. Per-frame uniform update from [blurStrength]/[blurEdge] and draw
 *
 * Shader source from res/raw (single source of truth with .glsl files).
 *
 * Performance notes:
 *  - RENDERMODE_WHEN_DIRTY, redraw only on angle/content change;
 *  - Texture downsampled 1/4 before blur, upscaled to screen, ~16× lower GPU load;
 *  - Non-critical frames can be capped at 30fps upstream.
 */
class GLBlurView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val renderer = BlurRenderer(context)

    /** Current blur strength (fold-progress driven, 0.0~1.0). */
    @Volatile
    var blurStrength: Float = 0f

    /** Current blur region right boundary (normalized x; blur on left, transparent on right). */
    @Volatile
    var blurEdge: Float = 1f

    /** Outer-screen alpha (= cos(θ)); blur layer fades with outer screen. */
    @Volatile
    var outerAlpha: Float = 1f

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        // Transparent overlay: GL surface above normal Views, below media layer;
        // translucent format + blending lets the clear right region show underlying content.
        setZOrderMediaOverlay(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** Set content snapshot to blur (usually from PixelCopy of the current window). */
    fun setContentBitmap(bitmap: Bitmap) {
        queueEvent { renderer.updateTexture(bitmap) }
        requestRender()
    }

    /** Update blur parameters and trigger one redraw. */
    fun setBlur(strength: Float, edge: Float, outerAlpha: Float = 1f) {
        blurStrength = strength
        blurEdge = edge
        this.outerAlpha = outerAlpha
        requestRender()
    }

    override fun onDetachedFromWindow() {
        queueEvent { renderer.release() }
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------
    // Renderer: full OpenGL ES pipeline
    // ------------------------------------------------------------------
    private inner class BlurRenderer(private val ctx: Context) : GLSurfaceView.Renderer {

        private var program = 0
        private var textureId = 0
        private var positionHandle = 0
        private var texCoordHandle = 0
        private var uBlurStrength = 0
        private var uBlurEdge = 0
        private var uOuterAlpha = 0
        private var uTexSize = 0
        private var uTexture = 0

        private val texSize = FloatArray(2) { 1f }
        private var pendingBitmap: Bitmap? = null

        private val quadBuffer: FloatBuffer
        private val texBuffer: FloatBuffer

        init {
            // Two triangles covering the full screen
            val quad = floatArrayOf(
                -1f, -1f, 1f, -1f, -1f, 1f,
                -1f, 1f, 1f, -1f, 1f, 1f
            )
            val tex = floatArrayOf(
                0f, 0f, 1f, 0f, 0f, 1f,
                0f, 1f, 1f, 0f, 1f, 1f
            )
            quadBuffer = newBuffer(quad)
            texBuffer = newBuffer(tex)
        }

        private fun newBuffer(arr: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(arr.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(arr); position(0) }

        private fun loadRaw(resId: Int): String =
            ctx.resources.openRawResource(resId).bufferedReader().use { it.readText() }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            // Alpha blending required for transparent overlay
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

            program = buildProgram(
                loadRaw(R.raw.blur_vertex),
                loadRaw(R.raw.blur_fragment)
            )
            GLES20.glUseProgram(program)

            positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            uBlurStrength = GLES20.glGetUniformLocation(program, "uBlurStrength")
            uBlurEdge = GLES20.glGetUniformLocation(program, "uBlurEdge")
            uOuterAlpha = GLES20.glGetUniformLocation(program, "uOuterAlpha")
            uTexSize = GLES20.glGetUniformLocation(program, "uTexSize")
            uTexture = GLES20.glGetUniformLocation(program, "uTexture")

            textureId = createTexture()
            // 1x1 placeholder texture so sampling is defined before content is set
            val placeholder = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            placeholder.eraseColor(0x00000000)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, placeholder, 0)
            placeholder.recycle()
            Log.i(TAG, "GLBlurView program linked, texture=$textureId")
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            if (pendingBitmap != null) {
                uploadBitmap(pendingBitmap!!)
                pendingBitmap = null
            }

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glUniform1i(uTexture, 0)

            GLES20.glUniform1f(uBlurStrength, blurStrength)
            GLES20.glUniform1f(uBlurEdge, blurEdge)
            GLES20.glUniform1f(uOuterAlpha, outerAlpha)
            GLES20.glUniform2f(uTexSize, texSize[0], texSize[1])

            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, quadBuffer)
            GLES20.glEnableVertexAttribArray(texCoordHandle)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texBuffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)

            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texCoordHandle)
        }

        fun updateTexture(bitmap: Bitmap) {
            pendingBitmap = bitmap
        }

        /** Upload content bitmap as GL texture; 1/4 downsample first to reduce GPU load. */
        private fun uploadBitmap(bitmap: Bitmap) {
            val w = maxOf(1, bitmap.width / 4)
            val h = maxOf(1, bitmap.height / 4)
            val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
            texSize[0] = w.toFloat()
            texSize[1] = h.toFloat()

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, small, 0)
            small.recycle()
        }

        fun release() {
            if (program != 0) GLES20.glDeleteProgram(program)
            if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            program = 0
            textureId = 0
        }
    }

    companion object {
        /** Compile and link shaders; returns usable program; throws and logs on failure. */
        fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
            val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
            val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
            val program = GLES20.glCreateProgram()
            check(program != 0) { "Failed to create GL program" }
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)

            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                throw RuntimeException("Program link failed: $log")
            }
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            return program
        }

        private fun compileShader(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            check(shader != 0) { "Failed to create shader type=$type" }
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw RuntimeException("Shader compile failed ($type): $log")
            }
            return shader
        }

        private fun createTexture(): Int {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            return ids[0]
        }
    }
}
