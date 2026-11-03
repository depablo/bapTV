package com.bonsaponsi.baptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class HttpRequestTest {

    @Test
    fun parsesHeaderAndLeavesBodyUnread() {
        val raw = "POST /install?x=1 HTTP/1.1\r\nHost: tv\r\nContent-Length: 4\r\nExpect: 100-continue\r\n\r\nBODY"
        val input = ByteArrayInputStream(raw.toByteArray())
        val r = HttpRequest.read(input)!!
        assertEquals("POST", r.method)
        assertEquals("/install", r.path)
        assertEquals("1", r.query["x"])
        assertEquals(4L, r.contentLength)
        assertEquals("100-continue", r.headers["expect"])
        assertEquals("BODY", input.readBytes().decodeToString())
    }

    @Test
    fun slowHeaderIsRejected() {
        var now = 0L
        // Each byte arrives 1 s later – within the socket timeout, but the whole header takes too long.
        val slow = object : java.io.InputStream() {
            val bytes = "GET / HTTP/1.1\r\nHost: tv\r\n\r\n".toByteArray()
            var i = 0
            override fun read(): Int { now += 1000; return if (i < bytes.size) bytes[i++].toInt() else -1 }
        }
        try {
            HttpRequest.read(slow, timeoutMs = 10_000, clock = { now })
            org.junit.Assert.fail()
        } catch (e: HttpRequest.BadRequest) {
            assertEquals("Header too slow", e.message)
        }
    }

    @Test
    fun parsesQueryWithEncoding() {
        val r = HttpRequest.read(ByteArrayInputStream("GET /log.txt?since=12&boot=99&a=b%20c HTTP/1.1\r\n\r\n".toByteArray()))!!
        assertEquals(mapOf("since" to "12", "boot" to "99", "a" to "b c"), r.query)
    }

    @Test
    fun handlesCrInsideHeaderTerminator() {
        val r = HttpRequest.read(ByteArrayInputStream("GET / HTTP/1.1\r\nA: b\r\r\n\r\n".toByteArray()))!!
        assertEquals("/", r.path)
    }

    @Test
    fun emptyConnectionReturnsNull() {
        assertNull(HttpRequest.read(ByteArrayInputStream(ByteArray(0))))
    }

    @Test(expected = HttpRequest.BadRequest::class)
    fun truncatedHeaderIsRejected() {
        HttpRequest.read(ByteArrayInputStream("GET / HTTP/1.1\r\nHost".toByteArray()))
    }

    @Test(expected = HttpRequest.BadRequest::class)
    fun badPercentEncodingIsBadRequest() {
        HttpRequest.read(ByteArrayInputStream("GET /status?a=%zz HTTP/1.1\r\n\r\n".toByteArray()))
    }
}
