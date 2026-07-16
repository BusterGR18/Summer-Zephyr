package com.goose.summerzf.core.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

class HudThemeRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val listeners = CopyOnWriteArrayList<(HudTheme) -> Unit>()
    private val _theme = MutableStateFlow(load())
    val theme: StateFlow<HudTheme> = _theme.asStateFlow()

    fun addListener(listener: (HudTheme) -> Unit) {
        listeners += listener
        listener(_theme.value)
    }

    fun removeListener(listener: (HudTheme) -> Unit) {
        listeners.remove(listener)
    }

    fun applyPreset(preset: HudTheme) {
        publish(
            preset.copy(
                id = "custom",
                name = preset.name
            )
        )
    }

    fun setPalette(palette: HudPalette) {
        publish(_theme.value.copy(palette = palette, name = "Custom theme"))
    }

    fun setWidgetEnabled(type: HudWidgetType, enabled: Boolean) {
        publish(HudThemeRules.setEnabled(_theme.value, type, enabled).copy(name = "Custom theme"))
    }

    /**
     * Returns true when the requested bounds were accepted. Overlapping widgets
     * are intentionally rejected in the first editor version so the motorcycle
     * scene always remains readable.
     */
    fun updateWidgetBounds(widgetId: String, requested: HudBounds): Boolean {
        val old = _theme.value
        val updated = HudThemeRules.updateWidget(old, widgetId, requested)
        if (updated == old) return false
        publish(updated.copy(name = "Custom theme"))
        return true
    }

    fun reset() {
        publish(HudThemePresets.mapAndMedia())
    }

    private fun publish(theme: HudTheme) {
        _theme.value = theme
        preferences.edit().putString(KEY_THEME, theme.toJson().toString()).apply()
        listeners.forEach { it(theme) }
    }

    private fun load(): HudTheme {
        val raw = preferences.getString(KEY_THEME, null) ?: return HudThemePresets.mapAndMedia()
        return try {
            JSONObject(raw).toHudTheme()
        } catch (_: Exception) {
            HudThemePresets.mapAndMedia()
        }
    }

    private companion object {
        const val PREFS_NAME = "hud_theme"
        const val KEY_THEME = "current_theme"
    }
}
