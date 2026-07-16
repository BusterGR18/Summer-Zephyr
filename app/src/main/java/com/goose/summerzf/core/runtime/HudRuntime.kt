package com.goose.summerzf.core.runtime

import android.content.Context
import com.goose.summerzf.core.content.HudContentRuntime
import com.goose.summerzf.core.hud.HudStreamController
import com.goose.summerzf.core.projection.HudProjectionRuntime
import com.goose.summerzf.core.projection.HudProjectionSceneComposer
import com.goose.summerzf.core.theme.HudSceneComposer
import com.goose.summerzf.core.theme.HudThemeRepository

/**
 * Process-wide owners for the HUD session, content providers, and selected
 * theme. The foreground service and Compose UI intentionally share these
 * instances so activity recreation, app switching, or screen locking does not
 * destroy the active encoder/session.
 */
object HudRuntime {
    @Volatile
    private var contentInstance: HudContentRuntime? = null
    @Volatile
    private var themeRepositoryInstance: HudThemeRepository? = null
    @Volatile
    private var controllerInstance: HudStreamController? = null
    @Volatile
    private var projectionInstance: HudProjectionRuntime? = null

    fun content(context: Context): HudContentRuntime {
        contentInstance?.let { return it }
        return synchronized(this) {
            contentInstance ?: HudContentRuntime(context.applicationContext).also {
                contentInstance = it
            }
        }
    }

    fun themes(context: Context): HudThemeRepository {
        themeRepositoryInstance?.let { return it }
        return synchronized(this) {
            themeRepositoryInstance ?: HudThemeRepository(context.applicationContext).also {
                themeRepositoryInstance = it
            }
        }
    }


    fun projection(context: Context): HudProjectionRuntime {
        projectionInstance?.let { return it }
        return synchronized(this) {
            projectionInstance ?: HudProjectionRuntime().also {
                projectionInstance = it
            }
        }
    }

    fun controller(context: Context): HudStreamController {
        controllerInstance?.let { return it }
        return synchronized(this) {
            controllerInstance ?: run {
                val appContext = context.applicationContext
                val content = content(appContext)
                val themes = themes(appContext)
                val projection = projection(appContext)
                HudStreamController(
                    context = appContext,
                    themeRepository = themes,
                    contentRuntime = content,
                    sceneComposer = HudSceneComposer(content.data),
                    projectionRuntime = projection,
                    projectionSceneComposer = HudProjectionSceneComposer(projection)
                ).also { controllerInstance = it }
            }
        }
    }
}
