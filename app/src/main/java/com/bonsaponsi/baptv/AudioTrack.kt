package com.bonsaponsi.baptv

/**
 * One audio PID of a transport stream: how many PES packets and payload bytes arrived on it, and the format
 * of the first frame header found in the payload. Only headers are read; the audio is not decoded. A track
 * without a frame header in its payload has no [format].
 */
class AudioTrack(private val stream: TsAnalyzer.Stream) {
    var pes = 0L; private set
    var bytes = 0L; private set
    var format: AudioFrames.Format? = null; private set

    fun onPacket(p: ByteArray) {
        val pusi = p[1].toInt() and 0x40 != 0
        val afc = (p[3].toInt() shr 4) and 0x3
        if (afc == 0 || afc == 2) return
        var i = 4
        if (afc == 3) i += 1 + (p[4].toInt() and 0xFF)
        if (i >= TsAnalyzer.PACKET) return
        if (pusi) {
            pes++
            // PES header: start code 00 00 01, stream_id, PES_packet_length (2), flags (2), header data length.
            if (i + 9 > TsAnalyzer.PACKET || p[i] != 0.toByte() || p[i + 1] != 0.toByte() || p[i + 2] != 1.toByte()) return
            i += 9 + (p[i + 8].toInt() and 0xFF)
            if (i >= TsAnalyzer.PACKET) return
        }
        bytes += TsAnalyzer.PACKET - i
        if (format == null) format = AudioFrames.find(stream, p, i, TsAnalyzer.PACKET, pusi)
    }

    /** One line for the report, e.g. "MPEG audio, lang=pol, PES 412, payload 91234 bytes, MPEG-1 layer 2, 48000 Hz, 2 ch, 256 kb/s". */
    fun summary(): String {
        val lang = stream.language?.let { ", lang=$it" }.orEmpty()
        val kind = when (stream.audioType) {
            0x01 -> ", clean effects"
            0x02 -> ", hearing impaired"
            0x03 -> ", visual impaired commentary"
            else -> ""
        }
        val fmt = format?.let {
            "${it.codec}, ${it.sampleRate?.let { r -> "$r Hz" } ?: "rate ?"}, ${it.channels?.let { c -> "$c ch" } ?: "channels ?"}" +
                it.bitrate?.let { b -> ", $b kb/s" }.orEmpty()
        } ?: "no frame header found"
        return "${AudioFrames.codecName(stream)}$lang$kind, PES $pes, payload $bytes bytes, $fmt"
    }
}

/** Frame headers of the audio codecs DVB and ATSC transport streams carry. */
object AudioFrames {
    /** [sampleRate] in Hz, [channels] including LFE and [bitrate] in kb/s; null where the header does not give them. */
    class Format(val codec: String, val sampleRate: Int?, val channels: Int?, val bitrate: Int? = null)

    fun codecName(stream: TsAnalyzer.Stream): String = when {
        stream.type == 0x03 || stream.type == 0x04 -> "MPEG audio"
        stream.type == 0x0F -> "AAC (ADTS)"
        stream.type == 0x11 -> "AAC (LATM)"
        stream.type == 0x81 || 0x6A in stream.descriptorTags -> "AC-3"
        stream.type == 0x87 || 0x7A in stream.descriptorTags -> "E-AC-3"
        stream.isAc4 -> "AC-4"
        0x7B in stream.descriptorTags -> "DTS"
        0x7C in stream.descriptorTags -> "AAC"
        else -> "stream type 0x%02X".format(stream.type)
    }

    /**
     * The format from the first header in buf[from until to], or null when none is there yet. [pesStart]: the
     * range begins a PES payload, where an aligned frame starts.
     */
    fun find(stream: TsAnalyzer.Stream, buf: ByteArray, from: Int, to: Int, pesStart: Boolean = true): Format? = when {
        stream.type == 0x03 || stream.type == 0x04 -> mpegAudio(buf, from, to)
        stream.type == 0x0F -> adts(buf, from, to)
        stream.type == 0x81 || 0x6A in stream.descriptorTags -> ac3(buf, from, to)
        stream.type == 0x87 || 0x7A in stream.descriptorTags -> eac3(buf, from, to)
        // A two-byte sync word turns up by chance inside the payload; AC-4 PES packets start with a frame.
        stream.isAc4 -> if (pesStart) ac4(buf, from, to) else null
        // Not read further: the codec alone is what the report can say.
        else -> Format(codecName(stream), null, null)
    }

    private val MPEG_RATES = arrayOf(
        intArrayOf(44100, 48000, 32000), // MPEG-1
        intArrayOf(22050, 24000, 16000), // MPEG-2
        intArrayOf(11025, 12000, 8000),  // MPEG-2.5
    )

    /** MPEG-1/2 audio frame header: 11 sync bits, version, layer, sampling rate index, channel mode. */
    private fun mpegAudio(buf: ByteArray, from: Int, to: Int): Format? {
        for (i in from until to - 3) {
            val b1 = buf[i + 1].toInt() and 0xFF
            val b2 = buf[i + 2].toInt() and 0xFF
            if (buf[i] != 0xFF.toByte() || b1 and 0xE0 != 0xE0) continue
            val version = (b1 shr 3) and 0x3 // 3 = MPEG-1, 2 = MPEG-2, 0 = MPEG-2.5, 1 = reserved
            val layer = (b1 shr 1) and 0x3 // 3 = layer I, 2 = II, 1 = III, 0 = reserved
            val rateIndex = (b2 shr 2) and 0x3
            if (version == 1 || layer == 0 || rateIndex == 3) continue
            val row = when (version) { 3 -> 0; 2 -> 1; else -> 2 }
            val mode = ((buf[i + 3].toInt() and 0xFF) shr 6) and 0x3 // 3 = mono
            val name = when (version) { 3 -> "MPEG-1"; 2 -> "MPEG-2"; else -> "MPEG-2.5" }
            return Format("$name layer ${4 - layer}", MPEG_RATES[row][rateIndex], if (mode == 3) 1 else 2)
        }
        return null
    }

