package com.goose.summerzf.core.theme

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

enum class HudWidgetType {
    MAP,
    MEDIA,
    CLOCK
}

data class HudBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height

    fun intersects(other: HudBounds): Boolean =
        x < other.right && right > other.x && y < other.bottom && bottom > other.y

    fun inset(value: Int): HudBounds = HudBounds(
        x = x + value,
        y = y + value,
        width = (width - value * 2).coerceAtLeast(1),
        height = (height - value * 2).coerceAtLeast(1)
    )
}

data class HudWidgetLayout(
    val id: String = UUID.randomUUID().toString(),
    val type: HudWidgetType,
    val bounds: HudBounds,
    val enabled: Boolean = true,
    val zIndex: Int = 0
)

data class HudPalette(
    val background: Int,
    val panel: Int,
    val panelAlt: Int,
    val accent: Int,
    val text: Int,
    val mutedText: Int
)

data class HudTheme(
    val id: String,
    val name: String,
    val palette: HudPalette,
    val widgets: List<HudWidgetLayout>,
    val version: Int = CURRENT_VERSION
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

data class HudWidgetConstraint(
    val minWidth: Int,
    val minHeight: Int,
    val maxWidth: Int = HudThemeRules.CANVAS_WIDTH,
    val maxHeight: Int = HudThemeRules.CANVAS_HEIGHT
)

object HudThemeRules {
    const val CANVAS_WIDTH = 800
    const val CANVAS_HEIGHT = 400
    const val GRID = 8
    const val GAP = 8

    private val constraints = mapOf(
        HudWidgetType.MAP to HudWidgetConstraint(minWidth = 240, minHeight = 160),
        HudWidgetType.MEDIA to HudWidgetConstraint(minWidth = 176, minHeight = 136),
        HudWidgetType.CLOCK to HudWidgetConstraint(minWidth = 120, minHeight = 56)
    )

    fun constraint(type: HudWidgetType): HudWidgetConstraint =
        constraints.getValue(type)

    fun snap(value: Float): Int = (value / GRID).roundToInt() * GRID

    fun sanitizeBounds(type: HudWidgetType, raw: HudBounds): HudBounds {
        val c = constraint(type)
        val width = snap(raw.width.toFloat())
            .coerceIn(c.minWidth, c.maxWidth)
        val height = snap(raw.height.toFloat())
            .coerceIn(c.minHeight, c.maxHeight)
        val x = snap(raw.x.toFloat())
            .coerceIn(0, CANVAS_WIDTH - width)
        val y = snap(raw.y.toFloat())
            .coerceIn(0, CANVAS_HEIGHT - height)
        return HudBounds(x, y, width, height)
    }

    fun updateWidget(
        theme: HudTheme,
        widgetId: String,
        requestedBounds: HudBounds,
        rejectOverlap: Boolean = true
    ): HudTheme {
        val current = theme.widgets.firstOrNull { it.id == widgetId } ?: return theme
        val sanitized = sanitizeBounds(current.type, requestedBounds)
        if (rejectOverlap) {
            val overlap = theme.widgets.any {
                it.enabled &&
                    it.id != widgetId &&
                    current.type != HudWidgetType.CLOCK &&
                    it.type != HudWidgetType.CLOCK &&
                    sanitized.intersects(it.bounds)
            }
            if (overlap) return theme
        }
        return theme.copy(
            widgets = theme.widgets.map {
                if (it.id == widgetId) it.copy(bounds = sanitized) else it
            }
        )
    }

    fun setEnabled(theme: HudTheme, type: HudWidgetType, enabled: Boolean): HudTheme {
        val existing = theme.widgets.firstOrNull { it.type == type }
        if (!enabled) {
            if (existing == null) return theme
            return theme.copy(
                widgets = theme.widgets.map {
                    if (it.id == existing.id) it.copy(enabled = false) else it
                }
            )
        }

        val enabledLayout = existing?.copy(enabled = true)
            ?: defaultWidget(type, theme.widgets)
        var widgets = if (existing == null) {
            theme.widgets + enabledLayout
        } else {
            theme.widgets.map { if (it.id == existing.id) enabledLayout else it }
        }

        // Map and media are the two primary panels. If a component is enabled
        // while the other currently occupies its space (for example enabling
        // media from the Full map preset), reflow both into the safe 2/3 + 1/3
        // arrangement instead of silently creating overlapping panels.
        if (type != HudWidgetType.CLOCK) {
            val map = widgets.firstOrNull { it.type == HudWidgetType.MAP && it.enabled }
            val media = widgets.firstOrNull { it.type == HudWidgetType.MEDIA && it.enabled }
            if (map != null && media != null && map.bounds.intersects(media.bounds)) {
                widgets = widgets.map { widget ->
                    when (widget.type) {
                        HudWidgetType.MAP -> widget.copy(bounds = HudBounds(8, 8, 520, 384))
                        HudWidgetType.MEDIA -> widget.copy(bounds = HudBounds(536, 8, 256, 384))
                        HudWidgetType.CLOCK -> widget
                    }
                }
            }
        }
        return theme.copy(widgets = widgets)
    }

    private fun defaultWidget(
        type: HudWidgetType,
        existing: List<HudWidgetLayout>
    ): HudWidgetLayout {
        val candidates = when (type) {
            HudWidgetType.MAP -> listOf(
                HudBounds(8, 8, 520, 384),
                HudBounds(8, 8, 784, 384)
            )
            HudWidgetType.MEDIA -> listOf(
                HudBounds(536, 8, 256, 384),
                HudBounds(8, 256, 784, 136)
            )
            HudWidgetType.CLOCK -> listOf(
                HudBounds(648, 16, 136, 64),
                HudBounds(16, 16, 136, 64)
            )
        }
        val selected = candidates.firstOrNull { candidate ->
            existing.none { it.enabled && candidate.intersects(it.bounds) }
        } ?: candidates.last()
        return HudWidgetLayout(type = type, bounds = sanitizeBounds(type, selected))
    }
}

object HudPalettes {
    val Night = HudPalette(
        background = Color.rgb(7, 11, 16),
        panel = Color.rgb(15, 23, 34),
        panelAlt = Color.rgb(23, 34, 48),
        accent = Color.rgb(124, 255, 178),
        text = Color.WHITE,
        mutedText = Color.rgb(158, 174, 194)
    )
    val Amber = HudPalette(
        background = Color.rgb(17, 13, 8),
        panel = Color.rgb(35, 27, 16),
        panelAlt = Color.rgb(55, 40, 20),
        accent = Color.rgb(255, 183, 77),
        text = Color.rgb(255, 248, 232),
        mutedText = Color.rgb(205, 184, 148)
    )
    val Ice = HudPalette(
        background = Color.rgb(7, 15, 21),
        panel = Color.rgb(13, 30, 41),
        panelAlt = Color.rgb(20, 45, 59),
        accent = Color.rgb(89, 214, 255),
        text = Color.rgb(238, 251, 255),
        mutedText = Color.rgb(151, 190, 204)
    )

    fun named(): List<Pair<String, HudPalette>> = listOf(
        "Night" to Night,
        "Amber" to Amber,
        "Ice" to Ice
    )
}

object HudThemePresets {
    fun mapAndMedia(): HudTheme = HudTheme(
        id = "map-media",
        name = "Map + media",
        palette = HudPalettes.Night,
        widgets = listOf(
            HudWidgetLayout(
                id = "map",
                type = HudWidgetType.MAP,
                bounds = HudBounds(8, 8, 520, 384),
                zIndex = 0
            ),
            HudWidgetLayout(
                id = "media",
                type = HudWidgetType.MEDIA,
                bounds = HudBounds(536, 8, 256, 384),
                zIndex = 1
            ),
            HudWidgetLayout(
                id = "clock",
                type = HudWidgetType.CLOCK,
                bounds = HudBounds(648, 16, 136, 64),
                enabled = false,
                zIndex = 2
            )
        )
    )

    fun fullMap(): HudTheme = HudTheme(
        id = "full-map",
        name = "Full map",
        palette = HudPalettes.Night,
        widgets = listOf(
            HudWidgetLayout(
                id = "map",
                type = HudWidgetType.MAP,
                bounds = HudBounds(8, 8, 784, 384)
            ),
            HudWidgetLayout(
                id = "media",
                type = HudWidgetType.MEDIA,
                bounds = HudBounds(536, 8, 256, 384),
                enabled = false,
                zIndex = 1
            ),
            HudWidgetLayout(
                id = "clock",
                type = HudWidgetType.CLOCK,
                bounds = HudBounds(648, 16, 136, 64),
                enabled = false,
                zIndex = 2
            )
        )
    )

    fun stacked(): HudTheme = HudTheme(
        id = "stacked",
        name = "Map above media",
        palette = HudPalettes.Night,
        widgets = listOf(
            HudWidgetLayout(
                id = "map",
                type = HudWidgetType.MAP,
                bounds = HudBounds(8, 8, 784, 240)
            ),
            HudWidgetLayout(
                id = "media",
                type = HudWidgetType.MEDIA,
                bounds = HudBounds(8, 256, 784, 136),
                zIndex = 1
            ),
            HudWidgetLayout(
                id = "clock",
                type = HudWidgetType.CLOCK,
                bounds = HudBounds(648, 16, 136, 64),
                enabled = false,
                zIndex = 2
            )
        )
    )

    fun all(): List<HudTheme> = listOf(mapAndMedia(), fullMap(), stacked())
}

fun HudTheme.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("version", version)
    put("palette", JSONObject().apply {
        put("background", palette.background)
        put("panel", palette.panel)
        put("panelAlt", palette.panelAlt)
        put("accent", palette.accent)
        put("text", palette.text)
        put("mutedText", palette.mutedText)
    })
    put("widgets", JSONArray().apply {
        widgets.forEach { widget ->
            put(JSONObject().apply {
                put("id", widget.id)
                put("type", widget.type.name)
                put("enabled", widget.enabled)
                put("zIndex", widget.zIndex)
                put("bounds", JSONObject().apply {
                    put("x", widget.bounds.x)
                    put("y", widget.bounds.y)
                    put("width", widget.bounds.width)
                    put("height", widget.bounds.height)
                })
            })
        }
    })
}

