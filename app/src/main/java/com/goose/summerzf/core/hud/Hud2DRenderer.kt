package com.goose.summerzf.core.hud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.view.Surface
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.withTranslation
import com.goose.summerzf.R

enum class HudScaleMode {
    STRETCH, // scale x and y independently - can cause distortion
    FIT,     // uniform scale - keeping aspect ratio, letterbox if needed
    FILL     // uniform scale - fill entire canvas, crop if needed
}

enum class HudFontStyle { REGULAR, BOLD, ITALIC, BOLD_ITALIC }

enum class HudBitmapScaleMode { FIT, CROP, STRETCH }

data class HudScene(
    val clearColor: Int = Color.TRANSPARENT,
    val elements: List<HudElement> = emptyList(),

    // virtual res
    val designWidth: Float,
    val designHeight: Float,
    val scaleMode: HudScaleMode = HudScaleMode.FIT
)

sealed class HudElement {
    abstract val z: Int // Z order: higher on top

    data class Rect(
        override val z: Int,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val color: Int,
        val cornerRadius: Float = 0f,
        val strokeWidth: Float = 0f // 0 = fill
    ) : HudElement()

    data class Line(
        override val z: Int,
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val color: Int,
        val strokeWidth: Float
    ) : HudElement()

    data class Circle(
        override val z: Int,
        val cx: Float,
        val cy: Float,
        val radius: Float,
        val color: Int,
        val strokeWidth: Float = 0f // 0 = fill
    ) : HudElement()

    data class Text(
        override val z: Int,
        val text: String,
        val x: Float,
        val y: Float,
        val sizePx: Float,
        val color: Int,
        val fontStyle: HudFontStyle = HudFontStyle.REGULAR,
        val align: android.graphics.Paint.Align = android.graphics.Paint.Align.LEFT
    ) : HudElement()

    data class Image(
        override val z: Int,
        @androidx.annotation.DrawableRes
        val resId: Int,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val alpha: Int = 255
    ) : HudElement()

    data class AnimatedImage(
        override val z: Int,
        val frameResIds: List<Int>,
        val frameDurationMs: Long,
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float
    ) : HudElement()

    data class TextDynamic(
        override val z: Int,
        val prefix: String,
        val value: DoubleBufferedValue<String>,
        val x: Float,
        val y: Float,
        val color: Int,
        val sizePx: Float,
        val fontStyle: HudFontStyle = HudFontStyle.REGULAR,
        val align: Paint.Align = Paint.Align.LEFT
    ) : HudElement() {
        internal var prefixWidth = Float.NaN
    }


    data class BitmapDynamic(
        override val z: Int,
        val value: DoubleBufferedValue<Bitmap?>,
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        val scaleMode: HudBitmapScaleMode = HudBitmapScaleMode.CROP,
        val cornerRadius: Float = 0f,
        val alpha: Int = 255,
        val placeholderColor: Int = Color.DKGRAY
    ) : HudElement()

    data class TextDynamicBox(
        override val z: Int,
        val value: DoubleBufferedValue<String>,
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        val color: Int,
        val sizePx: Float,
        val maxLines: Int = 1,
        val lineSpacing: Float = 1.2f,
        val fontStyle: HudFontStyle = HudFontStyle.REGULAR,
        val align: Paint.Align = Paint.Align.LEFT
    ) : HudElement() {
        internal var cachedText: String? = null
        internal var cachedLines: List<String> = emptyList()
    }

    data class SineWave(
        override val z: Int,
        val x: Float,
        val y: Float,              // vertical centerline (in design coords)
        val width: Float,
        val amplitude: Float,      // in design coords (px)
        val wavelength: Float,     // in design coords (px per cycle)
        val phaseSpeedRadPerSec: Float, // animation speed (radians/sec)
        val samples: Int,          // number of points along the width (>= 2)
        val color: Int,
        val strokeWidth: Float,
        val alpha: Int = 255,
    ) : HudElement() {
        // Reused buffer: (samples-1) segments, each segment is 4 floats: x1,y1,x2,y2
        internal val lineBuf: FloatArray = FloatArray(((maxOf(samples, 2) - 1) * 4))
    }
}

class DoubleBufferedValue<T>(initial: T) {
    private var a: T = initial
    private var b: T = initial
    @Volatile private var frontIsA = true

    fun publish(value: T) {
        if (frontIsA) b = value else a = value
        frontIsA = !frontIsA
    }

    fun read(): T = if (frontIsA) a else b
}

