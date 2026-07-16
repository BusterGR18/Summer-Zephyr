package com.goose.summerzf.core.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.goose.summerzf.MainActivity
import com.goose.summerzf.R
import com.goose.summerzf.core.hud.HudConnectionState
import com.goose.summerzf.core.hud.HudLogLevel
import com.goose.summerzf.core.projection.AndroidAutoBridge
import com.goose.summerzf.core.projection.HudMediaProjectionCapture
import com.goose.summerzf.core.projection.HudProjectionScaleMode
import com.goose.summerzf.core.projection.HudSceneMode

/**
 * Owns the long-lived MotoPlay stream and optional MediaProjection capture
 * while the UI is backgrounded.
 */
class HudStreamService : Service() {
    private val controller by lazy { HudRuntime.controller(applicationContext) }
    private val projectionRuntime by lazy { HudRuntime.projection(applicationContext) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val projectionCapture by lazy {
        HudMediaProjectionCapture(
            context = applicationContext,
            runtime = projectionRuntime,
            onProjectionStopped = {
                mainHandler.post {
                    controller.showCustomScene()
                    promoteForeground(includeMediaProjection = false)
                }
            }
        )
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var startedForStreaming = false

    private val notificationUpdater = object : Runnable {
        override fun run() {
            if (!startedForStreaming) return

            val snapshot = controller.diagnostics.snapshot.value
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(snapshot.state.name))

            if (snapshot.state == HudConnectionState.IDLE || snapshot.state == HudConnectionState.ERROR) {
                stopServiceRuntime(stopController = false)
                return
            }

            mainHandler.postDelayed(this, NOTIFICATION_UPDATE_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startStreamRuntime()
            ACTION_STOP -> stopServiceRuntime(stopController = true)
            ACTION_START_PROJECTION -> startProjectionRuntime(intent)
            ACTION_STOP_PROJECTION,
            ACTION_SHOW_CUSTOM -> stopProjectionRuntime(restoreCustom = true)
            ACTION_SHOW_ANDROID_AUTO -> showAndroidAutoRuntime()
            ACTION_SET_PROJECTION_SCALE -> setProjectionScale(intent)
            else -> if (!startedForStreaming) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(notificationUpdater)
        try {
            projectionCapture.stop(notify = false)
        } catch (error: Exception) {
            Log.w(TAG, "Projection stop during service destruction failed", error)
        }
        AndroidAutoBridge.stop()
        try {
            controller.stop()
        } catch (error: Exception) {
            Log.w(TAG, "Controller stop during service destruction failed", error)
        }
        releaseLocks()
        super.onDestroy()
    }

    private fun startStreamRuntime() {
        if (startedForStreaming) return
        startedForStreaming = true

        promoteForeground(includeMediaProjection = false)
        acquireLocks()
        controller.diagnostics.log(
            HudLogLevel.INFO,
            "SERVICE",
            "Foreground service active; CPU and high-performance Wi-Fi locks acquired"
        )
        mainHandler.removeCallbacks(notificationUpdater)
        mainHandler.post(notificationUpdater)

        val state = controller.diagnostics.snapshot.value.state
        if (state !in setOf(HudConnectionState.IDLE, HudConnectionState.ERROR)) return

        try {
            controller.start()
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start HUD stream", error)
            controller.diagnostics.recordError(
                "Foreground service failed to start stream: ${error.message}",
                fatal = true
            )
            stopServiceRuntime(stopController = false)
        }
    }

    private fun startProjectionRuntime(intent: Intent) {
        projectionRuntime.endAndroidAuto()
        AndroidAutoBridge.stop()
        if (!startedForStreaming || controller.diagnostics.snapshot.value.state != HudConnectionState.STREAMING) {
            projectionRuntime.fail("Connect to the motorcycle display before starting casting")
            return
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        val resultData = projectionResultData(intent)
        val mode = intent.getStringExtra(EXTRA_SCENE_MODE)
            ?.let { value -> runCatching { HudSceneMode.valueOf(value) }.getOrNull() }

        if (
            resultData == null ||
            resultCode == Int.MIN_VALUE ||
            mode !in setOf(HudSceneMode.APP_CAST, HudSceneMode.SCREEN_CAST)
        ) {
            projectionRuntime.fail("Invalid screen capture permission result")
            return
        }
        val captureMode = requireNotNull(mode)

        try {
            // Android 14 requires the mediaProjection foreground-service type
            // to be active before getMediaProjection() is called.
            promoteForeground(includeMediaProjection = true)
            projectionCapture.start(resultCode, resultData, captureMode)
            controller.showProjectionScene(captureMode)
            controller.diagnostics.log(HudLogLevel.INFO, "PROJECTION", "Started ${captureMode.label}")
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start screen capture", error)
            projectionRuntime.fail("Unable to start casting: ${error.message}")
            controller.showProjectionScene(captureMode)
            promoteForeground(includeMediaProjection = false)
        }
    }

    private fun stopProjectionRuntime(restoreCustom: Boolean) {
        try {
            projectionCapture.stop(notify = false)
        } catch (error: Exception) {
            Log.w(TAG, "Unable to stop MediaProjection", error)
        }
        projectionRuntime.endAndroidAuto()
        AndroidAutoBridge.stop()
        if (restoreCustom) controller.showCustomScene()
        promoteForeground(includeMediaProjection = false)
        controller.diagnostics.log(HudLogLevel.INFO, "PROJECTION", "Casting stopped")
    }

    private fun showAndroidAutoRuntime() {
        if (!startedForStreaming || controller.diagnostics.snapshot.value.state != HudConnectionState.STREAMING) {
            projectionRuntime.fail("Connect to the motorcycle display before starting Android Auto")
            return
        }
        try {
            projectionCapture.stop(notify = false)
        } catch (_: Exception) {
        }
        projectionRuntime.endAndroidAuto()
        AndroidAutoBridge.stop()
        promoteForeground(includeMediaProjection = false)
        controller.showAndroidAutoStarting()

        val logSink: (String) -> Unit = { message ->
            controller.diagnostics.log(HudLogLevel.INFO, "ANDROID_AUTO", message)
        }
        val result = AndroidAutoBridge.start(
            applicationContext,
            projectionRuntime,
            logSink,
            onSessionEnded = { clean ->
                mainHandler.post {
                    controller.diagnostics.log(
                        HudLogLevel.INFO,
                        "ANDROID_AUTO",
                        "Android Auto session ended (clean=$clean)"
                    )
                    controller.showCustomScene()
                    AndroidAutoBridge.stop()
                }
            }
        )
        result.onFailure { error ->
            val message = error.message ?: "Unable to start Android Auto receiver"
            controller.showAndroidAutoUnavailable(message)
            controller.diagnostics.log(HudLogLevel.ERROR, "ANDROID_AUTO", message)
        }
        result.onSuccess {
            controller.diagnostics.log(
                HudLogLevel.INFO,
                "ANDROID_AUTO",
                "Receiver listening; waiting for Android Auto self-mode"
            )
        }
    }

    private fun setProjectionScale(intent: Intent) {
        val mode = intent.getStringExtra(EXTRA_SCALE_MODE)
            ?.let { runCatching { HudProjectionScaleMode.valueOf(it) }.getOrNull() }
            ?: return
        controller.setProjectionScaleMode(mode)
    }

    private fun stopServiceRuntime(stopController: Boolean) {
        if (!startedForStreaming && !stopController) return
        startedForStreaming = false
        mainHandler.removeCallbacks(notificationUpdater)

        try {
            projectionCapture.stop(notify = false)
        } catch (_: Exception) {
        }
        projectionRuntime.endAndroidAuto()
        AndroidAutoBridge.stop()
        projectionRuntime.selectCustom()

        if (stopController) {
            try {
                controller.stop()
            } catch (error: Exception) {
                Log.w(TAG, "Unable to stop HUD controller", error)
            }
        }

        releaseLocks()
        controller.diagnostics.log(
            HudLogLevel.INFO,
            "SERVICE",
            "Foreground service stopping; CPU and Wi-Fi locks released"
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun promoteForeground(includeMediaProjection: Boolean) {
        if (!startedForStreaming) return
        val serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            if (includeMediaProjection) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(controller.diagnostics.snapshot.value.state.name),
            serviceTypes
        )
    }

    @Suppress("DEPRECATION")
    private fun projectionResultData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:HudStreamCpu"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        if (wifiLock?.isHeld != true) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "$packageName:HudStreamWifi"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "MotoPlay stream",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the CFMOTO HUD stream active in the background"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(state: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, HudStreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val projection = projectionRuntime.snapshot.value
        val sceneText = projection.sceneMode.label

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("CFMOTO HUD stream")
            .setContentText("$state · $sceneText · 800×400 @ 30 fps")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    companion object {
        private const val TAG = "HudStreamService"
        private const val CHANNEL_ID = "hud_stream"
        private const val NOTIFICATION_ID = 10920
        private const val NOTIFICATION_UPDATE_MS = 1_000L

        const val ACTION_START = "com.goose.summerzf.action.START_HUD_STREAM"
        const val ACTION_STOP = "com.goose.summerzf.action.STOP_HUD_STREAM"
        const val ACTION_START_PROJECTION = "com.goose.summerzf.action.START_PROJECTION"
        const val ACTION_STOP_PROJECTION = "com.goose.summerzf.action.STOP_PROJECTION"
        const val ACTION_SHOW_CUSTOM = "com.goose.summerzf.action.SHOW_CUSTOM_SCENE"
        const val ACTION_SHOW_ANDROID_AUTO = "com.goose.summerzf.action.SHOW_ANDROID_AUTO"
        const val ACTION_SET_PROJECTION_SCALE = "com.goose.summerzf.action.SET_PROJECTION_SCALE"

        private const val EXTRA_RESULT_CODE = "projection_result_code"
        private const val EXTRA_RESULT_DATA = "projection_result_data"
        private const val EXTRA_SCENE_MODE = "projection_scene_mode"
        private const val EXTRA_SCALE_MODE = "projection_scale_mode"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, HudStreamService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, HudStreamService::class.java).setAction(ACTION_STOP)
            )
        }

        fun startProjection(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            mode: HudSceneMode
        ) {
            require(mode == HudSceneMode.APP_CAST || mode == HudSceneMode.SCREEN_CAST)
            context.startService(
                Intent(context, HudStreamService::class.java)
                    .setAction(ACTION_START_PROJECTION)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_RESULT_DATA, resultData)
                    .putExtra(EXTRA_SCENE_MODE, mode.name)
            )
        }

        fun showCustom(context: Context) {
            context.startService(
                Intent(context, HudStreamService::class.java).setAction(ACTION_SHOW_CUSTOM)
            )
        }

        fun showAndroidAuto(context: Context) {
            context.startService(
                Intent(context, HudStreamService::class.java).setAction(ACTION_SHOW_ANDROID_AUTO)
            )
        }

        fun setProjectionScale(context: Context, mode: HudProjectionScaleMode) {
            context.startService(
                Intent(context, HudStreamService::class.java)
                    .setAction(ACTION_SET_PROJECTION_SCALE)
                    .putExtra(EXTRA_SCALE_MODE, mode.name)
            )
        }
    }
}
