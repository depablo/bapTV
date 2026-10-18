package com.bonsaponsi.baptv

import java.io.InputStream
import java.nio.charset.Charset

/**
 * MPEG-TS transport stream analyzer: assembles PSI/SI sections and decodes
 * PAT, PMT, SDT (service names) and AIT (HbbTV apps with URLs).
 *
 * Used to read a dtvinput recording – the only available path to the raw stream,
 * since the tuner file system (/mnt/dtvfs) is blocked for apps.
 */
class TsAnalyzer {

    data class Stream(val pid: Int, val type: Int, val descriptorTags: List<Int>) {
        /** stream_type 0x05 with application_signalling_descriptor (0x6F) – AIT stream. */
        val isAit get() = type == 0x05
    }

    data class Program(val number: Int, val pmtPid: Int, val streams: List<Stream>, val descriptorTags: List<Int> = emptyList()) {
        /** Conditional access descriptor (0x09) in the PMT – scrambled service. */
        val scrambled get() = 0x09 in descriptorTags || streams.any { 0x09 in it.descriptorTags }
        val hasVideo get() = streams.any { it.type in VIDEO_TYPES }
        /** Audio track: MPEG/AAC/AC-3 audio types or private PES (0x06) with an AC-3/E-AC-3/AAC/DTS descriptor. */
        val hasAudio get() = streams.any { it.type in AUDIO_TYPES || (it.type == 0x06 && it.descriptorTags.any { t -> t in AUDIO_DESCRIPTORS }) }
    }

    data class Service(val id: Int, val type: Int, val provider: String, val name: String)

    /** Programme from the EIT (UTC time in milliseconds). */
    data class Event(
        val serviceId: Int,
        val eventId: Int,
        val startMs: Long,
        val durationSec: Int,
        val title: String,
        val description: String,
        /** Genre from content_descriptor (upper nibble, EN 300 468 table 29), -1 if absent. */
        val genre: Int,
    ) {
        val endMs get() = startMs + durationSec * 1000L
    }

    data class HbbApp(
        val serviceAitPid: Int,
        val applicationType: Int,
        val organisationId: Long,
        val applicationId: Int,
        val controlCode: Int,
        val names: List<String>,
        val baseUrl: String?,
        val initialPath: String?,
    ) {
        val url: String? get() = baseUrl?.let { it + initialPath.orEmpty() }
        val controlName get() = when (controlCode) {
            1 -> "AUTOSTART"; 2 -> "PRESENT"; 3 -> "DESTROY"; 4 -> "KILL"; 7 -> "DISABLED"; else -> "?($controlCode)"
        }
    }

    var packets = 0L; private set
    var syncErrors = 0L; private set
    val pidCounts = sortedMapOf<Int, Long>()
    val programs = sortedMapOf<Int, Program>()
    val services = sortedMapOf<Int, Service>()
    var transportStreamId: Int? = null; private set
    /** TSID from the PAT (table_id_extension) – available even without an SDT. */
    var patTransportStreamId: Int? = null; private set
    /** (TSID, ONID) from EIT p/f actual (0x4E) for each service – substitutes for the SDT missing from the recording. */
    val eitIds = sortedMapOf<Int, Pair<Int, Int>>()
    /** Multiplex EPG from EIT actual (p/f and schedule): service -> (event_id -> programme). */
    val events = sortedMapOf<Int, MutableMap<Int, Event>>()
    var originalNetworkId: Int? = null; private set
    val apps = LinkedHashMap<String, HbbApp>()

    private val pmtPids = HashMap<Int, Int>()
    private val aitPids = HashSet<Int>()
    private val assemblers = HashMap<Int, SectionAssembler>()

    /** Reads a stream of 188-byte packets (resynchronizing on the 0x47 byte). */
    fun feed(input: InputStream, maxBytes: Long = Long.MAX_VALUE) {
        val packet = ByteArray(PACKET)
        var total = 0L
        while (total < maxBytes) {
            if (!readFully(input, packet, 0, PACKET)) return
            total += PACKET
            if (packet[0] != SYNC) {
                syncErrors++
                // Shift the window to the nearest sync byte.
                val idx = (1 until PACKET).firstOrNull { packet[it] == SYNC } ?: continue
                System.arraycopy(packet, idx, packet, 0, PACKET - idx)
                if (!readFully(input, packet, PACKET - idx, idx)) return
            }
            onPacket(packet)
        }
    }

