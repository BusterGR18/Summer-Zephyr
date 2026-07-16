package com.goose.summerzf.core.media

import android.app.Notification
import android.content.ComponentName
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.goose.summerzf.core.runtime.HudRuntime

class HudMediaListenerService : NotificationListenerService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val registrations = mutableListOf<Pair<MediaController, MediaController.Callback>>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        refreshActiveSession()
    }

    override fun onListenerDisconnected() {
        clearRegistrations()
        if (instance === this) instance = null
        val data = HudRuntime.content(applicationContext).data
        data.mediaTitle.publish("Media access disconnected")
        data.mediaArtist.publish("Re-enable notification access")
        data.mediaSource.publish("No media session access")
        data.mediaState.publish("OFFLINE")
        data.mediaArtwork.publish(null)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn?.notification?.category == Notification.CATEGORY_TRANSPORT ||
            sbn?.notification?.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
        ) {
            scheduleRefresh()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        scheduleRefresh()
    }

    override fun onDestroy() {
        clearRegistrations()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun scheduleRefresh() {
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.postDelayed(refreshRunnable, 100L)
    }

    private val refreshRunnable = Runnable(::refreshActiveSession)

    private fun refreshActiveSession() {
        val manager = getSystemService(MediaSessionManager::class.java)
        val component = ComponentName(this, HudMediaListenerService::class.java)
        val controllers = runCatching { manager.getActiveSessions(component) }.getOrDefault(emptyList())
        registerCallbacks(controllers)

        val active = controllers.firstOrNull {
            it.playbackState?.state in setOf(
                PlaybackState.STATE_PLAYING,
                PlaybackState.STATE_BUFFERING,
                PlaybackState.STATE_CONNECTING
            )
        } ?: controllers.firstOrNull()

        val data = HudRuntime.content(applicationContext).data
        if (active == null) {
            data.mediaTitle.publish("Nothing playing")
            data.mediaArtist.publish("Start playback on the phone")
            data.mediaSource.publish("Media session ready")
            data.mediaState.publish("IDLE")
            data.mediaArtwork.publish(null)
            return
        }

        val metadata = active.metadata
        val title = metadata?.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString()
            ?: metadata?.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.toString()
            ?: "Unknown title"
        val artist = metadata?.getText(MediaMetadata.METADATA_KEY_ARTIST)?.toString()
            ?: metadata?.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.toString()
            ?: metadata?.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)?.toString()
            ?: "Unknown artist"
        val artwork = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val appName = runCatching {
            val info = packageManager.getApplicationInfo(active.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(active.packageName)

        data.mediaTitle.publish(title)
        data.mediaArtist.publish(artist)
        data.mediaSource.publish(appName)
        data.mediaState.publish(playbackLabel(active.playbackState?.state))
        data.mediaArtwork.publish(artwork?.safeCopy())
    }

    private fun registerCallbacks(controllers: List<MediaController>) {
        clearRegistrations()
        controllers.forEach { controller ->
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    scheduleRefresh()
                }

                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    scheduleRefresh()
                }

                override fun onSessionDestroyed() {
                    scheduleRefresh()
                }
            }
            controller.registerCallback(callback, mainHandler)
            registrations += controller to callback
        }
    }

    private fun clearRegistrations() {
        registrations.forEach { (controller, callback) ->
            runCatching { controller.unregisterCallback(callback) }
        }
        registrations.clear()
    }

    private fun playbackLabel(state: Int?): String = when (state) {
        PlaybackState.STATE_PLAYING -> "PLAYING"
        PlaybackState.STATE_PAUSED -> "PAUSED"
        PlaybackState.STATE_BUFFERING -> "BUFFERING"
        PlaybackState.STATE_CONNECTING -> "CONNECTING"
        PlaybackState.STATE_FAST_FORWARDING -> "FAST FORWARD"
        PlaybackState.STATE_REWINDING -> "REWINDING"
        PlaybackState.STATE_SKIPPING_TO_NEXT -> "NEXT"
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "PREVIOUS"
        PlaybackState.STATE_STOPPED -> "STOPPED"
        PlaybackState.STATE_ERROR -> "ERROR"
        else -> "IDLE"
    }

    private fun Bitmap.safeCopy(): Bitmap =
        runCatching { copy(config ?: Bitmap.Config.ARGB_8888, false) }.getOrDefault(this)

    companion object {
        @Volatile
        private var instance: HudMediaListenerService? = null

        fun requestRefresh(context: android.content.Context) {
            val connected = instance
            if (connected != null) {
                connected.scheduleRefresh()
            } else {
                // Notification-listener access may already be granted while
                // Android has not rebound the service after an app update or
                // process restart. requestRebind is safe in this state.
                NotificationListenerService.requestRebind(
                    ComponentName(context, HudMediaListenerService::class.java)
                )
            }
        }
    }
}
