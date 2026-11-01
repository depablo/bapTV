package com.bonsaponsi.baptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
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
}