class Hud2DSceneRenderer (
    private val context: Context,
    private val assets: HudAssets
) {
    // Reuse paints to avoid allocations in the render loop
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2f
    }
    private val tmpRectF = RectF()

    private val ubuntuMonoRegular = ResourcesCompat.getFont(context, R.font.ubuntu_mono_r)
    private val ubuntuMonoBold = ResourcesCompat.getFont(context, R.font.ubuntu_mono_b)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        typeface = ubuntuMonoRegular
    }

    private data class Quadruple(
        val a: Float,
        val b: Float,
        val c: Float,
        val d: Float
    )

    private fun drawSceneElements(canvas: Canvas, ordered: List<HudElement>, elapsedNs: Long) {
        ordered.forEach { element ->
            when (element) {
                is HudElement.Rect -> drawRect(canvas, element)
                is HudElement.Circle -> drawCircle(canvas, element)
                is HudElement.Image -> drawImage(canvas, element)
                is HudElement.Line -> drawLine(canvas, element)
                is HudElement.Text -> drawText(canvas, element)
                is HudElement.AnimatedImage -> drawAnimatedImage(canvas, element, elapsedNs)
                is HudElement.TextDynamic -> drawTextDynamic(canvas, element)
                is HudElement.BitmapDynamic -> drawBitmapDynamic(canvas, element)
                is HudElement.TextDynamicBox -> drawTextDynamicBox(canvas, element)
                is HudElement.SineWave -> drawSineWave(canvas, element, elapsedNs)
            }
        }
    }

    private fun drawTextDynamic(canvas: Canvas, e: HudElement.TextDynamic) {
        textPaint.color = e.color
        textPaint.textSize = e.sizePx
        textPaint.textAlign = e.align
        textPaint.typeface = when (e.fontStyle) {
            HudFontStyle.REGULAR -> ubuntuMonoRegular
            HudFontStyle.BOLD -> ubuntuMonoBold
            HudFontStyle.ITALIC -> ResourcesCompat.getFont(context, R.font.ubuntu_mono_ri)
            HudFontStyle.BOLD_ITALIC -> ResourcesCompat.getFont(context, R.font.ubuntu_mono_bi)
        }

        if (e.prefixWidth.isNaN()) {
            e.prefixWidth = textPaint.measureText(e.prefix)
        }

        canvas.drawText(e.prefix, e.x, e.y, textPaint)
        canvas.drawText(e.value.read(), e.x + e.prefixWidth, e.y, textPaint)
    }

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clipPath = Path()
    private val srcRect = Rect()

    private fun drawBitmapDynamic(canvas: Canvas, e: HudElement.BitmapDynamic) {
        val bitmap = e.value.read()
        tmpRectF.set(e.left, e.top, e.left + e.width, e.top + e.height)
        if (bitmap == null || bitmap.isRecycled) {
            fillPaint.color = e.placeholderColor
            if (e.cornerRadius > 0f) {
                canvas.drawRoundRect(tmpRectF, e.cornerRadius, e.cornerRadius, fillPaint)
            } else {
                canvas.drawRect(tmpRectF, fillPaint)
            }
            return
        }

        val saveCount = canvas.save()
        if (e.cornerRadius > 0f) {
            clipPath.reset()
            clipPath.addRoundRect(tmpRectF, e.cornerRadius, e.cornerRadius, Path.Direction.CW)
            canvas.clipPath(clipPath)
        } else {
            canvas.clipRect(tmpRectF)
        }

        bitmapPaint.alpha = e.alpha.coerceIn(0, 255)
        val bitmapRatio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
        val targetRatio = e.width / e.height.coerceAtLeast(1f)

        when (e.scaleMode) {
            HudBitmapScaleMode.STRETCH -> {
                srcRect.set(0, 0, bitmap.width, bitmap.height)
                canvas.drawBitmap(bitmap, srcRect, tmpRectF, bitmapPaint)
            }
            HudBitmapScaleMode.FIT -> {
                srcRect.set(0, 0, bitmap.width, bitmap.height)
                val destination = RectF(tmpRectF)
                if (bitmapRatio > targetRatio) {
                    val fittedHeight = e.width / bitmapRatio
                    destination.top += (e.height - fittedHeight) / 2f
                    destination.bottom = destination.top + fittedHeight
                } else {
                    val fittedWidth = e.height * bitmapRatio
                    destination.left += (e.width - fittedWidth) / 2f
                    destination.right = destination.left + fittedWidth
                }
                canvas.drawBitmap(bitmap, srcRect, destination, bitmapPaint)
            }
            HudBitmapScaleMode.CROP -> {
                if (bitmapRatio > targetRatio) {
                    val sourceWidth = (bitmap.height * targetRatio).toInt().coerceAtLeast(1)
                    val left = ((bitmap.width - sourceWidth) / 2).coerceAtLeast(0)
                    srcRect.set(left, 0, left + sourceWidth, bitmap.height)
                } else {
                    val sourceHeight = (bitmap.width / targetRatio).toInt().coerceAtLeast(1)
                    val top = ((bitmap.height - sourceHeight) / 2).coerceAtLeast(0)
                    srcRect.set(0, top, bitmap.width, top + sourceHeight)
                }
                canvas.drawBitmap(bitmap, srcRect, tmpRectF, bitmapPaint)
            }
        }
        canvas.restoreToCount(saveCount)
    }

    private fun drawTextDynamicBox(canvas: Canvas, e: HudElement.TextDynamicBox) {
        textPaint.color = e.color
        textPaint.textSize = e.sizePx
        textPaint.textAlign = e.align
        textPaint.typeface = when (e.fontStyle) {
            HudFontStyle.REGULAR -> ubuntuMonoRegular
            HudFontStyle.BOLD -> ubuntuMonoBold
            HudFontStyle.ITALIC -> ResourcesCompat.getFont(context, R.font.ubuntu_mono_ri)
            HudFontStyle.BOLD_ITALIC -> ResourcesCompat.getFont(context, R.font.ubuntu_mono_bi)
        }

        val text = e.value.read()
        if (e.cachedText != text) {
            e.cachedText = text
            e.cachedLines = wrapText(text, e.width, e.maxLines, textPaint)
        }
        val lineHeight = e.sizePx * e.lineSpacing
        e.cachedLines.forEachIndexed { index, line ->
            val baseline = e.top + e.sizePx + index * lineHeight
            if (baseline <= e.top + e.height) {
                val x = when (e.align) {
                    Paint.Align.LEFT -> e.left
                    Paint.Align.CENTER -> e.left + e.width / 2f
                    Paint.Align.RIGHT -> e.left + e.width
                }
                canvas.drawText(line, x, baseline, textPaint)
            }
        }
    }

    private fun wrapText(
        source: String,
        maxWidth: Float,
        maxLines: Int,
        paint: Paint
    ): List<String> {
        if (source.isBlank() || maxLines <= 0) return emptyList()
        val words = source.trim().split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth) {
                current = candidate
                continue
            }
            if (current.isNotEmpty()) lines += current
            current = word
            if (lines.size == maxLines) break
        }
        if (lines.size < maxLines && current.isNotEmpty()) lines += current
        if (lines.size > maxLines) return lines.take(maxLines)
        if (lines.size == maxLines) {
            val consumed = lines.joinToString(" ")
            if (consumed.length < source.trim().length) {
                var last = lines.last()
                while (last.isNotEmpty() && paint.measureText("$last…") > maxWidth) {
                    last = last.dropLast(1)
                }
                lines[lines.lastIndex] = "$last…"
            }
        }
        return lines
    }

    private fun drawAnimatedImage(canvas: Canvas, e: HudElement.AnimatedImage, elapsedNs: Long) {
        val elapsedMs = elapsedNs / 1_000_000
        val frameIndex = ((elapsedMs / e.frameDurationMs) % e.frameResIds.size).toInt()

        val frameResId = e.frameResIds[frameIndex]
        val drawable = assets.drawable(frameResId)

        drawable.setBounds(
            e.left.toInt(),
            e.top.toInt(),
            (e.left + e.width).toInt(),
            (e.top + e.height).toInt()
        )
        drawable.draw(canvas)
    }

    fun render(canvas: Canvas, scene: HudScene, elements: List<HudElement>, elapsedNs: Long) {
        // clear the canvas
        canvas.drawColor(scene.clearColor, PorterDuff.Mode.SRC)

        if (elements.isEmpty()) return // skip if no elements on scene

        // canvas sizes
        val cw = canvas.width.toFloat()
        val ch = canvas.height.toFloat()

        // design sizes
        val dw = scene.designWidth
        val dh = scene.designHeight

        // scale
        val sx = cw/dw
        val sy = ch/dh
        val (scaleX, scaleY, tx, ty) = when (scene.scaleMode) {
            HudScaleMode.FIT -> {
                val s = minOf(sx, sy)
                val scaledW = dw * s
                val scaledH = dh * s
                val txFit = (cw - scaledW)/2f
                val tyFit = (ch - scaledH)/2f
                Quadruple(s, s, txFit, tyFit)
            }
            HudScaleMode.FILL -> {
                val s = maxOf(sx, sy)
                val scaledW = dw * s
                val scaledH = dh * s
                val txFill = (cw - scaledW)/2f
                val tyFill = (ch - scaledH)/2f
                Quadruple(s, s, txFill, tyFill)
            }
            HudScaleMode.STRETCH -> {
                Quadruple(sx, sy, 0f, 0f)
            }
        }

        canvas.withTranslation(tx, ty) {
            canvas.scale(scaleX, scaleY) // scale virtual coordinates to pixels
            drawSceneElements(canvas, elements, elapsedNs)
        }
    }

    private fun drawRect(canvas: Canvas, e: HudElement.Rect) {
        tmpRectF.set(e.left, e.top, e.right, e.bottom)

        if (e.strokeWidth <= 0f) {
            fillPaint.color = e.color
            fillPaint.style = Paint.Style.FILL
            if (e.cornerRadius > 0f) {
                canvas.drawRoundRect(tmpRectF, e.cornerRadius, e.cornerRadius, fillPaint)
            } else {
                canvas.drawRect(tmpRectF, fillPaint)
            }
        } else {
            strokePaint.color = e.color
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = e.strokeWidth
            if (e.cornerRadius > 0f) {
                canvas.drawRoundRect(tmpRectF, e.cornerRadius, e.cornerRadius, strokePaint)
            } else {
                canvas.drawRect(tmpRectF, strokePaint)
            }
        }
    }

    private fun drawLine(canvas: Canvas, e: HudElement.Line) {
        strokePaint.color = e.color
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = e.strokeWidth
        canvas.drawLine(e.startX, e.startY, e.endX, e.endY, strokePaint)
    }

    private fun drawCircle(canvas: Canvas, e: HudElement.Circle) {
        if (e.strokeWidth <= 0f) {
            fillPaint.color = e.color
            fillPaint.style = Paint.Style.FILL
            canvas.drawCircle(e.cx, e.cy, e.radius, fillPaint)
        } else {
            strokePaint.color = e.color
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = e.strokeWidth
            canvas.drawCircle(e.cx, e.cy, e.radius, strokePaint)
        }
    }

    private fun drawText(canvas: Canvas, e: HudElement.Text) {
        textPaint.color = e.color
        textPaint.textSize = e.sizePx
        textPaint.textAlign = e.align
        textPaint.typeface = when (e.fontStyle) {
            HudFontStyle.REGULAR -> ubuntuMonoRegular
            HudFontStyle.BOLD -> ubuntuMonoBold
            HudFontStyle.ITALIC -> ResourcesCompat.getFont(context, R.font.ubuntu_mono_ri)
            HudFontStyle.BOLD_ITALIC -> ResourcesCompat.getFont(context, R.font.ubuntu_mono_bi)
        }
        canvas.drawText(e.text, e.x, e.y, textPaint)
    }

    private fun drawImage(canvas: Canvas, e: HudElement.Image) {
        val drawable = assets.drawable(e.resId)
        drawable.setBounds(
            e.left,
            e.top,
            e.left + e.width,
            e.top + e.height
        )
        drawable.alpha = e.alpha
        drawable.draw(canvas)
    }

    private fun drawSineWave(canvas: Canvas, e: HudElement.SineWave, elapsedNs: Long) {
        val n = maxOf(e.samples, 2)
        val segCount = n - 1
        val buf = e.lineBuf

        // Paint setup
        strokePaint.style = Paint.Style.STROKE
        strokePaint.color = e.color
        strokePaint.strokeWidth = e.strokeWidth
        strokePaint.alpha = e.alpha
        strokePaint.isAntiAlias = true

        // Time → phase
        val t = elapsedNs / 1_000_000_000f
        val phase = t * e.phaseSpeedRadPerSec

        // Precompute constants
        val dx = e.width / (n - 1)
        val k = (2f * Math.PI.toFloat()) / e.wavelength // radians per px

        // Build line segments into float buffer
        var bi = 0
        var prevX = e.x
        var prevY = e.y + kotlin.math.sin(phase).toFloat() * e.amplitude

        for (i in 1 until n) {
            val x = e.x + i * dx
            val y = e.y + kotlin.math.sin(k * (i * dx) + phase).toFloat() * e.amplitude

            buf[bi++] = prevX
            buf[bi++] = prevY
            buf[bi++] = x
            buf[bi++] = y

            prevX = x
            prevY = y
        }

        canvas.drawLines(buf, 0, segCount * 4, strokePaint)
    }

}

data class RenderState(
    val scene: HudScene,
    val elements: List<HudElement>
)

class Hud2DRenderer(
    surface: Surface,
    fps: Int,
    private val defaultWidth: Float,
    private val defaultHeight: Float,
    private val sceneRenderer: Hud2DSceneRenderer,
    onStats: (HudRendererStats) -> Unit = {},
    onRenderError: (Throwable) -> Unit = {}
) : HudRenderer(surface, fps, onStats, onRenderError) {
    @Volatile
    private var state: RenderState = RenderState(
        HudScene(designWidth = defaultWidth, designHeight = defaultHeight),
        emptyList()
    )

    fun setScene(newScene: HudScene, elementsArePreSorted: Boolean = false) {
        val elements = if (elementsArePreSorted) newScene.elements
                        else newScene.elements.sortedBy { it.z }
        state = RenderState(newScene, elements)
    }

    override fun onDrawFrame(canvas: Canvas, frameIndex: Long, elapsedNs: Long) {
        val s = state
        sceneRenderer.render(canvas, s.scene, s.elements, elapsedNs)
    }
}