    fun onPacket(p: ByteArray) {
        packets++
        val pid = ((p[1].toInt() and 0x1F) shl 8) or (p[2].toInt() and 0xFF)
        pidCounts[pid] = (pidCounts[pid] ?: 0L) + 1
        if (p[1].toInt() and 0x80 != 0) return // transport_error_indicator
        if (!isSectionPid(pid)) return
        val pusi = p[1].toInt() and 0x40 != 0
        val afc = (p[3].toInt() shr 4) and 0x3
        if (afc == 0 || afc == 2) return
        var offset = 4
        if (afc == 3) offset += 1 + (p[4].toInt() and 0xFF)
        if (offset >= PACKET) return
        assemblers.getOrPut(pid) { SectionAssembler() }.push(p, offset, pusi) { onSection(pid, it) }
    }

    private fun isSectionPid(pid: Int) = pid == 0 || pid == SDT_PID || pid == EIT_PID || pid in pmtPids || pid in aitPids

    /**
     * The stream comes from the antenna – it is untrusted data. All supported tables use the long section
     * form (section_syntax_indicator = 1) and have a minimum header + CRC length; shorter ones are rejected
     * before the parser touches the header fields.
     */
    private fun onSection(pid: Int, s: ByteArray) {
        val table = s[0].toInt() and 0xFF
        val min = MIN_SECTION[table] ?: if (table in 0x50..0x5F) MIN_SECTION[0x4E]!! else return
        if (s.size < min || s[1].toInt() and 0x80 == 0) return
        when (table) {
            0x00 -> if (pid == 0) parsePat(s)
            0x02 -> pmtPids[pid]?.let { parsePmt(s) }
            0x42 -> if (pid == SDT_PID) parseSdt(s)
            0x74 -> if (pid in aitPids) parseAit(pid, s)
            0x4E, in 0x50..0x5F -> if (pid == EIT_PID) parseEit(s)
        }
    }

    private fun parsePat(s: ByteArray) {
        patTransportStreamId = u16(s, 3)
        val end = s.size - 4
        var i = 8
        while (i + 4 <= end) {
            val number = u16(s, i)
            val pmtPid = u16(s, i + 2) and 0x1FFF
            if (number != 0) pmtPids[pmtPid] = number
            i += 4
        }
    }

    private fun parsePmt(s: ByteArray) {
        val number = u16(s, 3)
        val programInfoLength = u16(s, 10) and 0xFFF
        var i = 12 + programInfoLength
        val end = s.size - 4
        val streams = ArrayList<Stream>()
        while (i + 5 <= end) {
            val type = s[i].toInt() and 0xFF
            val pid = u16(s, i + 1) and 0x1FFF
            val esLength = u16(s, i + 3) and 0xFFF
            val tags = descriptorTags(s, i + 5, minOf(i + 5 + esLength, end))
            val stream = Stream(pid, type, tags)
            streams += stream
            if (stream.isAit) aitPids += pid
            i += 5 + esLength
        }
        val pmtPid = pmtPids.entries.firstOrNull { it.value == number }?.key ?: -1
        programs[number] = Program(number, pmtPid, streams, descriptorTags(s, 12, minOf(12 + programInfoLength, end)))
    }

