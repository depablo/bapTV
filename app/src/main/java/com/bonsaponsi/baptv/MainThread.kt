package com.bonsaponsi.baptv

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Calls into the player from background threads (HTTP servers, signal measurement, stream probe). */
object MainThread {
    private val main = Handler(Looper.getMainLooper())

    private const val PENDING = 0
    private const val RUNNING = 1
    private const val CANCELLED = 2

    /**
     * Runs [block] on the main thread and waits for its result up to [timeoutMs]. Null when it throws (logged – an
     * exception posted to the main thread would end the process) or does not finish in time; a block still queued
     * when the time is up is skipped, so the caller's "no response" does not tune a channel later.
     */
    fun <T> call(timeoutMs: Long = 5000, block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return guarded(block)
        val state = AtomicInteger(PENDING)
        val done = CountDownLatch(1)
        var value: T? = null
        main.post {
            if (!state.compareAndSet(PENDING, RUNNING)) return@post
            try { value = guarded(block) } finally { done.countDown() }
        }
        if (done.await(timeoutMs, TimeUnit.MILLISECONDS)) return value
        if (!state.compareAndSet(PENDING, CANCELLED)) DebugLog.warn("Main", "main thread call still running after $timeoutMs ms")
        return null
    }

    private fun <T> guarded(block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        DebugLog.error("Main", "error in a main thread call", e)
        null
    }
}
