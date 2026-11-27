package com.bonsaponsi.baptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SagemcomAudioTest {

    private fun track(id: String, lang: String, encoding: Int?, ad: Boolean = false, sagemcom: Boolean = true) =
        SagemcomAudio.Track(id, lang, encoding, ad, sagemcom)

    // TVP1 as the Sagemcom DCTIW362 lists it: three E-AC-3 tracks marked mono and the AC-4 track without an encoding.
    private val tvp1 = listOf(
        track("103:pol:0:1", "pol", 7),
        track("104:qaa:0:1", "qaa", 7),
        track("108:aux:1:1", "aux", 7, ad = true),
        track("109:mul:0:0", "mul", null),
    )

    @Test
    fun ac4DefaultIsReplacedByTheFirstKnownTrack() {
        assertEquals("103:pol:0:1", SagemcomAudio.betterDefault(tvp1, "109:mul:0:0", null))
    }

    @Test
    fun preferredLanguageWinsAmongKnownTracks() {
        assertEquals("104:qaa:0:1", SagemcomAudio.betterDefault(tvp1, "109:mul:0:0", "qaa"))
    }

    @Test
    fun audioDescriptionIsTheLastChoice() {
        val tracks = listOf(track("108:aux:1:1", "aux", 7, ad = true), track("109:mul:0:0", "mul", null), track("103:pol:0:1", "pol", 7))
        assertEquals("103:pol:0:1", SagemcomAudio.betterDefault(tracks, "109:mul:0:0", "aux"))
    }

    @Test
    fun knownSelectionOrOtherInputsAreLeftAlone() {
        assertNull(SagemcomAudio.betterDefault(tvp1, "103:pol:0:1", null))
        assertNull(SagemcomAudio.betterDefault(listOf(track("531", "pol", null, sagemcom = false), track("532", "qaa", 7, sagemcom = false)), "531", null))
        assertNull(SagemcomAudio.betterDefault(listOf(track("109:mul:0:0", "mul", null)), "109:mul:0:0", null))
    }

    @Test
    fun allMonoEac3TracksLookSilent() {
        assertTrue(SagemcomAudio.likelySilent(tvp1)) // the AC-4 track (no encoding) does not play either
        assertTrue(SagemcomAudio.likelySilent(listOf(track("3503:pol:0:1", "pol", 7))))
    }

    @Test
    fun stereoMpegOrOtherInputsDoNotLookSilent() {
        assertFalse(SagemcomAudio.likelySilent(listOf(track("903:pol:0:3", "pol", 7), track("908:aux:1:3", "aux", 7, ad = true))))
        assertFalse(SagemcomAudio.likelySilent(listOf(track("312:pol:0:0", "pol", 2))))
        assertFalse(SagemcomAudio.likelySilent(listOf(track("204:org:0:1", "org", 3)))) // AC-3 marked mono
        assertFalse(SagemcomAudio.likelySilent(listOf(track("3503:pol:0:1", "pol", 7), track("204:org:0:1", "org", 3))))
        assertFalse(SagemcomAudio.likelySilent(listOf(track("109:mul:0:0", "mul", null))))
        assertFalse(SagemcomAudio.likelySilent(listOf(track("1:pol:0:1", "pol", 7, sagemcom = false))))
        assertFalse(SagemcomAudio.likelySilent(emptyList()))
    }
}
