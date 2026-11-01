package com.bonsaponsi.baptv

import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Semaphore

/** Accept loop shared by the debug server and the phone remote server. */
object AcceptLoop {
    /**
     * Accepts connections on [socket] until it is closed, each handled on its own thread – at most [maxClients] at a
     * time: idle connections from the LAN must not pile up hundreds of threads on a weak set-top box, so one over
     * the limit is closed at once. The loop runs guarded ([BackgroundWorker.start]) and survives accept errors.
     */
    fun start(socket: ServerSocket, name: String, maxClients: Int, handle: (Socket) -> Unit) {
        val slots = Semaphore(maxClients)
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
                if (!slots.tryAcquire()) {
                    DebugLog.warn(name, "too many connections ($maxClients) – closing ${client.inetAddress?.hostAddress}")
                    runCatching { client.close() }
                    continue
                }
                try {
                    BackgroundWorker.start("$name-client") {
                        try { handle(client) } finally { slots.release() }
                    }
                } catch (e: Throwable) {
                    // No thread could be created (out of memory): drop the connection, keep serving.
                    slots.release()
                    runCatching { client.close() }
                    DebugLog.error(name, "cannot start a connection thread", e)
                }
            }
        }
    }
}
