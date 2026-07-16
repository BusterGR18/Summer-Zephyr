package com.goose.summerzf.core.content

import android.content.Context
import com.goose.summerzf.core.map.HudMapRuntime
import com.goose.summerzf.core.media.HudMediaRuntime
import com.goose.summerzf.core.theme.HudTheme
import com.goose.summerzf.core.theme.HudWidgetType

class HudContentRuntime(
    context: Context,
    val data: HudContentData = HudContentData()
) {
    private val mapRuntime = HudMapRuntime(context.applicationContext, data)
    private val mediaRuntime = HudMediaRuntime(context.applicationContext, data)

    @Volatile
    private var started = false
    private var mapActive = false
    private var mediaActive = false

    fun start(theme: HudTheme) {
        if (!started) {
            started = true
            data.updateClock()
        }
        onThemeChanged(theme)
    }

    fun stop() {
        if (!started) return
        started = false
        if (mapActive) mapRuntime.stop()
        if (mediaActive) mediaRuntime.stop()
        mapActive = false
        mediaActive = false
    }

    fun onThemeChanged(theme: HudTheme) {
        val map = theme.widgets.firstOrNull { it.type == HudWidgetType.MAP && it.enabled }
        val mediaEnabled = theme.widgets.any { it.type == HudWidgetType.MEDIA && it.enabled }
        mapRuntime.setViewport(map?.bounds?.width ?: 520, map?.bounds?.height ?: 384)

        if (!started) return

        if (map != null && !mapActive) {
            mapRuntime.start()
            mapActive = true
        } else if (map == null && mapActive) {
            mapRuntime.stop()
            mapActive = false
        }

        if (mediaEnabled && !mediaActive) {
            mediaRuntime.start()
            mediaActive = true
        } else if (!mediaEnabled && mediaActive) {
            mediaRuntime.stop()
            mediaActive = false
        }
    }

    fun mediaAccessGranted(): Boolean = mediaRuntime.hasAccess()

    fun refreshMedia() {
        mediaRuntime.refresh()
    }
}