    private val ADTS_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
    private val AAC_PROFILES = arrayOf("Main", "LC", "SSR", "LTP")

    /** ADTS header: 12 sync bits, layer 00, profile, sampling index, channel configuration. */
    private fun adts(buf: ByteArray, from: Int, to: Int): Format? {
        for (i in from until to - 3) {
            val b1 = buf[i + 1].toInt() and 0xFF
            val b2 = buf[i + 2].toInt() and 0xFF
            val b3 = buf[i + 3].toInt() and 0xFF
            if (buf[i] != 0xFF.toByte() || b1 and 0xF6 != 0xF0) continue
            val rateIndex = (b2 shr 2) and 0xF
            if (rateIndex >= ADTS_RATES.size) continue
            val profile = AAC_PROFILES[(b2 shr 6) and 0x3]
            val config = ((b2 and 0x1) shl 2) or (b3 shr 6)
            // Configurations 1–6 are the channel count itself, 7 is 7.1; 0 means the channels are in the PCE.
            val channels = when (config) { in 1..6 -> config; 7 -> 8; else -> null }
            return Format("AAC $profile", ADTS_RATES[rateIndex], channels)
        }
        return null
    }

    private val AC3_RATES = intArrayOf(48000, 44100, 32000)
    private val ACMOD_CHANNELS = intArrayOf(2, 1, 2, 3, 3, 4, 4, 5)
    /** AC-3 bit rate in kb/s for frmsizecod / 2. */
    private val AC3_BITRATES = intArrayOf(32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 448, 512, 576, 640)

    /** AC-3 syncframe: sync word 0B 77, sampling rate code, frame size code, bit stream id, audio coding mode, then the LFE bit. */
    private fun ac3(buf: ByteArray, from: Int, to: Int): Format? {
        for (i in from until to - 7) {
            if (buf[i] != 0x0B.toByte() || buf[i + 1] != 0x77.toByte()) continue
            val fscod = ((buf[i + 4].toInt() and 0xFF) shr 6) and 0x3
            val bsid = ((buf[i + 5].toInt() and 0xFF) shr 3) and 0x1F
            if (fscod == 3 || bsid > 8) continue
            val acmod = ((buf[i + 6].toInt() and 0xFF) shr 5) and 0x7
            // Bits after acmod: cmixlev and surmixlev when present, then dsurmod in 2/0 mode, then lfeon.
            var bit = 3
            if (acmod and 0x1 != 0 && acmod != 1) bit += 2
            if (acmod and 0x4 != 0) bit += 2
            if (acmod == 2) bit += 2
            val lfePos = (i + 6) * 8 + bit
            val lfe = (buf[lfePos / 8].toInt() shr (7 - lfePos % 8)) and 0x1
            val frmsizecod = (buf[i + 4].toInt() and 0x3F) shr 1
            return Format("AC-3", AC3_RATES[fscod], ACMOD_CHANNELS[acmod] + lfe, AC3_BITRATES.getOrNull(frmsizecod))
        }
        return null
    }

    private val EAC3_HALF_RATES = intArrayOf(24000, 22050, 16000)
    private val EAC3_BLOCKS = intArrayOf(1, 2, 3, 6)

    /**
     * E-AC-3 syncframe (ETSI TS 102 366 Annex E): sync word 0B 77, strmtyp, substreamid, frmsiz (11 bits),
     * fscod, numblkscod (or fscod2), acmod, lfeon, bsid 11–16. Only independent substreams are read: a
     * dependent one carries extra channels of the frame before it.
     */
    private fun eac3(buf: ByteArray, from: Int, to: Int): Format? {
        for (i in from until to - 5) {
            if (buf[i] != 0x0B.toByte() || buf[i + 1] != 0x77.toByte()) continue
            val b2 = buf[i + 2].toInt() and 0xFF
            val b4 = buf[i + 4].toInt() and 0xFF
            val strmtyp = b2 shr 6
            val bsid = (buf[i + 5].toInt() and 0xFF) shr 3
            if (strmtyp == 1 || strmtyp == 3 || bsid !in 11..16) continue
            val fscod = b4 shr 6
            val code = (b4 shr 4) and 0x3
            if (fscod == 3 && code == 3) continue
            val rate = if (fscod == 3) EAC3_HALF_RATES[code] else AC3_RATES[fscod]
            val blocks = if (fscod == 3) 6 else EAC3_BLOCKS[code]
            val frameBytes = ((((b2 and 0x7) shl 8) or (buf[i + 3].toInt() and 0xFF)) + 1) * 2
            val acmod = (b4 shr 1) and 0x7
            val bitrate = (frameBytes * 8L * rate / (blocks * 256) / 1000).toInt()
            return Format("E-AC-3", rate, ACMOD_CHANNELS[acmod] + (b4 and 0x1), bitrate)
        }
        return null
    }

    /**
     * AC-4 sync frame (ETSI TS 103 190, Annex G) at the start of the range: sync word AC 40 or AC 41 (the
     * latter with a CRC). Only its presence is checked; rate and channels sit in the TOC and presentations.
     */
    private fun ac4(buf: ByteArray, from: Int, to: Int): Format? =
        if (to - from >= 2 && buf[from] == 0xAC.toByte() && (buf[from + 1] == 0x40.toByte() || buf[from + 1] == 0x41.toByte())) {
            Format("AC-4 sync frame", null, null)
        } else {
            null
        }
}
