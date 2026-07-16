package com.goose.summerzf.core.theme

import android.graphics.Color
import android.graphics.Paint
import com.goose.summerzf.core.content.HudContentData
import com.goose.summerzf.core.hud.HudBitmapScaleMode
import com.goose.summerzf.core.hud.HudElement
import com.goose.summerzf.core.hud.HudFontStyle
import com.goose.summerzf.core.hud.HudScaleMode
import com.goose.summerzf.core.hud.HudScene

interface HudWidgetRenderer {
    val type: HudWidgetType
    fun build(
        layout: HudWidgetLayout,
        palette: HudPalette,
        data: HudContentData
    ): List<HudElement>
}

class HudSceneComposer(
    private val data: HudContentData,
    renderers: List<HudWidgetRenderer> = listOf(
        MapWidgetRenderer(),
        MediaWidgetRenderer(),
        ClockWidgetRenderer()
    )
) {
    private val rendererByType = renderers.associateBy { it.type }

    fun compose(theme: HudTheme): HudScene {
        val elements = buildList {
            add(
                HudElement.Rect(
                    z = -100,
                    left = 0f,
                    top = 0f,
                    right = HudThemeRules.CANVAS_WIDTH.toFloat(),
                    bottom = HudThemeRules.CANVAS_HEIGHT.toFloat(),
                    color = theme.palette.background
                )
            )
            theme.widgets
                .asSequence()
                .filter { it.enabled }
                .sortedBy { it.zIndex }
                .forEach { layout ->
                    rendererByType[layout.type]
                        ?.build(layout, theme.palette, data)
                        ?.let(::addAll)
                }
        }
        return HudScene(
            clearColor = theme.palette.background,
            designWidth = HudThemeRules.CANVAS_WIDTH.toFloat(),
            designHeight = HudThemeRules.CANVAS_HEIGHT.toFloat(),
            scaleMode = HudScaleMode.FILL,
            elements = elements.sortedBy { it.z }
        )
    }
}

private class MapWidgetRenderer : HudWidgetRenderer {
    override val type = HudWidgetType.MAP

    override fun build(
        layout: HudWidgetLayout,
        palette: HudPalette,
        data: HudContentData
    ): List<HudElement> {
        val b = layout.bounds
        val inset = b.inset(2)
        val compact = b.height < 220 || b.width < 360
        val headerHeight = if (compact) 42f else 50f
        val statusSize = if (compact) 15f else 17f
        val baseZ = layout.zIndex * 20
        return listOf(
            HudElement.Rect(
                z = baseZ,
                left = b.x.toFloat(),
                top = b.y.toFloat(),
                right = b.right.toFloat(),
                bottom = b.bottom.toFloat(),
                color = palette.panel,
                cornerRadius = 14f
            ),
            HudElement.BitmapDynamic(
                z = baseZ + 1,
                value = data.mapBitmap,
                left = inset.x.toFloat(),
                top = inset.y.toFloat(),
                width = inset.width.toFloat(),
                height = inset.height.toFloat(),
                scaleMode = HudBitmapScaleMode.CROP,
                cornerRadius = 12f,
                placeholderColor = palette.panelAlt
            ),
            HudElement.Rect(
                z = baseZ + 2,
                left = inset.x.toFloat(),
                top = inset.y.toFloat(),
                right = inset.right.toFloat(),
                bottom = inset.y + headerHeight,
                color = withAlpha(palette.background, 215),
                cornerRadius = 12f
            ),
            HudElement.TextDynamicBox(
                z = baseZ + 3,
                value = data.mapStatus,
                left = inset.x + 14f,
                top = inset.y + 7f,
                width = inset.width - 28f,
                height = headerHeight - 10f,
                color = palette.text,
                sizePx = statusSize,
                maxLines = 1,
                fontStyle = HudFontStyle.BOLD
            ),
            HudElement.Circle(
                z = baseZ + 5,
                cx = b.x + b.width / 2f,
                cy = b.y + b.height / 2f,
                radius = if (compact) 8f else 11f,
                color = palette.accent
            ),
            HudElement.Circle(
                z = baseZ + 6,
                cx = b.x + b.width / 2f,
                cy = b.y + b.height / 2f,
                radius = if (compact) 3f else 4f,
                color = palette.background
            ),
            HudElement.Rect(
                z = baseZ + 2,
                left = inset.x.toFloat(),
                top = (inset.bottom - if (compact) 28 else 34).toFloat(),
                right = inset.right.toFloat(),
                bottom = inset.bottom.toFloat(),
                color = withAlpha(palette.background, 205),
                cornerRadius = 10f
            ),
            HudElement.TextDynamicBox(
                z = baseZ + 3,
                value = data.speed,
                left = inset.x + 12f,
                top = inset.bottom - if (compact) 27f else 32f,
                width = inset.width * 0.38f,
                height = 28f,
                color = palette.accent,
                sizePx = if (compact) 15f else 18f,
                maxLines = 1,
                fontStyle = HudFontStyle.BOLD
            ),
            HudElement.TextDynamicBox(
                z = baseZ + 3,
                value = data.location,
                left = inset.x + inset.width * 0.38f,
                top = inset.bottom - if (compact) 27f else 32f,
                width = inset.width * 0.6f - 12f,
                height = 28f,
                color = palette.mutedText,
                sizePx = if (compact) 12f else 14f,
                maxLines = 1,
                align = Paint.Align.RIGHT
            ),
            HudElement.Text(
                z = baseZ + 4,
                text = "OpenFreeMap © OpenMapTiles · Data from OpenStreetMap",
                x = inset.right - 8f,
                y = inset.bottom - 4f,
                sizePx = 8f,
                color = withAlpha(Color.WHITE, 150),
                align = Paint.Align.RIGHT
            )
        )
    }
}

