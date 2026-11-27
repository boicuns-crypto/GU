package com.example.core.projection

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hardware-accelerated OpenGL ES 2.0 `SurfaceTexture` -> `MediaCodec` (`encoderInputSurface`)
 * full-screen scaler for [AppMode.MIRRORING].
 *
 * Why this exists:
 * Android's `MediaProjection` (`VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR`) always fits the physical
 * smartphone screen (`screenWidth × screenHeight`, e.g. S26 Ultra `3120×1440` `19.5:9`) inside the
 * `VirtualDisplay` surface (`800×480` `5:3`, `1024×800` `5:4`, `1024×600`, or `1280×720` `16:9`)
 * with uniform aspect-ratio scaling (`min(vdW/screenW, vdH/screenH)`), leaving letterbox/pillarbox
 * black bars at the top and bottom (or sides).
 *
 * [FullScreenMirrorGlScaler] provides an intermediate [captureSurface] (`SurfaceTexture`) to
 * `VirtualDisplay` and samples **only the active drawn phone region** (`offsetX..offsetX+drawnW`,
 * `offsetY..offsetY+drawnH`), rendering it across **100% of the H.264 encoder surface**
 * (`0..targetWidth`, `0..targetHeight`) with zero black bars at any resolution (`800×480`,
 * `1024×600`, `1024×800`, `1280×720`).
 */
