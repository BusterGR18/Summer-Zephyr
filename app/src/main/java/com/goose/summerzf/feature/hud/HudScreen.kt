package com.goose.summerzf.feature.hud

import android.Manifest
import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.goose.summerzf.core.hud.HudConnectionState
import com.goose.summerzf.core.hud.HudStreamController
import com.goose.summerzf.core.projection.AndroidAutoBridge
import com.goose.summerzf.core.projection.HudProjectionScaleMode
import com.goose.summerzf.core.projection.HudSceneMode
import com.goose.summerzf.core.qr.QrContent
import com.goose.summerzf.core.runtime.HudRuntime
import com.goose.summerzf.core.runtime.HudStreamService
import com.goose.summerzf.feature.ap.WifiApController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun rememberHudStreamController(): HudStreamController {
    val appContext = LocalContext.current.applicationContext
    return remember(appContext) { HudRuntime.controller(appContext) }
}

@Composable
fun rememberWifiApController(
    onHudConnected: () -> Unit,
    onHudDisconnected: (WifiApController) -> Unit = {}
): WifiApController {
    val context = LocalContext.current.applicationContext
    val currentOnConnected by rememberUpdatedState(onHudConnected)
    val currentOnDisconnected by rememberUpdatedState(onHudDisconnected)
    val controller = remember(context) {
        WifiApController(
            context = context,
            onHudConnected = { currentOnConnected() },
            onHudDisconnected = { currentOnDisconnected(it) }
        )
    }

    DisposableEffect(controller) {
        controller.startWatching()
        onDispose { controller.stopWatching() }
    }

    return controller
}