    private fun parseSdt(s: ByteArray) {
        transportStreamId = u16(s, 3)
        originalNetworkId = u16(s, 8)
        var i = 11
        val end = s.size - 4
        while (i + 5 <= end) {
            val serviceId = u16(s, i)
            val loopLength = u16(s, i + 3) and 0xFFF
            var d = i + 5
            val loopEnd = minOf(d + loopLength, end)
            while (d + 2 <= loopEnd) {
                val tag = s[d].toInt() and 0xFF
                val len = s[d + 1].toInt() and 0xFF
                // service_descriptor: type(1) provider_len(1) provider name_len(1) name – lengths within the descriptor bounds.
                if (tag == 0x48 && d + 2 + len <= loopEnd && len >= 3) {
                    val type = s[d + 2].toInt() and 0xFF
                    val providerLen = s[d + 3].toInt() and 0xFF
                    if (3 + providerLen <= len) {
                        val provider = dvbText(s, d + 4, providerLen)
                        val nameLen = minOf(s[d + 4 + providerLen].toInt() and 0xFF, len - 3 - providerLen)
                        val name = dvbText(s, d + 5 + providerLen, nameLen)
                        services[serviceId] = Service(serviceId, type, provider, name)
                    }
                }
                d += 2 + len
            }
            i += 5 + loopLength
        }
    }

    /** EIT actual: p/f (0x4E) and schedule (0x50–0x5F) of the current multiplex. */
    private fun parseEit(s: ByteArray) {
        val sid = u16(s, 3)
        eitIds[sid] = u16(s, 8) to u16(s, 10)
        val end = s.size - 4
        var i = 14
        val map = events.getOrPut(sid) { HashMap() }
        while (i + 12 <= end) {
            val eventId = u16(s, i)
            val start = dvbTime(s, i + 2)
            val duration = bcd(s[i + 7]) * 3600 + bcd(s[i + 8]) * 60 + bcd(s[i + 9])
            val loopLength = u16(s, i + 10) and 0xFFF
            val loopEnd = minOf(i + 12 + loopLength, end)
            var title = ""
            var shortText = ""
            val extended = StringBuilder()
            var genre = -1
            parseDescriptors(s, i + 12, loopEnd) { tag, at, len ->
                when (tag) {
                    0x4D -> if (len >= 5) { // short_event_descriptor: lang(3) name_len name text_len text
                        val nameLen = minOf(s[at + 3].toInt() and 0xFF, len - 4)
                        title = dvbText(s, at + 4, nameLen)
                        val textAt = at + 4 + nameLen
                        if (textAt < at + len) shortText = dvbText(s, textAt + 1, minOf(s[textAt].toInt() and 0xFF, at + len - textAt - 1))
                    }
                    0x4E -> if (len >= 6) { // extended_event_descriptor: text may be split across several descriptors
                        val itemsLen = s[at + 4].toInt() and 0xFF
                        val textAt = at + 5 + itemsLen
                        if (textAt < at + len) extended.append(dvbText(s, textAt + 1, minOf(s[textAt].toInt() and 0xFF, at + len - textAt - 1)))
                    }
                    0x54 -> if (len >= 1 && genre < 0) genre = (s[at].toInt() and 0xF0) shr 4
                }
            }
            if (start != null && title.isNotEmpty()) {
                val description = listOf(shortText, extended.toString()).filter { it.isNotBlank() }.distinct().joinToString("\n")
                map[eventId] = Event(sid, eventId, start, duration, title, description, genre)
            }
            i += 12 + loopLength
        }
    }

    private fun parseAit(pid: Int, s: ByteArray) {
        val appType = u16(s, 3) and 0x7FFF
        val commonLength = u16(s, 8) and 0xFFF
        val end = s.size - 4
        var commonBase: String? = null
        parseDescriptors(s, 10, minOf(10 + commonLength, end)) { tag, at, len ->
            if (tag == 0x02) transportUrl(s, at, len)?.let { commonBase = it }
        }
        var i = 10 + commonLength
        if (i + 2 > end) return
        val loopLength = u16(s, i) and 0xFFF
        i += 2
        val loopEnd = minOf(i + loopLength, end)
        while (i + 9 <= loopEnd) {
            val orgId = u32(s, i)
            val appId = u16(s, i + 4)
            val control = s[i + 6].toInt() and 0xFF
            val descLength = u16(s, i + 7) and 0xFFF
            val names = ArrayList<String>()
            var base: String? = null
            var path: String? = null
            parseDescriptors(s, i + 9, minOf(i + 9 + descLength, loopEnd)) { tag, at, len ->
                when (tag) {
                    0x01 -> { // application_name_descriptor: [lang(3) len name]*
                        var n = at
                        while (n + 4 <= at + len) {
                            val nl = minOf(s[n + 3].toInt() and 0xFF, at + len - n - 4)
                            names += String(s, n, 3, Charsets.US_ASCII) + ":" + dvbText(s, n + 4, nl)
                            n += 4 + nl
                        }
                    }
                    0x02 -> transportUrl(s, at, len)?.let { base = it }
                    0x15 -> path = String(s, at, len, Charsets.ISO_8859_1)
                }
            }
            val app = HbbApp(pid, appType, orgId, appId, control, names, base ?: commonBase, path)
            apps["$pid/$orgId/$appId"] = app
            i += 9 + descLength
        }
    }