private class MediaWidgetRenderer : HudWidgetRenderer {
    override val type = HudWidgetType.MEDIA

    override fun build(
        layout: HudWidgetLayout,
        palette: HudPalette,
        data: HudContentData
    ): List<HudElement> {
        val b = layout.bounds
        val baseZ = layout.zIndex * 20
        val padding = 14f
        val horizontal = b.width > b.height * 1.8f
        val artworkSize = if (horizontal) {
            (b.height - padding * 2).coerceAtLeast(72f)
        } else {
            (b.width - padding * 2).coerceAtMost(b.height * 0.48f).coerceAtLeast(72f)
        }
        val artLeft = b.x + padding
        val artTop = b.y + padding
        val textLeft = if (horizontal) artLeft + artworkSize + padding else artLeft
        val textTop = if (horizontal) artTop else artTop + artworkSize + 13f
        val textWidth = if (horizontal) {
            b.right - textLeft - padding
        } else {
            b.width - padding * 2
        }
        val availableHeight = b.bottom - textTop - padding
        val titleSize = when {
            b.width < 220 -> 18f
            horizontal -> 22f
            else -> 24f
        }
        return listOf(
            HudElement.Rect(
                z = baseZ,
                left = b.x.toFloat(),
                top = b.y.toFloat(),
                right = b.right.toFloat(),
                bottom = b.bottom.toFloat(),
                color = palette.panel,
                cornerRadius = 14f
            ),
            HudElement.BitmapDynamic(
                z = baseZ + 1,
                value = data.mediaArtwork,
                left = artLeft,
                top = artTop,
                width = artworkSize,
                height = artworkSize,
                scaleMode = HudBitmapScaleMode.CROP,
                cornerRadius = 12f,
                placeholderColor = palette.panelAlt
            ),
            HudElement.TextDynamicBox(
                z = baseZ + 2,
                value = data.mediaTitle,
                left = textLeft,
                top = textTop,
                width = textWidth,
                height = (availableHeight * 0.38f).coerceAtLeast(titleSize * 1.3f),
                color = palette.text,
                sizePx = titleSize,
                maxLines = if (horizontal) 2 else 3,
                fontStyle = HudFontStyle.BOLD
            ),
            HudElement.TextDynamicBox(
                z = baseZ + 2,
                value = data.mediaArtist,
                left = textLeft,
                top = textTop + availableHeight * 0.40f,
                width = textWidth,
                height = availableHeight * 0.24f,
                color = palette.mutedText,
                sizePx = if (horizontal) 16f else 17f,
                maxLines = 2
            ),
            HudElement.TextDynamicBox(
                z = baseZ + 2,
                value = data.mediaSource,
                left = textLeft,
                top = textTop + availableHeight * 0.68f,
                width = textWidth,
                height = availableHeight * 0.16f,
                color = palette.mutedText,
                sizePx = 12f,
                maxLines = 1
            ),
            HudElement.TextDynamicBox(
                z = baseZ + 3,
                value = data.mediaState,
                left = textLeft,
                top = textTop + availableHeight * 0.84f,
                width = textWidth,
                height = availableHeight * 0.16f,
                color = palette.accent,
                sizePx = 14f,
                maxLines = 1,
                fontStyle = HudFontStyle.BOLD
            )
        )
    }
}

private class ClockWidgetRenderer : HudWidgetRenderer {
    override val type = HudWidgetType.CLOCK

    override fun build(
        layout: HudWidgetLayout,
        palette: HudPalette,
        data: HudContentData
    ): List<HudElement> {
        val b = layout.bounds
        val baseZ = layout.zIndex * 20
        return listOf(
            HudElement.Rect(
                z = baseZ,
                left = b.x.toFloat(),
                top = b.y.toFloat(),
                right = b.right.toFloat(),
                bottom = b.bottom.toFloat(),
                color = withAlpha(palette.background, 220),
                cornerRadius = 12f
            ),
            HudElement.TextDynamicBox(
                z = baseZ + 1,
                value = data.clock,
                left = b.x.toFloat(),
                top = b.y + 3f,
                width = b.width.toFloat(),
                height = b.height - 6f,
                color = palette.accent,
                sizePx = (b.height * 0.42f).coerceIn(20f, 34f),
                maxLines = 1,
                fontStyle = HudFontStyle.BOLD,
                align = Paint.Align.CENTER
            )
        )
    }
}

private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
    alpha.coerceIn(0, 255),
    Color.red(color),
    Color.green(color),
    Color.blue(color)
)
