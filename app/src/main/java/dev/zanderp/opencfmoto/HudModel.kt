// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import java.util.Locale

/**
 * Pure model + math for the on-dash rider HUD (idea B1). No Android dependencies here, so the
 * geometry, formatting and layout are all host-unit-testable ([HudModelTest]); the Android glue
 * (GPS/battery/thermal source, Canvas→GL rendering) lives in [HudStatsProvider] / [HudRenderer] /
 * [AaCompositor].
 *
 * Concept: when the screen fit is FIT, the AA video is letterboxed into the bike canvas, leaving
 * black bars. Instead of wasting them we paint live ride telemetry there — speed (hero), trip
 * distance/time, clock, phone battery and a hot-phone warning — without touching the AA image.
 */

/** Speed/distance unit for the HUD. */
enum class SpeedUnit(val speedLabel: String) { KMH("km/h"), MPH("mph") }

/** A snapshot of everything the HUD can show. Produced by [HudStatsProvider]; pure data. */
data class RideStats(
    val speedKmh: Int = 0,
    val distanceMeters: Double = 0.0,
    val movingTimeMs: Long = 0L,
    val hasFix: Boolean = false,
    val batteryPct: Int = -1,        // -1 = unknown
    val charging: Boolean = false,
    val thermalStatus: Int = 0,      // PowerManager.THERMAL_STATUS_* (0 = none)
) {
    /** Phone running warm enough to surface a warning (matches AdaptiveVideoController's throttle point). */
    val hot: Boolean get() = thermalStatus >= 2   // THERMAL_STATUS_MODERATE
}

/** Pure formatting for HUD values. Uses [Locale.US] so host tests are deterministic. */
object HudFormat {
    fun speed(stats: RideStats, unit: SpeedUnit): String {
        if (!stats.hasFix) return "--"
        val v = if (unit == SpeedUnit.MPH) Math.round(stats.speedKmh * 0.621371).toInt() else stats.speedKmh
        return v.coerceAtLeast(0).toString()
    }

    fun distance(meters: Double, unit: SpeedUnit): String =
        if (unit == SpeedUnit.MPH) {
            val mi = meters / 1609.344
            if (mi < 10) String.format(Locale.US, "%.1f mi", mi) else "${Math.round(mi)} mi"
        } else {
            val km = meters / 1000.0
            if (km < 10) String.format(Locale.US, "%.1f km", km) else "${Math.round(km)} km"
        }

    /** m:ss under an hour, h:mm:ss past it. */
    fun duration(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun battery(pct: Int, charging: Boolean): String {
        if (pct < 0) return "--"
        return if (charging) "$pct%+" else "$pct%"
    }
}

/** A detected letterbox bar the HUD can paint into (bike-canvas pixels). */
data class BarRect(val x: Int, val y: Int, val w: Int, val h: Int) {
    /** Tall-and-narrow bars stack their cells vertically; wide-and-short ones lay out in a row. */
    val vertical: Boolean get() = h >= w
    val area: Long get() = w.toLong() * h
}

object HudBars {
    /**
     * The bars FIT letterboxing produces = the canvas minus the centered AA viewport. Returns bars
     * only in FIT mode (FILL/STRETCH cover the whole canvas, so there are none) and only those at
     * least [minThickness] px thick, so text is legible. FIT centers exactly one axis, so this yields
     * either the two side bars or the two top/bottom bars, never both.
     */
    fun detect(
        canvasW: Int, canvasH: Int,
        vpX: Int, vpY: Int, vpW: Int, vpH: Int,
        fit: ScreenFit, minThickness: Int,
    ): List<BarRect> {
        if (fit != ScreenFit.FIT) return emptyList()
        if (canvasW <= 0 || canvasH <= 0 || vpW <= 0 || vpH <= 0) return emptyList()
        val bars = ArrayList<BarRect>(2)
        val leftW = vpX
        val rightW = canvasW - (vpX + vpW)
        val topH = vpY
        val botH = canvasH - (vpY + vpH)
        if (leftW >= minThickness) bars.add(BarRect(0, 0, leftW, canvasH))
        if (rightW >= minThickness) bars.add(BarRect(vpX + vpW, 0, rightW, canvasH))
        if (topH >= minThickness) bars.add(BarRect(0, 0, canvasW, topH))
        if (botH >= minThickness) bars.add(BarRect(0, vpY + vpH, canvasW, botH))
        return bars
    }
}

/** One HUD readout: a big [value] with a small [label] under it. */
data class HudCell(val value: String, val label: String, val big: Boolean = false)

/** A telemetry item the rider can turn on/off in the HUD settings screen. Order = draw order. */
enum class HudElement(val label: String) {
    SPEED("Speed"),
    TRIP("Trip distance"),
    TIME("Ride time"),
    CLOCK("Clock"),
    BATTERY("Phone battery"),
    TEMP("Overheat warning"),
    ;

    companion object {
        val ALL: Set<HudElement> = values().toSet()
    }
}

object HudLayout {
    /**
     * Ordered cells for a stats snapshot, filtered to the [enabled] elements (rider's choice). Speed
     * is the emphasized hero; [HudElement.TEMP] only appears when [enabled] and the phone is hot.
     * Fewer enabled elements → bigger, cleaner readouts, which is how thin/tall bars are kept legible.
     */
    fun cells(
        stats: RideStats,
        unit: SpeedUnit,
        clockHHmm: String,
        enabled: Set<HudElement> = HudElement.ALL,
    ): List<HudCell> {
        val list = ArrayList<HudCell>(6)
        if (HudElement.SPEED in enabled) list.add(HudCell(HudFormat.speed(stats, unit), unit.speedLabel, big = true))
        if (HudElement.TRIP in enabled) list.add(HudCell(HudFormat.distance(stats.distanceMeters, unit), "trip"))
        if (HudElement.TIME in enabled) list.add(HudCell(HudFormat.duration(stats.movingTimeMs), "time"))
        if (HudElement.CLOCK in enabled) list.add(HudCell(clockHHmm, "clock"))
        if (HudElement.BATTERY in enabled) list.add(HudCell(HudFormat.battery(stats.batteryPct, stats.charging), "phone"))
        if (HudElement.TEMP in enabled && stats.hot) list.add(HudCell("HOT", "temp"))
        return list
    }

    /**
     * Split cells across [barCount] bars, order-preserving (speed lands in the first bar). One bar
     * gets everything; two bars split roughly evenly (so the 800MT's two side bars share the load).
     */
    fun distribute(cells: List<HudCell>, barCount: Int): List<List<HudCell>> {
        if (barCount <= 1 || cells.isEmpty()) return listOf(cells)
        val perBar = Math.ceil(cells.size.toDouble() / barCount).toInt().coerceAtLeast(1)
        return (0 until barCount)
            .map { i -> cells.drop(i * perBar).take(perBar) }
            .filter { it.isNotEmpty() }
    }

    /** A content signature — when it's unchanged the HUD bitmap need not be re-rendered/re-uploaded. */
    fun signature(cells: List<HudCell>): String =
        cells.joinToString("|") { "${it.value}~${it.label}~${it.big}" }
}
