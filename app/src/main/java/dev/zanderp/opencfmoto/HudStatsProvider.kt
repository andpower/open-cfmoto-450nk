// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.format.DateFormat
import java.util.Date

/**
 * Shared holder the [AaCompositor] reads to draw the on-dash HUD (idea B1). Written by
 * [HudStatsProvider] (data) and [AndroidAutoService] (enabled/unit from prefs); read on the GL
 * thread. Mirrors the app's process-global style ([AaVideoBridge], [BikeLink]).
 */
object HudBus {
    @Volatile var enabled: Boolean = false
    @Volatile var unit: SpeedUnit = SpeedUnit.KMH
    @Volatile var elements: Set<HudElement> = HudElement.ALL
    @Volatile var stats: RideStats = RideStats()
    @Volatile var clock: String = ""
}

/**
 * Feeds [HudBus] while projecting: a lightweight 1 Hz GPS listener (speed + trip distance/time,
 * independent of the trip-logging/save path in [TripRecorder]), plus phone battery and thermal
 * status. A 1 s ticker publishes a fresh [RideStats] even when the bike is stopped (no GPS updates)
 * so the clock/battery still tick. Started/stopped with the AA session by [AndroidAutoService];
 * no-op without the location permission.
 */
class HudStatsProvider(private val context: Context, private val log: (String) -> Unit) : LocationListener {

    private val handler = Handler(Looper.getMainLooper())
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    @Volatile private var running = false

    // Live ride accumulators (this projection session), updated on GPS fixes.
    private var distanceMeters = 0.0
    private var movingTimeMs = 0L
    private var curSpeedKmh = 0
    private var hasFix = false
    private var lastFix: Location? = null
    private var lastFixAt = 0L

    private fun hasLocationPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (running) return
        running = true
        distanceMeters = 0.0; movingTimeMs = 0L; curSpeedKmh = 0; hasFix = false
        lastFix = null; lastFixAt = 0L
        if (hasLocationPermission() && lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            } catch (_: SecurityException) {
                log("[HUD] location permission missing — HUD speed will show '--'")
            }
        } else {
            log("[HUD] GPS unavailable — HUD speed will show '--'")
        }
        handler.postDelayed(ticker, TICK_MS)
        log("[HUD] stats provider started")
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(ticker)
        try { lm?.removeUpdates(this) } catch (_: Exception) {}
        log("[HUD] stats provider stopped")
    }

    override fun onLocationChanged(location: Location) {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        val prev = lastFix
        val dtMs = if (prev != null && lastFixAt > 0) now - lastFixAt else 0L
        val d = prev?.distanceTo(location) ?: 0f
        val speedMs = if (location.hasSpeed()) location.speed
            else if (dtMs in 1..5000) d / (dtMs / 1000f) else 0f
        if (prev != null && speedMs >= MIN_MOVING_MS && dtMs in 1..5000) {
            distanceMeters += d
            movingTimeMs += dtMs
        }
        curSpeedKmh = (speedMs * 3.6f).coerceAtLeast(0f).toInt()
        hasFix = true
        lastFix = location
        lastFixAt = now
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            try { publish() } catch (_: Exception) {}
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun publish() {
        val (batteryPct, charging) = readBattery()
        val thermal = try { powerManager.currentThermalStatus } catch (_: Exception) { 0 }
        HudBus.stats = RideStats(
            speedKmh = curSpeedKmh,
            distanceMeters = distanceMeters,
            movingTimeMs = movingTimeMs,
            hasFix = hasFix,
            batteryPct = batteryPct,
            charging = charging,
            thermalStatus = thermal,
        )
        HudBus.clock = DateFormat.getTimeFormat(context).format(Date())
    }

    /** Current battery percent + charging state from the sticky battery intent (no persistent receiver). */
    private fun readBattery(): Pair<Int, Boolean> {
        return try {
            val i: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            pct to charging
        } catch (_: Exception) {
            -1 to false
        }
    }

    companion object {
        private const val TICK_MS = 1000L
        private const val MIN_MOVING_MS = 0.8f   // ~2.9 km/h floor, matches TripRecorder
    }
}
