package com.goose.summerzf.feature.theme

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.goose.summerzf.core.hud.HudConnectionState
import com.goose.summerzf.core.runtime.HudRuntime
import com.goose.summerzf.core.theme.HudBounds
import com.goose.summerzf.core.theme.HudPalette
import com.goose.summerzf.core.theme.HudPalettes
import com.goose.summerzf.core.theme.HudTheme
import com.goose.summerzf.core.theme.HudThemePresets
import com.goose.summerzf.core.theme.HudThemeRepository
import com.goose.summerzf.core.theme.HudThemeRules
import com.goose.summerzf.core.theme.HudWidgetLayout
import com.goose.summerzf.core.theme.HudWidgetType
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeEditorScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val repository = remember(context) { HudRuntime.themes(context.applicationContext) }
    val controller = remember(context) { HudRuntime.controller(context.applicationContext) }
    val contentRuntime = remember(context) { HudRuntime.content(context.applicationContext) }
    val theme by repository.theme.collectAsState()
    val diagnostics by controller.diagnostics.snapshot.collectAsState()
    var selectedWidgetId by remember { mutableStateOf(theme.widgets.firstOrNull { it.enabled }?.id) }
    var mediaAccess by remember { mutableStateOf(contentRuntime.mediaAccessGranted()) }

    LaunchedEffect(Unit) {
        while (isActive) {
            mediaAccess = contentRuntime.mediaAccessGranted()
            if (mediaAccess) contentRuntime.refreshMedia()
            delay(1_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Theme creator") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(theme.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Drag a component to move it. Drag its lower-right handle to resize. " +
                            "The editor snaps to an 8 px grid and prevents map/media overlap. The clock is an overlay.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    HudThemePreview(
                        theme = theme,
                        selectedWidgetId = selectedWidgetId,
                        repository = repository,
                        onSelect = { selectedWidgetId = it }
                    )
                    Text(
                        "HUD canvas: 800×400 · ${if (diagnostics.state == HudConnectionState.STREAMING) "changes are live" else "saved for the next connection"}",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            PresetCard(repository)
            ComponentCard(theme, repository)
            PaletteCard(theme.palette, repository)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Now playing", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (mediaAccess) {
                            "Media access is enabled. The media component can display title, artist, source, artwork, and playback state."
                        } else {
                            "Android requires notification-listener access to read another app's active MediaSession metadata."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            }.onFailure {
                                Toast.makeText(context, "Unable to open notification access settings", Toast.LENGTH_LONG).show()
                            }
                        }
                    ) {
                        Text(if (mediaAccess) "Review media access" else "Enable media access")
                    }
                }
            }

            OutlinedButton(onClick = repository::reset) {
                Text("Reset theme")
            }
        }
    }
}

@Composable
private fun HudThemePreview(
    theme: HudTheme,
    selectedWidgetId: String?,
    repository: HudThemeRepository,
    onSelect: (String) -> Unit
) {
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(2f)
            .background(Color(theme.palette.background), RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
    ) {
        val canvasWidthPx = with(density) { maxWidth.toPx() }
        val scale = canvasWidthPx / HudThemeRules.CANVAS_WIDTH.toFloat()

        theme.widgets
            .filter { it.enabled }
            .sortedBy { it.zIndex }
            .forEach { widget ->
                val bounds = widget.bounds
                val widthDp = with(density) { (bounds.width * scale).toDp() }
                val heightDp = with(density) { (bounds.height * scale).toDp() }
                val selected = selectedWidgetId == widget.id

                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (bounds.x * scale).roundToInt(),
                                (bounds.y * scale).roundToInt()
                            )
                        }
                        .size(widthDp, heightDp)
                        .background(Color(theme.palette.panel), RoundedCornerShape(8.dp))
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) Color(theme.palette.accent) else Color(theme.palette.mutedText),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .pointerInput(widget.id, scale) {
                            var workingBounds = bounds
                            detectDragGestures(
                                onDragStart = {
                                    onSelect(widget.id)
                                    workingBounds = repository.theme.value.widgets
                                        .firstOrNull { it.id == widget.id }
                                        ?.bounds ?: bounds
                                }
                            ) { change, dragAmount ->
                                change.consume()
                                val requested = workingBounds.copy(
                                    x = workingBounds.x + (dragAmount.x / scale).roundToInt(),
                                    y = workingBounds.y + (dragAmount.y / scale).roundToInt()
                                )
                                if (repository.updateWidgetBounds(widget.id, requested)) {
                                    workingBounds = repository.theme.value.widgets
                                        .first { it.id == widget.id }.bounds
                                }
                            }
                        }
                ) {
                    WidgetPreview(widget, theme.palette)
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(24.dp)
                            .background(Color(theme.palette.accent), RoundedCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
                            .pointerInput(widget.id, scale) {
                                var workingBounds = bounds
                                detectDragGestures(
                                    onDragStart = {
                                        onSelect(widget.id)
                                        workingBounds = repository.theme.value.widgets
                                            .firstOrNull { it.id == widget.id }
                                            ?.bounds ?: bounds
                                    }
                                ) { change, dragAmount ->
                                    change.consume()
                                    val requested = workingBounds.copy(
                                        width = workingBounds.width + (dragAmount.x / scale).roundToInt(),
                                        height = workingBounds.height + (dragAmount.y / scale).roundToInt()
                                    )
                                    if (repository.updateWidgetBounds(widget.id, requested)) {
                                        workingBounds = repository.theme.value.widgets
                                            .first { it.id == widget.id }.bounds
                                    }
                                }
                            }
                    )
                }
            }
    }
}

