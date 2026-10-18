package com.bonsaponsi.baptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class EpgTest {

    /** Sample from a MUX-3 recording (TVP1, 2026-10-14): PAT, PMT, AIT and part of the EIT. */
    private fun mux3(): TsAnalyzer = TsAnalyzer().apply {
        javaClass.classLoader!!.getResourceAsStream("mux3_tables.ts").use { feed(it) }
    }

    private fun utc(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(ms)

    @Test
    fun eitFromRealRecordingCoversWholeMultiplex() {
        val a = mux3()
        // EPG for all services of the multiplex, not just the recorded one.
        assertTrue("services: ${a.events.keys}", a.events.keys.containsAll(listOf(1, 2, 9, 31, 32, 33, 35, 51)))
        assertEquals(ServiceCatalog.ONID_POLAND to 3, a.eitIds[1]!!.second to a.eitIds[1]!!.first)
        val tvp1 = a.events[1]!!.values.sortedBy { it.startMs }
        assertTrue("TVP1 programmes: ${tvp1.size}", tvp1.size > 20)
        val sekielski = tvp1.first { it.title.startsWith("Sekielski") }
        assertEquals("Sekielski wieczorową porą - odc. 41 - wydanie specjalne", sekielski.title)
        assertEquals("2026-10-14 01:30", utc(sekielski.startMs))
        // Schedule covers several days ahead.
        assertTrue((tvp1.last().startMs - tvp1.first().startMs) > 3 * 86_400_000L)
        assertTrue(tvp1.all { it.durationSec in 1..(12 * 3600) })
    }

    @Test
    fun realTvp1HasAudio() {
        // TVP1: E-AC-3 as private PES with descriptor 0x7A - a channel with audio, not a virtual one.
        val p = mux3().programs.values.single()
        assertTrue(p.hasVideo)
        assertTrue(p.hasAudio)
    }

    @Test
    fun descriptionsAndControlCodes() {
        val all = mux3().events.values.flatMap { it.values }
        assertTrue("no descriptions", all.count { it.description.isNotBlank() } > all.size / 4)
        // DVB control codes must not leak into the text.
        assertTrue(all.none { e -> (e.title + e.description).any { it in '\u0080'..'\u009F' } })
    }

    @Test
    fun catalogNamesRecordedServices() {
        assertEquals("TVP1 HD", ServiceCatalog.lookup(ServiceCatalog.ONID_POLAND, 1)?.name)
        assertEquals(34, ServiceCatalog.lookup(ServiceCatalog.ONID_POLAND, 35)?.lcn) // TVP Info
        assertEquals("TVP3 Gdańsk", ServiceCatalog.lookup(ServiceCatalog.ONID_POLAND, 9)?.name)
        assertEquals("RMF FM", ServiceCatalog.lookup(ServiceCatalog.ONID_POLAND, 4096)?.name)
        assertTrue(ServiceCatalog.lookup(ServiceCatalog.ONID_POLAND, 4096)!!.radio)
        assertNull("other network", ServiceCatalog.lookup(0x2174, 1))
        assertNull(ServiceCatalog.lookup(ServiceCatalog.ONID_POLAND, 12345))
    }

    private fun p(title: String, startMin: Int, endMin: Int) = Programme(title, "", startMin * 60_000L, endMin * 60_000L)

    @Test
    fun nowNext() {
        val list = listOf(p("A", 0, 30), p("B", 30, 60), p("C", 60, 90))
        assertEquals("B" to "C", EpgSchedule.nowNext(list, 45 * 60_000L).let { it.first?.title to it.second?.title })
        assertEquals(null to "A", EpgSchedule.nowNext(list, -5 * 60_000L).let { it.first?.title to it.second?.title })
        assertEquals(null to null, EpgSchedule.nowNext(list, 100 * 60_000L))
        assertEquals(500, p("X", 0, 60).progress(30 * 60_000L))
    }

    @Test
    fun mergeReplacesChangedScheduleAndDropsOld() {
        val now = 24 * 60 * 60_000L
        val old = listOf(p("old", 0, 60), p("A", 1440, 1500), p("B", 1500, 1560), p("C", 1560, 1620))
        // New data: schedule change from 1500 (B -> longer B2, overlapping C).
        val fresh = listOf(p("B2", 1500, 1590))
        val merged = EpgSchedule.merge(old, fresh, now)
        assertEquals(listOf("A", "B2"), merged.map { it.title })
    }
}
