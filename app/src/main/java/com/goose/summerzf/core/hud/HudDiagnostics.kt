package com.goose.summerzf.core.hud

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque

enum class HudConnectionState {
    IDLE,
    DISCOVERING,
    HOST_RESOLVED,
    STARTING_SESSION,
    WAITING_FOR_VIEW_AREA,
    STARTING_ENCODER,
    STREAMING,
    STOPPING,
    ERROR
}

enum class HudLogLevel { DEBUG, INFO, WARN, ERROR }

data class HudLogEntry(
    val timestampMs: Long,
    val level: HudLogLevel,
    val category: String,
    val message: String
) {
    fun asText(): String = "${TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(timestampMs))} " +
        "${level.name.padEnd(5)} [$category] $message"

    private companion object {
        val TIMESTAMP_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                .withZone(ZoneId.systemDefault())
    }
}

data class HudDiagnosticsSnapshot(
    val state: HudConnectionState = HudConnectionState.IDLE,
    val stateSinceMs: Long = System.currentTimeMillis(),
    val sessionStartedMs: Long? = null,
    val renderedFrames: Long = 0,
    val renderFps: Double = 0.0,
    val missedRenderDeadlines: Long = 0,
    val encodedFrames: Long = 0,
    val encoderFps: Double = 0.0,
    val encodedBitrateBps: Long = 0,
    val lastSampleBytes: Int = 0,
    val pushedFrames: Long = 0,
    val pushFailures: Long = 0,
    val lastHudEvent: String = "None",
    val lastError: String? = null,
    val recentLogs: List<HudLogEntry> = emptyList()
) {
    val uptimeMs: Long
        get() = sessionStartedMs?.let { (System.currentTimeMillis() - it).coerceAtLeast(0) } ?: 0
}

class HudDiagnostics {
    private val lock = Any()
    private val allLogs = ArrayDeque<HudLogEntry>()
    private val _snapshot = MutableStateFlow(HudDiagnosticsSnapshot())

    val snapshot: StateFlow<HudDiagnosticsSnapshot> = _snapshot.asStateFlow()

    fun beginSession() {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            _snapshot.value = HudDiagnosticsSnapshot(
                state = HudConnectionState.IDLE,
                stateSinceMs = now,
                sessionStartedMs = now,
                recentLogs = recentLogsLocked()
            )
            appendLocked(HudLogLevel.INFO, "SESSION", "New streaming session requested")
        }
    }

    fun transition(state: HudConnectionState, detail: String? = null) {
        synchronized(lock) {
            _snapshot.value = _snapshot.value.copy(
                state = state,
                stateSinceMs = System.currentTimeMillis(),
                lastError = _snapshot.value.lastError
            )
            appendLocked(
                if (state == HudConnectionState.ERROR) HudLogLevel.ERROR else HudLogLevel.INFO,
                "STATE",
                buildString {
                    append(state.name)
                    if (!detail.isNullOrBlank()) append(": $detail")
                }
            )
        }
    }

    fun log(level: HudLogLevel, category: String, message: String) {
        synchronized(lock) {
            appendLocked(level, category, message)
        }
    }

    fun recordError(message: String, fatal: Boolean = false) {
        synchronized(lock) {
            _snapshot.value = _snapshot.value.copy(
                lastError = message,
                state = if (fatal) HudConnectionState.ERROR else _snapshot.value.state,
                stateSinceMs = if (fatal) System.currentTimeMillis() else _snapshot.value.stateSinceMs
            )
            appendLocked(
                HudLogLevel.ERROR,
                if (fatal) "FATAL" else "ERROR",
                message
            )
        }
    }

    fun recordHudEvent(summary: String) {
        synchronized(lock) {
            _snapshot.value = _snapshot.value.copy(lastHudEvent = summary)
            appendLocked(HudLogLevel.INFO, "HUD_EVENT", summary)
        }
    }

    fun updateRenderer(stats: HudRendererStats) {
        synchronized(lock) {
            _snapshot.value = _snapshot.value.copy(
                renderedFrames = stats.totalFrames,
                renderFps = stats.fps,
                missedRenderDeadlines = stats.missedDeadlines
            )
        }
    }

    fun updateEncoder(
        stats: HudEncoderStats,
        pushedFrames: Long,
        pushFailures: Long
    ) {
        synchronized(lock) {
            _snapshot.value = _snapshot.value.copy(
                encodedFrames = stats.totalFrames,
                encoderFps = stats.fps,
                encodedBitrateBps = stats.bitrateBps,
                lastSampleBytes = stats.lastSampleBytes,
                pushedFrames = pushedFrames,
                pushFailures = pushFailures
            )
        }
    }

    fun export(context: Context): File {
        val snapshotCopy: HudDiagnosticsSnapshot
        val logsCopy: List<HudLogEntry>
        synchronized(lock) {
            snapshotCopy = _snapshot.value
            logsCopy = allLogs.toList()
        }

        val outputDir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val output = File(outputDir, "summer-zephyr-${System.currentTimeMillis()}.log")
        output.bufferedWriter().use { writer ->
            writer.appendLine("Summer Zephyr diagnostics")
            writer.appendLine("Generated: ${Instant.now()}")
            writer.appendLine("Fixed video mode: 800x400")
            writer.appendLine("State: ${snapshotCopy.state}")
            writer.appendLine("Rendered frames: ${snapshotCopy.renderedFrames}")
            writer.appendLine("Render FPS: ${"%.2f".format(snapshotCopy.renderFps)}")
            writer.appendLine("Missed render deadlines: ${snapshotCopy.missedRenderDeadlines}")
            writer.appendLine("Encoded frames: ${snapshotCopy.encodedFrames}")
            writer.appendLine("Encoder FPS: ${"%.2f".format(snapshotCopy.encoderFps)}")
            writer.appendLine("Encoded bitrate: ${snapshotCopy.encodedBitrateBps} bps")
            writer.appendLine("Pushed frames: ${snapshotCopy.pushedFrames}")
            writer.appendLine("Push failures: ${snapshotCopy.pushFailures}")
            writer.appendLine("Last HUD event: ${snapshotCopy.lastHudEvent}")
            writer.appendLine("Last error: ${snapshotCopy.lastError ?: "None"}")
            writer.appendLine()
            writer.appendLine("Event log")
            writer.appendLine("---------")
            logsCopy.forEach { writer.appendLine(it.asText()) }
        }
        return output
    }

    private fun appendLocked(level: HudLogLevel, category: String, message: String) {
        allLogs.addLast(
            HudLogEntry(
                timestampMs = System.currentTimeMillis(),
                level = level,
                category = category,
                message = message
            )
        )
        while (allLogs.size > MAX_LOG_ENTRIES) allLogs.removeFirst()
        _snapshot.value = _snapshot.value.copy(recentLogs = recentLogsLocked())
    }

    private fun recentLogsLocked(): List<HudLogEntry> =
        allLogs.toList().takeLast(RECENT_LOG_ENTRIES)

    private companion object {
        const val MAX_LOG_ENTRIES = 2_000
        const val RECENT_LOG_ENTRIES = 8
    }
}
