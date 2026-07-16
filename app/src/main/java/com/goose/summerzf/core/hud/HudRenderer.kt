package com.goose.summerzf.core.hud

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.view.Surface

data class HudRendererStats(
    val totalFrames: Long,
    val fps: Double,
    val missedDeadlines: Long
)

open class HudRenderer(
    private val surface: Surface,
    private val fps: Int = 30,
    private val onStats: (HudRendererStats) -> Unit = {},
    private val onRenderError: (Throwable) -> Unit = {}
) {
    @Volatile
    private var running = false
    private var renderThread: Thread? = null

    fun start() {
        if (running) return
        running = true
        renderThread = Thread(::renderLoop, "HudRenderer").apply { start() }
    }

    fun stop() {
        running = false
        renderThread?.interrupt()
        renderThread?.join()
        renderThread = null
    }

    protected open fun onDrawFrame(
        canvas: Canvas,
        frameIndex: Long,
        elapsedNs: Long
    ) {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 48f
        }
        canvas.drawColor(Color.DKGRAY)
        canvas.drawText("HUD frame: $frameIndex", 50f, 100f, textPaint)
    }

    private fun renderLoop() {
        val frameDurationNs = 1_000_000_000L / fps
        val startTime = System.nanoTime()
        var nextFrameTime = startTime
        var frameCount = 0L
        var intervalFrames = 0L
        var missedDeadlines = 0L
        var statsStartedNs = startTime

        while (running) {
            var now = System.nanoTime()
            if (now < nextFrameTime) {
                val remainingNs = nextFrameTime - now
                try {
                    Thread.sleep(
                        remainingNs / 1_000_000L,
                        (remainingNs % 1_000_000L).toInt()
                    )
                } catch (_: InterruptedException) {
                    if (!running) break
                }
                now = System.nanoTime()
            }

            val latenessNs = now - nextFrameTime
            if (latenessNs >= frameDurationNs) {
                val missed = latenessNs / frameDurationNs
                missedDeadlines += missed
                nextFrameTime += missed * frameDurationNs
            }

            var canvas: Canvas? = null
            try {
                canvas = surface.lockCanvas(null)
                onDrawFrame(canvas, frameCount, System.nanoTime() - startTime)
                frameCount++
                intervalFrames++
            } catch (t: Throwable) {
                if (running) {
                    Log.e("HudRenderer", "Render error, stopping renderer", t)
                    onRenderError(t)
                }
                break
            } finally {
                if (canvas != null) {
                    try {
                        surface.unlockCanvasAndPost(canvas)
                    } catch (_: Throwable) {
                        // The encoder surface may already be gone during teardown.
                    }
                }
            }

            nextFrameTime += frameDurationNs

            val statsNowNs = System.nanoTime()
            val statsElapsedNs = statsNowNs - statsStartedNs
            if (statsElapsedNs >= 1_000_000_000L) {
                val elapsedSeconds = statsElapsedNs / 1_000_000_000.0
                onStats(
                    HudRendererStats(
                        totalFrames = frameCount,
                        fps = intervalFrames / elapsedSeconds,
                        missedDeadlines = missedDeadlines
                    )
                )
                intervalFrames = 0
                statsStartedNs = statsNowNs
            }
        }
    }
}