fun requiredWifiPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
}.toTypedArray()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HudScreen(
    hudQrValue: QrContent?,
    hudStreamController: HudStreamController = rememberHudStreamController(),
    onShowQrScanner: () -> Unit = {},
    onShowDebug: () -> Unit = {},
    onShowThemeEditor: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val permissions = remember { requiredWifiPermissions() }
    val diagnostics by hudStreamController.diagnostics.snapshot.collectAsState()
    val projectionRuntime = remember(ctx) { HudRuntime.projection(ctx.applicationContext) }
    val projection by projectionRuntime.snapshot.collectAsState()
    val androidAutoAvailability = remember(ctx) { AndroidAutoBridge.availability(ctx.applicationContext) }
    val isStreaming = diagnostics.state == HudConnectionState.STREAMING
    val isBusy = diagnostics.state !in setOf(HudConnectionState.IDLE, HudConnectionState.ERROR)
    var showDeveloperMenu by remember { mutableStateOf(false) }

    fun streamSessionStart() {
        try {
            HudStreamService.start(ctx.applicationContext)
        } catch (e: Exception) {
            Log.e("HudStream", "start() failed", e)
            Toast.makeText(
                ctx,
                e.message ?: "Unable to start HUD stream",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun streamSessionStop() {
        try {
            HudStreamService.stop(ctx.applicationContext)
        } catch (e: Exception) {
            Log.e("HudStream", "stop() failed", e)
        }
    }

    var pendingProjectionMode by remember { mutableStateOf(HudSceneMode.APP_CAST) }
    val mediaProjectionManager = remember(ctx) {
        ctx.getSystemService(MediaProjectionManager::class.java)
    }
    val projectionPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            HudStreamService.startProjection(
                context = ctx.applicationContext,
                resultCode = result.resultCode,
                resultData = data,
                mode = pendingProjectionMode
            )
        } else {
            Toast.makeText(ctx, "Screen sharing was cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    fun requestProjection(mode: HudSceneMode) {
        if (!isStreaming) {
            Toast.makeText(
                ctx,
                "Connect to the motorcycle display first",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        pendingProjectionMode = mode
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val config = when (mode) {
                HudSceneMode.SCREEN_CAST -> MediaProjectionConfig.createConfigForDefaultDisplay()
                HudSceneMode.APP_CAST -> MediaProjectionConfig.createConfigForUserChoice()
                else -> throw IllegalArgumentException("Unsupported projection mode: $mode")
            }
            mediaProjectionManager.createScreenCaptureIntent(config)
        } else {
            mediaProjectionManager.createScreenCaptureIntent()
        }
        projectionPermissionLauncher.launch(captureIntent)
    }

    val streamPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            streamSessionStart()
        } else {
            Toast.makeText(
                ctx,
                "Location/Wi-Fi permissions are required to connect to the HUD Wi-Fi.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun startWithPermissions() {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(ctx, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) streamSessionStart()
        else streamPermissionLauncher.launch(missing.toTypedArray())
    }

    val scope = rememberCoroutineScope()
    lateinit var wifiController: WifiApController
    wifiController = rememberWifiApController(
        onHudConnected = {
            scope.launch {
                delay(8_000)
                if (
                    wifiController.isHudConnected &&
                    hudStreamController.diagnostics.snapshot.value.state in setOf(
                        HudConnectionState.IDLE,
                        HudConnectionState.ERROR
                    )
                ) {
                    streamSessionStart()
                }
            }
        },
        onHudDisconnected = {
            streamSessionStop()
        }
    )

    var pendingHudQr by remember { mutableStateOf<QrContent.HudWifi?>(null) }
    val qrPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val qr = pendingHudQr
        pendingHudQr = null
        if (result.values.all { it } && qr != null) {
            val status = wifiController.suggestHudNetwork(qr)
            if (status != WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                Toast.makeText(
                    ctx,
                    "Failed to suggest HUD AP (status=$status)",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            Toast.makeText(
                ctx,
                "Location/Wi-Fi permissions are required to connect to the HUD Wi-Fi.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    LaunchedEffect(hudQrValue) {
        when (hudQrValue) {
            is QrContent.HudWifi -> {
                val missing = permissions.filter {
                    ContextCompat.checkSelfPermission(ctx, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                if (missing.isNotEmpty()) {
                    pendingHudQr = hudQrValue
                    qrPermissionLauncher.launch(missing.toTypedArray())
                } else {
                    val status = wifiController.suggestHudNetwork(hudQrValue)
                    if (status != WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                        Toast.makeText(
                            ctx,
                            "Failed to suggest HUD AP (status=$status)",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            is QrContent.BadQr -> Toast.makeText(ctx, "Invalid HUD QR", Toast.LENGTH_LONG).show()
            QrContent.Unknown, null -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Summer Zephyr") },
                actions = {
                    TextButton(onClick = onShowQrScanner) { Text("Scan QR") }
                    TextButton(onClick = { showDeveloperMenu = true }) { Text("⋮") }
                    DropdownMenu(
                        expanded = showDeveloperMenu,
                        onDismissRequest = { showDeveloperMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Developer tools") },
                            onClick = {
                                showDeveloperMenu = false
                                onShowDebug()
                            }
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Motorcycle display", style = MaterialTheme.typography.titleLarge)
                    Text("HUD Wi-Fi: ${if (wifiController.isHudConnected) "Connected" else "Not connected"}")
                    Text("Projection: ${userFacingState(diagnostics.state)}")
                    diagnostics.lastError?.takeIf { diagnostics.state == HudConnectionState.ERROR }?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = ::startWithPermissions, enabled = !isBusy) {
                    Text(if (isStreaming) "Connected" else "Connect")
                }
                OutlinedButton(onClick = ::streamSessionStop, enabled = isBusy) {
                    Text("Disconnect")
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Navigation", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            !isStreaming -> "Connect to the motorcycle display to begin navigation projection."
                            projection.sceneMode == HudSceneMode.CUSTOM ->
                                "The custom dashboard remains active in the background and while the phone is locked."
                            projection.sceneMode == HudSceneMode.ANDROID_AUTO ->
                                "Android Auto runs through the background HUD service; keep the private receiver build installed while testing."
                            else ->
                                "The MotoPlay connection remains active in the background. Android can stop screen capture when the phone is locked or permission is revoked."
                        }
                    )
                    Button(onClick = onShowThemeEditor) {
                        Text("Customize display")
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Projection scenes", style = MaterialTheme.typography.titleMedium)
                    Text("Current: ${projection.sceneMode.label}")
                    projection.lastError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }

                    Button(
                        onClick = { HudStreamService.showCustom(ctx.applicationContext) },
                        enabled = isStreaming && projection.sceneMode != HudSceneMode.CUSTOM
                    ) {
                        Text("Custom dashboard")
                    }
                    Button(
                        onClick = { requestProjection(HudSceneMode.APP_CAST) },
                        enabled = isStreaming && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    ) {
                        Text("Cast a selected app")
                    }
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        Text(
                            "Selected-app capture requires Android 14 or newer.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Button(
                        onClick = { requestProjection(HudSceneMode.SCREEN_CAST) },
                        enabled = isStreaming
                    ) {
                        Text("Mirror whole screen")
                    }
                    OutlinedButton(
                        onClick = {
                            HudStreamService.showAndroidAuto(ctx.applicationContext)
                            scope.launch {
                                repeat(10) {
                                    delay(200)
                                    if (AndroidAutoBridge.isRunning()) {
                                        val result = AndroidAutoBridge.triggerSelfMode(
                                            ctx,
                                            log = { message ->
                                                hudStreamController.diagnostics.log(
                                                    com.goose.summerzf.core.hud.HudLogLevel.INFO,
                                                    "ANDROID_AUTO",
                                                    message
                                                )
                                            }
                                        )
                                        result.exceptionOrNull()?.let { error ->
                                            Toast.makeText(
                                                ctx,
                                                "Android Auto could not start: ${error.message}",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                        return@launch
                                    }
                                }
                                Toast.makeText(
                                    ctx,
                                    "Android Auto receiver did not become ready",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        enabled = isStreaming && androidAutoAvailability.available
                    ) {
                        Text("Android Auto")
                    }
                    Text(
                        androidAutoAvailability.message,
                        style = MaterialTheme.typography.bodySmall
                    )

                    if (projection.sceneMode != HudSceneMode.CUSTOM) {
                        Text("Image scaling", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    HudStreamService.setProjectionScale(
                                        ctx.applicationContext,
                                        HudProjectionScaleMode.FIT
                                    )
                                },
                                enabled = projection.scaleMode != HudProjectionScaleMode.FIT
                            ) { Text("Fit") }
                            OutlinedButton(
                                onClick = {
                                    HudStreamService.setProjectionScale(
                                        ctx.applicationContext,
                                        HudProjectionScaleMode.FILL
                                    )
                                },
                                enabled = projection.scaleMode != HudProjectionScaleMode.FILL
                            ) { Text("Fill") }
                        }
                        if (projection.sceneMode in setOf(HudSceneMode.APP_CAST, HudSceneMode.SCREEN_CAST)) {
                            Text(
                                "Android requests capture approval for every new session. Selected-app capture uses Android's system picker.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun userFacingState(state: HudConnectionState): String = when (state) {
    HudConnectionState.IDLE -> "Disconnected"
    HudConnectionState.DISCOVERING -> "Finding display…"
    HudConnectionState.HOST_RESOLVED,
    HudConnectionState.STARTING_SESSION,
    HudConnectionState.WAITING_FOR_VIEW_AREA,
    HudConnectionState.STARTING_ENCODER -> "Connecting…"
    HudConnectionState.STREAMING -> "Connected"
    HudConnectionState.STOPPING -> "Disconnecting…"
    HudConnectionState.ERROR -> "Connection failed"
    else -> "Unknown"
}
