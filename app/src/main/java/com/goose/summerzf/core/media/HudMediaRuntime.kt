package com.goose.summerzf.core.media

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.goose.summerzf.core.content.HudContentData

class HudMediaRuntime(
    private val context: Context,
    private val data: HudContentData
) {
    fun start() {
        if (!hasAccess()) {
            data.mediaTitle.publish("Media access required")
            data.mediaArtist.publish("Enable notification access in Theme editor")
            data.mediaSource.publish("No media session access")
            data.mediaState.publish("SETUP")
            data.mediaArtwork.publish(null)
            return
        }
        refresh()
    }

    fun stop() = Unit

    fun hasAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun refresh() {
        if (!hasAccess()) {
            start()
            return
        }
        HudMediaListenerService.requestRefresh(context)
    }
}
