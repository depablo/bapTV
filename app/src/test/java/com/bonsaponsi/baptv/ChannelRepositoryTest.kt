package com.bonsaponsi.baptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelRepositoryTest {

    @Test
    fun genreFromCanonicalTakesFirstKnownGenre() {
        assertEquals(1, ChannelRepository.genreFromCanonical("MOVIES"))
        assertEquals(4, ChannelRepository.genreFromCanonical("\"SPORTS\",\"NEWS\""))
        assertEquals(2, ChannelRepository.genreFromCanonical("NEWS, MOVIES"))
        assertEquals(-1, ChannelRepository.genreFromCanonical("UNKNOWN"))
        assertEquals(-1, ChannelRepository.genreFromCanonical(null))
        assertEquals(-1, ChannelRepository.genreFromCanonical(""))
    }

    @Test
    fun numberKeyOrdersMajorThenMinor() {
        assertTrue(ChannelRepository.numberKey("2") < ChannelRepository.numberKey("10"))
        assertTrue(ChannelRepository.numberKey("12") < ChannelRepository.numberKey("12.1"))
        assertEquals(ChannelRepository.numberKey("12.1"), ChannelRepository.numberKey("12-1"))
        assertEquals(Long.MAX_VALUE, ChannelRepository.numberKey(""))
    }

    @Test
    fun numberKeyDoesNotOverflow() {
        assertEquals(Long.MAX_VALUE, ChannelRepository.numberKey("99999999999999999"))
        assertEquals(Long.MAX_VALUE, ChannelRepository.numberKey("TVP"))
        assertTrue(ChannelRepository.numberKey("12.99999") < ChannelRepository.numberKey("13"))
    }
}
