package com.goose.summerzf.core.projection

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Converts Android MediaProjection frames into two reusable 800x400 bitmaps.
 * The HUD renderer reads the published front bitmap while the capture thread
 * writes the other buffer, avoiding per-frame bitmap allocation.
 */
class HudMediaProjectionCapture(
    context: Context,
    private val runtime: HudProjectionRuntime,
    private val onProjectionStopped: () -> Unit
) {
    private val appContext = context.applicationContext
    private val projectionManager =
        appContext.getSystemService(MediaProjectionManager::class.java)

    private val stopping = AtomicBoolean(false)
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var callback: MediaProjection.Callback? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private val outputBitmaps = arrayOf(
        Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888),
        Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    )
    private val outputCanvases = outputBitmaps.map(::Canvas)
    private var nextOutputIndex = 0
    private var lastPublishedNs = 0L
    private var scratchBitmap: Bitmap? = null
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val sourceRect = Rect()
    private val destinationRect = Rect(0, 0, WIDTH, HEIGHT)

    val isActive: Boolean
        get() = mediaProjection != null

    @Synchronized
    fun start(resultCode: Int, data: Intent, mode: HudSceneMode) {
        require(resultCode == Activity.RESULT_OK) { "Screen capture permission was not granted" }
        require(mode == HudSceneMode.APP_CAST || mode == HudSceneMode.SCREEN_CAST)
        stop(notify = false)
        stopping.set(false)
        runtime.beginCapture(mode)

        val thread = HandlerThread("HudMediaProjection").apply { start() }
        val handler = Handler(thread.looper)
        captureThread = thread
        captureHandler = handler

        val projection = projectionManager.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("Android did not return a MediaProjection session")
        mediaProjection = projection

        val projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                if (!stopping.compareAndSet(false, true)) return
                runtime.endCapture("Screen sharing stopped by Android")
                releaseProjectionResources(stopProjection = false)
                onProjectionStopped()
            }
        }
        callback = projectionCallback
        projection.registerCallback(projectionCallback, handler)

        val reader = ImageReader.newInstance(
            WIDTH,
            HEIGHT,
            PixelFormat.RGBA_8888,
            3
        )
        imageReader = reader
        reader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val nowNs = System.nanoTime()
                if (nowNs - lastPublishedNs >= FRAME_INTERVAL_NS) {
                    publishImage(image)
                    lastPublishedNs = nowNs
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to process projection frame", error)
                runtime.fail("Screen capture frame failed: ${error.message}")
            } finally {
                image.close()
            }
        }, handler)

        virtualDisplay = projection.createVirtualDisplay(
            "CFMOTO-HUD-Capture",
            WIDTH,
            HEIGHT,
            appContext.resources.configuration.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )
    }

    @Synchronized
    fun stop(notify: Boolean = true) {
        if (!stopping.compareAndSet(false, true) && mediaProjection == null) return
        if (notify) runtime.endCapture()
        releaseProjectionResources(stopProjection = true)
    }

    private fun publishImage(image: Image) {
        val plane = image.planes.firstOrNull() ?: return
        if (plane.pixelStride <= 0 || plane.rowStride <= 0) return

        val paddedWidth = (plane.rowStride / plane.pixelStride).coerceAtLeast(image.width)
        val scratch = scratchBitmap
            ?.takeIf { it.width == paddedWidth && it.height == image.height && !it.isRecycled }
            ?: Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888).also {
                scratchBitmap = it
            }

        val buffer = plane.buffer
        buffer.rewind()
        scratch.copyPixelsFromBuffer(buffer)

        val outputIndex = nextOutputIndex
        nextOutputIndex = (nextOutputIndex + 1) % outputBitmaps.size
        val output = outputBitmaps[outputIndex]
        val canvas = outputCanvases[outputIndex]
        canvas.drawColor(Color.BLACK)
        sourceRect.set(0, 0, image.width.coerceAtMost(scratch.width), image.height)
        canvas.drawBitmap(scratch, sourceRect, destinationRect, bitmapPaint)
        runtime.publishFrame(output)
    }

    @Synchronized
    private fun releaseProjectionResources(stopProjection: Boolean) {
        imageReader?.setOnImageAvailableListener(null, null)
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null

        val projection = mediaProjection
        mediaProjection = null
        callback?.let { cb ->
            try {
                projection?.unregisterCallback(cb)
            } catch (_: Exception) {
            }
        }
        callback = null
        if (stopProjection) {
            try {
                projection?.stop()
            } catch (_: Exception) {
            }
        }

        captureHandler = null
        captureThread?.quitSafely()
        captureThread = null
    }

    private companion object {
        const val TAG = "HudMediaProjection"
        const val WIDTH = 800
        const val HEIGHT = 400
        const val FRAME_INTERVAL_NS = 1_000_000_000L / 30L
    }
}
