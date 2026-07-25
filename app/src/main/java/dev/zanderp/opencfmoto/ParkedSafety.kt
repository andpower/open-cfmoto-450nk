// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Andrés Hugo Quintana and contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock

/** Best-effort motion guard for the parked-app surface. */
object ParkedSafety {
    private const val MAX_FIX_AGE_MS = 15_000L
    internal const val BLOCK_ABOVE_KMH = 5f

    fun recentSpeedKmh(context: Context): Float? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return null

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val locations = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider ->
                try {
                    lm.getLastKnownLocation(provider)
                } catch (_: SecurityException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        val freshest = locations.maxByOrNull(Location::getElapsedRealtimeNanos) ?: return null
        val ageMs = (SystemClock.elapsedRealtimeNanos() - freshest.elapsedRealtimeNanos)
            .coerceAtLeast(0L) / 1_000_000L
        if (ageMs > MAX_FIX_AGE_MS || !freshest.hasSpeed()) return null
        return freshest.speed.coerceAtLeast(0f) * 3.6f
    }

    internal fun isMoving(speedKmh: Float?): Boolean =
        speedKmh != null && speedKmh > BLOCK_ABOVE_KMH
}
