package com.goose.summerzf.core.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.goose.summerzf.core.content.HudContentData
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import java.util.Locale
import kotlin.math.roundToInt

class HudMapRuntime(
    private val context: Context,
    private val data: HudContentData
) {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var snapshotter: MapSnapshotter? = null
    private var snapshotInProgress = false
    private var snapshotPending = false
    private var started = false
    private var viewportWidth = 520
    private var viewportHeight = 384
    private var latestLocation: Location? = null
    private var lastSnapshotLocation: Location? = null
    private var lastSnapshotAtMs = 0L

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            latestLocation = location
            data.location.publish(
                String.format(Locale.US, "%.5f, %.5f", location.latitude, location.longitude)
            )
            data.speed.publish(
                if (location.hasSpeed()) {
                    "${(location.speed * 3.6f).roundToInt()} km/h"
                } else {
                    "-- km/h"
                }
            )
            data.bearing.publish(
                if (location.hasBearing()) "${location.bearing.roundToInt()}°" else "--°"
            )
            data.mapStatus.publish(
                if (location.hasBearing()) "Following · ${location.bearing.roundToInt()}°" else "Following location"
            )
            requestSnapshot(location)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) {
            data.mapStatus.publish("Waiting for location provider")
        }
    }

    init {
        MapLibre.getInstance(context)
    }

    fun setViewport(width: Int, height: Int) {
        val sanitizedWidth = width.coerceIn(128, 1024)
        val sanitizedHeight = height.coerceIn(128, 1024)
        if (sanitizedWidth == viewportWidth && sanitizedHeight == viewportHeight) return
        viewportWidth = sanitizedWidth
        viewportHeight = sanitizedHeight
        mainHandler.post {
            recreateSnapshotter()
            // A size change needs a new bitmap even when the phone has not
            // moved since the previous snapshot.
            lastSnapshotLocation = null
            lastSnapshotAtMs = 0L
            latestLocation?.let(::requestSnapshot)
        }
    }

    fun start() {
        if (started) return
        started = true
        if (!hasLocationPermission()) {
            data.mapStatus.publish("Location permission required")
            return
        }
        mainHandler.post {
            startLocationUpdates()
        }
    }

    fun stop() {
        if (!started) return
        started = false
        mainHandler.removeCallbacksAndMessages(null)
        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: Exception) {
            // Provider may already be detached.
        }
        snapshotter?.cancel()
        snapshotter = null
        snapshotInProgress = false
        snapshotPending = false
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!started || !hasLocationPermission()) return
        data.mapStatus.publish("Waiting for GPS")

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        providers.forEach { provider ->
            if (runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)) {
                runCatching {
                    locationManager.requestLocationUpdates(
                        provider,
                        LOCATION_INTERVAL_MS,
                        LOCATION_DISTANCE_METERS,
                        locationListener,
                        Looper.getMainLooper()
                    )
                }
            }
        }

        val last = providers
            .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null) locationListener.onLocationChanged(last)
    }

    private fun recreateSnapshotter() {
        snapshotter?.cancel()
        snapshotter = null
        snapshotInProgress = false
        snapshotPending = false
    }

    private fun ensureSnapshotter(location: Location): MapSnapshotter {
        snapshotter?.let { return it }
        return MapSnapshotter(
            context,
            MapSnapshotter.Options(viewportWidth, viewportHeight)
                .withPixelRatio(1f)
                .withStyleBuilder(Style.Builder().fromUri(DEFAULT_STYLE_URI))
                .withCameraPosition(cameraFor(location))
        ).also { snapshotter = it }
    }

    private fun cameraFor(location: Location): CameraPosition = CameraPosition.Builder()
        .target(LatLng(location.latitude, location.longitude))
        .zoom(DEFAULT_ZOOM)
        .bearing(if (location.hasBearing()) location.bearing.toDouble() else 0.0)
        .tilt(0.0)
        .build()

    private fun requestSnapshot(location: Location) {
        if (!started) return
        val now = System.currentTimeMillis()
        val previous = lastSnapshotLocation
        val moved = previous == null || previous.distanceTo(location) >= SNAPSHOT_DISTANCE_METERS
        val stale = now - lastSnapshotAtMs >= SNAPSHOT_INTERVAL_MS
        if (!moved && !stale) return

        mainHandler.post {
            if (!started) return@post
            if (snapshotInProgress) {
                snapshotPending = true
                return@post
            }
            snapshotInProgress = true
            snapshotPending = false
            data.mapStatus.publish("Updating map")
            val active = ensureSnapshotter(location)
            active.setCameraPosition(cameraFor(location))
            active.start({ snapshot ->
                data.mapBitmap.publish(snapshot.bitmap)
                data.mapStatus.publish(
                    if (location.hasBearing()) "Following · ${location.bearing.roundToInt()}°" else "Following location"
                )
                lastSnapshotLocation = Location(location)
                lastSnapshotAtMs = System.currentTimeMillis()
                snapshotInProgress = false
                if (snapshotPending) {
                    snapshotPending = false
                    latestLocation?.let(::requestSnapshot)
                }
            }) { error ->
                data.mapStatus.publish("Map unavailable · $error")
                snapshotInProgress = false
                if (snapshotPending) {
                    snapshotPending = false
                    latestLocation?.let(::requestSnapshot)
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_STYLE_URI = "https://tiles.openfreemap.org/styles/liberty"
        const val DEFAULT_ZOOM = 16.2
        const val LOCATION_INTERVAL_MS = 1_000L
        const val LOCATION_DISTANCE_METERS = 1f
        const val SNAPSHOT_INTERVAL_MS = 1_500L
        const val SNAPSHOT_DISTANCE_METERS = 4f
    }
}
