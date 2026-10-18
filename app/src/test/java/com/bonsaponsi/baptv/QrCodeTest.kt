package com.bonsaponsi.baptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class QrCodeTest {
    private val samples = listOf(
        "https://github.com/depablo/bapTV/issues/1",
        "https://github.com/depablo/bapTV/issues/new?title=Report",
        "https://reports.bonsaponsi.com/v1/reports/abcdefghijklmnopqrstuvwx",
        "x".repeat(150),
        "a".repeat(115),
        "https://github.com/depablo/bapTV/issues/new?title=" + "Report%20from%20bapTV%20".repeat(3) + "v1.0.0&body=x",
        (1..60).joinToString("") { (it * 7 % 10).toString() } + "-" + (1..50).joinToString("") { ('a' + it % 26).toString() },
        "c".repeat(100),
        "b".repeat(210),
        "Zażółć gęślą jaźń – " + "y".repeat(60),
    )

    @Test
    fun sizesGrowWithLength() {
        assertEquals(21, QrCode.encode("hello")!!.size)
        assertNotNull(QrCode.encode("z".repeat(213)))
        assertNull(QrCode.encode("z".repeat(214)))
    }

    /** Writes the matrices for an external decoder check (QR_DUMP_DIR set by the developer). */
    @Test
    fun dumpForExternalDecoder() {
        val dir = System.getenv("QR_DUMP_DIR") ?: return
        samples.forEachIndexed { i, text ->
            val qr = QrCode.encode(text)!!
            val rows = (0 until qr.size).joinToString("\n") { y -> (0 until qr.size).joinToString("") { x -> if (qr[x, y]) "1" else "0" } }
            File(dir, "qr$i.txt").writeText(text + "\n" + rows)
        }
    }
}
