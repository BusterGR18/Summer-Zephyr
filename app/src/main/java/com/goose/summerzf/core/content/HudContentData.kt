package com.goose.summerzf.core.content

import android.graphics.Bitmap
import com.goose.summerzf.core.hud.DoubleBufferedValue
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class HudContentData {
    val mapBitmap = DoubleBufferedValue<Bitmap?>(null)
    val mapStatus = DoubleBufferedValue("Waiting for GPS")
    val location = DoubleBufferedValue("--")
    val speed = DoubleBufferedValue("-- km/h")
    val bearing = DoubleBufferedValue("--°")

    val mediaArtwork = DoubleBufferedValue<Bitmap?>(null)
    val mediaTitle = DoubleBufferedValue("Nothing playing")
    val mediaArtist = DoubleBufferedValue("Open a media app on the phone")
    val mediaSource = DoubleBufferedValue("Media access not enabled")
    val mediaState = DoubleBufferedValue("IDLE")

    val clock = DoubleBufferedValue("--:--")

    fun updateClock() {
        clock.publish(LocalTime.now().format(CLOCK_FORMATTER))
    }

    private companion object {
        val CLOCK_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
