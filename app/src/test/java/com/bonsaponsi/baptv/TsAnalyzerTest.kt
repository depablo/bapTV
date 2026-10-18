package com.bonsaponsi.baptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class TsAnalyzerTest {

    private fun section(tableId: Int, idExt: Int, body: ByteArray): ByteArray {
        // table_id, syntax=1, length; id_ext; version/current; section numbers; body; CRC
        val length = 5 + body.size + 4
        val head = byteArrayOf(
            tableId.toByte(), (0xB0 or (length shr 8)).toByte(), length.toByte(),
            (idExt shr 8).toByte(), idExt.toByte(), 0xC1.toByte(), 0, 0,
        )
        val noCrc = head + body
        val crc = TsAnalyzer.crc32(noCrc)
        return noCrc + byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
    }

    /** Splits a section into TS packets with pointer_field in the first one and 0xFF stuffing in the last. */
    private fun packetize(pid: Int, section: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val payload = byteArrayOf(0) + section
        var off = 0
        var first = true
        var cc = 0
        while (off < payload.size) {
            val p = ByteArray(188) { 0xFF.toByte() }
            p[0] = 0x47
            p[1] = ((if (first) 0x40 else 0) or (pid shr 8)).toByte()
            p[2] = pid.toByte()
            p[3] = (0x10 or (cc++ and 0xF)).toByte()
            val n = minOf(184, payload.size - off)
            System.arraycopy(payload, off, p, 4, n)
            off += n
            first = false
            out.write(p)
        }
        return out.toByteArray()
    }

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun desc(tag: Int, data: ByteArray) = byteArrayOf(tag.toByte(), data.size.toByte()) + data

    @Test
    fun decodesPatPmtSdtAndAit() {
        val pat = section(0x00, 1, u16(0) + u16(0xE010) + u16(101) + u16(0xE100))
        val pmt = section(0x02, 101,
            u16(0xE101) + u16(0xF000) +
                byteArrayOf(0x1B) + u16(0xE101) + u16(0xF000) +
                byteArrayOf(0x05) + u16(0xE105) + u16(0xF000 or 4) + desc(0x6F, u16(0x0010)),
        )
        val name = "TVP Info Łódź".toByteArray(Charsets.ISO_8859_1).let { byteArrayOf(0x10, 0x00, 0x02) + "TVP Info Łódź".toByteArray(charset("ISO-8859-2")) }
        val provider = "TVP".toByteArray()
        val serviceDesc = desc(0x48, byteArrayOf(0x01, provider.size.toByte()) + provider + byteArrayOf(name.size.toByte()) + name)
        val sdt = section(0x42, 0x0002, u16(0x2174) + byteArrayOf(0xFF.toByte()) + u16(101) + byteArrayOf(0xFC.toByte()) + u16(0x8000 or serviceDesc.size) + serviceDesc)
        val url = "http://hbbtv.example.pl/apps/".toByteArray()
        val transport = desc(0x02, u16(0x0003) + byteArrayOf(0x01, url.size.toByte()) + url + byteArrayOf(0))
        val path = desc(0x15, ("start/index.html?channel=tvpinfo&" + "x".repeat(150)).toByteArray())
        val appName = desc(0x01, "pol".toByteArray() + byteArrayOf(7) + "TVP Hub".toByteArray())
        val appDescs = appName + transport + path
        val appLoop = byteArrayOf(0, 0, 0, 0x15) + u16(0x0001) + byteArrayOf(0x01) + u16(0xF000 or appDescs.size) + appDescs
        val ait = section(0x74, 0x0010, u16(0xF000) + u16(0xF000 or appLoop.size) + appLoop)

        val ts = packetize(0, pat) + packetize(0x100, pmt) + packetize(0x11, sdt) + packetize(0x105, ait)
        assertTrue("AIT should span several packets", ait.size > 183)

        val a = TsAnalyzer()
        a.feed(ByteArrayInputStream(ts))
        assertEquals(listOf(101), a.programs.keys.toList())
        assertTrue(a.programs[101]!!.streams.any { it.pid == 0x105 && it.isAit })
        // Video and AIT without an audio track - this is what a virtual channel looks like (still image + HbbTV).
        assertTrue(a.programs[101]!!.hasVideo)
        assertTrue(!a.programs[101]!!.hasAudio)
        assertEquals("TVP Info Łódź", a.services[101]!!.name)
        assertEquals(0x2174, a.originalNetworkId)
        val app = a.apps.values.single()
        assertEquals("AUTOSTART", app.controlName)
        assertEquals(listOf("pol:TVP Hub"), app.names)
        assertEquals("http://hbbtv.example.pl/apps/start/index.html?channel=tvpinfo&" + "x".repeat(150), app.url)
        assertTrue(a.report().contains("URL: http://hbbtv.example.pl/apps/"))
    }

    @Test
    fun rejectsCorruptedSection() {
        val pat = section(0x00, 1, u16(101) + u16(0xE100)).also { it[9] = 0x55 }
        val a = TsAnalyzer()
        a.feed(ByteArrayInputStream(packetize(0, pat)))
        assertTrue(a.programs.isEmpty())
    }

    @Test
    fun resynchronizesAfterGarbage() {
        val pat = section(0x00, 1, u16(101) + u16(0xE100))
        val pmt = section(0x02, 101, u16(0xE101) + u16(0xF000) + byteArrayOf(0x1B) + u16(0xE101) + u16(0xF000))
        val a = TsAnalyzer()
        a.feed(ByteArrayInputStream(byteArrayOf(1, 2, 3) + packetize(0, pat) + packetize(0x100, pmt)))
        assertEquals(1L, a.syncErrors)
        assertEquals(listOf(101), a.programs.keys.toList())
    }

    // ---- Robustness against malformed broadcast data ----

    /** A section with a valid CRC and arbitrary (possibly too short) content. */
    private fun rawSection(bytes: ByteArray): ByteArray {
        val crc = TsAnalyzer.crc32(bytes)
        return bytes + byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
    }

    @Test
    fun randomPacketsDoNotThrow() {
        val random = java.util.Random(42)
        repeat(200) {
            val a = TsAnalyzer()
            val data = ByteArray(188 * 50).also { random.nextBytes(it) }
            // Give packets a valid header on table PIDs so the random data reaches the parsers.
            for (k in 0 until 50) {
                data[k * 188] = 0x47
                data[k * 188 + 1] = (if (random.nextBoolean()) 0x40 else 0).toByte()
                data[k * 188 + 2] = listOf(0x00, 0x11, 0x12).random(kotlin.random.Random(random.nextLong())).toByte()
                data[k * 188 + 3] = 0x10
            }
            a.feed(ByteArrayInputStream(data))
        }
    }

    @Test
    fun truncatedPatIsIgnored() {
        val a = TsAnalyzer()
        // PAT section with length 1 (+CRC) - too short for the header.
        a.feed(ByteArrayInputStream(packetize(0, rawSection(byteArrayOf(0x00, 0xB0.toByte(), 0x05, 0x00)))))
        a.feed(ByteArrayInputStream(packetize(0, byteArrayOf(0x00, 0xB0.toByte(), 0x00))))
        assertEquals(null, a.patTransportStreamId)
    }

    @Test
    fun sdtServiceDescriptorWithTooLongProviderIsSkipped() {
        // service_descriptor declares provider_len 200 with a descriptor length of 3.
        val loop = desc(0x48, byteArrayOf(0x01, 200.toByte(), 0x00))
        val body = u16(8808) + byteArrayOf(0xFF.toByte()) + u16(1) + byteArrayOf(0xFC.toByte()) + u16(0x8000 or loop.size) + loop
        val a = TsAnalyzer()
        a.feed(ByteArrayInputStream(packetize(0x11, section(0x42, 3, body))))
        assertTrue(a.services.isEmpty())
    }

    @Test
    fun aitWithHugeCommonLengthIsIgnored() {
        val pat = section(0x00, 1, u16(101) + u16(0xE000 or 0x100))
        val pmt = section(0x02, 101, u16(0xE000 or 0x101) + u16(0xF000) + byteArrayOf(0x05) + u16(0xE000 or 0x105) + u16(0xF000))
        val ait = section(0x74, 0x0010, u16(0xFFFF))
        val a = TsAnalyzer()
        a.feed(ByteArrayInputStream(packetize(0, pat) + packetize(0x100, pmt) + packetize(0x105, ait)))
        assertTrue(a.apps.isEmpty())
    }

    @Test
    fun decodesIso6937PolishLetters() {
        // "Łódź" and "żółć" in ISO 6937: Ł = 0xE8, ó = 0xC2 'o', ź = 0xC2 'z', ż = 0xC7 'z', ł = 0xF8, ć = 0xC2 'c'.
        val lodz = byteArrayOf(0xE8.toByte(), 0xC2.toByte(), 'o'.code.toByte(), 'd'.code.toByte(), 0xC2.toByte(), 'z'.code.toByte())
        assertEquals("Łódź", TsAnalyzer.dvbText(lodz, 0, lodz.size))
        val zolc = byteArrayOf(0xC7.toByte(), 'z'.code.toByte(), 0xC2.toByte(), 'o'.code.toByte(), 0xF8.toByte(), 0xC2.toByte(), 'c'.code.toByte())
        assertEquals("żółć", TsAnalyzer.dvbText(zolc, 0, zolc.size))
        // ą, ę - ogonek (0xCE).
        val ae = byteArrayOf(0xCE.toByte(), 'a'.code.toByte(), 0xCE.toByte(), 'e'.code.toByte())
        assertEquals("ąę", TsAnalyzer.dvbText(ae, 0, ae.size))
    }

    @Test
    fun decodesOtherDvbCharsets() {
        val latin2 = byteArrayOf(0x10, 0x00, 0x02, 0xA3.toByte(), 'o'.code.toByte(), 0xB3.toByte())
        assertEquals("Łoł", TsAnalyzer.dvbText(latin2, 0, latin2.size))
        val utf16 = byteArrayOf(0x11) + "Ża".toByteArray(Charsets.UTF_16BE)
        assertEquals("Ża", TsAnalyzer.dvbText(utf16, 0, utf16.size))
        val utf8 = byteArrayOf(0x15) + "a\uE08Ab".toByteArray(Charsets.UTF_8)
        assertEquals("a\nb", TsAnalyzer.dvbText(utf8, 0, utf8.size))
        // Lengths beyond the array do not throw.
        assertEquals("", TsAnalyzer.dvbText(latin2, 4, 10))
    }
}
