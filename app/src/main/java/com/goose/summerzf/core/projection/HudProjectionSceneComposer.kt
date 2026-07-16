package com.goose.summerzf.core.projection

import android.graphics.Color
import android.graphics.Paint
import com.goose.summerzf.core.hud.HudBitmapScaleMode
import com.goose.summerzf.core.hud.HudElement
import com.goose.summerzf.core.hud.HudFontStyle
import com.goose.summerzf.core.hud.HudScaleMode
import com.goose.summerzf.core.hud.HudScene

class HudProjectionSceneComposer(
    private val runtime: HudProjectionRuntime
) {
    fun compose(): HudScene {
        val snapshot = runtime.snapshot.value
        val bitmapScale = when (snapshot.scaleMode) {
            HudProjectionScaleMode.FIT -> HudBitmapScaleMode.FIT
            HudProjectionScaleMode.FILL -> HudBitmapScaleMode.CROP
        }

        return HudScene(
            clearColor = Color.BLACK,
            designWidth = WIDTH,
            designHeight = HEIGHT,
            scaleMode = HudScaleMode.FILL,
            elements = listOf(
                HudElement.BitmapDynamic(
                    z = 0,
                    value = runtime.frame,
                    left = 0f,
                    top = 0f,
                    width = WIDTH,
                    height = HEIGHT,
                    scaleMode = bitmapScale,
                    placeholderColor = Color.BLACK
                ),
                HudElement.TextDynamicBox(
                    z = 2,
                    value = runtime.statusText,
                    left = 80f,
                    top = 145f,
                    width = 640f,
                    height = 110f,
                    color = Color.WHITE,
                    sizePx = 25f,
                    maxLines = 3,
                    lineSpacing = 1.25f,
                    fontStyle = HudFontStyle.BOLD,
                    align = Paint.Align.CENTER
                )
            )
        )
    }

    private companion object {
        const val WIDTH = 800f
        const val HEIGHT = 400f
    }
}
