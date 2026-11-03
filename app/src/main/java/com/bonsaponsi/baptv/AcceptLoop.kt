package com.bonsaponsi.baptv

import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/** Accept loop shared by the debug server and the phone remote server. */
object AcceptLoop {
    /**
     * Accepts connections on [socket] until it is closed, each handled on its own thread – at most [maxClients] at a
     * time: idle connections from the LAN must not pile up hundreds of threads on a weak set-top box, so one over
     * the limit is closed at once. One address may hold only half of the slots, so a single device cannot take
     * them all. The loop runs guarded ([BackgroundWorker.start]) and survives accept errors.
     */
    fun start(socket: ServerSocket, name: String, maxClients: Int, handle: (Socket) -> Unit) {
        val slots = Semaphore(maxClients)
        val perHost = maxOf(2, maxClients / 2)
        val byHost = ConcurrentHashMap<String, AtomicInteger>()
        BackgroundWorker.start(name) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    if (socket.isClosed) break
                    DebugLog.warn(name, "accept failed", e)
                    Thread.sleep(500)
                    continue
                }
                val host = client.inetAddress?.hostAddress.orEmpty()
                val count = byHost.computeIfAbsent(host) { AtomicInteger() }
                if (count.incrementAndGet() > perHost) {
                    count.decrementAndGet()
                    DebugLog.warn(name, "too many connections from $host ($perHost) – closing")
                    runCatching { client.close() }
                    continue
                }
                if (!slots.tryAcquire()) {
                    count.decrementAndGet()
                    DebugLog.warn(name, "too many connections ($maxClients) – closing $host")
                    runCatching { client.close() }
                    continue
                }
                val release = { slots.release(); count.decrementAndGet(); Unit }
                try {
                    BackgroundWorker.start("$name-client") {
                        try { handle(client) } finally { release() }
                    }
                } catch (e: Throwable) {
                    // No thread could be created (out of memory): drop the connection, keep serving.
                    release()
                    runCatching { client.close() }
                    DebugLog.error(name, "cannot start a connection thread", e)
                }
            }
        }
    }
}