class FullScreenMirrorGlScaler(
    private val encoderOutputSurface: Surface,
    initialWidth: Int,
    initialHeight: Int,
    initialPhoneWidth: Int,
    initialPhoneHeight: Int,
    initialScaleMode: MirrorVideoScaleMode = MirrorVideoScaleMode.CENTER_CROP_FULLSCREEN
) : SurfaceTexture.OnFrameAvailableListener {

    @Volatile
    var targetWidth: Int = initialWidth.coerceAtLeast(1)
        private set

    @Volatile
    var targetHeight: Int = initialHeight.coerceAtLeast(1)
        private set

    @Volatile
    var phoneScreenWidth: Int = initialPhoneWidth.coerceAtLeast(1)
        private set

    @Volatile
    var phoneScreenHeight: Int = initialPhoneHeight.coerceAtLeast(1)
        private set

    @Volatile
    var scaleMode: MirrorVideoScaleMode = initialScaleMode
        private set

    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var oesTextureId: Int = 0
    private var surfaceTexture: SurfaceTexture? = null

    @Volatile
    var captureSurface: Surface? = null
        private set

    private var programId: Int = 0
    private var aPositionLoc: Int = -1
    private var aTexCoordLoc: Int = -1
    private var uTexMatrixLoc: Int = -1
    private var uCropRectLoc: Int = -1

    private val texMatrix = FloatArray(16)
    private val released = AtomicBoolean(false)

    @Volatile
    var isInitialized: Boolean = false
        private set

    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(FULL_QUAD_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(FULL_QUAD_COORDS)
            position(0)
        }

    /**
     * Starts the dedicated GL thread, initializes EGL against [encoderOutputSurface],
     * and creates the [captureSurface] to pass to `MediaProjection.createVirtualDisplay(...)`.
     * Returns the [captureSurface] if EGL/GLES initialization succeeded, or `null` on fallback/headless JVM.
     */
    fun start(): Surface? {
        val thread = HandlerThread("CarLifeFullScreenGlScaler").apply { start() }
        val handler = Handler(thread.looper)
        glThread = thread
        glHandler = handler

        val latch = CountDownLatch(1)
        var createdSurface: Surface? = null

        handler.post {
            try {
                if (initEglAndGles()) {
                    val st = SurfaceTexture(oesTextureId)
                    st.setDefaultBufferSize(targetWidth, targetHeight)
                    st.setOnFrameAvailableListener(this, handler)
                    val surf = Surface(st)
                    surfaceTexture = st
                    captureSurface = surf
                    createdSurface = surf
                    isInitialized = true
                }
            } catch (t: Throwable) {
                Log.w(TAG, "FullScreenMirrorGlScaler init failed, falling back to direct surface: ${t.message}")
                cleanupGlLocked()
            } finally {
                latch.countDown()
            }
        }

        runCatching { latch.await(1500L, TimeUnit.MILLISECONDS) }
        if (createdSurface == null) {
            release()
        }
        return createdSurface
    }

    fun updateGeometry(
        newTargetWidth: Int,
        newTargetHeight: Int,
        newPhoneWidth: Int,
        newPhoneHeight: Int,
        newScaleMode: MirrorVideoScaleMode = scaleMode
    ) {
        val safeW = newTargetWidth.coerceAtLeast(1)
        val safeH = newTargetHeight.coerceAtLeast(1)
        val sizeChanged = (safeW != targetWidth || safeH != targetHeight)
        targetWidth = safeW
        targetHeight = safeH
        scaleMode = newScaleMode
        if (newPhoneWidth > 0 && newPhoneHeight > 0) {
            phoneScreenWidth = newPhoneWidth
            phoneScreenHeight = newPhoneHeight
        }
        if (sizeChanged) {
            glHandler?.post {
                runCatching { surfaceTexture?.setDefaultBufferSize(safeW, safeH) }
            }
        }
    }

    fun updateScaleMode(newScaleMode: MirrorVideoScaleMode) {
        scaleMode = newScaleMode
    }

    override fun onFrameAvailable(st: SurfaceTexture?) {
        if (released.get() || !isInitialized) return
        val activeSt = surfaceTexture ?: return
        try {
            activeSt.updateTexImage()
            activeSt.getTransformMatrix(texMatrix)

            val outW = targetWidth.coerceAtLeast(1)
            val outH = targetHeight.coerceAtLeast(1)
            val crop = when (scaleMode) {
                MirrorVideoScaleMode.CENTER_CROP_FULLSCREEN -> computeCenterCrop1to1Rect(
                    captureWidth = outW,
                    captureHeight = outH,
                    screenWidth = phoneScreenWidth,
                    screenHeight = phoneScreenHeight
                )
                MirrorVideoScaleMode.FIT_NATIVE -> ActiveCropRect(0f, 0f, 1f, 1f)
            }

            GLES20.glViewport(0, 0, outW, outH)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(programId)

            vertexBuffer.position(0)
            GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, vertexBuffer)
            GLES20.glEnableVertexAttribArray(aPositionLoc)

            vertexBuffer.position(2)
            GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, vertexBuffer)
            GLES20.glEnableVertexAttribArray(aTexCoordLoc)

            GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
            GLES20.glUniform4f(
                uCropRectLoc,
                crop.uOffset,
                crop.vOffset,
                crop.uScale,
                crop.vScale
            )

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        } catch (t: Throwable) {
            Log.w(TAG, "Frame render error in FullScreenMirrorGlScaler: ${t.message}")
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        val handler = glHandler
        val thread = glThread
        glHandler = null
        glThread = null

        if (handler != null && thread != null && thread.isAlive) {
            val latch = CountDownLatch(1)
            handler.post {
                try {
                    cleanupGlLocked()
                } finally {
                    latch.countDown()
                }
            }
            runCatching { latch.await(800L, TimeUnit.MILLISECONDS) }
            runCatching { thread.quitSafely() }
        }
    }

    private fun initEglAndGles(): Boolean {
        Matrix.setIdentityM(texMatrix, 0)
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0) ||
            numConfigs[0] <= 0
        ) {
            return false
        }
        val config = configs[0] ?: return false

        val ctxAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) return false

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, encoderOutputSurface, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) return false

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return false

        programId = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (programId == 0) return false

        aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")
        uTexMatrixLoc = GLES20.glGetUniformLocation(programId, "uTexMatrix")
        uCropRectLoc = GLES20.glGetUniformLocation(programId, "uCropRect")

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        oesTextureId = texIds[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        return true
    }

    private fun cleanupGlLocked() {
        isInitialized = false
        runCatching { surfaceTexture?.setOnFrameAvailableListener(null) }
        runCatching { captureSurface?.release() }
        captureSurface = null
        runCatching { surfaceTexture?.release() }
        surfaceTexture = null

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            runCatching {
                if (oesTextureId != 0) {
                    GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
                    oesTextureId = 0
                }
                if (programId != 0) {
                    GLES20.glDeleteProgram(programId)
                    programId = 0
                }
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    eglSurface = EGL14.EGL_NO_SURFACE
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                    eglContext = EGL14.EGL_NO_CONTEXT
                }
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) return 0
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (fragmentShader == 0) return 0

        val program = GLES20.glCreateProgram()
        if (program == 0) return 0
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun loadShader(shaderType: Int, source: String): Int {
        val shader = GLES20.glCreateShader(shaderType)
        if (shader == 0) return 0
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    /**
     * Normalized UV sub-rectangle `(uOffset, vOffset, uScale, vScale)` describing the active
     * phone screen content inside the `VirtualDisplay` frame so the shader strips top/bottom
     * (or left/right) black bars and fills 100% of the Head Unit screen.
     */
    data class ActiveCropRect(
        val uOffset: Float,
        val vOffset: Float,
        val uScale: Float,
        val vScale: Float
    )

    companion object {
        private const val TAG = "FullScreenMirrorGl"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val STRIDE_BYTES = 4 * 4

        // Triangle strip covering [-1..1] with standard UV [0..1]
        private val FULL_QUAD_COORDS = floatArrayOf(
            -1.0f, -1.0f, 0.0f, 0.0f,
             1.0f, -1.0f, 1.0f, 0.0f,
            -1.0f,  1.0f, 0.0f, 1.0f,
             1.0f,  1.0f, 1.0f, 1.0f
        )

        private const val VERTEX_SHADER = """
            uniform mat4 uTexMatrix;
            uniform vec4 uCropRect; // (uOffset, vOffset, uScale, vScale)
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vec2 croppedUv = vec2(
                    uCropRect.x + aTexCoord.x * uCropRect.z,
                    uCropRect.y + aTexCoord.y * uCropRect.w
                );
                vTexCoord = (uTexMatrix * vec4(croppedUv, 0.0, 1.0)).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        /**
         * Computes the normalized `[0..1]` UV sub-rectangle where Android `MediaProjection`
         * (`VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR`) draws the physical phone screen inside a
         * `captureWidth × captureHeight` `VirtualDisplay`.
         *
         * - In landscape (`screenWidth > screenHeight`, e.g. `3120×1440` inside `1280×720`, `1024×800`, or `800×480`):
         *   strips the top and bottom letterbox black bars (`vOffset > 0`, `vScale < 1`, `uOffset = 0`, `uScale = 1`)
         *   so the landscape phone screen fills 100% of the Head Unit display across all resolutions!
         * - In portrait (`screenWidth < screenHeight`): preserves the full frame (`0, 0, 1, 1`) so a vertical
         *   phone screen is not distorted horizontally by 3.8x.
         */
        fun computeNormalizedActiveCropRect(
            captureWidth: Int,
            captureHeight: Int,
            screenWidth: Int,
            screenHeight: Int
        ): ActiveCropRect {
            val safeCapW = captureWidth.coerceAtLeast(1).toFloat()
            val safeCapH = captureHeight.coerceAtLeast(1).toFloat()
            val safeScrW = screenWidth.coerceAtLeast(1).toFloat()
            val safeScrH = screenHeight.coerceAtLeast(1).toFloat()

            // Only stretch-to-fill when the phone is in landscape orientation (or square),
            // stripping top/bottom letterbox bars at 800x480, 1024x600, 1024x800, and 1280x720.
            if (safeScrW < safeScrH) {
                return ActiveCropRect(0f, 0f, 1f, 1f)
            }

            val scale = minOf(safeCapW / safeScrW, safeCapH / safeScrH)
            val drawnW = (safeScrW * scale).coerceIn(1f, safeCapW)
            val drawnH = (safeScrH * scale).coerceIn(1f, safeCapH)

            val uScale = (drawnW / safeCapW).coerceIn(0.1f, 1f)
            val vScale = (drawnH / safeCapH).coerceIn(0.1f, 1f)
            val uOffset = ((1f - uScale) * 0.5f).coerceIn(0f, 0.45f)
            val vOffset = ((1f - vScale) * 0.5f).coerceIn(0f, 0.45f)

            return ActiveCropRect(
                uOffset = uOffset,
                vOffset = vOffset,
                uScale = uScale,
                vScale = vScale
            )
        }

        /**
         * Variant 2 ([MirrorVideoScaleMode.CENTER_CROP_FULLSCREEN]):
         * Computes a strictly 1:1 aspect-ratio-preserving (`uScale == vScale`) center-crop UV sub-rectangle.
         *
         * Because the input `VirtualDisplay` texture and the output encoder surface share the same
         * `captureWidth × captureHeight` dimensions, setting `uScale = vScale = uniformSpan` magnifies
         * horizontal and vertical pixels by the exact same factor:
         * - Top and bottom letterbox black bars are 100% removed.
         * - Circular buttons and icons remain strictly circular (zero oval distortion).
         * - Only the excess left/right edges of a `19.5:9` phone screen (where the camera cutout and
         *   system navigation bar sit) are cropped out.
         */
        fun computeCenterCrop1to1Rect(
            captureWidth: Int,
            captureHeight: Int,
            screenWidth: Int,
            screenHeight: Int
        ): ActiveCropRect {
            val safeCapW = captureWidth.coerceAtLeast(1).toFloat()
            val safeCapH = captureHeight.coerceAtLeast(1).toFloat()
            val safeScrW = screenWidth.coerceAtLeast(1).toFloat()
            val safeScrH = screenHeight.coerceAtLeast(1).toFloat()

            if (safeScrW < safeScrH) {
                return ActiveCropRect(0f, 0f, 1f, 1f)
            }

            val fitScale = minOf(safeCapW / safeScrW, safeCapH / safeScrH)
            val drawnW = (safeScrW * fitScale).coerceIn(1f, safeCapW)
            val drawnH = (safeScrH * fitScale).coerceIn(1f, safeCapH)

            val uniformSpan = minOf(drawnW / safeCapW, drawnH / safeCapH).coerceIn(0.1f, 1f)
            val offset = ((1f - uniformSpan) * 0.5f).coerceIn(0f, 0.45f)

            return ActiveCropRect(
                uOffset = offset,
                vOffset = offset,
                uScale = uniformSpan,
                vScale = uniformSpan
            )
        }
    }
}
