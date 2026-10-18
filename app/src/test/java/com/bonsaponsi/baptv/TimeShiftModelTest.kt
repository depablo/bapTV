package com.bonsaponsi.baptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeShiftModelTest {

    @Test
    fun delayGrowsOnlyWhilePaused() {
        val m = TimeShiftModel()
        assertEquals(0, m.delayMs(1_000))
        m.pause(10_000)
        assertTrue(m.active && m.paused)
        assertEquals(15_000, m.delayMs(25_000))
        m.resume(25_000)
        // Delayed playback - the delay stays constant.
        assertEquals(15_000, m.delayMs(60_000))
        m.pause(60_000)
        assertEquals(25_000, m.delayMs(70_000))
    }

    @Test
    fun seekIsLimitedToBuffer() {
        val m = TimeShiftModel()
        m.pause(100_000)
        m.resume(130_000) // 30 s delay, buffer starts at 97 s
        val now = 130_000L
        assertEquals(33_000, m.maxDelayMs(now))
        // 60 s back - at most to the start of the buffer.
        assertEquals(33_000, m.targetDelayAfterSeek(now, m.delayMs(now), -60_000))
        // 60 s forward - at most to live.
        assertEquals(0, m.targetDelayAfterSeek(now, m.delayMs(now), 60_000))
        assertEquals(20_000, m.targetDelayAfterSeek(now, 30_000, 10_000))
        m.seekApplied(now, 20_000)
        assertEquals(20_000, m.delayMs(now + 5_000))
        assertEquals(now + 5_000 - 20_000, m.positionMs(now + 5_000))
    }

    @Test
    fun bufferIsCappedAtThirtyMinutes() {
        val m = TimeShiftModel()
        m.pause(0)
        val later = 45 * 60_000L
        assertEquals(TimeShiftModel.MAX_BUFFER_MS, m.delayMs(later))
        assertEquals(later - TimeShiftModel.MAX_BUFFER_MS, m.bufferStartMs(later))
    }

    @Test
    fun resetReturnsToLive() {
        val m = TimeShiftModel()
        m.pause(0)
        m.reset()
        assertFalse(m.active)
        assertEquals(0, m.delayMs(10_000))
    }

    @Test
    fun formatsDelay() {
        assertEquals("45 s", TimeShiftModel.formatDelay(45_400))
        assertEquals("1 min 30 s", TimeShiftModel.formatDelay(90_000))
        assertEquals("2 min", TimeShiftModel.formatDelay(120_000))
        assertEquals("1 h 5 min", TimeShiftModel.formatDelay(3_900_000))
    }
}
