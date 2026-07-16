package com.goose.summerzf.core.hud

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import api.Api
import api.MobileCallback
import api.MobileConfig
import api.MobileSession
import api.StreamHost
import com.goose.summerzf.R
import com.goose.summerzf.core.content.HudContentRuntime
import com.goose.summerzf.core.projection.HudProjectionRuntime
import com.goose.summerzf.core.projection.HudProjectionScaleMode
import com.goose.summerzf.core.projection.HudProjectionSceneComposer
import com.goose.summerzf.core.projection.HudSceneMode
import com.goose.summerzf.core.theme.HudSceneComposer
import com.goose.summerzf.core.theme.HudTheme
import com.goose.summerzf.core.theme.HudThemeRepository
import org.json.JSONObject
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class HudStreamController(
    private val context: Context,
    val themeRepository: HudThemeRepository,
    private val contentRuntime: HudContentRuntime,
    private val sceneComposer: HudSceneComposer,
    val projectionRuntime: HudProjectionRuntime,
    private val projectionSceneComposer: HudProjectionSceneComposer
) {
    val diagnostics = HudDiagnostics()

    private var mobileSession: MobileSession? = null
    private var encoder: HudEncoder? = null
    private var renderer: Hud2DRenderer? = null

    private val targetWidth = 800
    private val targetHeight = 400
    private val targetFps = 30
    private val targetBitrate = 2_500_000

    private val pushedFrames = AtomicLong(0)
    private val pushFailures = AtomicLong(0)
    private val stopping = AtomicBoolean(false)

    private val stateValue = DoubleBufferedValue(HudConnectionState.IDLE.name)
    private val clockValue = DoubleBufferedValue("--:--:--")
    private val renderFpsValue = DoubleBufferedValue("0.0")
    private val encoderFpsValue = DoubleBufferedValue("0.0")
    private val bitrateValue = DoubleBufferedValue("0.00 Mbps")
    private val renderedValue = DoubleBufferedValue("0")
    private val encodedValue = DoubleBufferedValue("0")
    private val pushedValue = DoubleBufferedValue("0")
    private val droppedValue = DoubleBufferedValue("0")
    private val eventValue = DoubleBufferedValue("None")

    private val staticSignalBytes: ByteArray = context.resources
        .openRawResource(R.raw.static_signal)
        .readBytes()

    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val nsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val serviceType = "_EasyConn._tcp."
    private val expectedPackageName = "com.cfmoto.cfmotointernational"

    @Volatile
    private var isRunning = false

    @Volatile
    private var isDiscovering = false

    private val themeListener: (HudTheme) -> Unit = { theme ->
        contentRuntime.onThemeChanged(theme)
        if (
            diagnostics.snapshot.value.state == HudConnectionState.STREAMING &&
            projectionRuntime.snapshot.value.sceneMode == HudSceneMode.CUSTOM
        ) {
            renderer?.setScene(sceneComposer.compose(theme), elementsArePreSorted = true)
            diagnostics.log(HudLogLevel.INFO, "THEME", "Applied ${theme.name}")
        }
    }

    init {
        themeRepository.addListener(themeListener)
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        multicastLock = wifiManager.createMulticastLock("summer_zephyr_mdns_lock").apply {
            setReferenceCounted(false)
            acquire()
        }
        diagnostics.log(HudLogLevel.DEBUG, "NSD", "Multicast lock acquired")
    }

    private fun releaseMulticastLock() {
        multicastLock?.let {
            if (it.isHeld) it.release()
        }
        multicastLock = null
    }

    @Suppress("DEPRECATION")
    fun discoverHost(timeoutMs: Long = 20_000, onResult: (Result<StreamHost>) -> Unit) {
        if (isDiscovering) throw IllegalStateException("controller is already searching")
        if (isRunning) throw IllegalStateException("controller is already streaming")

        acquireMulticastLock()
        isDiscovering = true
        transition(HudConnectionState.DISCOVERING, "Browsing $serviceType")

        val completed = AtomicBoolean(false)

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) {
                diagnostics.log(HudLogLevel.INFO, "NSD", "Discovery started: $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                diagnostics.log(HudLogLevel.DEBUG, "NSD", "Discovery stopped: $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo == null || !serviceInfo.serviceType.endsWith(this@HudStreamController.serviceType)) {
                    return
                }
                diagnostics.log(
                    HudLogLevel.DEBUG,
                    "NSD",
                    "Service found: ${serviceInfo.serviceName} (${serviceInfo.serviceType})"
                )

                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                        diagnostics.log(HudLogLevel.WARN, "NSD", "Resolve failed: code=$errorCode")
                    }

                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        if (!isDiscovering || completed.get()) return

                        try {
                            val attrs: Map<String, ByteArray> = resolved.attributes
                            val pkgAttr = attrs["packagename"]?.toString(Charsets.UTF_8)
                            val ipAttr = attrs["ip"]?.toString(Charsets.UTF_8)
                            val port = resolved.port
                            val ip = ipAttr ?: resolved.host.hostAddress
                            val packageName = pkgAttr ?: expectedPackageName
                            val matchesPackage =
                                expectedPackageName.isEmpty() || packageName == expectedPackageName

                            if (matchesPackage && ip != null) {
                                if (!completed.compareAndSet(false, true)) return
                                stopDiscoveryInternal()
                                transition(
                                    HudConnectionState.HOST_RESOLVED,
                                    "$ip:$port package=$packageName"
                                )
                                onResult(
                                    Result.success(
                                        Api.newStreamHost(ip, port.toString(), packageName)
                                    )
                                )
                            } else {
                                diagnostics.log(
                                    HudLogLevel.DEBUG,
                                    "NSD",
                                    "Ignoring service package=$packageName ip=$ip"
                                )
                            }
                        } catch (t: Throwable) {
                            diagnostics.log(HudLogLevel.ERROR, "NSD", "Resolve parsing failed: ${t.message}")
                        }
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                diagnostics.log(HudLogLevel.WARN, "NSD", "Service lost: $serviceInfo")
            }

            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                stopDiscoveryInternal()
                if (completed.compareAndSet(false, true)) {
                    onResult(Result.failure(RuntimeException("NSD start failed: $errorCode")))
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                diagnostics.log(HudLogLevel.WARN, "NSD", "Stop discovery failed: code=$errorCode")
                stopDiscoveryInternal()
            }
        }

        discoveryListener = listener

        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            stopDiscoveryInternal()
            if (completed.compareAndSet(false, true)) onResult(Result.failure(e))
            return
        }

        mainHandler.postDelayed({
            if (!isDiscovering || !completed.compareAndSet(false, true)) return@postDelayed
            stopDiscoveryInternal()
            onResult(Result.failure(Exception("mDNS discovery timeout after $timeoutMs ms")))
        }, timeoutMs)
    }

    fun stopDiscovery() {
        stopDiscoveryInternal()
        if (!isRunning) transition(HudConnectionState.IDLE)
    }

    fun setRendererScene(scene: HudScene, elementsArePreSorted: Boolean = false) {
        if (diagnostics.snapshot.value.state != HudConnectionState.STREAMING) {
            throw IllegalStateException("controller is not in a renderable state")
        }
        renderer?.setScene(scene, elementsArePreSorted)
            ?: throw IllegalStateException("renderer is unavailable")
        diagnostics.log(HudLogLevel.INFO, "RENDER", "Custom scene installed")
    }

    fun setDiagnosticScene() {
        setRendererScene(buildDiagnosticScene(), elementsArePreSorted = true)
        diagnostics.log(HudLogLevel.INFO, "RENDER", "Diagnostic scene restored")
    }

    fun setStandbyScene() {
        showCustomScene()
    }

    fun showCustomScene() {
        projectionRuntime.selectCustom()
        if (diagnostics.snapshot.value.state == HudConnectionState.STREAMING) {
            setRendererScene(
                sceneComposer.compose(themeRepository.theme.value),
                elementsArePreSorted = true
            )
        }
        diagnostics.log(HudLogLevel.INFO, "RENDER", "Custom dashboard restored")
    }

    fun showProjectionScene(mode: HudSceneMode) {
        require(mode != HudSceneMode.CUSTOM)
        if (diagnostics.snapshot.value.state == HudConnectionState.STREAMING) {
            setRendererScene(projectionSceneComposer.compose(), elementsArePreSorted = true)
        }
        diagnostics.log(HudLogLevel.INFO, "RENDER", "${mode.label} scene installed")
    }

    fun setProjectionScaleMode(mode: HudProjectionScaleMode) {
        projectionRuntime.setScaleMode(mode)
        val sceneMode = projectionRuntime.snapshot.value.sceneMode
        if (
            diagnostics.snapshot.value.state == HudConnectionState.STREAMING &&
            sceneMode != HudSceneMode.CUSTOM
        ) {
            renderer?.setScene(projectionSceneComposer.compose(), elementsArePreSorted = true)
        }
        diagnostics.log(HudLogLevel.INFO, "RENDER", "Projection scaling set to ${mode.label}")
    }

    fun showAndroidAutoStarting() {
        projectionRuntime.beginAndroidAuto()
        showProjectionScene(HudSceneMode.ANDROID_AUTO)
    }

    fun showAndroidAutoUnavailable(reason: String) {
        projectionRuntime.showAndroidAutoUnavailable(reason)
        showProjectionScene(HudSceneMode.ANDROID_AUTO)
    }

    fun applyCurrentTheme() {
        showCustomScene()
    }

    private fun activeUserScene(): HudScene = when (projectionRuntime.snapshot.value.sceneMode) {
        HudSceneMode.CUSTOM -> sceneComposer.compose(themeRepository.theme.value)
        else -> projectionSceneComposer.compose()
    }

    fun createDiagnosticsShareIntent(): Intent {
        val file = diagnostics.export(context)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        diagnostics.log(HudLogLevel.INFO, "EXPORT", "Diagnostics exported: ${file.name}")
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Summer Zephyr diagnostics")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    @Synchronized
    fun start() {
        if (isDiscovering) throw IllegalStateException("controller is discovering services")
        if (isRunning) throw IllegalStateException("controller is already streaming")
        if (stopping.get()) throw IllegalStateException("controller is stopping")

        pushedFrames.set(0)
        pushFailures.set(0)
        diagnostics.beginSession()
        refreshDiagnosticValues()

        discoverHost { result ->
            result.onFailure { error ->
                Log.e("HudStream", "Failed to discover HUD", error)
                diagnostics.recordError(error.message ?: "HUD discovery failed", fatal = true)
                refreshDiagnosticValues()
            }
            result.onSuccess { host -> startMobileSession(host) }
        }
    }

    fun stop() {
        stopInternal(fromSessionCallback = false)
    }

    private fun startMobileSession(host: StreamHost) {
        transition(HudConnectionState.STARTING_SESSION)

        val cfg: MobileConfig = Api.newMobileConfig(
            staticSignalBytes,
            targetFps.toLong(),
            10L,
            5L,
            10L,
            3L
        )

        val callback = object : MobileCallback {
            override fun onError(msg: String?, fatal: Boolean) {
                val message = msg ?: "Unknown HUD error"
                diagnostics.recordError(message, fatal)
                refreshDiagnosticValues()
                if (fatal) {
                    mainHandler.post { stopInternal(fromSessionCallback = false, finalState = HudConnectionState.ERROR) }
                }
            }

            override fun onEvent(time: Long, type: Long, payload: ByteArray?) {
                handleHudEvent(time, type, payload)
            }

            override fun onStopped() {
                diagnostics.log(HudLogLevel.INFO, "SESSION", "HUD session stopped callback")
                mainHandler.post { stopInternal(fromSessionCallback = true) }
            }
        }

        val session = try {
            Api.newMobileSession(cfg, callback) as MobileSession
        } catch (e: Exception) {
            diagnostics.recordError("Failed to create MobileSession: ${e.message}", fatal = true)
            refreshDiagnosticValues()
            return
        }

        try {
            session.setECHost(host)
            mobileSession = session
            isRunning = true
            session.startSession()
            if (diagnostics.snapshot.value.state != HudConnectionState.STREAMING) {
                transition(HudConnectionState.WAITING_FOR_VIEW_AREA)
            }
        } catch (e: Exception) {
            mobileSession = null
            isRunning = false
            diagnostics.recordError("Failed to start HUD session: ${e.message}", fatal = true)
            refreshDiagnosticValues()
            try {
                session.stopSession()
            } catch (_: Exception) {
                // Best-effort cleanup after a failed start.
            }
        }
    }

    private fun startEncoder(viewArea: JSONObject?) {
        viewArea?.let {
            diagnostics.log(
                HudLogLevel.INFO,
                "HUD_CONFIG",
                "Reported safe area ${it.optInt("width")}x${it.optInt("height")}; " +
                    "using fixed ${targetWidth}x${targetHeight} stream"
            )
        }

        if (!isRunning || mobileSession == null) return
        if (encoder != null || renderer != null) return

        transition(
            HudConnectionState.STARTING_ENCODER,
            "${targetWidth}x${targetHeight} @ ${targetFps}fps"
        )

        try {
            val newEncoder = HudEncoder(
                width = targetWidth,
                height = targetHeight,
                fps = targetFps,
                bitrate = targetBitrate,
                onStats = { stats ->
                    diagnostics.updateEncoder(
                        stats,
                        pushedFrames = pushedFrames.get(),
                        pushFailures = pushFailures.get()
                    )
                    refreshDiagnosticValues()
                }
            ) { avccSample ->
                try {
                    mobileSession?.pushFrame(avccSample)
                    pushedFrames.incrementAndGet()
                } catch (e: Exception) {
                    pushFailures.incrementAndGet()
                    diagnostics.log(HudLogLevel.ERROR, "VIDEO", "pushFrame failed: ${e.message}")
                }
            }

            val assets = HudAssets(context)
            val sceneRenderer = Hud2DSceneRenderer(context, assets)
            val newRenderer = Hud2DRenderer(
                surface = newEncoder.inputSurface,
                fps = targetFps,
                defaultWidth = targetWidth.toFloat(),
                defaultHeight = targetHeight.toFloat(),
                sceneRenderer = sceneRenderer,
                onStats = { stats ->
                    diagnostics.updateRenderer(stats)
                    contentRuntime.data.updateClock()
                    refreshDiagnosticValues()
                },
                onRenderError = { error ->
                    diagnostics.recordError("Renderer failed: ${error.message}", fatal = true)
                    refreshDiagnosticValues()
                }
            )

            contentRuntime.start(themeRepository.theme.value)
            newRenderer.setScene(
                activeUserScene(),
                elementsArePreSorted = true
            )
            encoder = newEncoder
            renderer = newRenderer
            newEncoder.start()
            newRenderer.start()
            transition(HudConnectionState.STREAMING)
        } catch (e: Exception) {
            diagnostics.recordError("Encoder startup failed: ${e.message}", fatal = true)
            refreshDiagnosticValues()
            stopInternal(fromSessionCallback = false, finalState = HudConnectionState.ERROR)
        }
    }

    private fun stopDiscoveryInternal() {
        if (!isDiscovering) return
        isDiscovering = false
        discoveryListener?.let { listener ->
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (_: IllegalArgumentException) {
                // Already stopped.
            } catch (e: Exception) {
                diagnostics.log(HudLogLevel.WARN, "NSD", "stopServiceDiscovery failed: ${e.message}")
            }
        }
        discoveryListener = null
        releaseMulticastLock()
    }

    private fun stopInternal(
        fromSessionCallback: Boolean,
        finalState: HudConnectionState = HudConnectionState.IDLE
    ) {
        if (!stopping.compareAndSet(false, true)) return

        try {
            val hadWork = isRunning || isDiscovering || encoder != null || renderer != null
            if (!hadWork) {
                if (diagnostics.snapshot.value.state != finalState) {
                    transition(finalState)
                }
                return
            }

            transition(HudConnectionState.STOPPING)
            stopDiscoveryInternal()
            contentRuntime.stop()
            isRunning = false

            val oldRenderer = renderer
            renderer = null
            try {
                oldRenderer?.stop()
            } catch (e: Exception) {
                diagnostics.log(HudLogLevel.ERROR, "RENDER", "Renderer stop failed: ${e.message}")
            }

            val oldEncoder = encoder
            encoder = null
            try {
                oldEncoder?.stop()
            } catch (e: Exception) {
                diagnostics.log(HudLogLevel.ERROR, "VIDEO", "Encoder stop failed: ${e.message}")
            }

            val oldSession = mobileSession
            mobileSession = null
            if (!fromSessionCallback) {
                try {
                    oldSession?.stopSession()
                } catch (e: Exception) {
                    diagnostics.log(HudLogLevel.ERROR, "SESSION", "Session stop failed: ${e.message}")
                }
            }

            transition(finalState)
        } finally {
            stopping.set(false)
        }
    }

    private fun handleHudEvent(time: Long, type: Long, payload: ByteArray?) {
        val summary = summarizeHudEvent(time, type, payload)
        diagnostics.recordHudEvent(summary)
        refreshDiagnosticValues()

        when (type) {
            3L -> {
                // Media-control source emits a binary init packet before the JSON
                // screen configuration. It is valid protocol traffic, not a JSON
                // parsing failure, so only inspect JSON-looking payloads here.
                val text = payload?.toString(Charsets.UTF_8)?.trim().orEmpty()
                if (!text.startsWith("{")) return

                try {
                    val root = JSONObject(text)
                    val area = root
                        .optJSONObject("viewAreaConfig")
                        ?.optJSONArray("viewAreas")
                        ?.optJSONObject(0)
                        ?.optJSONObject("safeArea")
                    if (area != null) startEncoder(area)
                } catch (e: Exception) {
                    diagnostics.recordError("Unable to parse HUD setup JSON: ${e.message}")
                    refreshDiagnosticValues()
                }
            }
        }
    }

    private fun summarizeHudEvent(time: Long, type: Long, payload: ByteArray?): String {
        val payloadSummary = when {
            payload == null -> "null"
            payload.isEmpty() -> "empty"
            else -> {
                val text = payload.toString(Charsets.UTF_8)
                    .replace(Regex("[\\p{Cc}&&[^\\r\\n\\t]]"), "?")
                    .replace("\n", " ")
                    .replace("\r", " ")
                    .trim()
                val printable = text.count { !it.isISOControl() }
                if (text.isNotEmpty() && printable >= text.length * 0.8) {
                    text.take(180)
                } else {
                    payload.take(64).joinToString("") { "%02X".format(it) }
                }
            }
        }
        return "type=$type time=$time payload=$payloadSummary"
    }

    private fun transition(state: HudConnectionState, detail: String? = null) {
        diagnostics.transition(state, detail)
        refreshDiagnosticValues()
    }

    private fun refreshDiagnosticValues() {
        val snapshot = diagnostics.snapshot.value
        stateValue.publish(snapshot.state.name)
        clockValue.publish(LocalTime.now().format(CLOCK_FORMATTER))
        renderFpsValue.publish("%.1f".format(snapshot.renderFps))
        encoderFpsValue.publish("%.1f".format(snapshot.encoderFps))
        bitrateValue.publish("%.2f Mbps".format(snapshot.encodedBitrateBps / 1_000_000.0))
        renderedValue.publish(snapshot.renderedFrames.toString())
        encodedValue.publish(snapshot.encodedFrames.toString())
        pushedValue.publish(snapshot.pushedFrames.toString())
        droppedValue.publish((snapshot.missedRenderDeadlines + snapshot.pushFailures).toString())
        eventValue.publish(snapshot.lastHudEvent.take(72))
    }

    private fun buildDiagnosticScene(): HudScene = HudScene(
        clearColor = Color.rgb(9, 13, 20),
        designWidth = targetWidth.toFloat(),
        designHeight = targetHeight.toFloat(),
        scaleMode = HudScaleMode.FILL,
        elements = listOf(
            HudElement.Rect(0, 0f, 0f, 800f, 58f, Color.rgb(18, 27, 40)),
            HudElement.Text(1, "Summer Zephyr diagnostics", 22f, 39f, 25f, Color.WHITE, HudFontStyle.BOLD),
            HudElement.TextDynamic(1, "", clockValue, 650f, 38f, Color.rgb(124, 255, 178), 23f),

            HudElement.TextDynamic(2, "STATE  ", stateValue, 28f, 93f, Color.rgb(124, 255, 178), 24f, HudFontStyle.BOLD),
            HudElement.Line(1, 28f, 108f, 772f, 108f, Color.rgb(54, 72, 92), 1f),

            HudElement.TextDynamic(2, "Render FPS   ", renderFpsValue, 40f, 150f, Color.WHITE, 22f),
            HudElement.TextDynamic(2, "Encoder FPS  ", encoderFpsValue, 40f, 190f, Color.WHITE, 22f),
            HudElement.TextDynamic(2, "Bitrate      ", bitrateValue, 40f, 230f, Color.WHITE, 22f),

            HudElement.TextDynamic(2, "Rendered  ", renderedValue, 430f, 150f, Color.WHITE, 22f),
            HudElement.TextDynamic(2, "Encoded   ", encodedValue, 430f, 190f, Color.WHITE, 22f),
            HudElement.TextDynamic(2, "Pushed    ", pushedValue, 430f, 230f, Color.WHITE, 22f),
            HudElement.TextDynamic(2, "Drops     ", droppedValue, 630f, 230f, Color.rgb(255, 190, 92), 18f),

            HudElement.Rect(1, 24f, 258f, 776f, 326f, Color.rgb(14, 21, 31), 10f),
            HudElement.Text(2, "LAST HUD EVENT", 38f, 282f, 17f, Color.rgb(139, 158, 181), HudFontStyle.BOLD),
            HudElement.TextDynamic(2, "", eventValue, 38f, 311f, Color.WHITE, 15f),

            HudElement.Text(2, "800x400  H.264  target 30 fps  2.5 Mbps", 28f, 370f, 17f, Color.rgb(139, 158, 181)),
            HudElement.SineWave(
                z = 1,
                x = 485f,
                y = 365f,
                width = 285f,
                amplitude = 10f,
                wavelength = 90f,
                phaseSpeedRadPerSec = 3.2f,
                samples = 100,
                color = Color.rgb(124, 255, 178),
                strokeWidth = 2f
            )
        )
    )

    private fun buildStandbyScene(): HudScene = HudScene(
        clearColor = Color.rgb(8, 12, 18),
        designWidth = targetWidth.toFloat(),
        designHeight = targetHeight.toFloat(),
        scaleMode = HudScaleMode.FILL,
        elements = listOf(
            HudElement.Rect(0, 0f, 0f, 800f, 62f, Color.rgb(17, 25, 37)),
            HudElement.Text(
                1,
                "CFMOTO DASH",
                28f,
                41f,
                26f,
                Color.WHITE,
                HudFontStyle.BOLD
            ),
            HudElement.TextDynamic(
                1,
                "",
                clockValue,
                650f,
                40f,
                Color.rgb(124, 255, 178),
                23f
            ),
            HudElement.Rect(0, 104f, 108f, 696f, 292f, Color.rgb(14, 21, 31), 18f),
            HudElement.Text(
                1,
                "CONNECTED",
                276f,
                180f,
                38f,
                Color.rgb(124, 255, 178),
                HudFontStyle.BOLD
            ),
            HudElement.Text(
                1,
                "Ready for navigation",
                257f,
                227f,
                24f,
                Color.WHITE
            ),
            HudElement.Text(
                1,
                "Ride safely",
                337f,
                357f,
                19f,
                Color.rgb(139, 158, 181)
            )
        )
    )

    private companion object {
        val CLOCK_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
