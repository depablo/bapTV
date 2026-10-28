package com.bonsaponsi.baptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvTest {

    @Test
    fun parsesExtendedM3u() {
        val playlist = Iptv.parseM3u(
            """
            #EXTM3U x-tvg-url="https://example.com/guide.xml.gz,https://example.com/other.xml"
            #EXTINF:-1 tvg-id="TVPInfo.pl" tvg-logo="https://example.com/info.png" group-title="News",TVP Info
            https://example.com/info/playlist.m3u8
            #EXTINF:-1 tvg-id="" group-title="Kids, Family" http-user-agent="Agent/1.0",Bajki, dla dzieci (720p)
            #EXTVLCOPT:http-referrer=https://example.com/
            http://example.com/kids.m3u8
            #EXTINF:-1,Radio
            #EXTGRP:Radio
            http://example.com/radio.aac
            """.trimIndent(),
        )
        assertEquals("https://example.com/guide.xml.gz", playlist.epgUrl)
        assertEquals(3, playlist.streams.size)
        val (info, kids, radio) = playlist.streams
        assertEquals("TVP Info", info.name)
        assertEquals("News", info.group)
        assertEquals("TVPInfo.pl", info.tvgId)
        assertEquals("https://example.com/info.png", info.logo)
        assertNull(info.userAgent)
        // A comma inside a quoted attribute does not end the attributes; the title keeps its own commas.
        assertEquals("Bajki, dla dzieci (720p)", kids.name)
        assertEquals("Kids, Family", kids.group)
        assertEquals("Agent/1.0", kids.userAgent)
        assertEquals("https://example.com/", kids.referrer)
        assertEquals("Radio", radio.group)
    }

    @Test
    fun marksDrmAndResetsBetweenEntries() {
        val streams = Iptv.parseM3u(
            """
            #EXTM3U
            #EXTINF:-1,Protected
            #KODIPROP:inputstream.adaptive.license_type=clearkey
            #EXTVLCOPT:http-user-agent=Special
            https://example.com/a.mpd
            #EXTINF:-1,Plain
            https://example.com/b.m3u8
            https://example.com/no-extinf.m3u8
            """.trimIndent(),
        ).streams
        assertEquals(3, streams.size)
        assertTrue(streams[0].drm)
        assertEquals("Special", streams[0].userAgent)
        assertFalse(streams[1].drm)
        assertNull(streams[1].userAgent)
        // An address without #EXTINF is named after itself.
        assertEquals("https://example.com/no-extinf.m3u8", streams[2].name)
    }
}
