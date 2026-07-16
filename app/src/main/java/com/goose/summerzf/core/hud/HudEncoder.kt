package com.goose.summerzf.core.hud

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import kotlin.concurrent.Volatile

data class HudEncoderStats(
    val totalFrames: Long,
    val fps: Double,
    val bitrateBps: Long,
    val lastSampleBytes: Int
)

class HudEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val onStats: (HudEncoderStats) -> Unit = {},
    private val onSampleEncoded: (ByteArray) -> Unit
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(
        MediaFormat.MIMETYPE_VIDEO_AVC
    )
    val inputSurface: Surface

    @Volatile
    private var running = false
    private var drainThread: Thread? = null

    init {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            width,
            height
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        }

        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
    }

    fun start() {
        if (running) return
        running = true
        codec.start()
        drainThread = Thread(::drainLoop, "HudEncoderDrain").apply { start() }
    }

    fun stop() {
        running = false
        drainThread?.interrupt()
        drainThread?.join()
        drainThread = null
        try {
            codec.stop()
        } finally {
            codec.release()
            inputSurface.release()
        }
    }

    private fun drainLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        var totalFrames = 0L
        var intervalFrames = 0L
        var intervalBytes = 0L
        var lastSampleBytes = 0
        var statsStartedNs = System.nanoTime()

        while (running) {
            val index = try {
                codec.dequeueOutputBuffer(bufferInfo, 10_000)
            } catch (t: Throwable) {
                if (running) Log.e("HudEncoder", "Encoder drain failed", t)
                break
            }

            if (!running) break

            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.i("HudEncoder", "output format changed: ${codec.outputFormat}")
                    continue
                }
                index < 0 -> continue
                else -> {
                    try {
                        if (bufferInfo.size > 0) {
                            val outputBuffer = codec.getOutputBuffer(index)
                            if (outputBuffer != null) {
                                val data = ByteArray(bufferInfo.size)
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                outputBuffer.get(data)

                                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                    totalFrames++
                                    intervalFrames++
                                    intervalBytes += data.size
                                    lastSampleBytes = data.size
                                    onSampleEncoded(data)
                                }
                            }
                        }
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }

            val nowNs = System.nanoTime()
            val elapsedNs = nowNs - statsStartedNs
            if (elapsedNs >= 1_000_000_000L) {
                val elapsedSeconds = elapsedNs / 1_000_000_000.0
                onStats(
                    HudEncoderStats(
                        totalFrames = totalFrames,
                        fps = intervalFrames / elapsedSeconds,
                        bitrateBps = ((intervalBytes * 8.0) / elapsedSeconds).toLong(),
                        lastSampleBytes = lastSampleBytes
                    )
                )
                intervalFrames = 0
                intervalBytes = 0
                statsStartedNs = nowNs
            }
        }
    }
}
