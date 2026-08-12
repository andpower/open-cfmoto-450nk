// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.zanderp.opencfmoto

import kotlin.math.abs

/**
 * 450NK handlebar policy.
 *
 * The stock pod uses the same ▲/▼ rocker for two jobs that must keep working while MotoPlay/AA is
 * projected:
 *  - one short press changes music volume normally;
 *  - two quick presses in the same direction navigate the projected UI;
 *  - a long press is delivered as an AVRCP previous/next transport key and is forwarded to music.
 *
 * This detector only decides the volume side. It deliberately does not consume a single press.
 * When a double is recognized, [restoreVolume] is the level from before the pair so navigation does
 * not leave the music two steps louder/quieter.
 */
internal class Nk450VolumeGestureDetector(
    private val doubleTapWindowMs: () -> Long,
    private val coalescedDoubleSteps: Int = 3,
) {
    enum class Kind { SINGLE_VOLUME, DOUBLE_NAVIGATION }

    data class Decision(
        val kind: Kind,
        val direction: Int,
        val restoreVolume: Int? = null,
    )

    private var lastDirection = 0
    private var lastTapAt = 0L
    private var pairStartVolume = -1

    fun onVolumeChange(previous: Int, current: Int, nowMs: Long): Decision? {
        if (previous < 0 || current == previous) return null
        val jump = current - previous
        val direction = if (jump > 0) +1 else -1

        // Some CFMOTO firmwares coalesce a fast ▲▲ / ▼▼ into one larger AVRCP absolute-volume write.
        if (abs(jump) >= coalescedDoubleSteps) {
            reset()
            return Decision(Kind.DOUBLE_NAVIGATION, direction, restoreVolume = previous)
        }

        val window = doubleTapWindowMs().coerceAtLeast(1L)
        val gap = nowMs - lastTapAt
        val samePair = direction == lastDirection && lastTapAt > 0L && gap in 1 until window
        if (samePair) {
            val restore = if (pairStartVolume >= 0) pairStartVolume else previous
            reset()
            return Decision(Kind.DOUBLE_NAVIGATION, direction, restoreVolume = restore)
        }

        lastDirection = direction
        lastTapAt = nowMs
        pairStartVolume = previous
        return Decision(Kind.SINGLE_VOLUME, direction)
    }

    fun reset() {
        lastDirection = 0
        lastTapAt = 0L
        pairStartVolume = -1
    }
}