    /** transport_protocol_descriptor: returns the base URL for the HTTP protocol (0x0003). */
    private fun transportUrl(s: ByteArray, at: Int, len: Int): String? {
        if (len < 4 || u16(s, at) != 0x0003) return null
        val urlLen = s[at + 3].toInt() and 0xFF
        if (4 + urlLen > len) return null
        return String(s, at + 4, urlLen, Charsets.ISO_8859_1)
    }

    private fun descriptorTags(s: ByteArray, from: Int, to: Int): List<Int> {
        val tags = ArrayList<Int>()
        parseDescriptors(s, from, to) { tag, _, _ -> tags += tag }
        return tags
    }

    private inline fun parseDescriptors(s: ByteArray, from: Int, to: Int, block: (tag: Int, at: Int, len: Int) -> Unit) {
        var d = from
        while (d + 2 <= to) {
            val tag = s[d].toInt() and 0xFF
            val len = s[d + 1].toInt() and 0xFF
            if (d + 2 + len > to) break
            block(tag, d + 2, len)
            d += 2 + len
        }
    }

    /** Text report for the web server. */
    fun report(): String = buildString {
        appendLine("Packets: $packets, sync errors: $syncErrors")
        appendLine("TSID: ${transportStreamId ?: patTransportStreamId ?: "?"}, ONID: ${originalNetworkId ?: eitIds.values.firstOrNull()?.second ?: "?"}")
        appendLine("PIDs (packet count):")
        pidCounts.forEach { (pid, n) -> appendLine("  0x%04X (%d): %d".format(pid, pid, n)) }
        appendLine("Programs (PMT):")
        if (programs.isEmpty()) appendLine("  none")
        programs.values.forEach { p ->
            appendLine("  service ${p.number} (PMT 0x%04X)%s%s".format(p.pmtPid, if (p.scrambled) " SCRAMBLED" else "", if (p.hasVideo) " video" else " no video"))
            p.streams.forEach { st ->
                appendLine("    PID 0x%04X type 0x%02X descriptors %s%s".format(st.pid, st.type, st.descriptorTags.joinToString { "0x%02X".format(it) }, if (st.isAit) "  <- AIT" else ""))
            }
        }
        appendLine("Services (SDT):")
        if (services.isEmpty()) appendLine("  none")
        services.values.forEach { appendLine("  ${it.id}: \"${it.name}\" (${it.provider}, type ${it.type})") }
        appendLine("EPG (EIT): " + if (events.isEmpty()) "none" else events.entries.joinToString { (sid, e) -> "$sid: ${e.size}" })
        appendLine("HbbTV apps (AIT):")
        if (apps.isEmpty()) appendLine("  none")
        apps.values.forEach {
            appendLine("  PID 0x%04X org=0x%X app=0x%X %s type=0x%X %s".format(it.serviceAitPid, it.organisationId, it.applicationId, it.controlName, it.applicationType, it.names.joinToString()))
            appendLine("    URL: ${it.url ?: "none (base=${it.baseUrl}, path=${it.initialPath})"}")
        }
    }

    /** Assembles sections from packet payloads (pointer_field, multiple sections per packet, multi-packet sections). */
    private class SectionAssembler {
        private var buffer = ByteArray(0)
        private var started = false

