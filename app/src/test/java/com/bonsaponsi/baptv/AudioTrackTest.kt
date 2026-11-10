package com.bonsaponsi.baptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioTrackTest {

    private fun bytes(vararg values: Int, size: Int = 16): ByteArray =
        ByteArray(size) { i -> (values.getOrElse(i) { 0 }).toByte() }

    @Test
    fun mpegLayer2At48kHzStereo() {
        // Sync 11 bits, MPEG-1, layer II, 48 kHz, stereo mode.
        val f = AudioFrames.find(TsAnalyzer.Stream(0x100, 0x03, emptyList()), bytes(0xFF, 0xFD, 0x94, 0x00), 0, 16)
        assertEquals("MPEG-1 layer 2", f?.codec)
        assertEquals(48000, f?.sampleRate)
        assertEquals(2, f?.channels)
    }

    @Test
    fun adtsLowComplexity48kHzStereo() {
        // Layer 00, LC profile, sampling index 3 (48 kHz), channel configuration 2.
        val f = AudioFrames.find(TsAnalyzer.Stream(0x100, 0x0F, emptyList()), bytes(0xFF, 0xF1, 0x4C, 0x80), 0, 16)
        assertEquals("AAC LC", f?.codec)
        assertEquals(48000, f?.sampleRate)
        assertEquals(2, f?.channels)
    }

    @Test
    fun ac3ThreeTwoWithLfe() {
        // Syncword, fscod 0 (48 kHz), bsid 8, acmod 7 (3/2) with the LFE bit set after the two mix level fields.
        val f = AudioFrames.find(
            TsAnalyzer.Stream(0x100, 0x81, emptyList()),
            bytes(0x0B, 0x77, 0x00, 0x00, 0x00, 0x40, 0xE1),
            0, 16,
        )
        assertEquals("AC-3", f?.codec)
        assertEquals(48000, f?.sampleRate)
        assertEquals(6, f?.channels)
        // frmsizecod 0 is the lowest AC-3 rate.
        assertEquals(32, f?.bitrate)
    }

    @Test
    fun eac3IndependentThreeTwoWithLfeAt256Kbps() {
        // Syncword, strmtyp 0, frmsiz 511 (1024 bytes), fscod 0 (48 kHz), numblkscod 3 (6 blocks), acmod 7, lfeon 1, bsid 16.
        val f = AudioFrames.find(
            TsAnalyzer.Stream(0x100, 0x06, listOf(0x7A)),
            bytes(0x0B, 0x77, 0x01, 0xFF, 0x3F, 0x80),
            0, 16,
        )
        assertEquals("E-AC-3", f?.codec)
        assertEquals(48000, f?.sampleRate)
        assertEquals(6, f?.channels)
        assertEquals(256, f?.bitrate)
    }

    @Test
    fun eac3StereoAt128KbpsSkipsADependentSubstream() {
        // A dependent substream (strmtyp 1) first, then an independent 2/0 frame of 512 bytes without LFE.
        val f = AudioFrames.find(
            TsAnalyzer.Stream(0x100, 0x06, listOf(0x7A)),
            bytes(0x0B, 0x77, 0x41, 0xFF, 0x3F, 0x80, 0x0B, 0x77, 0x00, 0xFF, 0x34, 0x80),
            0, 16,
        )
        assertEquals(2, f?.channels)
        assertEquals(128, f?.bitrate)
    }

    @Test
    fun ac4SyncWordCountsOnlyAtThePesStart() {
        val stream = TsAnalyzer.Stream(0x100, 0x06, listOf(0x7F), extensionTags = listOf(0x15))
        assertEquals("AC-4 sync frame", AudioFrames.find(stream, bytes(0xAC, 0x41), 0, 16)?.codec)
        assertNull(AudioFrames.find(stream, bytes(0x00, 0xAC, 0x40), 0, 16))
        assertNull(AudioFrames.find(stream, bytes(0xAC, 0x40), 0, 16, pesStart = false))
    }

    @Test
    fun noHeaderYieldsNoFormat() {
        assertNull(AudioFrames.find(TsAnalyzer.Stream(0x100, 0x03, emptyList()), bytes(0x12, 0x34), 0, 16))
    }

    @Test
    fun pesPacketCountsPayloadAndFindsTheHeader() {
        val p = ByteArray(TsAnalyzer.PACKET)
        // Transport header: payload unit start, PID 0x0101, payload only.
        p[0] = 0x47
        p[1] = 0x41
        p[2] = 0x01
        p[3] = 0x10
        // PES header: start code, audio stream_id, length (0), flags, header data length 0; then an ADTS header.
        val pes = intArrayOf(0x00, 0x00, 0x01, 0xC0, 0x00, 0x00, 0x80, 0x00, 0x00, 0xFF, 0xF1, 0x4C, 0x80)
        pes.forEachIndexed { i, v -> p[4 + i] = v.toByte() }

        val track = AudioTrack(TsAnalyzer.Stream(0x101, 0x0F, emptyList()))
        track.onPacket(p)

        assertEquals(1L, track.pes)
        assertEquals((TsAnalyzer.PACKET - 13).toLong(), track.bytes)
        assertEquals("AAC LC", track.format?.codec)
    }
}
