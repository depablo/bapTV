package com.bonsaponsi.baptv

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AcceptLoopTest {

    /** Connects and returns the first byte from the server: 'H' when handled, -1 when closed at once. */
    private fun firstByte(port: Int): Int = Socket(InetAddress.getLoopbackAddress(), port).use { s ->
        s.soTimeout = 5000
        try { s.getInputStream().read() } catch (e: SocketTimeoutException) { -2 }
    }

    @Test
    fun oneAddressGetsHalfOfTheSlotsAndFreedSlotsAreReused() {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val release = CountDownLatch(1)
        val handled = AtomicInteger()
        AcceptLoop.start(server, "test", maxClients = 4) { client ->
            client.use {
                it.getOutputStream().apply { write('H'.code); flush() }
                handled.incrementAndGet()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        try {
            // Two connections from 127.0.0.1 (half of 4 slots) are handled and held open.
            val held = List(2) { Socket(InetAddress.getLoopbackAddress(), server.localPort).apply { soTimeout = 5000 } }
            held.forEach { assertEquals('H'.code, it.getInputStream().read()) }
            // The third from the same address is closed without being handled.
            assertEquals(-1, firstByte(server.localPort))
            assertEquals(2, handled.get())
            // Once the held ones finish, the address may connect again.
            release.countDown()
            held.forEach { assertEquals(-1, it.getInputStream().read()); it.close() }
            var answer = -1
            for (i in 0 until 50) {
                answer = firstByte(server.localPort)
                if (answer == 'H'.code) break
                Thread.sleep(20)
            }
            assertEquals('H'.code, answer)
        } finally {
            release.countDown()
            server.close()
        }
    }
}