        fun push(p: ByteArray, start: Int, pusi: Boolean, emit: (ByteArray) -> Unit) {
            var offset = start
            if (pusi) {
                val pointer = p[offset].toInt() and 0xFF
                offset += 1
                if (started && offset + pointer <= PACKET) {
                    append(p, offset, pointer)
                    drain(emit)
                }
                offset += pointer
                buffer = ByteArray(0)
                started = true
            } else if (!started) {
                return
            }
            if (offset < PACKET) append(p, offset, PACKET - offset)
            drain(emit)
        }

        private fun append(p: ByteArray, from: Int, len: Int) {
            if (len <= 0) return
            buffer += p.copyOfRange(from, from + len)
            if (buffer.size > MAX_SECTION * 2) { buffer = ByteArray(0); started = false }
        }

        /** Emits all complete sections from the buffer; 0xFF bytes are stuffing. */
        private fun drain(emit: (ByteArray) -> Unit) {
            while (buffer.isNotEmpty()) {
                if (buffer[0] == 0xFF.toByte()) { buffer = ByteArray(0); started = false; return }
                if (buffer.size < 3) return
                val length = 3 + (((buffer[1].toInt() and 0x0F) shl 8) or (buffer[2].toInt() and 0xFF))
                if (buffer.size < length) return
                val section = buffer.copyOfRange(0, length)
                buffer = buffer.copyOfRange(length, buffer.size)
                if (crcOk(section)) emit(section)
            }
        }
    }

