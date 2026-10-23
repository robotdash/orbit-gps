package com.robotdash.orbitgps

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper

/**
 * Reads the phone's GPS chip directly (works with no cell data).
 *
 * Every fix comes out with a heading and speed filled in:
 *  - heading: from the GPS when moving; otherwise worked out from the last two
 *    positions; when stopped, the last good heading is kept so the arrow doesn't spin.
 *  - speed: from the GPS, or worked out from distance / time.
 */
class GpsTracker(context: Context, private val onFix: (Location) -> Unit) {

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var last: Location? = null
    private var lastBearing = 0f

    /** Seconds since the last GPS fix, for the "GPS lost" warning. */
    val secondsSinceFix: Long
        get() = last?.let { (System.currentTimeMillis() - it.time) / 1000 } ?: Long.MAX_VALUE

    val isGpsEnabled: Boolean
        get() = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) = handle(loc)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    fun start() {
        // Show something right away from the last known GPS fix, if any.
        lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { handle(it) }
        // One fix per second from the GPS chip itself.
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
    }

    fun stop() = lm.removeUpdates(listener)

    private fun handle(raw: Location) {
        val loc = Location(raw)
        val prev = last

        // --- Speed (m/s) ---
        if (!loc.hasSpeed() && prev != null) {
            val dt = (loc.time - prev.time) / 1000f
            if (dt > 0.2f) loc.speed = prev.distanceTo(loc) / dt
        }
        val speed = if (loc.hasSpeed()) loc.speed else 0f

        // --- Heading (degrees) ---
        val moving = speed > 1.5f // ~5 km/h
        when {
            moving && loc.hasBearing() -> lastBearing = loc.bearing
            moving && prev != null && prev.distanceTo(loc) > 4f -> lastBearing = prev.bearingTo(loc).let { if (it < 0) it + 360f else it }
            // stopped or creeping: keep the last heading
        }
        loc.bearing = lastBearing

        last = loc
        onFix(loc)
    }
}
