package com.goose.summerzf.core.projection

import android.graphics.Bitmap
import com.goose.summerzf.core.hud.DoubleBufferedValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class HudSceneMode(val label: String) {
    CUSTOM("Custom dashboard"),
    APP_CAST("Selected app"),
    SCREEN_CAST("Whole screen"),
    ANDROID_AUTO("Android Auto")
}

enum class HudProjectionScaleMode(val label: String) {
    FIT("Fit"),
    FILL("Fill")
}

enum class HudProjectionState {
    IDLE,
    WAITING_FOR_FRAME,
    ACTIVE,
    UNAVAILABLE,
    ERROR
}

data class HudProjectionSnapshot(
    val sceneMode: HudSceneMode = HudSceneMode.CUSTOM,
    val scaleMode: HudProjectionScaleMode = HudProjectionScaleMode.FIT,
    val state: HudProjectionState = HudProjectionState.IDLE,
    val status: String = "Custom dashboard active",
    val capturedFrames: Long = 0,
    val lastError: String? = null
)

/**
 * Process-wide state shared by the foreground service, HUD renderer, and UI.
 * Captured frames are kept outside Compose and are consumed directly by the
 * fixed 800x400 renderer.
 */
class HudProjectionRuntime {
    val frame = DoubleBufferedValue<Bitmap?>(null)
    val statusText = DoubleBufferedValue("Custom dashboard active")

    private val mutableSnapshot = MutableStateFlow(HudProjectionSnapshot())
    val snapshot: StateFlow<HudProjectionSnapshot> = mutableSnapshot.asStateFlow()

    @Synchronized
    fun selectCustom() {
        statusText.publish("Custom dashboard active")
        mutableSnapshot.value = HudProjectionSnapshot(
            sceneMode = HudSceneMode.CUSTOM,
            scaleMode = mutableSnapshot.value.scaleMode,
            state = HudProjectionState.IDLE,
            status = "Custom dashboard active"
        )
    }

    @Synchronized
    fun beginCapture(mode: HudSceneMode) {
        require(mode == HudSceneMode.APP_CAST || mode == HudSceneMode.SCREEN_CAST)
        val message = when (mode) {
            HudSceneMode.APP_CAST -> "Waiting for the selected app…"
            HudSceneMode.SCREEN_CAST -> "Waiting for screen frames…"
            else -> error("Unsupported capture mode")
        }
        frame.publish(null)
        statusText.publish(message)
        mutableSnapshot.value = HudProjectionSnapshot(
            sceneMode = mode,
            scaleMode = mutableSnapshot.value.scaleMode,
            state = HudProjectionState.WAITING_FOR_FRAME,
            status = message
        )
    }

    @Synchronized
    fun publishFrame(bitmap: Bitmap) {
        frame.publish(bitmap)
        val current = mutableSnapshot.value
        statusText.publish("")
        mutableSnapshot.value = current.copy(
            state = HudProjectionState.ACTIVE,
            status = "Casting",
            capturedFrames = current.capturedFrames + 1,
            lastError = null
        )
    }

    @Synchronized
    fun setScaleMode(mode: HudProjectionScaleMode) {
        mutableSnapshot.value = mutableSnapshot.value.copy(scaleMode = mode)
    }

    @Synchronized
    fun beginAndroidAuto() {
        frame.publish(null)
        val message = "Waiting for Android Auto…"
        statusText.publish(message)
        mutableSnapshot.value = HudProjectionSnapshot(
            sceneMode = HudSceneMode.ANDROID_AUTO,
            scaleMode = mutableSnapshot.value.scaleMode,
            state = HudProjectionState.WAITING_FOR_FRAME,
            status = message
        )
    }

    @Synchronized
    fun publishAndroidAutoFrame(bitmap: Bitmap) {
        val current = mutableSnapshot.value
        if (current.sceneMode != HudSceneMode.ANDROID_AUTO) return
        frame.publish(bitmap)
        statusText.publish("")
        mutableSnapshot.value = current.copy(
            state = HudProjectionState.ACTIVE,
            status = "Android Auto",
            capturedFrames = current.capturedFrames + 1,
            lastError = null
        )
    }

    @Synchronized
    fun endAndroidAuto(message: String = "Android Auto ended") {
        frame.publish(null)
        statusText.publish(message)
        val current = mutableSnapshot.value
        if (current.sceneMode == HudSceneMode.ANDROID_AUTO) {
            mutableSnapshot.value = current.copy(
                state = HudProjectionState.IDLE,
                status = message
            )
        }
    }

    @Synchronized
    fun showAndroidAutoUnavailable(reason: String) {
        frame.publish(null)
        statusText.publish(reason)
        mutableSnapshot.value = HudProjectionSnapshot(
            sceneMode = HudSceneMode.ANDROID_AUTO,
            scaleMode = mutableSnapshot.value.scaleMode,
            state = HudProjectionState.UNAVAILABLE,
            status = reason,
            lastError = reason
        )
    }

    @Synchronized
    fun fail(message: String) {
        statusText.publish(message)
        mutableSnapshot.value = mutableSnapshot.value.copy(
            state = HudProjectionState.ERROR,
            status = message,
            lastError = message
        )
    }

    @Synchronized
    fun endCapture(message: String = "Capture ended") {
        frame.publish(null)
        statusText.publish(message)
        mutableSnapshot.value = mutableSnapshot.value.copy(
            state = HudProjectionState.IDLE,
            status = message
        )
    }
}
