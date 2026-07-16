package com.goose.summerzf.core.projection

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Hardware-decoder-compatible frame sink for the private Android Auto receiver.
 *
 * Android Auto's MediaCodec decoder renders into an external OES texture. A
 * small EGL pipeline samples that texture, reads the 800x480 frame at a capped
 * rate, and publishes reusable bitmaps to the existing 800x400 HUD scene.
 */
class AndroidAutoFrameSink(
    private val runtime: HudProjectionRuntime,
    private val log: (String) -> Unit,
    private val sourceWidth: Int = 800,
    private val sourceHeight: Int = 480,
    private val maxPublishFps: Int = 20
) {
    private val thread = HandlerThread("AndroidAutoFrameSink")
    private lateinit var handler: Handler

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null

    private val readBuffer = ByteBuffer
        .allocateDirect(sourceWidth * sourceHeight * 4)
        .order(ByteOrder.nativeOrder())
    private val rgbaBytes = ByteArray(sourceWidth * sourceHeight * 4)
    private val argbPixels = IntArray(sourceWidth * sourceHeight)
    private val bitmaps = Array(3) {
        Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888)
    }
    private var bitmapIndex = 0
    private var lastPublishedMs = 0L

    lateinit var surface: Surface
        private set

    fun start() {
        if (thread.isAlive) return
        thread.start()
        handler = Handler(thread.looper)
        val ready = CountDownLatch(1)
        var failure: Throwable? = null
        handler.post {
            try {
                initializeGl()
            } catch (error: Throwable) {
                failure = error
            } finally {
                ready.countDown()
            }
        }
        check(ready.await(5, TimeUnit.SECONDS)) { "Timed out initializing Android Auto frame sink" }
        failure?.let { throw IllegalStateException("Unable to initialize Android Auto frame sink", it) }
    }

    fun stop() {
        if (!thread.isAlive) return
        val done = CountDownLatch(1)
        handler.post {
            try {
                releaseGl()
            } finally {
                done.countDown()
            }
        }
        done.await(2, TimeUnit.SECONDS)
        thread.quitSafely()
        runCatching { thread.join(1_000) }
    }

    private fun initializeGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val versions = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, versions, 0, versions, 1)) {
            "eglInitialize failed"
        }

        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)
        val configAttributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        check(
            EGL14.eglChooseConfig(
                eglDisplay,
                configAttributes,
                0,
                configs,
                0,
                configs.size,
                configCount,
                0
            ) && configCount[0] > 0
        ) { "No compatible EGL config" }
        val config = requireNotNull(configs[0])

        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        eglSurface = EGL14.eglCreatePbufferSurface(
            eglDisplay,
            config,
            intArrayOf(
                EGL14.EGL_WIDTH, sourceWidth,
                EGL14.EGL_HEIGHT, sourceHeight,
                EGL14.EGL_NONE
            ),
            0
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "eglMakeCurrent failed"
        }

        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        textureId = createExternalTexture()
        surfaceTexture = SurfaceTexture(textureId).apply {
            setDefaultBufferSize(sourceWidth, sourceHeight)
            setOnFrameAvailableListener({ renderLatestFrame() }, handler)
        }
        surface = Surface(requireNotNull(surfaceTexture))
        log("[AA] decoder frame sink ready: ${sourceWidth}x$sourceHeight")
    }

    private fun renderLatestFrame() {
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
        } catch (error: Throwable) {
            log("[AA] updateTexImage failed: ${error.message}")
            return
        }

        val now = SystemClock.elapsedRealtime()
        val minimumInterval = max(1L, 1_000L / maxPublishFps)
        if (now - lastPublishedMs < minimumInterval) return
        lastPublishedMs = now

        val transform = FloatArray(16)
        st.getTransformMatrix(transform)
        drawExternalTexture(transform)
        publishReadback()
    }

    private fun drawExternalTexture(transform: FloatArray) {
        GLES20.glViewport(0, 0, sourceWidth, sourceHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        val positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        val textureLocation = GLES20.glGetAttribLocation(program, "aTexCoord")
        val matrixLocation = GLES20.glGetUniformLocation(program, "uTexMatrix")
        val samplerLocation = GLES20.glGetUniformLocation(program, "sTexture")

        VERTICES.position(0)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, VERTICES)

        TEX_COORDS.position(0)
        GLES20.glEnableVertexAttribArray(textureLocation)
        GLES20.glVertexAttribPointer(textureLocation, 2, GLES20.GL_FLOAT, false, 0, TEX_COORDS)

        GLES20.glUniformMatrix4fv(matrixLocation, 1, false, transform, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(samplerLocation, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glFinish()

        GLES20.glDisableVertexAttribArray(positionLocation)
        GLES20.glDisableVertexAttribArray(textureLocation)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    private fun publishReadback() {
        readBuffer.clear()
        GLES20.glReadPixels(
            0,
            0,
            sourceWidth,
            sourceHeight,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            readBuffer
        )
        readBuffer.position(0)
        readBuffer.get(rgbaBytes)

        var destination = 0
        for (y in 0 until sourceHeight) {
            val sourceY = sourceHeight - 1 - y
            var source = sourceY * sourceWidth * 4
            for (x in 0 until sourceWidth) {
                val r = rgbaBytes[source].toInt() and 0xff
                val g = rgbaBytes[source + 1].toInt() and 0xff
                val b = rgbaBytes[source + 2].toInt() and 0xff
                val a = rgbaBytes[source + 3].toInt() and 0xff
                argbPixels[destination++] = Color.argb(a, r, g, b)
                source += 4
            }
        }

        val bitmap = bitmaps[bitmapIndex]
        bitmapIndex = (bitmapIndex + 1) % bitmaps.size
        bitmap.setPixels(argbPixels, 0, sourceWidth, 0, 0, sourceWidth, sourceHeight)
        runtime.publishAndroidAutoFrame(bitmap)
    }

    private fun releaseGl() {
        if (::surface.isInitialized) runCatching { surface.release() }
        surfaceTexture?.release()
        surfaceTexture = null
        if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        if (program != 0) GLES20.glDeleteProgram(program)
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        bitmaps.forEach { it.recycle() }
    }

    private fun createExternalTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val id = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        return id
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val result = GLES20.glCreateProgram()
        GLES20.glAttachShader(result, vertex)
        GLES20.glAttachShader(result, fragment)
        GLES20.glLinkProgram(result)
        val status = IntArray(1)
        GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
        val message = GLES20.glGetProgramInfoLog(result)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        check(status[0] == GLES20.GL_TRUE) { "Unable to link Android Auto shader: $message" }
        return result
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        val message = GLES20.glGetShaderInfoLog(shader)
        check(status[0] == GLES20.GL_TRUE) { "Unable to compile Android Auto shader: $message" }
        return shader
    }

    private companion object {
        val VERTICES: FloatBuffer = floatBufferOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )
        val TEX_COORDS: FloatBuffer = floatBufferOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )

        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        fun floatBufferOf(vararg values: Float): FloatBuffer = ByteBuffer
            .allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }
    }
}
