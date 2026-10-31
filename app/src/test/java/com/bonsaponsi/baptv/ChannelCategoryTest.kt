package com.bonsaponsi.baptv

import com.bonsaponsi.baptv.ChannelCategory.EDUCATION
import com.bonsaponsi.baptv.ChannelCategory.ENTERTAINMENT
import com.bonsaponsi.baptv.ChannelCategory.FILM
import com.bonsaponsi.baptv.ChannelCategory.KIDS
import com.bonsaponsi.baptv.ChannelCategory.LEISURE
import com.bonsaponsi.baptv.ChannelCategory.MUSIC
import com.bonsaponsi.baptv.ChannelCategory.NEWS
import com.bonsaponsi.baptv.ChannelCategory.NONE
import com.bonsaponsi.baptv.ChannelCategory.SPORT
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelCategoryTest {

    @Test
    fun knownPolishChannels() {
        assertEquals(ENTERTAINMENT, ChannelCategory.guess("TVP1 HD"))
        assertEquals(ENTERTAINMENT, ChannelCategory.guess("Polsat"))
        assertEquals(NEWS, ChannelCategory.guess("TVN 24 HD"))
        assertEquals(NEWS, ChannelCategory.guess("TVP3 Gdańsk"))
        assertEquals(NEWS, ChannelCategory.guess("TVP3 Gdansk"))
        assertEquals(NEWS, ChannelCategory.guess("TOK FM"))
        assertEquals(KIDS, ChannelCategory.guess("TVP ABC"))
        assertEquals(MUSIC, ChannelCategory.guess("Polo TV"))
        assertEquals(FILM, ChannelCategory.guess("Stopklatka TV"))
        assertEquals(EDUCATION, ChannelCategory.guess("TVP Historia"))
        assertEquals(LEISURE, ChannelCategory.guess("TVN Turbo HD"))
    }

    @Test
    fun keywordsInName() {
        assertEquals(SPORT, ChannelCategory.guess("Eurosport 1"))
        assertEquals(SPORT, ChannelCategory.guess("Polsat Sport 2"))
        assertEquals(SPORT, ChannelCategory.guess("Eleven Sports 1"))
        assertEquals(NEWS, ChannelCategory.guess("TVP Info"))
        assertEquals(NEWS, ChannelCategory.guess("Polsat News Polityka"))
        assertEquals(MUSIC, ChannelCategory.guess("Radio ZET"))
        assertEquals(MUSIC, ChannelCategory.guess("RMF MAXX"))
        assertEquals(EDUCATION, ChannelCategory.guess("Discovery Channel"))
        assertEquals(NONE, ChannelCategory.guess("Kanał 7"))
        // Playlist notes in brackets do not hide a known name.
        assertEquals(FILM, ChannelCategory.guess("Puls 2 (1080p)"))
        assertEquals(NEWS, ChannelCategory.guess("Polsat News 2 (1080p) [Geo-blocked]"))
        assertEquals(MUSIC, ChannelCategory.guess("4 Fun TV"))
        assertEquals(SPORT, ChannelCategory.guess("PFL MMA (720p)"))
        assertEquals(KIDS, ChannelCategory.guess("Polsat JimJam (576p)"))
    }

    @Test
    fun playlistGroupComesBeforeName() {
        assertEquals(KIDS, ChannelCategory.guess("Bajki", "Kids, Family"))
        assertEquals(FILM, ChannelCategory.guess("Some channel", "Filmy"))
        assertEquals(NEWS, ChannelCategory.guess("Euronews", "Informacyjne"))
        assertEquals(MUSIC, ChannelCategory.guess("4 Fun TV", "Muzyczne"))
        // An unknown group falls back to the name.
        assertEquals(SPORT, ChannelCategory.guess("Sport TV", "Poland"))
        // A known channel keeps its category whatever the group says.
        assertEquals(NEWS, ChannelCategory.guess("TVN 24", "General"))
    }

    @Test
    fun normalizesNames() {
        assertEquals("tvn 24", ChannelCategory.normalize("TVN 24 HD"))
        assertEquals("zlote przeboje", ChannelCategory.normalize("Złote Przeboje"))
        assertEquals("canal+ sport", ChannelCategory.normalize("CANAL+ Sport FHD"))
    }

    @Test
    fun categoryFromGuide() {
        val h = 3_600_000L
        fun p(start: Int, hours: Int, genre: Int) = Programme("", "", start * h, (start + hours) * h, genre)
        assertEquals(SPORT, ChannelCategory.fromProgrammes(listOf(p(0, 2, 4), p(2, 2, 4), p(4, 1, 2), p(5, 2, 4), p(7, 1, -1))))
        // No genre fills half of the time.
        assertEquals(NONE, ChannelCategory.fromProgrammes(listOf(p(0, 2, 4), p(2, 2, 2), p(4, 2, 1), p(6, 2, -1))))
        // Too few programmes to tell.
        assertEquals(NONE, ChannelCategory.fromProgrammes(listOf(p(0, 2, 4), p(2, 2, 4))))
    }
}
