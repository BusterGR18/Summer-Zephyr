package com.goose.summerzf.core.hud

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.SparseArray
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources

class HudAssets (
    private val context: Context
) {
    private val drawableCache = SparseArray<Drawable>()

    fun drawable(@DrawableRes resId: Int): Drawable {
        val existing = drawableCache[resId]
        if (existing != null) return existing

        val created = AppCompatResources.getDrawable(context, resId)
            ?: error("Drawable $resId not found")

        // mutate so we can modify
        val mutable = created.mutate()
        drawableCache.put(resId, mutable)
        return mutable
    }
}