fun JSONObject.toHudTheme(): HudTheme {
    val paletteJson = getJSONObject("palette")
    val widgetsJson = getJSONArray("widgets")
    val widgets = buildList {
        for (index in 0 until widgetsJson.length()) {
            val json = widgetsJson.getJSONObject(index)
            val bounds = json.getJSONObject("bounds")
            val type = HudWidgetType.valueOf(json.getString("type"))
            add(
                HudWidgetLayout(
                    id = json.optString("id", UUID.randomUUID().toString()),
                    type = type,
                    enabled = json.optBoolean("enabled", true),
                    zIndex = json.optInt("zIndex", index),
                    bounds = HudThemeRules.sanitizeBounds(
                        type,
                        HudBounds(
                            x = bounds.getInt("x"),
                            y = bounds.getInt("y"),
                            width = bounds.getInt("width"),
                            height = bounds.getInt("height")
                        )
                    )
                )
            )
        }
    }
    return HudTheme(
        id = optString("id", "custom"),
        name = optString("name", "Custom theme"),
        version = optInt("version", HudTheme.CURRENT_VERSION),
        palette = HudPalette(
            background = paletteJson.getInt("background"),
            panel = paletteJson.getInt("panel"),
            panelAlt = paletteJson.getInt("panelAlt"),
            accent = paletteJson.getInt("accent"),
            text = paletteJson.getInt("text"),
            mutedText = paletteJson.getInt("mutedText")
        ),
        widgets = widgets
    )
}