@Composable
private fun WidgetPreview(widget: HudWidgetLayout, palette: HudPalette) {
    when (widget.type) {
        HudWidgetType.MAP -> MapPreview(palette)
        HudWidgetType.MEDIA -> MediaPreview(palette)
        HudWidgetType.CLOCK -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("12:34", color = Color(palette.accent), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MapPreview(palette: HudPalette) {
    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(Color(0xFF263744))
            val road = Path().apply {
                moveTo(0f, size.height * 0.72f)
                cubicTo(
                    size.width * 0.25f,
                    size.height * 0.48f,
                    size.width * 0.55f,
                    size.height * 0.90f,
                    size.width,
                    size.height * 0.32f
                )
            }
            drawPath(road, Color(0xFFD9D2B6), style = Stroke(width = (size.minDimension * 0.06f).coerceAtLeast(3f)))
            drawPath(road, Color(0xFF6D7780), style = Stroke(width = (size.minDimension * 0.015f).coerceAtLeast(1f)))
            drawCircle(
                color = Color(palette.accent),
                radius = (size.minDimension * 0.06f).coerceAtLeast(4f),
                center = Offset(size.width / 2f, size.height / 2f)
            )
        }
        Text(
            "MAP",
            modifier = Modifier.padding(8.dp),
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun MediaPreview(palette: HudPalette) {
    Column(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .aspectRatio(1f)
                .background(Color(palette.panelAlt), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("ART", color = Color(palette.mutedText))
        }
        Text("Track title", color = Color(palette.text), fontWeight = FontWeight.Bold)
        Text("Artist", color = Color(palette.mutedText), style = MaterialTheme.typography.labelSmall)
        Text("PLAYING", color = Color(palette.accent), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PresetCard(repository: HudThemeRepository) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Layout presets", style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HudThemePresets.all().forEach { preset ->
                    FilterChip(
                        modifier = Modifier.fillMaxWidth(),
                        selected = false,
                        onClick = { repository.applyPreset(preset) },
                        label = { Text(preset.name) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ComponentCard(theme: HudTheme, repository: HudThemeRepository) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Components", style = MaterialTheme.typography.titleMedium)
            HudWidgetType.entries.forEach { type ->
                val enabled = theme.widgets.firstOrNull { it.type == type }?.enabled == true
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(type.displayName())
                    Switch(
                        checked = enabled,
                        onCheckedChange = { repository.setWidgetEnabled(type, it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PaletteCard(selected: HudPalette, repository: HudThemeRepository) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Color theme", style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HudPalettes.named().forEach { (name, palette) ->
                    FilterChip(
                        modifier = Modifier.fillMaxWidth(),
                        selected = selected == palette,
                        onClick = { repository.setPalette(palette) },
                        label = { Text(name) },
                        leadingIcon = {
                            Canvas(modifier = Modifier.size(16.dp)) {
                                drawRect(Color(palette.accent), size = Size(size.width, size.height))
                            }
                        }
                    )
                }
            }
        }
    }
}

private fun HudWidgetType.displayName(): String = when (this) {
    HudWidgetType.MAP -> "Map"
    HudWidgetType.MEDIA -> "Now playing"
    HudWidgetType.CLOCK -> "Clock"
}
