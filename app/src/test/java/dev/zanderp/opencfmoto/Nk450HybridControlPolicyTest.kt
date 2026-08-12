package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Nk450HybridControlPolicyTest {
    private fun detector() = Nk450VolumeGestureDetector(doubleTapWindowMs = { 450L })

    @Test
    fun singlePressRemainsVolume() {
        val d = detector().onVolumeChange(previous = 7, current = 8, nowMs = 1_000L)
        assertEquals(Nk450VolumeGestureDetector.Kind.SINGLE_VOLUME, d?.kind)
        assertEquals(+1, d?.direction)
        assertNull(d?.restoreVolume)
    }

    @Test
    fun twoQuickPressesBecomeNavigationAndRestoreOriginalVolume() {
        val detector = detector()
        detector.onVolumeChange(previous = 7, current = 8, nowMs = 1_000L)
        val d = detector.onVolumeChange(previous = 8, current = 9, nowMs = 1_250L)
        assertEquals(Nk450VolumeGestureDetector.Kind.DOUBLE_NAVIGATION, d?.kind)
        assertEquals(+1, d?.direction)
        assertEquals(7, d?.restoreVolume)
    }

    @Test
    fun oppositeDirectionsStayVolume() {
        val detector = detector()
        val up = detector.onVolumeChange(previous = 7, current = 8, nowMs = 1_000L)
        val down = detector.onVolumeChange(previous = 8, current = 7, nowMs = 1_200L)
        assertEquals(Nk450VolumeGestureDetector.Kind.SINGLE_VOLUME, up?.kind)
        assertEquals(Nk450VolumeGestureDetector.Kind.SINGLE_VOLUME, down?.kind)
    }

    @Test
    fun slowSecondPressStaysVolume() {
        val detector = detector()
        detector.onVolumeChange(previous = 7, current = 8, nowMs = 1_000L)
        val d = detector.onVolumeChange(previous = 8, current = 9, nowMs = 1_700L)
        assertEquals(Nk450VolumeGestureDetector.Kind.SINGLE_VOLUME, d?.kind)
    }

    @Test
    fun coalescedLargeJumpIsDoubleNavigation() {
        val d = detector().onVolumeChange(previous = 7, current = 10, nowMs = 1_000L)
        assertEquals(Nk450VolumeGestureDetector.Kind.DOUBLE_NAVIGATION, d?.kind)
        assertEquals(7, d?.restoreVolume)
    }
}