    companion object {
        const val PACKET = 188
        private const val SYNC = 0x47.toByte()
        private const val SDT_PID = 0x11
        private const val EIT_PID = 0x12
        /** Video stream_type: MPEG-1/2, MPEG-4 Part 2, H.264, HEVC. */
        val VIDEO_TYPES = setOf(0x01, 0x02, 0x10, 0x1B, 0x24)
        /** Audio stream_type: MPEG-1/2, AAC ADTS/LATM, AC-3 and E-AC-3 (ATSC). */
        val AUDIO_TYPES = setOf(0x03, 0x04, 0x0F, 0x11, 0x81, 0x87)
        /** DVB audio descriptors in private PES: AC-3, DTS, E-AC-3, AAC. */
        val AUDIO_DESCRIPTORS = setOf(0x6A, 0x7B, 0x7A, 0x7C)
        private const val MAX_SECTION = 4096

        private fun u16(s: ByteArray, i: Int) = ((s[i].toInt() and 0xFF) shl 8) or (s[i + 1].toInt() and 0xFF)
        private fun u32(s: ByteArray, i: Int) = (u16(s, i).toLong() shl 16) or u16(s, i + 2).toLong()

        private fun readFully(input: InputStream, b: ByteArray, off: Int, len: Int): Boolean {
            var n = 0
            while (n < len) {
                val r = input.read(b, off + n, len - n)
                if (r < 0) return false
                n += r
            }
            return true
        }

        /**
         * Section CRC-32/MPEG-2 (with the trailing CRC included the result must be 0). Sections without section
         * syntax are not checked, but ones too short for a CRC are rejected.
         */
        fun crcOk(section: ByteArray): Boolean {
            if (section.size < 3) return false
            if (section[1].toInt() and 0x80 == 0) return true
            return section.size >= 4 && crc32(section) == 0
        }

        /** Minimum section length (header + CRC) for supported tables; schedule EIT same as 0x4E. */
        private val MIN_SECTION = mapOf(0x00 to 12, 0x02 to 16, 0x42 to 15, 0x74 to 16, 0x4E to 18)

        fun crc32(data: ByteArray): Int {
            var crc = -1
            for (b in data) {
                crc = crc xor ((b.toInt() and 0xFF) shl 24)
                repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
            }
            return crc
        }

        /**
         * DVB text (EN 300 468, Annex A): the first byte may select the encoding; without it
         * ISO/IEC 6937 applies (accented letters as diacritic + letter, some letters such as Ł/ł separately).
         */
        fun dvbText(s: ByteArray, at: Int, len: Int): String {
            if (len <= 0 || at < 0 || at + len > s.size) return ""
            val first = s[at].toInt() and 0xFF
            val text = when {
                first >= 0x20 -> iso6937(s, at, len)
                first in 0x01..0x0B -> String(s, at + 1, len - 1, charsetOrLatin1("ISO-8859-${first + 4}"))
                first == 0x10 -> if (len >= 3) String(s, at + 3, len - 3, charsetOrLatin1("ISO-8859-${s[at + 2].toInt() and 0xFF}")) else ""
                first == 0x11 -> String(s, at + 1, len - 1, Charsets.UTF_16BE)
                first == 0x12 -> String(s, at + 1, len - 1, charsetOrLatin1("EUC-KR"))
                first == 0x13 -> String(s, at + 1, len - 1, charsetOrLatin1("GB2312"))
                first == 0x14 -> String(s, at + 1, len - 1, charsetOrLatin1("Big5"))
                first == 0x15 -> String(s, at + 1, len - 1, Charsets.UTF_8)
                else -> iso6937(s, at + 1, len - 1)
            }
            // DVB control codes: 0x8A (U+E08A in UTF-8/UTF-16) = newline; the other 0x80–0x9F and U+E080–U+E09F are dropped.
            return text
                .map { if (it == '\u008A' || it == '\uE08A') '\n' else it }
                .filter { it == '\n' || (it >= ' ' && it !in '\u0080'..'\u009F' && it !in '\uE080'..'\uE09F') }
                .joinToString("").trim()
        }

        /** ISO/IEC 6937: 0xC1–0xCF are diacritics preceding the letter, 0xA0–0xFF are additional characters. */
        fun iso6937(s: ByteArray, at: Int, len: Int): String {
            val out = StringBuilder(len)
            var i = at
            val end = at + len
            while (i < end) {
                val b = s[i].toInt() and 0xFF
                val accent = COMBINING_6937[b]
                if (accent != null) {
                    if (i + 1 < end) {
                        out.append(java.text.Normalizer.normalize("${(s[i + 1].toInt() and 0xFF).toChar()}$accent", java.text.Normalizer.Form.NFC))
                    }
                    i += 2
                    continue
                }
                out.append(if (b >= 0xA0) SPECIAL_6937[b - 0xA0] else b.toChar())
                i++
            }
            return out.toString()
        }

        private val COMBINING_6937 = mapOf(
            0xC1 to '\u0300', 0xC2 to '\u0301', 0xC3 to '\u0302', 0xC4 to '\u0303', 0xC5 to '\u0304', 0xC6 to '\u0306',
            0xC7 to '\u0307', 0xC8 to '\u0308', 0xCA to '\u030A', 0xCB to '\u0327', 0xCD to '\u030B', 0xCE to '\u0328', 0xCF to '\u030C',
        )

        /** Characters 0xA0–0xFF in ISO/IEC 6937 (0xC0–0xCF are diacritics – handled above). */
        private const val SPECIAL_6937 =
            "\u00A0¡¢£\$¥#§¤‘“«←↑→↓" +
            "°±²³×µ¶·÷’”»¼½¾¿" +
            "\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD" +
            "―¹®©™♪¬¦\uFFFD\uFFFD\uFFFD\uFFFD⅛⅜⅝⅞" +
            "ΩÆĐªĦ\uFFFDĲĿŁØŒºÞŦŊŉ" +
            "ĸæđðħıĳŀłøœßþŧŋ\u00AD"

        private fun bcd(b: Byte) = ((b.toInt() shr 4) and 0xF) * 10 + (b.toInt() and 0xF)

        /** DVB time: MJD (16 bits) + hour, minute, second in BCD, UTC. Null for 0xFFFFFFFFFF (undefined). */
        fun dvbTime(s: ByteArray, at: Int): Long? {
            if ((0 until 5).all { s[at + it] == 0xFF.toByte() }) return null
            val mjd = u16(s, at).toLong()
            // MJD 40587 = 1970-01-01.
            val days = mjd - 40587
            return ((days * 86400) + bcd(s[at + 2]) * 3600 + bcd(s[at + 3]) * 60 + bcd(s[at + 4])) * 1000
        }

        private fun charsetOrLatin1(name: String): Charset = runCatching { Charset.forName(name) }.getOrDefault(Charsets.ISO_8859_1)
    }
}
