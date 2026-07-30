// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context

/**
 * How long to wait before committing a single handlebar press as a tap (vs waiting for a double-tap).
 * Longer = more forgiving doubles, but singles feel laggier. Applied live by [MediaButtonBridge].
 */
enum class DoubleTapDelay(val ms: Long, val label: String) {
    FAST(200L, "200 ms — snappier singles"),
    NORMAL(300L, "300 ms — balanced (recommended)"),
    SLOW(450L, "450 ms — forgiving doubles"),
    /** For pods that need a long physical press per click — leave room for a second press. */
    VERY_SLOW(1000L, "1000 ms — slow doubles (long physical press)"),
}

/**
 * How long ◀ / ▶ / ★ must be held to count as a hold gesture instead of a tap. Applied live to
 * every hold channel (not only Select). Ignored when [ButtonTimingPrefs.holdsEnabled] is false.
 */
enum class LongPressDelay(val ms: Long, val label: String) {
    SHORT(500L, "500 ms — quicker hold"),
    NORMAL(600L, "600 ms — balanced (recommended)"),
    LONG(800L, "800 ms — deliberate hold"),
}

/**
 * Handlebar gesture timing. Global (not per-bike) — it's about how the rider taps, not the dash.
 */
object ButtonTimingPrefs {
    private const val PREFS = "opencfmoto_btn_timing"
    private const val KEY_DOUBLE = "double_tap_ms"
    private const val KEY_LONG = "long_press_ms"
    private const val KEY_HOLDS = "holds_enabled"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun doubleTap(ctx: Context): DoubleTapDelay {
        val ms = prefs(ctx).getLong(KEY_DOUBLE, DoubleTapDelay.NORMAL.ms)
        return DoubleTapDelay.entries.firstOrNull { it.ms == ms } ?: DoubleTapDelay.NORMAL
    }

    fun setDoubleTap(ctx: Context, delay: DoubleTapDelay) {
        prefs(ctx).edit().putLong(KEY_DOUBLE, delay.ms).apply()
    }

    fun longPress(ctx: Context): LongPressDelay {
        val ms = prefs(ctx).getLong(KEY_LONG, LongPressDelay.NORMAL.ms)
        return LongPressDelay.entries.firstOrNull { it.ms == ms } ?: LongPressDelay.NORMAL
    }

    fun setLongPress(ctx: Context, delay: LongPressDelay) {
        prefs(ctx).edit().putLong(KEY_LONG, delay.ms).apply()
    }

    /**
     * When false, [MediaButtonBridge] never classifies a press as hold (KEY_UP always goes to
     * the tap / ×2 path). Use with a long [doubleTap] window when the bike needs a long physical
     * press per click — otherwise that press is eaten as a hold.
     */
    fun holdsEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_HOLDS, true)

    fun setHoldsEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_HOLDS, enabled).apply()
    }

    fun doubleTapMs(ctx: Context): Long = doubleTap(ctx).ms
    fun longPressMs(ctx: Context): Long = longPress(ctx).ms
}
