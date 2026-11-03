package com.bonsaponsi.baptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream

class IptvGuideTest {

    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE tv SYSTEM "xmltv.dtd">
        <tv generator-info-name="test">
          <channel id="TVPInfo.pl"><display-name lang="pl">TVP Info</display-name></channel>
          <channel id="euronews.fr"><display-name>Euronews HD</display-name><display-name>Euronews English</display-name></channel>
          <channel id="other.pl"><display-name>Other</display-name></channel>
          <programme start="20261031200000 +0100" stop="20261031203000 +0100" channel="TVPInfo.pl">
            <title lang="pl">Teleexpress</title><title lang="en">Teleexpress (EN)</title>
            <desc lang="pl">Wiadomości &amp; pogoda</desc><category lang="en">News</category>
          </programme>
          <programme start="20261031203000 +0100" stop="20261031210000 +0100" channel="TVPInfo.pl">
            <title>Sport</title><category>Sports</category>
          </programme>
          <programme start="20261031190000 +0000" stop="20261031200000 +0000" channel="euronews.fr"><title>Euronews Now</title></programme>
          <programme start="20261031190000 +0000" stop="20261031200000 +0000" channel="other.pl"><title>Not ours</title></programme>
          <programme start="20261031190000 +0000" stop="20261031200000 +0000" channel="Polsat.pl"><title>No channel element</title></programme>
          <programme start="20261001190000 +0000" stop="20261001200000 +0000" channel="TVPInfo.pl"><title>Too old</title></programme>
        </tv>
    """.trimIndent()

    private val matcher get() = IptvGuide.Matcher(listOf(
        IptvGuide.Matcher.Target(1, "TVP Info HD", "tvpinfo.pl"),
        IptvGuide.Matcher.Target(2, "Euronews", ""),
        IptvGuide.Matcher.Target(3, "Polsat", "Polsat.pl"),
        IptvGuide.Matcher.Target(4, "Unmatched", ""),
    ))

    private val from = IptvGuide.parseTime("20261030000000 +0000")!!
    private val to = IptvGuide.parseTime("20261103000000 +0000")!!

    @Test
    fun parsesAndMatchesProgrammes() {
        val guide = IptvGuide.parse(xml.byteInputStream(), matcher, from, to)
        assertEquals(setOf(1L, 2L, 3L), guide.keys)
        val info = guide.getValue(1)
        assertEquals(listOf("Teleexpress", "Sport"), info.map { it.title })
        assertEquals("Wiadomości & pogoda", info[0].description)
        assertEquals(ChannelCategory.NEWS, info[0].genre)
        assertEquals(ChannelCategory.SPORT, info[1].genre)
        assertEquals(IptvGuide.parseTime("20261031190000 +0000"), info[0].startMs)
        assertEquals("Euronews Now", guide.getValue(2).single().title)
        assertEquals("No channel element", guide.getValue(3).single().title)
    }

    @Test
    fun readsGzip() {
        val packed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(xml.toByteArray()) } }.toByteArray()
        val guide = IptvGuide.parse(IptvGuide.unpack(packed.inputStream()), matcher, from, to)
        assertEquals(2, guide.getValue(1).size)
        // Plain XML passes through unchanged.
        assertEquals(2, IptvGuide.parse(IptvGuide.unpack(xml.byteInputStream()), matcher, from, to).getValue(1).size)
    }

    @Test
    fun parsesXmltvTimes() {
        assertEquals(IptvGuide.parseTime("20261031190000 +0000"), IptvGuide.parseTime("20261031200000 +0100"))
        assertEquals(IptvGuide.parseTime("20261031190000 +0000"), IptvGuide.parseTime("20261031190000"))
        assertEquals(IptvGuide.parseTime("20261031190000 +0000"), IptvGuide.parseTime("202610311900 +0000"))
        assertEquals(IptvGuide.parseTime("20261031203000 +0000"), IptvGuide.parseTime("20261031190000 -0130"))
        assertEquals(1793473200000L, IptvGuide.parseTime("20261031190000 +0000"))
        assertNull(IptvGuide.parseTime("2026"))
        assertNull(IptvGuide.parseTime("20261331190000 +0000"))
        assertTrue(IptvGuide.parseTime("20261031190000 +0000")!! > 0)
    }

    @Test
    fun programmesWithoutStopEndAtTheNextStart() {
        val doc = """
            <tv>
              <programme start="20261031200000 +0000" channel="TVPInfo.pl"><title>First</title></programme>
              <programme start="20261031203000 +0000" channel="TVPInfo.pl"><title>Second</title></programme>
              <programme start="20261031190000 +0000" channel="TVPInfo.pl"><title>Earlier</title></programme>
              <programme start="bad" stop="20261031200000 +0000" channel="TVPInfo.pl"><title>No start</title></programme>
            </tv>
        """.trimIndent()
        val list = IptvGuide.parse(doc.byteInputStream(), matcher, from, to).getValue(1)
        assertEquals(listOf("Earlier", "First", "Second"), list.map { it.title })
        assertEquals(IptvGuide.parseTime("20261031200000 +0000"), list[0].endMs)
        assertEquals(IptvGuide.parseTime("20261031203000 +0000"), list[1].endMs)
        // The last one gets an hour.
        assertEquals(list[2].startMs + 3600_000L, list[2].endMs)
    }

    @Test
    fun tvgIdMatchWinsOverNameMatch() {
        val doc = """
            <tv>
              <channel id="tvn.pl"><display-name>TVN</display-name></channel>
              <channel id="tvn-plus1.pl"><display-name>TVN</display-name></channel>
              <channel id="polsat-sd.pl"><display-name>Polsat 2</display-name></channel>
              <channel id="polsat2.pl"><display-name>Polsat 2</display-name></channel>
              <programme start="20261031200000 +0000" stop="20261031210000 +0000" channel="tvn.pl"><title>Main</title></programme>
              <programme start="20261031210000 +0000" stop="20261031220000 +0000" channel="tvn-plus1.pl"><title>Shifted</title></programme>
              <programme start="20261031200000 +0000" stop="20261031210000 +0000" channel="polsat-sd.pl"><title>SD</title></programme>
              <programme start="20261031210000 +0000" stop="20261031220000 +0000" channel="polsat2.pl"><title>HD</title></programme>
            </tv>
        """.trimIndent()
        val guide = IptvGuide.parse(doc.byteInputStream(), IptvGuide.Matcher(listOf(
            IptvGuide.Matcher.Target(1, "TVN", "tvn-plus1.pl"),
            IptvGuide.Matcher.Target(2, "Polsat 2", ""),
        )), from, to)
        assertEquals(listOf("Shifted"), guide.getValue(1).map { it.title })
        // Without a tvg-id only the first channel with the name is taken.
        assertEquals(listOf("SD"), guide.getValue(2).map { it.title })
    }

    @Test
    fun limitedStreamStopsAtLimitAndDeadline() {
        val big = IptvGuide.LimitedInputStream(ByteArray(100).inputStream(), 10)
        assertEquals(10, big.read(ByteArray(50), 0, 50))
        try { big.read(); fail() } catch (e: IOException) { assertEquals("guide too large", e.message) }
        var now = 0L
        val slow = IptvGuide.LimitedInputStream(ByteArray(100).inputStream(), 1000, deadlineMs = 5, clock = { now })
        assertEquals(0, slow.read())
        now = 6
        try { slow.read(); fail() } catch (e: IOException) { assertEquals("guide download too slow", e.message) }
    }

    @Test
    fun failedRefreshWaitsBeforeTheNextAttempt() {
        val day = 24 * 3600_000L
        assertTrue(IptvGuide.isStale(day, successMs = 0, triedMs = 0))
        assertFalse(IptvGuide.isStale(day, successMs = 0, triedMs = day - 60_000))
        assertTrue(IptvGuide.isStale(day, successMs = 0, triedMs = day - IptvGuide.RETRY_AFTER_MS))
        assertFalse(IptvGuide.isStale(day, successMs = day - 3600_000L, triedMs = 0))
    }
}
