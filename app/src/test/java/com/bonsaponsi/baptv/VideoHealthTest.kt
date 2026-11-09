package com.bonsaponsi.baptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoHealthTest {

    @Test
    fun emptyMemoryKnowsNoChannel() {
        assertFalse(VideoHealth.contains(emptySet(), 2306))
    }

    @Test
    fun addedChannelIsRemembered() {
        val ids = VideoHealth.withChannel(emptySet(), 2306)
        assertTrue(VideoHealth.contains(ids, 2306))
        assertFalse(VideoHealth.contains(ids, 2307))
    }

    @Test
    fun addingKeepsTheOtherChannelsAndDoesNotChangeTheOriginalSet() {
        val original = setOf("2306")
        val ids = VideoHealth.withChannel(original, 2308)
        assertTrue(VideoHealth.contains(ids, 2306))
        assertTrue(VideoHealth.contains(ids, 2308))
        assertFalse(VideoHealth.contains(original, 2308))
    }

    @Test
    fun addingTheSameChannelTwiceKeepsOneEntry() {
        val ids = VideoHealth.withChannel(VideoHealth.withChannel(emptySet(), 2306), 2306)
        assertEquals(1, ids.size)
    }
}
