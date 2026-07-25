package dev.zanderp.opencfmoto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkedSafetyTest {
    @Test
    fun unknownOrStoppedSpeedDoesNotHardBlock() {
        assertFalse(ParkedSafety.isMoving(null))
        assertFalse(ParkedSafety.isMoving(0f))
        assertFalse(ParkedSafety.isMoving(ParkedSafety.BLOCK_ABOVE_KMH))
    }

    @Test
    fun movingSpeedHardBlocks() {
        assertTrue(ParkedSafety.isMoving(ParkedSafety.BLOCK_ABOVE_KMH + 0.1f))
        assertTrue(ParkedSafety.isMoving(60f))
    }
}
