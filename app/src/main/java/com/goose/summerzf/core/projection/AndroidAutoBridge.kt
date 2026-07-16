package com.goose.summerzf.core.projection

import android.content.Context
import android.os.Build

/** Build/runtime capability exposed to the normal UI. */
data class AndroidAutoAvailability(
    val available: Boolean,
    val receiverInstalled: Boolean,
    val identityInstalled: Boolean,
    val message: String
)

/**
 * Implemented only by the generated private source set.
 *
 * Keeping this interface in the public source tree lets normal builds compile
 * without importing the receiver or its private identity.
 */
interface AndroidAutoReceiverAdapter {
    fun start(
        context: Context,
        runtime: HudProjectionRuntime,
        log: (String) -> Unit,
        onSessionEnded: (clean: Boolean) -> Unit
    ): Result<Unit>

    fun triggerSelfMode(context: Context, log: (String) -> Unit): Result<Unit>

    fun stop()
}

/**
 * Reflection boundary for the optional private Android Auto receiver.
 *
 * The concrete adapter is generated under tooling/private/android-auto and is
 * compiled only when -PincludeAndroidAutoReceiver=true is supplied.
 */
object AndroidAutoBridge {
    private const val PRIVATE_ADAPTER_CLASS =
        "com.goose.summerzf.privateaa.AndroidAutoPrivateAdapter"

    @Volatile
    private var adapter: AndroidAutoReceiverAdapter? = null

    fun availability(context: Context): AndroidAutoAvailability {
        val appContext = context.applicationContext
        val platformSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        val receiverInstalled = privateAdapterClass() != null
        val identityInstalled = rawResourceExists(appContext, "aa_cert") &&
            rawResourceExists(appContext, "aa_identity_data")

        val message = when {
            !platformSupported ->
                "The private Android Auto receiver currently requires Android 14 or newer."
            !receiverInstalled && !identityInstalled ->
                "Private Android Auto receiver and identity are not included in this build."
            !receiverInstalled ->
                "Android Auto identity is present, but the private receiver source is not included."
            !identityInstalled ->
                "Android Auto receiver is present, but its private identity is not included."
            else ->
                "Android Auto private receiver is ready."
        }

        return AndroidAutoAvailability(
            available = platformSupported && receiverInstalled && identityInstalled,
            receiverInstalled = receiverInstalled,
            identityInstalled = identityInstalled,
            message = message
        )
    }

    @Synchronized
    fun start(
        context: Context,
        runtime: HudProjectionRuntime,
        log: (String) -> Unit,
        onSessionEnded: (clean: Boolean) -> Unit
    ): Result<Unit> {
        val appContext = context.applicationContext
        val availability = availability(appContext)
        if (!availability.available) {
            runtime.showAndroidAutoUnavailable(availability.message)
            return Result.failure(IllegalStateException(availability.message))
        }

        stop()
        val receiver = instantiateAdapter()
            ?: return Result.failure(
                IllegalStateException("Private Android Auto adapter could not be loaded")
            )

        runtime.beginAndroidAuto()
        val result = receiver.start(appContext, runtime, log, onSessionEnded)
        if (result.isSuccess) {
            adapter = receiver
        } else {
            receiver.stop()
            runtime.fail(result.exceptionOrNull()?.message ?: "Android Auto receiver failed")
        }
        return result
    }

    fun isRunning(): Boolean = adapter != null

    @Synchronized
    fun triggerSelfMode(context: Context, log: (String) -> Unit): Result<Unit> {
        val active = adapter
            ?: return Result.failure(IllegalStateException("Android Auto receiver is not running"))
        return active.triggerSelfMode(context, log)
    }

    @Synchronized
    fun stop() {
        val old = adapter
        adapter = null
        runCatching { old?.stop() }
    }

    private fun instantiateAdapter(): AndroidAutoReceiverAdapter? = try {
        privateAdapterClass()
            ?.getDeclaredConstructor()
            ?.newInstance() as? AndroidAutoReceiverAdapter
    } catch (_: Throwable) {
        null
    }

    private fun privateAdapterClass(): Class<*>? = try {
        Class.forName(PRIVATE_ADAPTER_CLASS)
    } catch (_: Throwable) {
        null
    }

    private fun rawResourceExists(context: Context, name: String): Boolean =
        context.resources.getIdentifier(name, "raw", context.packageName) != 0
}
