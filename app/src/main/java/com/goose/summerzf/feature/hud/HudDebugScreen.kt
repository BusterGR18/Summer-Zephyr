package com.goose.summerzf.feature.hud

import android.content.Intent
import android.graphics.Color
import android.text.format.DateFormat
import android.widget.Toast
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import com.goose.summerzf.R
import com.goose.summerzf.core.hud.DoubleBufferedValue
import com.goose.summerzf.core.hud.HudConnectionState
import com.goose.summerzf.core.hud.HudDiagnosticsSnapshot
import com.goose.summerzf.core.hud.HudElement
import com.goose.summerzf.core.hud.HudScaleMode
import com.goose.summerzf.core.hud.HudScene
import com.goose.summerzf.core.hud.HudStreamController
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HudDebugScreen(
    hudStreamController: HudStreamController = rememberHudStreamController(),
    onBack: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val diagnostics by hudStreamController.diagnostics.snapshot.collectAsState()
    val isStreaming = diagnostics.state == HudConnectionState.STREAMING
    var selectedScene by remember { mutableStateOf("Unchanged") }
    val timeBuffer = remember { DoubleBufferedValue("--:--:--") }
    val timeFormatter = remember {
        if (DateFormat.is24HourFormat(ctx)) {
            DateTimeFormatter.ofPattern("HH:mm:ss")
        } else {
            DateTimeFormatter.ofPattern("hh:mm:ss a")
        }
    }

    LaunchedEffect(isStreaming) {
        if (!isStreaming) return@LaunchedEffect
        while (isActive) {
            timeBuffer.publish(timeFormatter.format(LocalTime.now()))
            delay(1_000)
        }
    }

    fun applyScene(name: String, action: () -> Unit) {
        try {
            action()
            selectedScene = name
        } catch (e: Exception) {
            Toast.makeText(ctx, e.message ?: "Unable to change scene", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Developer tools") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Test scenes", style = MaterialTheme.typography.titleMedium)
                    Text("Active selection: $selectedScene")
                    Text(
                        if (isStreaming) {
                            "Scene changes are sent immediately to the motorcycle display."
                        } else {
                            "Connect the HUD from the main screen before selecting a test scene."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                applyScene("Diagnostics") {
                                    hudStreamController.setDiagnosticScene()
                                }
                            },
                            enabled = isStreaming
                        ) {
                            Text("Diagnostics")
                        }

                        Button(
                            onClick = {
                                applyScene("Animated clock") {
                                    hudStreamController.setRendererScene(buildTimeScene(timeBuffer))
                                }
                            },
                            enabled = isStreaming
                        ) {
                            Text("Animated clock")
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            applyScene("User theme") {
                                hudStreamController.setStandbyScene()
                            }
                        },
                        enabled = isStreaming
                    ) {
                        Text("Restore user theme")
                    }
                }
            }

            OutlinedButton(
                onClick = {
                    try {
                        val sendIntent = hudStreamController.createDiagnosticsShareIntent()
                        ctx.startActivity(Intent.createChooser(sendIntent, "Share diagnostics"))
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            ) {
                Text("Share diagnostics log")
            }

            DiagnosticsCard(diagnostics)
        }
    }
}

@Composable
private fun DiagnosticsCard(diagnostics: HudDiagnosticsSnapshot) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text("Stream diagnostics", style = MaterialTheme.typography.titleMedium)
            Text("State: ${diagnostics.state.name}")
            Text("Video: 800×400 · H.264 · target 30 fps · 2.5 Mbps")
            Text("Render: ${"%.1f".format(diagnostics.renderFps)} fps · ${diagnostics.renderedFrames} frames")
            Text("Encoder: ${"%.1f".format(diagnostics.encoderFps)} fps · ${diagnostics.encodedFrames} frames")
            Text("Measured bitrate: ${"%.2f".format(diagnostics.encodedBitrateBps / 1_000_000.0)} Mbps")
            Text("Pushed: ${diagnostics.pushedFrames} · failures: ${diagnostics.pushFailures}")
            Text("Missed render deadlines: ${diagnostics.missedRenderDeadlines}")
            Text("Last HUD event: ${diagnostics.lastHudEvent}")
            diagnostics.lastError?.let {
                Text("Last error: $it", color = MaterialTheme.colorScheme.error)
            }

            if (diagnostics.recentLogs.isNotEmpty()) {
                Text(
                    "Recent events",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
                diagnostics.recentLogs.takeLast(8).forEach { entry ->
                    Text(entry.asText(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun buildTimeScene(timeBuffer: DoubleBufferedValue<String>): HudScene = HudScene(
    clearColor = Color.LTGRAY,
    designWidth = 800f,
    designHeight = 400f,
    scaleMode = HudScaleMode.FILL,
    elements = listOf(
        HudElement.AnimatedImage(
            z = 0,
            frameResIds = listOf(R.drawable.home_f0, R.drawable.home_f1),
            frameDurationMs = 200,
            left = 0f,
            top = 0f,
            width = 800f,
            height = 400f
        ),
        HudElement.TextDynamic(
            z = 1,
            prefix = "",
            value = timeBuffer,
            x = 50f,
            y = 80f,
            color = "#FF7CFFB2".toColorInt(),
            sizePx = 32f
        ),
        HudElement.SineWave(
            z = 2,
            x = 235f,
            y = 370f,
            width = 360f,
            amplitude = 15f,
            wavelength = 140f,
            phaseSpeedRadPerSec = 3.0f,
            samples = 160,
            color = 0xFF7CFFB2.toInt(),
            strokeWidth = 1f
        )
    )
)
