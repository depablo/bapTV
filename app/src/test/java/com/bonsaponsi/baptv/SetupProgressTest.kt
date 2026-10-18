package com.bonsaponsi.baptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetupProgressTest {

    @Test
    fun estimateMatchesMeasuredRun() {
        // 2026-10-16: 47 UHF frequencies, 74 channels.
        val e = SetupProgress.estimate(SetupProgress.DEFAULT_RATES, frequencies = 48, channels = 74)
        assertEquals(144.0, e.scanSec, 0.1)
        assertEquals(80 * 2.7, e.discoverySec, 0.1)
        assertEquals(74 * 7.8, e.identifySec, 0.1)
        assertEquals("~16 min", SetupProgress.format(e.totalSec))
    }

    @Test
    fun formatting() {
        assertEquals("~5 s", SetupProgress.format(2.0))
        assertEquals("~45 s", SetupProgress.format(41.0))
        assertEquals("~2 min", SetupProgress.format(61.0))
        assertEquals("~1 h 5 min", SetupProgress.format(3600.0 + 4 * 60 + 10))
    }

    @Test
    fun blendAndExtrapolation() {
        assertEquals(5.0, SetupProgress.blend(4.0, 6.0), 1e-9)
        assertEquals(4.0, SetupProgress.blend(4.0, Double.NaN), 1e-9)
        assertEquals(30.0, SetupProgress.remainingFromProgress(30.0, 50)!!, 1e-9)
        assertNull(SetupProgress.remainingFromProgress(1.0, 2))
        assertNull(SetupProgress.remainingFromProgress(100.0, 100))
    }

    @Test
    fun formatRoundsHoursCorrectly() {
        org.junit.Assert.assertEquals("~2 h 0 min", SetupProgress.format(7199.0))
        org.junit.Assert.assertEquals("~1 h 10 min", SetupProgress.format(4200.0))
    }
}
